package com.palco.facturacion.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;

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

import com.palco.common.ErroresDeNegocio;
import com.palco.common.datos.BaseDeDatos;
import com.palco.facturacion.Ejemplos;
import com.palco.facturacion.dominio.ComprobanteRechazado;
import com.palco.facturacion.dominio.Factura;

/**
 * Los mismos casos que {@link FacturaEnH2Test}, contra un PostgreSQL de verdad:
 * la imagen del servicio {@code db} del docker-compose, levantada en un
 * contenedor con Testcontainers. La migración de Flyway, el repositorio y
 * Hibernate son los del servidor; lo que cambia es la base. Así la restricción
 * única y el código de "clave duplicada" (SQLState 23505) son los de
 * PostgreSQL, y no los de H2 imitándolo.
 *
 * Lo corre failsafe en "mvn verify" (ver backend/pom.xml). Sin Docker se
 * saltea con un aviso.
 */
@EnabledIfSystemProperty(named = "palco.pruebas.integracion", matches = "true",
        disabledReason = "Lo corre failsafe en mvn verify (-Dpalco.pruebas.integracion=true)")
class FacturaEnPostgresIT {

    /** Un contenedor para todos los tests de la clase; cada test migra un esquema nuevo. */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private PGSimpleDataSource base;
    private EntityManagerFactory fabrica;
    private EntityManager em;
    private FacturaRepositoryJpa repositorio;

