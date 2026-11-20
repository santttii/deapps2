package com.palco.ventas.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
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

import com.palco.common.datos.BaseDeDatos;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.TitularDeVenta;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;

/**
 * La marca de reembolso pendiente, contra una base de verdad: H2 en modo
 * PostgreSQL con el esquema de las migraciones (incluida V4). Comprueba que
 * se pone solo en una venta anulada con pago, que el barrido la encuentra con
 * su pago, que al sacarla deja de aparecer, y que si la anulación se deshace
 * la marca se deshace con ella.
 */
class ReembolsoPendienteEnH2Test {

    private static final LocalDateTime ABIERTA = LocalDateTime.of(2026, 10, 30, 12, 0, 0);

    private EntityManagerFactory fabrica;
    private EntityManager em;
    private VentaRepositoryJpa ventas;

    @BeforeEach
    void crearBase() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        JdbcDataSource base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "ventas");

        fabrica = new PersistenceConfiguration("ventas-h2-reembolsos")
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
        ventas = new VentaRepositoryJpa();
        ventas.em = em;
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
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

    /** Una compra pagada (CONFIRMADA) con su pago, como la deja la confirmación. */
    private void compraPagada(String id, String pagoId) {
        enTransaccion(() -> {
            Venta venta = new Venta(id, "U-comprador", "E-evento", UUID.randomUUID().toString(), ABIERTA,
                    ABIERTA.plusMinutes(10));
            venta.agregarItem("S-campo", "Campo", 55_000, 2);
            ventas.guardar(venta);
            return ventas.confirmar(id, pagoId, ABIERTA.plusMinutes(1));
        });
    }

    private List<String> pendientes() {
        return enTransaccion(() -> ventas.buscarReembolsosPendientes()).stream().map(Venta::getId).toList();
    }

    @Test
    void unaVentaAnuladaSinMarcaNoEstaPendiente() {
        // Es el caso de un reembolso que Pagos sí contestó: la anulación no pone la marca por sí sola.
        compraPagada("V-11111111", "P-11111111");
        assertTrue(enTransaccion(() -> ventas.anularPorCancelacionDelEvento("V-11111111", true)));

        assertTrue(pendientes().isEmpty());
        assertFalse(ventas.buscarPorId("V-11111111").orElseThrow().isReembolsoPendiente());
    }

    @Test
    void laMarcaSePoneEnLaAnulacionYElBarridoEncuentraLaVentaConSuPago() {
        compraPagada("V-11111111", "P-11111111");
        compraPagada("V-22222222", "P-22222222");
        // Las dos cosas en la misma transacción, como en AnulacionDeCompras.
        assertTrue(enTransaccion(() -> ventas.anularPorCancelacionDelEvento("V-11111111", true)
                && ventas.marcarReembolsoPendiente("V-11111111")));

        List<Venta> pendientes = enTransaccion(() -> ventas.buscarReembolsosPendientes());

        assertEquals(1, pendientes.size());
        Venta venta = pendientes.get(0);
        assertEquals("V-11111111", venta.getId());
        assertEquals(EstadoVenta.CANCELADA, venta.getEstado());
        assertEquals("P-11111111", venta.getPagoId());
        assertTrue(venta.isReembolsoPendiente());
    }

    @Test
    void siLaAnulacionSeDeshaceLaMarcaSeDeshaceConElla() {
        compraPagada("V-11111111", "P-11111111");

        em.getTransaction().begin();
        assertTrue(ventas.anularPorCancelacionDelEvento("V-11111111", true));
        assertTrue(ventas.marcarReembolsoPendiente("V-11111111"));
        em.getTransaction().rollback();
        em.clear();

        Venta venta = ventas.buscarPorId("V-11111111").orElseThrow();
        assertEquals(EstadoVenta.CONFIRMADA, venta.getEstado());
        assertFalse(venta.isReembolsoPendiente());
        assertTrue(pendientes().isEmpty());
    }

    @Test
    void sacarLaMarcaLaQuitaDelBarridoYRepetirloNoCambiaNada() {
        compraPagada("V-11111111", "P-11111111");
        compraPagada("V-22222222", "P-22222222");
        for (String id : List.of("V-11111111", "V-22222222")) {
            assertTrue(enTransaccion(() -> ventas.anularPorCancelacionDelEvento(id, true)
                    && ventas.marcarReembolsoPendiente(id)));
        }

        assertTrue(enTransaccion(() -> ventas.marcarReembolsoResuelto("V-11111111")));
        assertFalse(enTransaccion(() -> ventas.marcarReembolsoResuelto("V-11111111")));

        assertEquals(List.of("V-22222222"), pendientes());
    }

    @Test
    void noSeMarcaUnaVentaQueNoEstaAnuladaNiUnaCanceladaSinPago() {
        // Pagada y vigente: no hay reembolso que pedir.
        compraPagada("V-11111111", "P-11111111");
        assertFalse(enTransaccion(() -> ventas.marcarReembolsoPendiente("V-11111111")));

        // Cancelada por su comprador antes de pagar: CANCELADA, pero sin pago que devolver.
        enTransaccion(() -> {
            Venta venta = new Venta("V-22222222", "U-comprador", "E-evento", UUID.randomUUID().toString(), ABIERTA,
                    ABIERTA.plusMinutes(10));
            venta.agregarItem("S-campo", "Campo", 55_000, 1);
            ventas.guardar(venta);
            return null;
        });
        assertTrue(enTransaccion(() -> ventas.cancelar("V-22222222")));
        assertFalse(enTransaccion(() -> ventas.marcarReembolsoPendiente("V-22222222")));

        assertTrue(pendientes().isEmpty());
    }
}
