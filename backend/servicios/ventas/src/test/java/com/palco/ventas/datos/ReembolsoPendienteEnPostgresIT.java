package com.palco.ventas.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceUnitTransactionType;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.palco.common.datos.BaseDeDatos;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.TitularDeVenta;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;

/**
 * Los mismos casos que {@link ReembolsoPendienteEnH2Test}, contra un
 * PostgreSQL de verdad: la imagen del servicio {@code db} del docker-compose,
 * levantada en un contenedor con Testcontainers. La marca de reembolso
 * pendiente se pone solo en una venta anulada con pago, el barrido la encuentra
 * con su pago y, si la anulación se deshace, el rollback de PostgreSQL deshace
 * también la marca.
 *
 * Lo corre failsafe en "mvn verify" (ver backend/pom.xml). Sin Docker se
 * saltea con un aviso.
 */
@EnabledIfSystemProperty(named = "palco.pruebas.integracion", matches = "true",
        disabledReason = "Lo corre failsafe en mvn verify (-Dpalco.pruebas.integracion=true)")
class ReembolsoPendienteEnPostgresIT {

    private static final LocalDateTime ABIERTA = LocalDateTime.of(2026, 10, 30, 12, 0, 0);

    /** Un contenedor para todos los tests de la clase; cada test migra un esquema nuevo. */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private EntityManagerFactory fabrica;
    private EntityManager em;
    private VentaRepositoryJpa ventas;

    @BeforeAll
    static void levantarPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Sin Docker no hay PostgreSQL en contenedor: se saltea (los mismos casos corren en "
                        + "ReembolsoPendienteEnH2Test)");
        POSTGRES.start();
    }

    @AfterAll
    static void bajarPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void crearBase() throws SQLException {
        PGSimpleDataSource base = new PGSimpleDataSource();
        base.setUrl(POSTGRES.getJdbcUrl());
        base.setUser(POSTGRES.getUsername());
        base.setPassword(POSTGRES.getPassword());
        // Cada test arranca de cero, como con la base nueva de H2: se borra el esquema y Flyway lo vuelve a crear.
        try (Connection conexion = base.getConnection(); Statement sql = conexion.createStatement()) {
            sql.execute("drop schema if exists ventas cascade");
        }
        BaseDeDatos.migrar(base, "ventas");

        fabrica = new PersistenceConfiguration("ventas-postgresql-reembolsos")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Venta.class)
                .managedClass(VentaItem.class)
                .managedClass(Entrada.class)
                .managedClass(TitularDeVenta.class)
                .property(PersistenceConfiguration.JDBC_URL, POSTGRES.getJdbcUrl())
                .property(PersistenceConfiguration.JDBC_USER, POSTGRES.getUsername())
                .property(PersistenceConfiguration.JDBC_PASSWORD, POSTGRES.getPassword())
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
