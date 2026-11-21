package com.palco.ventas.negocio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceUnitTransactionType;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.ReglasDeNegocio;
import com.palco.common.mensajeria.PublicadorPagoConfirmado;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.datos.BaseDeDatos;
import com.palco.ventas.api.Dtos.LiquidacionDto;
import com.palco.ventas.api.Dtos.TitularDto;
import com.palco.ventas.api.DtosDeReventa.ControlDeCompraDto;
import com.palco.ventas.api.DtosDeReventa.TransferenciaDto;
import com.palco.ventas.api.DtosDeReventa.TransferenciaHechaDto;
import com.palco.ventas.datos.EntradaRepositoryJpa;
import com.palco.ventas.datos.ReventaDeEntradasRepositoryJpa;
import com.palco.ventas.datos.VentaRepositoryJpa;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.EstadoEntrada;
import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.TitularDeVenta;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;

/**
 * Las operaciones de reventa de Ventas contra H2 en modo PostgreSQL, con el
 * esquema que crea Flyway y los repositorios de verdad: publicar, retirar y la
 * transferencia completa, y que dos transferencias a la vez de la misma
 * entrada dejen pasar una sola.
 */
class ReventaDeEntradasEnH2Test {

    private static final String VENDEDOR = "U-vendedor";
    private static final String COMPRADOR = "U-comprador";
    private static final String VENTA_ID = "V-3f9a1c2b";
    private static final String ENTRADA_ID = "T-00000001";
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 20, 18, 0, 0);

    private EntityManagerFactory fabrica;
    private EntityManager em;
    private ReventaDeEntradas reventa;
    private VentaRepositoryJpa ventas;

    @BeforeEach
    void crearBase() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        JdbcDataSource base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "ventas");

        fabrica = new PersistenceConfiguration("ventas-h2-reventa")
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
        em = fabrica.createEntityManager();
        reventa = beanCon(em);
        ventas = (VentaRepositoryJpa) reventa.ventas;
        entradaEmitida();
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
    }

    /** Lo que publicó la transferencia como REVENTA_CONFIRMADA (puede llamarse desde dos hilos). */
    private final List<PagoConfirmadoMensaje> reventasPublicadas = Collections.synchronizedList(new ArrayList<>());

    /** El bean con los tres repositorios de verdad, todos sobre el mismo EntityManager. */
    private ReventaDeEntradas beanCon(EntityManager em) {
        ReventaDeEntradas bean = new ReventaDeEntradas();
        EntradaRepositoryJpa entradas = new EntradaRepositoryJpa();
        VentaRepositoryJpa ventas = new VentaRepositoryJpa();
        ReventaDeEntradasRepositoryJpa reventa = new ReventaDeEntradasRepositoryJpa();
        conEntityManager(entradas, em);
        conEntityManager(ventas, em);
        conEntityManager(reventa, em);
        bean.entradas = entradas;
        bean.ventas = ventas;
        bean.reventa = reventa;
        bean.publicador = new PublicadorPagoConfirmado() {
            @Override
            public void publicar(PagoConfirmadoMensaje mensaje) {
                throw new AssertionError("La transferencia de una reventa no publica PAGO_CONFIRMADO.");
            }

            @Override
            public void publicarReventa(PagoConfirmadoMensaje mensaje) {
                reventasPublicadas.add(mensaje);
            }
        };
        bean.reloj = Clock.fixed(AHORA.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        return bean;
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

    private <T> T enTransaccion(EntityManager em, Supplier<T> paso) {
        em.getTransaction().begin();
        try {
            T resultado = paso.get();
            em.getTransaction().commit();
            return resultado;
        } catch (RuntimeException e) {
            em.getTransaction().rollback();
            throw e;
        }
    }

    /** Una venta del vendedor con una entrada emitida (VALIDA) de 50.000 pesos. */
    private void entradaEmitida() {
        enTransaccion(em, () -> {
            EntradaRepositoryJpa entradas = (EntradaRepositoryJpa) reventa.entradas;
            Venta venta = new Venta(VENTA_ID, VENDEDOR, "ev-1", UUID.randomUUID().toString(),
                    AHORA.minusDays(3), AHORA.minusDays(3).plusMinutes(10));
            venta.agregarItem("sec-1-1", "Campo", 50_000, 1);
            ventas.guardar(venta);
            entradas.guardar(List.of(new Entrada(ENTRADA_ID, venta.getItems().get(0), "Ana Gómez", "30111222", 1)));
            ventas.confirmar(VENTA_ID, "P-00000001", AHORA.minusDays(3));
            return entradas.emitirPendientes(VENTA_ID, AHORA.minusDays(3));
        });
    }

    private EstadoEntrada estado(String entradaId) {
        return reventa.entradas.buscarPorId(entradaId).orElseThrow().getEstado();
    }

    private static TransferenciaDto transferencia(String publicacionId, String compradorId) {
        return new TransferenciaDto(publicacionId, ENTRADA_ID, VENDEDOR, compradorId,
                new TitularDto("Bruno Díaz", "40111222"), 52_000, "P-reventa1");
    }

    /** La transferencia a COMPRADOR con otro titular. */
    private static TransferenciaDto transferenciaA(TitularDto titular) {
        return new TransferenciaDto("R-00000010", ENTRADA_ID, VENDEDOR, COMPRADOR, titular, 52_000, "P-reventa1");
    }

    // ---- Caracteres de control en los ids ----
    // PostgreSQL no acepta el carácter nulo (U+0000) en un texto: la primera consulta fallaba y la
    // transferencia terminaba en un 500. H2 sí lo acepta, así que acá se prueba que el id se rechaza
    // con un 400 del campo antes de llegar a la base, y que la entrada no cambia.

    @Test
    void elPedidoDelContratoConIdsConCaracteresDeControlEsUn400DeLaPublicacion() {
        // Los datos del cuerpo con el que Schemathesis obtuvo el 500; el comprador va recortado, con su nulo.
        // Sin el control, H2 lo dejaba pasar hasta el nombre del titular (U+0093) y en PostgreSQL fallaba antes.
        TransferenciaDto delContrato = new TransferenciaDto("¤2񱜤\u009d", "servicio", "񁼉",
                "󶨭7=h񟴀\u0000Î¢,", new TitularDto("\u0093", "29888777"), 66_381_373,
                "󤏚");

        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> enTransaccion(em, () -> reventa.transferir(delContrato)));

        assertEquals(Set.of("publicacionId"), error.campos().keySet());
    }

    @Test
    void unCompradorConElCaracterNuloEsUn400YLaEntradaSigueEnLaReventa() {
        enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR));

        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> enTransaccion(em, () -> reventa.transferir(transferencia("R-00000020", "U-\u0000"))));

        assertEquals(Set.of("compradorId"), error.campos().keySet());
        assertEquals(EstadoEntrada.PUBLICADA, estado(ENTRADA_ID));
        assertTrue(reventasPublicadas.isEmpty());
    }

    @Test
    void unaEntradaOUnVendedorConElCaracterNuloSonUn400EnCadaOperacion() {
        assertThrows(ErroresDeNegocio.DatosInvalidos.class, () -> reventa.consultar("T-\u0000", VENDEDOR));
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, "U-\u0000")));
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> enTransaccion(em, () -> reventa.retirar("T-\u0000", VENDEDOR)));
        assertThrows(ErroresDeNegocio.DatosInvalidos.class, () -> reventa.controlarCompra(
                new ControlDeCompraDto(ENTRADA_ID, VENDEDOR, "U-\u0000", new TitularDto("Bruno Díaz", "40111222"))));
        assertEquals(EstadoEntrada.VALIDA, estado(ENTRADA_ID));
    }

    @Test
    void publicarYRetirarCambianElEstadoYUnaAjenaEsNoEncontrada() {
        assertEquals("PUBLICADA", enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR)).estado());
        assertEquals(EstadoEntrada.PUBLICADA, estado(ENTRADA_ID));

        // Publicarla de nuevo choca: ya no está VALIDA.
        ErroresDeNegocio.Conflicto otraVez = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR)));
        assertEquals(ReventaDeEntradas.ENTRADA_NO_DISPONIBLE, otraVez.tipo());

        // Otro usuario no puede tocarla, y no se entera de que existe.
        assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> enTransaccion(em, () -> reventa.retirar(ENTRADA_ID, "U-otro")));

        assertEquals("VALIDA", enTransaccion(em, () -> reventa.retirar(ENTRADA_ID, VENDEDOR)).estado());
        // Retirar dos veces deja la entrada igual.
        assertEquals("VALIDA", enTransaccion(em, () -> reventa.retirar(ENTRADA_ID, VENDEDOR)).estado());
    }

    @Test
    void laTransferenciaVendeLaOriginalYCreaUnaVentaEmitidaDelComprador() {
        enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR));
        String codigoOriginal = reventa.entradas.buscarPorId(ENTRADA_ID).orElseThrow().getCodigoQr();

        TransferenciaHechaDto hecha = enTransaccion(em, () -> reventa.transferir(transferencia("R-0000000a", COMPRADOR)));

        assertEquals(EstadoEntrada.VENDIDA, estado(ENTRADA_ID));
        assertEquals("VALIDA", hecha.entrada().estado());
        assertNotNull(hecha.entrada().codigoQr());
        assertNotEquals(codigoOriginal, hecha.entrada().codigoQr());
        assertEquals("Bruno Díaz", hecha.entrada().titular().nombre());
        assertEquals(52_000, hecha.entrada().precioPagado());
        assertEquals("ev-1", hecha.entrada().eventoId());
        assertEquals("sec-1-1", hecha.entrada().sectorId());

        // La venta nueva es del comprador y aparece en su lista como cualquier compra emitida.
        List<Venta> delComprador = ventas.buscarPorComprador(COMPRADOR);
        assertEquals(1, delComprador.size());
        Venta venta = delComprador.get(0);
        assertEquals(hecha.ventaId(), venta.getId());
        assertEquals(EstadoVenta.EMITIDA, venta.getEstado());
        assertEquals("P-reventa1", venta.getPagoId());
        assertEquals(52_000 + ReglasDeNegocio.cargoServicio(52_000), venta.getMontoTotal());

        // Sale REVENTA_CONFIRMADA con la venta nueva, para el aviso, la auditoría y la factura (L13).
        assertEquals(1, reventasPublicadas.size());
        PagoConfirmadoMensaje publicado = reventasPublicadas.get(0);
        assertEquals(hecha.ventaId(), publicado.ventaId());
        assertEquals(COMPRADOR, publicado.compradorId());
        assertEquals("ev-1", publicado.eventoId());
        assertEquals(venta.getMontoTotal(), publicado.montoTotal());
        assertEquals(1, publicado.entradas().size());
        assertEquals(hecha.entrada().id(), publicado.entradas().get(0).entradaId());
        assertEquals("Bruno Díaz", publicado.entradas().get(0).titular().nombre());

        // Repetir el pedido devuelve la misma venta y no crea otra.
        TransferenciaHechaDto repetida = enTransaccion(em,
                () -> reventa.transferir(transferencia("R-0000000a", COMPRADOR)));
        assertEquals(hecha.ventaId(), repetida.ventaId());
        assertEquals(hecha.entrada().id(), repetida.entrada().id());
        assertEquals(1, ventas.buscarPorComprador(COMPRADOR).size());
        // Ni publica otra vez: el aviso, la auditoría y la factura salen una sola vez.
        assertEquals(1, reventasPublicadas.size());
    }

    @Test
    void retirarUnaEntradaQueYaSeVendioEsUnConflictoYNoVuelveAValida() {
        // Reventa pide retirar una publicación cuya entrada ya pasó al comprador: no puede volver al vendedor.
        enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR));
        enTransaccion(em, () -> reventa.transferir(transferencia("R-0000000f", COMPRADOR)));

        ErroresDeNegocio.Conflicto conflicto = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> enTransaccion(em, () -> reventa.retirar(ENTRADA_ID, VENDEDOR)));

        assertEquals(ReventaDeEntradas.ENTRADA_NO_PUBLICADA, conflicto.tipo());
        assertEquals("La entrada no está publicada: está VENDIDA.", conflicto.getMessage());
        assertEquals(EstadoEntrada.VENDIDA, estado(ENTRADA_ID));
    }

    @Test
    void unTitularConUnSaltoDeLineaOUnDniMalEscritoNoRecibeLaEntrada() {
        enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR));

        TransferenciaDto conSalto = transferenciaA(new TitularDto("Bruno\nDíaz", "40111222"));
        ErroresDeNegocio.DatosInvalidos nombre = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> enTransaccion(em, () -> reventa.transferir(conSalto)));
        assertTrue(nombre.campos().containsKey("titular.nombre"), "campos: " + nombre.campos());

        TransferenciaDto conPuntos = transferenciaA(new TitularDto("Bruno Díaz", "40.111.222"));
        ErroresDeNegocio.DatosInvalidos dni = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> enTransaccion(em, () -> reventa.transferir(conPuntos)));
        assertTrue(dni.campos().containsKey("titular.dni"), "campos: " + dni.campos());

        // La entrada sigue publicada, sin venta nueva ni aviso: la puede comprar otra persona.
        assertEquals(EstadoEntrada.PUBLICADA, estado(ENTRADA_ID));
        assertTrue(ventas.buscarPorComprador(COMPRADOR).isEmpty());
        assertTrue(reventasPublicadas.isEmpty());
    }

    @Test
    void sinPublicarNoSeTransfiereYNoQuedaNada() {
        ErroresDeNegocio.Conflicto conflicto = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> enTransaccion(em, () -> reventa.transferir(transferencia("R-0000000b", COMPRADOR))));

        assertEquals(ReventaDeEntradas.ENTRADA_NO_PUBLICADA, conflicto.tipo());
        assertEquals(EstadoEntrada.VALIDA, estado(ENTRADA_ID));
        assertTrue(ventas.buscarPorComprador(COMPRADOR).isEmpty());
    }

    @Test
    void dosTransferenciasALaVezDeLaMismaEntradaDejanPasarUnaSola() throws Exception {
        enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR));
        EntityManager otro = fabrica.createEntityManager();
        try {
            // La primera transferencia hace su UPDATE y todavía no confirma: la fila queda bloqueada.
            em.getTransaction().begin();
            reventa.transferir(transferencia("R-0000000c", COMPRADOR));

            // La segunda, de otro comprador, espera el bloqueo; cuando la primera confirma, ya la ve VENDIDA.
            ReventaDeEntradas otroBean = beanCon(otro);
            CompletableFuture<Object> segunda = CompletableFuture.supplyAsync(() -> {
                try {
                    return enTransaccion(otro, () -> otroBean.transferir(transferencia("R-0000000c", "U-otro")));
                } catch (RuntimeException e) {
                    return e;
                }
            });
            assertFalse(esperaTerminar(segunda, 300), "la segunda transferencia no esperó a la primera");
            em.getTransaction().commit();

            Object resultado = segunda.get(10, TimeUnit.SECONDS);
            assertTrue(resultado instanceof ErroresDeNegocio.Conflicto, "la segunda transferencia pasó: " + resultado);
            assertEquals(1, ventas.buscarPorComprador(COMPRADOR).size());
            assertTrue(ventas.buscarPorComprador("U-otro").isEmpty());
        } finally {
            otro.close();
        }
    }

    private static boolean esperaTerminar(CompletableFuture<?> tarea, long milisegundos) {
        try {
            tarea.get(milisegundos, TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    @Test
    void laCompraEnLaReventaNoEntraEnLaLiquidacionDelOrganizador() {
        enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR));
        enTransaccion(em, () -> reventa.transferir(transferencia("R-0000000e", COMPRADOR)));

        // La consulta trae las dos ventas pagadas del evento: la original, que sigue pagada aunque su
        // entrada esté VENDIDA, y la que nació de la reventa...
        List<Venta> pagadas = ventas.pagadasDelEventoConItems("ev-1");
        assertEquals(2, pagadas.size());

        // ...pero el lugar se le liquida al organizador una sola vez, al precio de lista y no al de reventa.
        LiquidacionDto liquidacion = ResumenDeVentas.resumir("ev-1", pagadas).liquidacion();
        assertEquals(1, liquidacion.ventas());
        assertEquals(1, liquidacion.entradas());
        assertEquals(50_000, liquidacion.aLiquidarAlOrganizador());
    }

    // ---- El control antes de cobrar: el tope por evento y un DNI por evento (DEF-30) ----

    /** Una venta emitida de ese comprador para ev-1, con una entrada por DNI: T-xxxxxxxx-1, T-xxxxxxxx-2... */
    private void ventaEmitida(String ventaId, String compradorId, String... dnis) {
        ventaEmitidaPara("ev-1", ventaId, compradorId, dnis);
    }

    private void ventaEmitidaPara(String eventoId, String ventaId, String compradorId, String... dnis) {
        enTransaccion(em, () -> {
            EntradaRepositoryJpa entradas = (EntradaRepositoryJpa) reventa.entradas;
            Venta venta = new Venta(ventaId, compradorId, eventoId, UUID.randomUUID().toString(),
                    AHORA.minusDays(2), AHORA.minusDays(2).plusMinutes(10));
            venta.agregarItem("sec-1-1", "Campo", 50_000, dnis.length);
            ventas.guardar(venta);
            List<Entrada> nuevas = new ArrayList<>();
            for (String dni : dnis) {
                nuevas.add(new Entrada("T-" + ventaId.substring(2) + "-" + (nuevas.size() + 1),
                        venta.getItems().get(0), "Titular " + dni, dni, nuevas.size() + 1));
            }
            entradas.guardar(nuevas);
            ventas.confirmar(ventaId, "P-" + ventaId.substring(2), AHORA.minusDays(2));
            return entradas.emitirPendientes(ventaId, AHORA.minusDays(2));
        });
    }

    /** El control de una compra de esa entrada del vendedor, a nombre de ese DNI. */
    private Void controlar(String entradaId, String compradorId, String dni) {
        return enTransaccion(em, () -> {
            reventa.controlarCompra(new ControlDeCompraDto(entradaId, VENDEDOR, compradorId,
                    new TitularDto("Bruno Díaz", dni)));
            return null;
        });
    }

    @Test
    void conCincoEntradasParaElEventoLaDeLaReventaEsLaSextaYPasa() {
        enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR));
        ventaEmitida("V-00000002", COMPRADOR, "40000001", "40000002", "40000003", "40000004", "40000005");

        assertDoesNotThrow(() -> controlar(ENTRADA_ID, COMPRADOR, "40111222"));
        // El control no cambia nada: la entrada sigue publicada y el comprador no tiene una venta más.
        assertEquals(EstadoEntrada.PUBLICADA, estado(ENTRADA_ID));
        assertEquals(1, ventas.buscarPorComprador(COMPRADOR).size());
    }

    @Test
    void conSeisEntradasParaElEventoNoCompraOtraEnLaReventa() {
        enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR));
        ventaEmitida("V-00000002", COMPRADOR, "40000001", "40000002", "40000003", "40000004", "40000005",
                "40000006");

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> controlar(ENTRADA_ID, COMPRADOR, "40111222"));

        // El mismo error que una compra del catálogo.
        assertEquals(TiposDeProblema.TOPE_POR_EVENTO, error.tipo());
        assertEquals("Cada persona puede tener hasta 6 entradas por evento, sumando todas sus compras. Ya tenés 6:"
                + " no podés comprar más para este evento.", error.getMessage());
    }

    @Test
    void lasEntradasYLosDniDeOtroEventoNoCuentan() {
        // El evento sale de la entrada que se revende (ev-1): seis entradas y el mismo DNI en ev-2 no la frenan.
        enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR));
        ventaEmitidaPara("ev-2", "V-00000002", COMPRADOR, "40000001", "40000002", "40000003", "40000004",
                "40000005", "40111222");

        assertDoesNotThrow(() -> controlar(ENTRADA_ID, COMPRADOR, "40111222"));
    }

    @Test
    void unDniQueYaTieneEntradaParaElEventoNoRecibeLaDeLaReventa() {
        enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR));
        ventaEmitida("V-00000002", "U-otro", "40111222");

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> controlar(ENTRADA_ID, COMPRADOR, "40111222"));

        assertEquals(TiposDeProblema.DNI_CON_ENTRADA, error.tipo());
        assertEquals("El DNI 40111222 ya tiene una entrada para este evento. Cada persona puede tener una sola"
                + " entrada por evento.", error.getMessage());
    }

    @Test
    void laEntradaQueSeRevendeNoCuentaParaElDniPeroLasOtrasDeEsaVentaSi() {
        // La entrada que se revende está a nombre de 30111222: esa persona puede comprarla.
        enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR));
        assertDoesNotThrow(() -> controlar(ENTRADA_ID, COMPRADOR, "30111222"));

        // Una venta del vendedor con dos entradas: se revende la de 30999888 y la de 30777666 queda en pie.
        ventaEmitida("V-00000003", VENDEDOR, "30999888", "30777666");
        enTransaccion(em, () -> reventa.publicar("T-00000003-1", VENDEDOR));
        assertDoesNotThrow(() -> controlar("T-00000003-1", COMPRADOR, "30999888"));
        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> controlar("T-00000003-1", COMPRADOR, "30777666"));
        assertEquals(TiposDeProblema.DNI_CON_ENTRADA, error.tipo());
    }

    @Test
    void unaEntradaAjenaOUnDniMalEscritoNoSeControlan() {
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> enTransaccion(em, () -> {
            reventa.controlarCompra(new ControlDeCompraDto(ENTRADA_ID, "U-otro", COMPRADOR,
                    new TitularDto("Bruno Díaz", "40111222")));
            return null;
        }));
        ErroresDeNegocio.DatosInvalidos dni = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> controlar(ENTRADA_ID, COMPRADOR, "40.111.222"));
        assertTrue(dni.campos().containsKey("titular.dni"), "campos: " + dni.campos());
    }

    @Test
    void elRelojDelTestEsElDeLaVentaNueva() {
        enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR));
        TransferenciaHechaDto hecha = enTransaccion(em, () -> reventa.transferir(transferencia("R-0000000d", COMPRADOR)));
        Venta venta = ventas.buscarPorId(hecha.ventaId()).orElseThrow();
        assertEquals(AHORA, venta.getCreada());
        assertEquals(Instant.parse("2026-11-20T18:00:00Z").toString(), hecha.entrada().emitida());
    }
}
