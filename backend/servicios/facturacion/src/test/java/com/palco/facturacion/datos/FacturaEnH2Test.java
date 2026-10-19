package com.palco.facturacion.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

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
import com.palco.facturacion.Ejemplos;
import com.palco.facturacion.dominio.Factura;

/**
 * La migración y el repositorio contra una base de verdad: H2 en modo
 * PostgreSQL, con las mismas opciones que el datasource de WildFly en
 * desarrollo y el esquema que crea Flyway. Lo que se comprueba es que la
 * restricción única la pone la base: una segunda factura de la misma venta no
 * entra, ni por JDBC ni por el repositorio. Lo único que cambia respecto del
 * servidor es que la transacción la abre el test.
 */
class FacturaEnH2Test {

    private JdbcDataSource base;
    private EntityManagerFactory fabrica;
    private EntityManager em;
    private FacturaRepositoryJpa repositorio;

    @BeforeEach
    void crearBase() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "facturacion");

        fabrica = new PersistenceConfiguration("facturacion-h2")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Factura.class)
                .property(PersistenceConfiguration.JDBC_URL, url)
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
                java.time.LocalDateTime.of(2026, 10, 17, 1, 31));
        em.getTransaction().begin();
        ErroresDeNegocio.Conflicto choque = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> repositorio.guardar(otraDeLaMismaVenta));
        em.getTransaction().rollback();

        assertEquals(FacturaRepository.FACTURA_REPETIDA, choque.tipo());
        assertEquals(1, filas());
    }

    @Test
    void laRestriccionEstaEnLaBaseYNoSoloEnElCodigo() throws Exception {
        try (Connection conexion = base.getConnection()) {
            insertar(conexion, "F-00000001");
            SQLException error = assertThrows(SQLException.class, () -> insertar(conexion, "F-00000002"));
            assertTrue(BaseDeDatos.esClaveDuplicada(error), error.getSQLState());
        }
        assertEquals(1, filas());
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

    private int filas() throws SQLException {
        try (Connection conexion = base.getConnection();
                PreparedStatement consulta = conexion.prepareStatement("select count(*) from facturacion.factura");
                ResultSet fila = consulta.executeQuery()) {
            fila.next();
            return fila.getInt(1);
        }
    }
}
