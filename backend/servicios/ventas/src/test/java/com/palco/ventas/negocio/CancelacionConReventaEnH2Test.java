package com.palco.ventas.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceUnitTransactionType;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ReglasDeNegocio;
import com.palco.common.datos.BaseDeDatos;
import com.palco.common.mensajeria.CompraAnuladaMensaje;
import com.palco.common.mensajeria.EventoCanceladoMensaje;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.mensajeria.PublicadorDeCancelaciones;
import com.palco.common.mensajeria.PublicadorPagoConfirmado;
import com.palco.ventas.api.Dtos.TitularDto;
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
import com.palco.ventas.negocio.externos.Cobros;
import com.palco.ventas.negocio.externos.ResultadoDeCobro;

/**
 * La cancelación de un evento con una entrada que se revendió, contra H2 en
 * modo PostgreSQL con el esquema de Flyway y los repositorios de verdad: la
 * transferencia de la reventa, la anulación de las dos compras y el barrido de
 * reembolsos. Pagos es un doble que hace lo mismo que el de verdad con un
 * reembolso: lo acepta solo con la venta con la que guardó el cobro, y no
 * devuelve más de lo cobrado.
 *
 * Lo que se cuida: que cada lugar se devuelva una sola vez y a quien lo tenía
 * al cancelarse el evento. Quien compró en la reventa recupera lo que pagó,
 * pedido con el id de la publicación; quien la vendió, solo lo que pagó por
 * las entradas que todavía tenía; y el cupo lo devuelve solo la compra
 * original, que es la que lo reservó.
 */
class CancelacionConReventaEnH2Test {

