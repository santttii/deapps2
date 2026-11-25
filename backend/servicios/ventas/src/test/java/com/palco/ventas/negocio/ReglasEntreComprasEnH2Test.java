package com.palco.ventas.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceUnitTransactionType;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.datos.BaseDeDatos;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.mensajeria.PublicadorPagoConfirmado;
import com.palco.ventas.api.Dtos.ItemPedidoDto;
import com.palco.ventas.api.Dtos.NuevaVentaDto;
import com.palco.ventas.api.Dtos.NuevoPagoDto;
import com.palco.ventas.api.Dtos.TitularDto;
import com.palco.ventas.api.Dtos.VentaDto;
import com.palco.ventas.api.DtosDeReventa.ControlDeCompraDto;
import com.palco.ventas.api.DtosDeReventa.TransferenciaDto;
import com.palco.ventas.api.DtosDeReventa.TransferenciaHechaDto;
import com.palco.ventas.datos.EntradaRepositoryJpa;
import com.palco.ventas.datos.ReventaDeEntradasRepositoryJpa;
import com.palco.ventas.datos.VentaRepositoryJpa;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.EstadoEntrada;
import com.palco.ventas.dominio.TitularDeVenta;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;
import com.palco.ventas.negocio.externos.ClienteDeEventos;
import com.palco.ventas.negocio.externos.ClienteDeEventos.Sector;
import com.palco.ventas.negocio.externos.Cobros;
import com.palco.ventas.negocio.externos.ResultadoDeCobro;

/**
 * DEF-46: dos compras que se cruzan no pueden esquivar las reglas entre
 * compras. Contra H2 en modo PostgreSQL, con el esquema de Flyway (también la
 * V5, que crea {@code candado_de_compra}) y los repositorios de verdad. Cada
 * compra es una instancia de {@link ServicioDeVentas} con su propia conexión,
 * como dos pedidos que atiende el servidor a la vez, y corre en su propio hilo.
 * Eventos y Pagos son dobles.
 *
 * Las transacciones las abre el test en lugar del contenedor, en los mismos
 * lugares: una por cada operación de {@link ReglasEntreCompras}. Para que la
 * carrera pase siempre igual, la primera compra se detiene dentro de su
 * transacción, después de escribir y antes de confirmar: es el peor momento
 * para la segunda, que ya no puede ver lo que la primera escribió. Sin el
 * candado, la segunda no espera, no ve a la primera y pasa también.
 */
class ReglasEntreComprasEnH2Test {

