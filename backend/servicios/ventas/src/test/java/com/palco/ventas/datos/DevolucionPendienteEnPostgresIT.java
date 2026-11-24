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
 * Los mismos casos que {@link DevolucionPendienteEnH2Test}, contra un
 * PostgreSQL de verdad: la imagen del servicio {@code db} del docker-compose,
 * levantada en un contenedor con Testcontainers. La marca de devolución de cupo
 * pendiente la pone el mismo UPDATE que cierra la venta, y el barrido la busca
 * con sus renglones.
 *
 * Lo corre failsafe en "mvn verify" (ver backend/pom.xml). Sin Docker se
 * saltea con un aviso.
 */
@EnabledIfSystemProperty(named = "palco.pruebas.integracion", matches = "true",
        disabledReason = "Lo corre failsafe en mvn verify (-Dpalco.pruebas.integracion=true)")
class DevolucionPendienteEnPostgresIT {

    private static final LocalDateTime ABIERTA = LocalDateTime.of(2026, 10, 23, 12, 0, 0);
    private static final LocalDateTime VENCIDA = ABIERTA.plusMinutes(11);

    /** Un contenedor para todos los tests de la clase; cada test migra un esquema nuevo. */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private EntityManagerFactory fabrica;
    private EntityManager em;
    private VentaRepositoryJpa ventas;

    @BeforeAll
    static void levantarPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Sin Docker no hay PostgreSQL en contenedor: se saltea (los mismos casos corren en "
                        + "DevolucionPendienteEnH2Test)");
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

        fabrica = new PersistenceConfiguration("ventas-postgresql")
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

    private void compraAbierta(String id) {
        enTransaccion(() -> {
            Venta venta = new Venta(id, "U-comprador", "E-evento", UUID.randomUUID().toString(), ABIERTA,
                    ABIERTA.plusMinutes(10));
            venta.agregarItem("S-campo", "Campo", 55_000, 2);
            venta.agregarItem("S-platea", "Platea", 80_000, 1);
            ventas.guardar(venta);
            return null;
        });
    }

    private List<String> pendientes() {
        return enTransaccion(() -> ventas.buscarDevolucionesPendientes()).stream().map(Venta::getId).toList();
    }

    @Test
    void unaCompraAbiertaNoTieneDevolucionPendiente() {
        compraAbierta("V-11111111");

        assertTrue(pendientes().isEmpty());
    }

    @Test
    void cancelarPoneLaMarcaYElBarridoEncuentraLaVentaConSusRenglones() {
        compraAbierta("V-11111111");
        assertTrue(enTransaccion(() -> ventas.cancelar("V-11111111")));

        List<Venta> pendientes = enTransaccion(() -> ventas.buscarDevolucionesPendientes());

        assertEquals(1, pendientes.size());
        Venta venta = pendientes.get(0);
        assertEquals(EstadoVenta.CANCELADA, venta.getEstado());
        assertTrue(venta.isDevolucionPendiente());
        // Con los renglones cargados: el barrido los necesita para saber qué devolver.
        assertEquals(List.of("S-campo", "S-platea"), venta.getItems().stream().map(VentaItem::getSectorId).toList());
    }

    @Test
    void vencerTambienPoneLaMarca() {
        compraAbierta("V-11111111");
        assertTrue(enTransaccion(() -> ventas.vencer("V-11111111", VENCIDA)));

        assertEquals(List.of("V-11111111"), pendientes());
    }

    @Test
    void vencerUnPagoSinCobroTambienPoneLaMarca() {
        compraAbierta("V-11111111");
        assertTrue(enTransaccion(() -> ventas.guardarClaveDePago("V-11111111", "clave-pago", ABIERTA.plusMinutes(1))));
        assertTrue(enTransaccion(() -> ventas.vencerPagoSinCobro("V-11111111", "clave-pago", VENCIDA)));

        assertEquals(List.of("V-11111111"), pendientes());
    }

    @Test
    void sacarLaMarcaLaQuitaDelBarridoYRepetirloNoCambiaNada() {
        compraAbierta("V-11111111");
        compraAbierta("V-22222222");
        assertTrue(enTransaccion(() -> ventas.cancelar("V-11111111")));
        assertTrue(enTransaccion(() -> ventas.cancelar("V-22222222")));

        assertTrue(enTransaccion(() -> ventas.marcarCupoDevuelto("V-11111111")));
        assertFalse(enTransaccion(() -> ventas.marcarCupoDevuelto("V-11111111")));

        assertEquals(List.of("V-22222222"), pendientes());
    }

    @Test
    void unaCompraQueNoSePudoCerrarNoQuedaConLaMarca() {
        // El UPDATE no cambió la fila (la venta tenía un pago en curso): tampoco pone la marca.
        compraAbierta("V-11111111");
        assertTrue(enTransaccion(() -> ventas.guardarClaveDePago("V-11111111", "clave-pago", ABIERTA.plusMinutes(1))));
        assertFalse(enTransaccion(() -> ventas.cancelar("V-11111111")));

        assertTrue(pendientes().isEmpty());
    }
}