    private static final String VENDEDOR = "U-vendedor";
    private static final String COMPRADOR = "U-comprador";
    private static final String EVENTO = "ev-1";
    private static final long PRECIO = 27_500;
    private static final long PRECIO_DE_REVENTA = 30_000;
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 20, 18, 0, 0);
    private static final EventoCanceladoMensaje CANCELACION = new EventoCanceladoMensaje(EVENTO, "Show",
            "Prueba", AHORA.toInstant(ZoneOffset.UTC));

    private EntityManagerFactory fabrica;
    private EntityManager em;
    private VentaRepositoryJpa ventas;
    private EntradaRepositoryJpa entradas;
    private ReventaDeEntradas reventa;
    private CancelacionDeEventos cancelacion;
    private ReintentoDeReembolsos barrido;
    private final PagosSimulado pagos = new PagosSimulado();
    private final List<CompraAnuladaMensaje> avisos = new ArrayList<>();

    @BeforeEach
    void crearBase() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        JdbcDataSource base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "ventas");

        fabrica = new PersistenceConfiguration("ventas-h2-cancelacion-con-reventa")
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
        armarBeans();
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
    }

    /** Los beans de verdad, unidos a mano como los une el contenedor, todos sobre el mismo EntityManager. */
    private void armarBeans() {
        ventas = new VentaRepositoryJpa();
        entradas = new EntradaRepositoryJpa();
        ReventaDeEntradasRepositoryJpa deReventa = new ReventaDeEntradasRepositoryJpa();
        conEntityManager(ventas);
        conEntityManager(entradas);
        conEntityManager(deReventa);
        Clock reloj = Clock.fixed(AHORA.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

        reventa = new ReventaDeEntradas();
        reventa.ventas = ventas;
        reventa.entradas = entradas;
        reventa.reventa = deReventa;
        reventa.reloj = reloj;
        reventa.publicador = new PublicadorPagoConfirmado() {
            @Override
            public void publicar(PagoConfirmadoMensaje mensaje) {
                throw new AssertionError("La transferencia de una reventa no publica PAGO_CONFIRMADO.");
            }

            @Override
            public void publicarReventa(PagoConfirmadoMensaje mensaje) {
                // El aviso de la compra en la reventa no es lo que se prueba acá.
            }
        };

        AnulacionDeCompras anulacion = new AnulacionDeCompras();
        anulacion.ventas = ventas;
        anulacion.entradas = entradas;
        anulacion.cobros = pagos;
        anulacion.reloj = reloj;
        anulacion.cancelaciones = new PublicadorDeCancelaciones() {
            @Override
            public void publicarEventoCancelado(EventoCanceladoMensaje mensaje) {
                throw new AssertionError("Ventas no publica EVENTO_CANCELADO: eso lo hace Eventos.");
            }

            @Override
            public void publicarCompraAnulada(CompraAnuladaMensaje mensaje) {
                mensaje.validar();
                avisos.add(mensaje);
            }
        };
        cancelacion = new CancelacionDeEventos();
        cancelacion.ventas = ventas;
        cancelacion.anulacion = anulacion;

        barrido = new ReintentoDeReembolsos();
        barrido.ventas = ventas;
        barrido.entradas = entradas;
        barrido.cobros = pagos;
    }

    /** El campo {@code em} de los repositorios es del paquete datos: desde acá se lo pone por reflexión. */
    private void conEntityManager(Object repositorio) {
        try {
            var campo = repositorio.getClass().getDeclaredField("em");
            campo.setAccessible(true);
            campo.set(repositorio, em);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T enTransaccion(Supplier<T> paso) {
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

    /**
     * Una compra del catálogo del vendedor, emitida, con {@code cantidad} entradas de {@link #PRECIO}, y su
     * cobro en Pagos, guardado con el id de la venta.
     */
    private void compraDelVendedor(String ventaId, String pagoId, int cantidad) {
        Venta venta = enTransaccion(() -> {
            Venta nueva = new Venta(ventaId, VENDEDOR, EVENTO, UUID.randomUUID().toString(), AHORA.minusDays(3),
                    AHORA.minusDays(3).plusMinutes(10));
            nueva.agregarItem("sec-1-1", "Platea B", PRECIO, cantidad);
            ventas.guardar(nueva);
            List<Entrada> nuevas = new ArrayList<>();
            for (int i = 1; i <= cantidad; i++) {
                nuevas.add(new Entrada(ventaId.replace("V-", "T-") + i, nueva.getItems().get(0), "Ana Gómez",
                        String.valueOf(30_111_220 + i), i));
            }
            entradas.guardar(nuevas);
            ventas.confirmar(ventaId, pagoId, AHORA.minusDays(3));
            entradas.emitirPendientes(ventaId, AHORA.minusDays(3));
            ventas.marcarEmitida(ventaId);
            return nueva;
        });
        pagos.cobrado(pagoId, ventaId, venta.getMontoTotal());
    }

    /**
     * La entrada, publicada y vendida en la reventa: Ventas la transfiere, y el cobro queda en Pagos con el id
     * de la publicación, como lo hace Reventa. Devuelve la venta nueva del comprador.
     */
    private String revender(String entradaId, String publicacionId, String compradorId, String pagoId) {
        enTransaccion(() -> reventa.publicar(entradaId, VENDEDOR));
        TransferenciaHechaDto hecha = enTransaccion(() -> reventa.transferir(new TransferenciaDto(publicacionId,
                entradaId, VENDEDOR, compradorId, new TitularDto("Bruno Díaz", "40111222"), PRECIO_DE_REVENTA,
                pagoId)));
        pagos.cobrado(pagoId, publicacionId, PRECIO_DE_REVENTA + ReglasDeNegocio.cargoServicio(PRECIO_DE_REVENTA));
        return hecha.ventaId();
    }

    private int cancelarElEvento() {
        return enTransaccion(() -> cancelacion.anularComprasDelEvento(CANCELACION));
    }

    private Venta venta(String ventaId) {
        return ventas.buscarPorId(ventaId).orElseThrow();
    }

    private CompraAnuladaMensaje avisoDe(String ventaId) {
        return avisos.stream().filter(a -> a.ventaId().equals(ventaId)).findFirst()
                .orElseThrow(() -> new AssertionError("No salió el aviso de la venta " + ventaId));
    }

    private List<String> conCupoPorDevolver() {
        return enTransaccion(() -> ventas.buscarDevolucionesPendientes()).stream().map(Venta::getId).toList();
    }

    @Test
    void alCancelarseElEventoCadaLugarSeDevuelveUnaSolaVezYAQuienLoTenia() {
        compraDelVendedor("V-vendedor", "P-vendedor", 2);
        String delComprador = revender("T-vendedor1", "R-0000000a", COMPRADOR, "P-reventa1");

        assertEquals(2, cancelarElEvento());

        // Quien compró en la reventa: su pago, pedido con la publicación, que es como lo guardó Pagos.
        long pagadoEnLaReventa = PRECIO_DE_REVENTA + ReglasDeNegocio.cargoServicio(PRECIO_DE_REVENTA);
        assertEquals(new Pedido("P-reventa1", "R-0000000a", pagadoEnLaReventa), pagos.pedidoDe("P-reventa1"));
        assertEquals(Cobros.Devolucion.HECHA, pagos.ultimaRespuesta.get("P-reventa1"));
        assertEquals(pagadoEnLaReventa, pagos.devuelto("P-reventa1"));
        assertTrue(avisoDe(delComprador).reembolsado());
        assertEquals(pagadoEnLaReventa, avisoDe(delComprador).monto());
        Venta comprada = venta(delComprador);
        assertEquals(EstadoVenta.CANCELADA, comprada.getEstado());
        assertEquals(EstadoEntrada.ANULADA, comprada.getEntradas().get(0).getEstado());

        // Quien vendió: solo la entrada que todavía tenía, con su cargo. La revendida no se le devuelve.
        long laQueLeQuedaba = PRECIO + ReglasDeNegocio.cargoServicio(PRECIO);
        assertEquals(new Pedido("P-vendedor", "V-vendedor", laQueLeQuedaba), pagos.pedidoDe("P-vendedor"));
        assertEquals(laQueLeQuedaba, pagos.devuelto("P-vendedor"));
        assertEquals(laQueLeQuedaba, avisoDe("V-vendedor").monto());
        Venta original = venta("V-vendedor");
        assertEquals(EstadoVenta.CANCELADA, original.getEstado());
        assertEquals(EstadoEntrada.VENDIDA, entradas.buscarPorId("T-vendedor1").orElseThrow().getEstado());
        assertEquals(EstadoEntrada.ANULADA, entradas.buscarPorId("T-vendedor2").orElseThrow().getEstado());

        // El cupo: lo devuelve la compra original, entera; la de la reventa no reservó ninguno.
        assertEquals(List.of("V-vendedor"), conCupoPorDevolver());
        assertFalse(comprada.isDevolucionPendiente());
        // Y no queda ningún reembolso para el barrido.
        assertTrue(enTransaccion(() -> ventas.buscarReembolsosPendientes()).isEmpty());
    }

    @Test
    void siPagosNoContestaElReembolsoQuedaPendienteYElBarridoLoVuelveAPedirIgual() {
        compraDelVendedor("V-vendedor", "P-vendedor", 2);
        String delComprador = revender("T-vendedor1", "R-0000000a", COMPRADOR, "P-reventa1");
        pagos.sinRespuesta = true;

        assertEquals(2, cancelarElEvento());

        assertFalse(avisoDe(delComprador).reembolsado());
        assertTrue(venta(delComprador).isReembolsoPendiente());
        assertTrue(venta("V-vendedor").isReembolsoPendiente());
        assertEquals(0, pagos.devuelto("P-reventa1"));

        // Pagos vuelve: el barrido pide lo mismo que la anulación, con la publicación y por el mismo monto.
        pagos.sinRespuesta = false;
        pagos.pedidos.clear();
        enTransaccion(() -> {
            barrido.reintentarReembolsos();
            return null;
        });

        long pagadoEnLaReventa = PRECIO_DE_REVENTA + ReglasDeNegocio.cargoServicio(PRECIO_DE_REVENTA);
        long laQueLeQuedaba = PRECIO + ReglasDeNegocio.cargoServicio(PRECIO);
        assertEquals(new Pedido("P-reventa1", "R-0000000a", pagadoEnLaReventa), pagos.pedidoDe("P-reventa1"));
        assertEquals(new Pedido("P-vendedor", "V-vendedor", laQueLeQuedaba), pagos.pedidoDe("P-vendedor"));
        assertEquals(pagadoEnLaReventa, pagos.devuelto("P-reventa1"));
        assertEquals(laQueLeQuedaba, pagos.devuelto("P-vendedor"));
        assertTrue(enTransaccion(() -> ventas.buscarReembolsosPendientes()).isEmpty());
    }

    @Test
    void siQuienVendioLasRevendioTodasNoSeLeDevuelveNiSeLeAvisaPeroSuCompraDevuelveElCupo() {
        compraDelVendedor("V-unasola", "P-unasola", 1);
        String delComprador = revender("T-unasola1", "R-0000000b", COMPRADOR, "P-reventa2");

        assertEquals(2, cancelarElEvento());

        assertTrue(pagos.pedidoDeEsePago("P-unasola").isEmpty(), "a quien la vendió no se le pide ningún reembolso");
        assertEquals(List.of(delComprador), avisos.stream().map(CompraAnuladaMensaje::ventaId).toList());
        assertEquals(EstadoVenta.CANCELADA, venta("V-unasola").getEstado());
        assertEquals(List.of("V-unasola"), conCupoPorDevolver());
        assertFalse(venta("V-unasola").isReembolsoPendiente());
        assertEquals(PRECIO_DE_REVENTA + ReglasDeNegocio.cargoServicio(PRECIO_DE_REVENTA),
                pagos.devuelto("P-reventa2"));
    }

    @Test
    void conElIdDeLaVentaPagosNoDevolveriaElCobroDeLaReventa() {
        // Es lo que pasaba antes: el doble contesta como Pagos, que exige la venta con la que guardó el cobro.
        compraDelVendedor("V-vendedor", "P-vendedor", 2);
        String delComprador = revender("T-vendedor1", "R-0000000a", COMPRADOR, "P-reventa1");

        assertEquals(Cobros.Devolucion.RECHAZADA, pagos.reembolsar("P-reventa1", delComprador, 33_000, "x"));
        assertEquals(0, pagos.devuelto("P-reventa1"));
    }

    // ---------------------------------------------------------------------------------------------

    /** Un pedido de reembolso, como lo recibió Pagos. */
    private record Pedido(String pagoId, String ventaId, long monto) {
    }

    /**
     * Pagos, con lo que importa del reembolso: cada cobro guardado con su venta y su monto. Igual que
     * {@code ServicioDePagos.reembolsar}, rechaza un pedido con otra venta o que pasa lo cobrado, y devuelve
     * un pago una sola vez.
     */
    private static final class PagosSimulado implements Cobros {
        private record Cobro(String ventaId, long monto) {
        }

        private final Map<String, Cobro> cobros = new HashMap<>();
        private final Map<String, Long> devueltos = new HashMap<>();
        final Map<String, Devolucion> ultimaRespuesta = new HashMap<>();
        final List<Pedido> pedidos = new ArrayList<>();
        boolean sinRespuesta;

        void cobrado(String pagoId, String ventaId, long monto) {
            cobros.put(pagoId, new Cobro(ventaId, monto));
        }

        long devuelto(String pagoId) {
            return devueltos.getOrDefault(pagoId, 0L);
        }

        Optional<Pedido> pedidoDeEsePago(String pagoId) {
            return pedidos.stream().filter(p -> p.pagoId().equals(pagoId)).reduce((primero, ultimo) -> ultimo);
        }

        Pedido pedidoDe(String pagoId) {
            return pedidoDeEsePago(pagoId).orElseThrow(() -> new AssertionError("No se pidió el reembolso de "
                    + pagoId + ". Pedidos: " + pedidos));
        }

        @Override
        public Devolucion reembolsar(String pagoId, String ventaId, long monto, String motivo) {
            pedidos.add(new Pedido(pagoId, ventaId, monto));
            Devolucion respuesta = responder(pagoId, ventaId, monto);
            ultimaRespuesta.put(pagoId, respuesta);
            return respuesta;
        }

        private Devolucion responder(String pagoId, String ventaId, long monto) {
            if (sinRespuesta) {
                return Devolucion.SIN_RESPUESTA;
            }
            Cobro cobro = cobros.get(pagoId);
            if (cobro == null || !cobro.ventaId().equals(ventaId) || monto > cobro.monto()) {
                return Devolucion.RECHAZADA;
            }
            Long anterior = devueltos.putIfAbsent(pagoId, monto);
            return anterior == null || anterior == monto ? Devolucion.HECHA : Devolucion.RECHAZADA;
        }

        @Override
        public ResultadoDeCobro cobrar(String ventaId, long monto, String medioDePago, String token,
                String claveIdempotencia) {
            throw new AssertionError("La cancelación no cobra.");
        }

        @Override
        public Optional<ResultadoDeCobro> buscarCobro(String ventaId, String claveIdempotencia) {
            throw new AssertionError("La cancelación no concilia cobros.");
        }
    }
}