    private static final String EVENTO = "ev-1";
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 24, 18, 0, 0);
    /** Lo que se espera para dar por hecho que la segunda compra está detenida en el candado. */
    private static final long ESPERA_MS = 500;

    private EntityManagerFactory fabrica;
    private final List<EntityManager> conexiones = new ArrayList<>();
    private final ExecutorService hilos = Executors.newFixedThreadPool(2);

    @BeforeEach
    void crearBase() {
        // LOCK_TIMEOUT: cuánto puede esperar una transacción a un candado antes de fallar; holgado para el test.
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;LOCK_TIMEOUT=10000";
        JdbcDataSource base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "ventas");

        fabrica = new PersistenceConfiguration("ventas-h2-reglas")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Venta.class)
                .managedClass(VentaItem.class)
                .managedClass(Entrada.class)
                .managedClass(TitularDeVenta.class)
                .property(PersistenceConfiguration.JDBC_URL, url)
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "none")
                .property("hibernate.default_schema", "ventas")
                .property("jakarta.persistence.validation.mode", "none")
                .createEntityManagerFactory();
    }

    @AfterEach
    void cerrar() {
        hilos.shutdownNow();
        conexiones.forEach(EntityManager::close);
        fabrica.close();
    }

    // ---- El tope por comprador y evento ----

    @Test
    void dosComprasDeSeisDelMismoCompradorALaVezAbreUnaSolaYLaOtraDevuelveSuCupo() throws Exception {
        Pedido primera = new Pedido();
        Pedido segunda = new Pedido();
        CountDownLatch reservo = avisarAlReservar(segunda);
        Pausa pausa = primera.reglas.detenerAntesDeConfirmar();

        Future<Object> deLaPrimera = hilos.submit(() -> resultado(
                () -> primera.compra.iniciarCompra("U-comprador", seisEntradas("3000000"), UUID.randomUUID().toString())));
        assertTrue(pausa.escribio.await(10, TimeUnit.SECONDS), "la primera compra no llegó a guardar su venta");

        // La primera guardó su venta de 6 y tiene el candado, sin confirmar. La segunda no la ve: pasa la primera
        // mirada del tope, reserva su cupo y, al guardar, espera el candado.
        Future<Object> deLaSegunda = hilos.submit(() -> resultado(
                () -> segunda.compra.iniciarCompra("U-comprador", seisEntradas("4000000"), UUID.randomUUID().toString())));
        assertTrue(reservo.await(10, TimeUnit.SECONDS), "la segunda compra no llegó a reservar su cupo");
        assertFalse(terminaEn(deLaSegunda, ESPERA_MS), "la segunda compra no esperó el candado de la primera");

        pausa.seguir.countDown();
        Object primerResultado = deLaPrimera.get(10, TimeUnit.SECONDS);
        Object segundoResultado = deLaSegunda.get(10, TimeUnit.SECONDS);

        // Cuando entra, la segunda cuenta las 6 de la primera: 409 tope-por-evento, y devuelve lo que reservó.
        assertEquals("EN_CHECKOUT", assertInstanceOf(VentaDto.class, primerResultado).estado());
        ErroresDeNegocio.Conflicto conflicto = assertInstanceOf(ErroresDeNegocio.Conflicto.class, segundoResultado);
        assertEquals(TiposDeProblema.TOPE_POR_EVENTO, conflicto.tipo());
        assertEquals("Cada persona puede tener hasta 6 entradas por evento, sumando todas sus compras. Ya tenés 6:"
                + " no podés comprar más para este evento.", conflicto.getMessage());
        verify(segunda.eventos).reservarCupo(EVENTO, "sec-1-1", 6);
        verify(segunda.eventos).liberarCupo(anyString(), eq(EVENTO), eq("sec-1-1"), eq(6));
        // En la base quedó una sola venta, con 6 entradas.
        Pedido mirada = new Pedido();
        assertEquals(1, mirada.ventas.buscarPorComprador("U-comprador").size());
        assertEquals(6, mirada.ventas.entradasDelCompradorParaElEvento("U-comprador", EVENTO, AHORA));
    }

    @Test
    void conLaFilaDelCandadoYaCreadaPorUnaCompraAnteriorLaSegundaTambienEspera() throws Exception {
        // Una compra anterior del mismo comprador y evento, de 1 entrada, ya creó y confirmó la fila del candado.
        // Ahora el INSERT de la segunda no tiene a quién esperar: la que la hace esperar es el SELECT ... FOR UPDATE.
        new Pedido().compra.iniciarCompra("U-comprador", entradas("2000000", 1), UUID.randomUUID().toString());
        Pedido primera = new Pedido();
        Pedido segunda = new Pedido();
        CountDownLatch reservo = avisarAlReservar(segunda);
        Pausa pausa = primera.reglas.detenerAntesDeConfirmar();

        Future<Object> deLaPrimera = hilos.submit(() -> resultado(
                () -> primera.compra.iniciarCompra("U-comprador", entradas("3000000", 5), UUID.randomUUID().toString())));
        assertTrue(pausa.escribio.await(10, TimeUnit.SECONDS), "la primera compra no llegó a guardar su venta");

        // Las dos piden 5 y el comprador ya tiene 1: cada una sola llega a 6 y pasa la primera mirada del tope.
        Future<Object> deLaSegunda = hilos.submit(() -> resultado(
                () -> segunda.compra.iniciarCompra("U-comprador", entradas("4000000", 5), UUID.randomUUID().toString())));
        assertTrue(reservo.await(10, TimeUnit.SECONDS), "la segunda compra no llegó a reservar su cupo");
        assertFalse(terminaEn(deLaSegunda, ESPERA_MS), "la segunda compra no esperó el candado que ya existía");

        pausa.seguir.countDown();
        assertInstanceOf(VentaDto.class, deLaPrimera.get(10, TimeUnit.SECONDS));
        ErroresDeNegocio.Conflicto conflicto = assertInstanceOf(ErroresDeNegocio.Conflicto.class,
                deLaSegunda.get(10, TimeUnit.SECONDS));
        assertEquals(TiposDeProblema.TOPE_POR_EVENTO, conflicto.tipo());
        verify(segunda.eventos).liberarCupo(anyString(), eq(EVENTO), eq("sec-1-1"), eq(5));
        assertEquals(6, new Pedido().ventas.entradasDelCompradorParaElEvento("U-comprador", EVENTO, AHORA));
    }

    @Test
    void conElCandadoDosComprasDeDistintosCompradoresNoSeEsperan() throws Exception {
        Pedido primera = new Pedido();
        Pedido segunda = new Pedido();
        Pausa pausa = primera.reglas.detenerAntesDeConfirmar();

        Future<Object> deLaPrimera = hilos.submit(() -> resultado(
                () -> primera.compra.iniciarCompra("U-ana", seisEntradas("3000000"), UUID.randomUUID().toString())));
        assertTrue(pausa.escribio.await(10, TimeUnit.SECONDS), "la primera compra no llegó a guardar su venta");

        // El candado es por comprador y evento: la compra de otra persona no espera a la primera.
        Object segundoResultado = hilos.submit(() -> resultado(
                () -> segunda.compra.iniciarCompra("U-luis", seisEntradas("4000000"), UUID.randomUUID().toString())))
                .get(10, TimeUnit.SECONDS);
        pausa.seguir.countDown();

        assertInstanceOf(VentaDto.class, segundoResultado);
        assertInstanceOf(VentaDto.class, deLaPrimera.get(10, TimeUnit.SECONDS));
    }

    @Test
    void unPedidoRepetidoConLaMismaClaveSigueSiendoClaveRepetidaYNoTopePorEvento() {
        // Si el tope se contara antes de guardar, el pedido repetido de una compra de 6 contaría las 6 de su propia
        // venta y respondería tope-por-evento; con clave-repetida, ComprasEnCurso devuelve la venta que ya existe.
        String clave = UUID.randomUUID().toString();
        Pedido primera = new Pedido();
        primera.compra.iniciarCompra("U-comprador", seisEntradas("3000000"), clave);
        Pedido repetido = new Pedido();
        Venta mismaClave = new Venta("V-repetida", "U-comprador", EVENTO, clave, AHORA, AHORA.plusMinutes(10));
        mismaClave.agregarItem("sec-1-1", "Campo", 50_000, 6);

        Object resultado = resultado(() -> {
            repetido.reglas.guardarDentroDelTope(mismaClave, AHORA);
            return null;
        });

        ErroresDeNegocio.Conflicto conflicto = assertInstanceOf(ErroresDeNegocio.Conflicto.class, resultado);
        assertEquals("clave-repetida", conflicto.tipo());
    }

    // ---- Un DNI por evento ----

    @Test
    void dosComprasConElMismoDniALaVezSoloUnaLlegaACobrar() throws Exception {
        // Dos personas abren su compra con un titular de DNI 30111222: las dos se abren, porque nadie lo tiene todavía.
        Pedido deAna = new Pedido();
        Pedido deLuis = new Pedido();
        VentaDto ventaDeAna = deAna.compra.iniciarCompra("U-ana",
                pedido(new TitularDto("Rosa Díaz", "30111222"), new TitularDto("Ana Gómez", "30222333")),
                UUID.randomUUID().toString());
        VentaDto ventaDeLuis = deLuis.compra.iniciarCompra("U-luis",
                pedido(new TitularDto("Rosa Díaz", "30111222"), new TitularDto("Luis Pérez", "30999888")),
                UUID.randomUUID().toString());
        // Lo que hace ComprasEnCurso antes de llamar a la instancia: anota la clave del intento de pago.
        deAna.enTransaccion(() -> deAna.ventas.guardarClaveDePago(ventaDeAna.id(), "clave-ana", AHORA));
        deLuis.enTransaccion(() -> deLuis.ventas.guardarClaveDePago(ventaDeLuis.id(), "clave-luis", AHORA));
        when(deAna.cobros.cobrar(eq(ventaDeAna.id()), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new ResultadoDeCobro("P-00000001", ResultadoDeCobro.ESTADO_RECHAZADO,
                        "fondos_insuficientes"));
        Pausa pausa = deAna.reglas.detenerAntesDeConfirmar();

        Future<Object> pagoDeAna = hilos.submit(() -> resultado(
                () -> deAna.compra.confirmarCompra(new NuevoPagoDto("tarjeta", "tok_test"), "clave-ana")));
        assertTrue(pausa.escribio.await(10, TimeUnit.SECONDS), "el pago de Ana no llegó a copiar sus titulares");

        // Ana miró el DNI y copió sus titulares, sin confirmar todavía. Luis, que paga ahora, espera el candado del DNI.
        Future<Object> pagoDeLuis = hilos.submit(() -> resultado(
                () -> deLuis.compra.confirmarCompra(new NuevoPagoDto("tarjeta", "tok_test"), "clave-luis")));
        assertFalse(terminaEn(pagoDeLuis, ESPERA_MS), "el pago de Luis no esperó el candado del DNI");

        pausa.seguir.countDown();
        Object deAnaResultado = pagoDeAna.get(10, TimeUnit.SECONDS);
        Object deLuisResultado = pagoDeLuis.get(10, TimeUnit.SECONDS);

        // Ana llegó a cobrar (el cobro se rechazó: el test no necesita más). Luis ve la copia de Ana y no cobra.
        assertInstanceOf(ErroresDeNegocio.PagoRechazado.class, deAnaResultado);
        ErroresDeNegocio.Conflicto conflicto = assertInstanceOf(ErroresDeNegocio.Conflicto.class, deLuisResultado);
        assertEquals(TiposDeProblema.DNI_CON_ENTRADA, conflicto.tipo());
        assertTrue(conflicto.getMessage().startsWith("El DNI 30111222 ya tiene una entrada para este evento."),
                conflicto.getMessage());
        verifyNoInteractions(deLuis.cobros);
        // La transacción de Luis se deshizo: no quedó la copia de sus titulares.
        Pedido mirada = new Pedido();
        assertEquals(2, mirada.ventas.buscarTitulares(ventaDeAna.id()).size());
        assertTrue(mirada.ventas.buscarTitulares(ventaDeLuis.id()).isEmpty());
    }

    // ---- Una compra en la reventa contra una del catálogo ----
    // La compra en la reventa no deja nada en Ventas hasta la transferencia, que llega después del cobro. Por eso la
    // transferencia toma los mismos candados y vuelve a mirar las reglas: la que entra segunda cuenta a la primera.

    @Test
    void unaCompraDelCatalogoConElCandadoFrenaLaTransferenciaDeLaReventaQueLaPasaria() throws Exception {
        // El comprador tiene 5 entradas y compra a la vez una en la reventa y una en el catálogo: cada una sola llega
        // a 6. El control de la reventa pasó antes de cobrar (5 + 1) y Reventa ya cobró.
        Pedido base = new Pedido();
        entradaPublicadaEnLaReventa(base);
        ventaEmitida(base, "V-00000005", "U-comprador", "40000001", "40000002", "40000003", "40000004", "40000005");
        base.enTransaccion(() -> {
            reventaDe(base).controlarCompra(new ControlDeCompraDto(QUE_SE_REVENDE, VENDEDOR, "U-comprador",
                    new TitularDto("Bruno Díaz", "40111222")));
            return null;
        });

        Pedido catalogo = new Pedido();
        Pausa pausa = catalogo.reglas.detenerAntesDeConfirmar();
        Future<Object> compra = hilos.submit(() -> resultado(
                () -> catalogo.compra.iniciarCompra("U-comprador", entradas("3000000", 1), UUID.randomUUID().toString())));
        assertTrue(pausa.escribio.await(10, TimeUnit.SECONDS), "la compra del catálogo no llegó a guardar su venta");

        // La compra del catálogo guardó su venta y tiene el candado del comprador, sin confirmar: la transferencia
        // lo espera.
        Pedido deLaReventa = new Pedido();
        ReventaDeEntradas reventa = reventaDe(deLaReventa);
        Future<Object> transferencia = hilos.submit(() -> resultado(
                () -> deLaReventa.enTransaccion(() -> reventa.transferir(transferenciaA("U-comprador", "40111222")))));
        assertFalse(terminaEn(transferencia, ESPERA_MS), "la transferencia no esperó el candado del comprador");

        pausa.seguir.countDown();
        assertInstanceOf(VentaDto.class, compra.get(10, TimeUnit.SECONDS));
        ErroresDeNegocio.Conflicto conflicto = assertInstanceOf(ErroresDeNegocio.Conflicto.class,
                transferencia.get(10, TimeUnit.SECONDS));
        assertEquals(TiposDeProblema.TOPE_POR_EVENTO, conflicto.tipo());
        // No cambió nada en la reventa: la entrada sigue publicada y el comprador queda con 6.
        Pedido mirada = new Pedido();
        assertEquals(EstadoEntrada.PUBLICADA, mirada.entradas.buscarPorId(QUE_SE_REVENDE).orElseThrow().getEstado());
        assertTrue(mirada.ventas.buscarPorClave("U-comprador", "reventa:R-00000001").isEmpty());
        assertEquals(6, mirada.ventas.entradasDelCompradorParaElEvento("U-comprador", EVENTO, AHORA));
    }

    @Test
    void unaTransferenciaConElCandadoFrenaLaCompraDelCatalogoQueLaPasaria() throws Exception {
        Pedido base = new Pedido();
        entradaPublicadaEnLaReventa(base);
        ventaEmitida(base, "V-00000005", "U-comprador", "40000001", "40000002", "40000003", "40000004", "40000005");

        // La transferencia toma los candados y escribe la venta nueva, sin confirmar todavía.
        Pedido deLaReventa = new Pedido();
        ReventaDeEntradas reventa = reventaDe(deLaReventa);
        CountDownLatch transfirio = new CountDownLatch(1);
        CountDownLatch confirmar = new CountDownLatch(1);
        Future<Object> transferencia = hilos.submit(() -> resultado(() -> deLaReventa.enTransaccion(() -> {
            TransferenciaHechaDto hecha = reventa.transferir(transferenciaA("U-comprador", "40111222"));
            transfirio.countDown();
            esperar(confirmar);
            return hecha;
        })));
        assertTrue(transfirio.await(10, TimeUnit.SECONDS), "la transferencia no llegó a escribir la venta nueva");

        // La compra del catálogo no ve la venta sin confirmar: pasa la primera mirada, reserva y espera el candado.
        Pedido catalogo = new Pedido();
        CountDownLatch reservo = avisarAlReservar(catalogo);
        Future<Object> compra = hilos.submit(() -> resultado(
                () -> catalogo.compra.iniciarCompra("U-comprador", entradas("3000000", 1), UUID.randomUUID().toString())));
        assertTrue(reservo.await(10, TimeUnit.SECONDS), "la compra del catálogo no llegó a reservar su cupo");
        assertFalse(terminaEn(compra, ESPERA_MS), "la compra del catálogo no esperó el candado de la transferencia");

        confirmar.countDown();
        assertInstanceOf(TransferenciaHechaDto.class, transferencia.get(10, TimeUnit.SECONDS));
        ErroresDeNegocio.Conflicto conflicto = assertInstanceOf(ErroresDeNegocio.Conflicto.class,
                compra.get(10, TimeUnit.SECONDS));
        assertEquals(TiposDeProblema.TOPE_POR_EVENTO, conflicto.tipo());
        verify(catalogo.eventos).liberarCupo(anyString(), eq(EVENTO), eq("sec-1-1"), eq(1));
        assertEquals(6, new Pedido().ventas.entradasDelCompradorParaElEvento("U-comprador", EVENTO, AHORA));
    }

    @Test
    void unaCompraDelCatalogoQueSeEstaCobrandoConElMismoDniFrenaLaTransferencia() throws Exception {
        // Luis paga en el catálogo una entrada a nombre de 40111222, y a la vez la reventa transfiere otra a nombre
        // del mismo DNI. El control de la reventa pasó antes: nadie tenía ese DNI.
        Pedido base = new Pedido();
        entradaPublicadaEnLaReventa(base);
        Pedido deLuis = new Pedido();
        VentaDto ventaDeLuis = deLuis.compra.iniciarCompra("U-luis", pedido(new TitularDto("Rosa Díaz", "40111222")),
                UUID.randomUUID().toString());
        deLuis.enTransaccion(() -> deLuis.ventas.guardarClaveDePago(ventaDeLuis.id(), "clave-luis", AHORA));
        CountDownLatch cobrando = new CountDownLatch(1);
        CountDownLatch terminarCobro = new CountDownLatch(1);
        when(deLuis.cobros.cobrar(eq(ventaDeLuis.id()), anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(llamada -> {
                    cobrando.countDown();
                    esperar(terminarCobro);
                    return new ResultadoDeCobro("P-00000002", ResultadoDeCobro.ESTADO_RECHAZADO,
                            "fondos_insuficientes");
                });
        Pausa pausa = deLuis.reglas.detenerAntesDeConfirmar();
        Future<Object> pagoDeLuis = hilos.submit(() -> resultado(
                () -> deLuis.compra.confirmarCompra(new NuevoPagoDto("tarjeta", "tok_test"), "clave-luis")));
        assertTrue(pausa.escribio.await(10, TimeUnit.SECONDS), "el pago de Luis no llegó a copiar su titular");

        // Luis miró el DNI y copió su titular, sin confirmar: la transferencia espera el candado del DNI.
        Pedido deLaReventa = new Pedido();
        ReventaDeEntradas reventa = reventaDe(deLaReventa);
        Future<Object> transferencia = hilos.submit(() -> resultado(
                () -> deLaReventa.enTransaccion(() -> reventa.transferir(transferenciaA("U-comprador", "40111222")))));
        assertFalse(terminaEn(transferencia, ESPERA_MS), "la transferencia no esperó el candado del DNI");

        // Luis confirma la copia y queda cobrando: la transferencia la ve y no da el DNI otra vez.
        pausa.seguir.countDown();
        assertTrue(cobrando.await(10, TimeUnit.SECONDS), "el pago de Luis no llegó a cobrar");
        ErroresDeNegocio.Conflicto conflicto = assertInstanceOf(ErroresDeNegocio.Conflicto.class,
                transferencia.get(10, TimeUnit.SECONDS));
        terminarCobro.countDown();
        assertEquals(TiposDeProblema.DNI_CON_ENTRADA, conflicto.tipo());
        assertInstanceOf(ErroresDeNegocio.PagoRechazado.class, pagoDeLuis.get(10, TimeUnit.SECONDS));
        assertEquals(EstadoEntrada.PUBLICADA,
                new Pedido().entradas.buscarPorId(QUE_SE_REVENDE).orElseThrow().getEstado());
    }

    // ---- Lo que hace el contenedor ----

    @Test
    void elCandadoExigeUnaTransaccionYElTopeSeGuardaEnUnaCortaQueAbreElContenedor() throws NoSuchMethodException {
        // MANDATORY: un candado fuera de una transacción se soltaría al volver.
        Method tomar = VentaRepositoryJpa.class.getMethod("tomarCandados", Collection.class);
        assertEquals(TransactionAttributeType.MANDATORY, tomar.getAnnotation(TransactionAttribute.class).value());
        // Sin anotación es REQUIRED: abrir la compra no tiene transacción y el contenedor abre una para guardar.
        Method guardar = ReglasEntreCompras.class.getMethod("guardarDentroDelTope", Venta.class, LocalDateTime.class);
        assertNull(guardar.getAnnotation(TransactionAttribute.class));
        assertNull(ReglasEntreCompras.class.getAnnotation(TransactionAttribute.class));
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Un pedido que atiende el servidor: su instancia de la compra, su conexión a la base, sus repositorios y
     * los dobles de Eventos y Pagos. Eventos le vende cualquier cantidad de Campo a 50.000 pesos.
     */
    private final class Pedido {
        final EntityManager em = fabrica.createEntityManager();
        final VentaRepositoryJpa ventas = new VentaRepositoryJpa();
        final EntradaRepositoryJpa entradas = new EntradaRepositoryJpa();
        final ClienteDeEventos eventos = mock(ClienteDeEventos.class);
        final Cobros cobros = mock(Cobros.class);
        final ReglasEnTransaccion reglas = new ReglasEnTransaccion(this);
        final ServicioDeVentas compra = new ServicioDeVentas();

        Pedido() {
            conexiones.add(em);
            conEntityManager(ventas, em);
            conEntityManager(entradas, em);
            reglas.ventas = ventas;
            when(eventos.reservarCupo(eq(EVENTO), eq("sec-1-1"), anyInt()))
                    .thenReturn(new Sector("sec-1-1", "Campo", 50_000, 2000, 0));
            compra.ventas = ventas;
            compra.entradas = entradas;
            compra.eventos = eventos;
            compra.cobros = cobros;
            compra.compras = mock(ComprasEnCurso.class);
            compra.reglas = reglas;
            compra.publicador = new PublicadorPagoConfirmado() {
                @Override
                public void publicar(PagoConfirmadoMensaje mensaje) {
                    throw new AssertionError("Ningún cobro de este test se aprueba.");
                }

                @Override
                public void publicarReventa(PagoConfirmadoMensaje mensaje) {
                    throw new AssertionError("Una compra del catálogo no publica REVENTA_CONFIRMADA.");
                }
            };
            compra.reloj = Clock.fixed(AHORA.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        }

        <T> T enTransaccion(Supplier<T> paso) {
            em.getTransaction().begin();
            try {
                T resultado = paso.get();
                em.getTransaction().commit();
                return resultado;
            } finally {
                if (em.getTransaction().isActive()) {
                    em.getTransaction().rollback();
                }
                em.clear();
            }
        }
    }

    /**
     * Hace de contenedor para {@link ReglasEntreCompras}: cada operación corre en su transacción, sobre la
     * conexión de su pedido. Con una pausa, la transacción se detiene después de escribir y antes de confirmar.
     */
    private static final class ReglasEnTransaccion extends ReglasEntreCompras {
        private final Pedido pedido;
        private Pausa pausa;

        ReglasEnTransaccion(Pedido pedido) {
            this.pedido = pedido;
        }

        Pausa detenerAntesDeConfirmar() {
            pausa = new Pausa();
            return pausa;
        }

        @Override
        public void guardarDentroDelTope(Venta venta, LocalDateTime ahora) {
            pedido.enTransaccion(() -> {
                super.guardarDentroDelTope(venta, ahora);
                return detener();
            });
        }

        @Override
        public void exigirDnisSinEntradaYCopiarTitulares(String ventaId, String eventoId,
                List<TitularDeVenta> titulares) {
            pedido.enTransaccion(() -> {
                super.exigirDnisSinEntradaYCopiarTitulares(ventaId, eventoId, titulares);
                return detener();
            });
        }

        private Void detener() {
            if (pausa != null) {
                pausa.escribio.countDown();
                try {
                    if (!pausa.seguir.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("El test no soltó la pausa.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return null;
        }
    }

    /** La primera compra avisa que escribió y espera la señal para confirmar. */
    private static final class Pausa {
        final CountDownLatch escribio = new CountDownLatch(1);
        final CountDownLatch seguir = new CountDownLatch(1);
    }

    /** Lo que devolvió la operación, o la excepción con la que terminó. */
    private static Object resultado(Supplier<Object> operacion) {
        try {
            return operacion.get();
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static boolean terminaEn(Future<?> tarea, long milisegundos) throws Exception {
        try {
            tarea.get(milisegundos, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    /** Seis entradas de Campo, a nombre de seis DNI que empiezan con ese prefijo de siete dígitos. */
    private static NuevaVentaDto seisEntradas(String prefijo) {
        return entradas(prefijo, 6);
    }

    /** Esa cantidad de entradas de Campo (hasta 9), a nombre de DNI que empiezan con ese prefijo de siete dígitos. */
    private static NuevaVentaDto entradas(String prefijo, int cantidad) {
        List<TitularDto> titulares = new ArrayList<>();
        for (int i = 1; i <= cantidad; i++) {
            titulares.add(new TitularDto("Titular " + i, prefijo + i));
        }
        return new NuevaVentaDto(EVENTO, List.of(new ItemPedidoDto("sec-1-1", cantidad)), titulares);
    }

    /**
     * Eventos avisa cuando la compra de ese pedido reservó su cupo, es decir, cuando ya pasó la primera mirada
     * del tope. Así el test sabe que la segunda compra llegó hasta el candado antes de soltar a la primera.
     */
    private static CountDownLatch avisarAlReservar(Pedido pedido) {
        CountDownLatch reservo = new CountDownLatch(1);
        when(pedido.eventos.reservarCupo(eq(EVENTO), eq("sec-1-1"), anyInt())).thenAnswer(llamada -> {
            reservo.countDown();
            return new Sector("sec-1-1", "Campo", 50_000, 2000, 0);
        });
        return reservo;
    }

    private static final String VENDEDOR = "U-vendedor";
    /** La entrada que se revende: la primera de la venta V-0000vend del vendedor, a nombre de 30111222. */
    private static final String QUE_SE_REVENDE = "T-0000vend-1";

    /** El bean de la reventa sobre la conexión de ese pedido, con los repositorios de verdad. */
    private static ReventaDeEntradas reventaDe(Pedido pedido) {
        ReventaDeEntradas bean = new ReventaDeEntradas();
        ReventaDeEntradasRepositoryJpa reventa = new ReventaDeEntradasRepositoryJpa();
        conEntityManager(reventa, pedido.em);
        bean.entradas = pedido.entradas;
        bean.ventas = pedido.ventas;
        bean.reventa = reventa;
        bean.publicador = new PublicadorPagoConfirmado() {
            @Override
            public void publicar(PagoConfirmadoMensaje mensaje) {
                throw new AssertionError("La transferencia de una reventa no publica PAGO_CONFIRMADO.");
            }

            @Override
            public void publicarReventa(PagoConfirmadoMensaje mensaje) {
                // Sale al tópico en la transacción de la transferencia; acá no hace falta mirarlo.
            }
        };
        bean.reloj = Clock.fixed(AHORA.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        return bean;
    }

    /** La venta del vendedor con su entrada emitida, publicada en la reventa. */
    private void entradaPublicadaEnLaReventa(Pedido pedido) {
        ventaEmitida(pedido, "V-0000vend", VENDEDOR, "30111222");
        pedido.enTransaccion(() -> reventaDe(pedido).publicar(QUE_SE_REVENDE, VENDEDOR));
    }

    /** Una venta pagada y emitida de ese comprador para el evento, con una entrada por DNI: T-xxxxxxxx-1, -2... */
    private static void ventaEmitida(Pedido pedido, String ventaId, String compradorId, String... dnis) {
        pedido.enTransaccion(() -> {
            Venta venta = new Venta(ventaId, compradorId, EVENTO, UUID.randomUUID().toString(), AHORA.minusDays(2),
                    AHORA.minusDays(2).plusMinutes(10));
            venta.agregarItem("sec-1-1", "Campo", 50_000, dnis.length);
            pedido.ventas.guardar(venta);
            List<Entrada> nuevas = new ArrayList<>();
            for (String dni : dnis) {
                nuevas.add(new Entrada("T-" + ventaId.substring(2) + "-" + (nuevas.size() + 1),
                        venta.getItems().get(0), "Titular " + dni, dni, nuevas.size() + 1));
            }
            pedido.entradas.guardar(nuevas);
            pedido.ventas.confirmar(ventaId, "P-" + ventaId.substring(2), AHORA.minusDays(2));
            return pedido.entradas.emitirPendientes(ventaId, AHORA.minusDays(2));
        });
    }

    /** La transferencia de la entrada que se revende a ese comprador, a nombre de ese DNI. */
    private static TransferenciaDto transferenciaA(String compradorId, String dni) {
        return new TransferenciaDto("R-00000001", QUE_SE_REVENDE, VENDEDOR, compradorId,
                new TitularDto("Bruno Díaz", dni), 55_000, "P-reventa1");
    }

    private static void esperar(CountDownLatch senal) {
        try {
            if (!senal.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("El test no dio la señal.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Una entrada de Campo por titular. */
    private static NuevaVentaDto pedido(TitularDto... titulares) {
        return new NuevaVentaDto(EVENTO, List.of(new ItemPedidoDto("sec-1-1", titulares.length)), List.of(titulares));
    }

    /** El campo {@code em} de los repositorios es del paquete datos: desde acá se lo pone por reflexión. */
    private static void conEntityManager(Object repositorio, EntityManager em) {
        try {
            var campo = repositorio.getClass().getDeclaredField("em");
            campo.setAccessible(true);
            campo.set(repositorio, em);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
