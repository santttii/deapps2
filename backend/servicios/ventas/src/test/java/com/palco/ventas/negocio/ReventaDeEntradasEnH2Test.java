package com.palco.ventas.negocio;

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
    void elRelojDelTestEsElDeLaVentaNueva() {
        enTransaccion(em, () -> reventa.publicar(ENTRADA_ID, VENDEDOR));
        TransferenciaHechaDto hecha = enTransaccion(em, () -> reventa.transferir(transferencia("R-0000000d", COMPRADOR)));
        Venta venta = ventas.buscarPorId(hecha.ventaId()).orElseThrow();
        assertEquals(AHORA, venta.getCreada());
        assertEquals(Instant.parse("2026-11-20T18:00:00Z").toString(), hecha.entrada().emitida());
    }
}