    @BeforeAll
    static void levantarPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Sin Docker no hay PostgreSQL en contenedor: se saltea (los mismos casos corren en FacturaEnH2Test)");
        POSTGRES.start();
    }

    @AfterAll
    static void bajarPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void crearBase() throws SQLException {
        base = new PGSimpleDataSource();
        base.setUrl(POSTGRES.getJdbcUrl());
        base.setUser(POSTGRES.getUsername());
        base.setPassword(POSTGRES.getPassword());
        // Cada test arranca de cero, como con la base nueva de H2: se borra el esquema y Flyway lo vuelve a crear.
        try (Connection conexion = base.getConnection(); Statement sql = conexion.createStatement()) {
            sql.execute("drop schema if exists facturacion cascade");
        }
        BaseDeDatos.migrar(base, "facturacion");

        fabrica = new PersistenceConfiguration("facturacion-postgresql")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Factura.class)
                .managedClass(ComprobanteRechazado.class)
                .property(PersistenceConfiguration.JDBC_URL, POSTGRES.getJdbcUrl())
                .property(PersistenceConfiguration.JDBC_USER, POSTGRES.getUsername())
                .property(PersistenceConfiguration.JDBC_PASSWORD, POSTGRES.getPassword())
                // Las tablas las crea Flyway, igual que en el servidor.
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "none")
                .property("hibernate.default_schema", "facturacion")
                .property("jakarta.persistence.validation.mode", "none")
                .createEntityManagerFactory();
        em = fabrica.createEntityManager();
        repositorio = new FacturaRepositoryJpa();
        repositorio.em = em;
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
    }

    @Test
    void guardaLaFacturaYLaEncuentraPorVenta() {
        em.getTransaction().begin();
        repositorio.guardar(Ejemplos.factura(Ejemplos.VENTA, Ejemplos.COMPRADOR));
        em.getTransaction().commit();
        em.clear();

        Factura leida = repositorio.buscarPorVenta(Ejemplos.VENTA).orElseThrow();
        assertEquals(7, leida.getNumero());
        assertEquals("75123456789012", leida.getCae());
        assertEquals(LocalDate.of(2026, 10, 26), leida.getVencimientoCae());
        assertEquals(60500, leida.getImporte());
        assertTrue(repositorio.buscarPorVenta("V-otra").isEmpty());
    }

    @Test
    void unaSegundaFacturaDeLaMismaVentaEsUnConflictoYNoSeGuarda() throws Exception {
        em.getTransaction().begin();
        repositorio.guardar(Ejemplos.factura(Ejemplos.VENTA, Ejemplos.COMPRADOR));
        em.getTransaction().commit();

        Factura otraDeLaMismaVenta = new Factura("F-99999999", Ejemplos.VENTA, Ejemplos.COMPRADOR, "ev-1", 6, 1, 8,
                "75123456789013", LocalDate.of(2026, 10, 26), 60500, LocalDate.of(2026, 10, 16),
                LocalDateTime.of(2026, 10, 17, 1, 31));
        em.getTransaction().begin();
        ErroresDeNegocio.Conflicto choque = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> repositorio.guardar(otraDeLaMismaVenta));
        em.getTransaction().rollback();

        assertEquals(FacturaRepository.FACTURA_REPETIDA, choque.tipo());
        assertEquals(1, filas("factura"));
    }

    @Test
    void laRestriccionEstaEnLaBaseYNoSoloEnElCodigo() throws Exception {
        try (Connection conexion = base.getConnection()) {
            insertar(conexion, "F-00000001");
            SQLException error = assertThrows(SQLException.class, () -> insertar(conexion, "F-00000002"));
            assertTrue(BaseDeDatos.esClaveDuplicada(error), error.getSQLState());
        }
        assertEquals(1, filas("factura"));
    }

    @Test
    void guardaElRechazoDeArcaYLoEncuentraPorVenta() {
        em.getTransaction().begin();
        repositorio.guardarRechazo(Ejemplos.rechazo(Ejemplos.VENTA, Ejemplos.COMPRADOR));
        em.getTransaction().commit();
        em.clear();

        ComprobanteRechazado leido = repositorio.buscarRechazoPorVenta(Ejemplos.VENTA).orElseThrow();
        assertEquals(Ejemplos.COMPRADOR, leido.getCompradorId());
        assertEquals(60500, leido.getImporte());
        assertEquals(LocalDate.of(2026, 10, 16), leido.getFecha());
        assertEquals("ARCA rechazó el comprobante: importe inválido", leido.getMotivo());
        assertEquals(LocalDateTime.of(2026, 10, 17, 1, 30, 5), leido.getRechazado());
        assertTrue(repositorio.buscarRechazoPorVenta("V-otra").isEmpty());
        // El rechazo no es una factura: la venta sigue sin factura.
        assertTrue(repositorio.buscarPorVenta(Ejemplos.VENTA).isEmpty());
    }

    @Test
    void unSegundoRechazoDeLaMismaVentaEsUnConflictoYNoSeGuarda() throws Exception {
        em.getTransaction().begin();
        repositorio.guardarRechazo(Ejemplos.rechazo(Ejemplos.VENTA, Ejemplos.COMPRADOR));
        em.getTransaction().commit();

        ComprobanteRechazado otro = new ComprobanteRechazado("R-99999999", Ejemplos.VENTA, Ejemplos.COMPRADOR, "ev-1",
                60500, LocalDate.of(2026, 10, 16), "otro motivo", LocalDateTime.of(2026, 10, 17, 1, 31));
        em.getTransaction().begin();
        ErroresDeNegocio.Conflicto choque = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> repositorio.guardarRechazo(otro));
        em.getTransaction().rollback();

        assertEquals(FacturaRepository.RECHAZO_REPETIDO, choque.tipo());
        assertEquals(1, filas("comprobante_rechazado"));
    }

    @Test
    void unMotivoMasLargoQueLaColumnaSeGuardaRecortadoYUnImporteImposibleTambienEntra() {
        // En PostgreSQL un texto más largo que la columna es un error (22001), no se recorta solo:
        // lo tiene que recortar la entidad antes de llegar a la base.
        ComprobanteRechazado largo = new ComprobanteRechazado("R-0a1b2c3e", "V-largo", Ejemplos.COMPRADOR, "ev-1", 0,
                LocalDate.of(2026, 10, 16), "x".repeat(ComprobanteRechazado.LARGO_MAXIMO_MOTIVO + 100),
                LocalDateTime.of(2026, 10, 17, 1, 30, 5));
        em.getTransaction().begin();
        repositorio.guardarRechazo(largo);
        em.getTransaction().commit();
        em.clear();

        ComprobanteRechazado leido = repositorio.buscarRechazoPorVenta("V-largo").orElseThrow();
        assertEquals(ComprobanteRechazado.LARGO_MAXIMO_MOTIVO, leido.getMotivo().length());
        assertEquals(0, leido.getImporte());
    }

    private static void insertar(Connection conexion, String id) throws SQLException {
        try (PreparedStatement insert = conexion.prepareStatement(
                "insert into facturacion.factura (id, venta_id, comprador_id, evento_id, tipo_comprobante, "
                        + "punto_de_venta, numero, cae, vencimiento_cae, importe, fecha, emitida) "
                        + "values (?, 'V-3f9a1c2b', 'user-titular', 'ev-1', 6, 1, 1, '75123456789012', "
                        + "date '2026-10-26', 60500, date '2026-10-16', timestamp '2026-10-17 01:30:05')")) {
            insert.setString(1, id);
            insert.executeUpdate();
        }
    }

    private int filas(String tabla) throws SQLException {
        try (Connection conexion = base.getConnection();
                PreparedStatement consulta = conexion.prepareStatement("select count(*) from facturacion." + tabla);
                ResultSet fila = consulta.executeQuery()) {
            fila.next();
            return fila.getInt(1);
        }
    }
}
