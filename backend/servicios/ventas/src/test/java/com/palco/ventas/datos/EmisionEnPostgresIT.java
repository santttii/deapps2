package com.palco.ventas.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;

/**
 * El mismo caso que {@link EmisionEnH2Test}, contra un PostgreSQL de verdad:
 * la imagen del servicio {@code db} del docker-compose, levantada en un
 * contenedor con Testcontainers. La emisión es idempotente porque los UPDATE de
 * los repositorios llevan la condición adentro; acá la que la evalúa es la base
 * que usa el sistema levantado, no H2 imitándola. Migraciones de Flyway,
 * repositorios e Hibernate son los del servidor; la transacción la abre el test.
 *
 * Lo corre failsafe en "mvn verify" (ver backend/pom.xml). Sin Docker se
 * saltea con un aviso.
 */
@EnabledIfSystemProperty(named = "palco.pruebas.integracion", matches = "true",
        disabledReason = "Lo corre failsafe en mvn verify (-Dpalco.pruebas.integracion=true)")
class EmisionEnPostgresIT {

    private static final String VENTA_ID = "V-3f9a1c2b";
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 19, 21, 0, 7);

    /** Un contenedor para todos los tests de la clase; cada test migra un esquema nuevo. */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private PGSimpleDataSource base;
    private EntityManagerFactory fabrica;
    private EntityManager em;
    private VentaRepositoryJpa ventas;
    private EntradaRepositoryJpa entradas;

    @BeforeAll
    static void levantarPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Sin Docker no hay PostgreSQL en contenedor: se saltea (el mismo caso corre en EmisionEnH2Test)");
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
            sql.execute("drop schema if exists ventas cascade");
        }
        BaseDeDatos.migrar(base, "ventas");

        fabrica = new PersistenceConfiguration("ventas-postgresql")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Venta.class)
                .managedClass(VentaItem.class)
                .managedClass(Entrada.class)
                .property(PersistenceConfiguration.JDBC_URL, POSTGRES.getJdbcUrl())
                .property(PersistenceConfiguration.JDBC_USER, POSTGRES.getUsername())
                .property(PersistenceConfiguration.JDBC_PASSWORD, POSTGRES.getPassword())
                // Las tablas las crea Flyway, igual que en el servidor.
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "none")
                .property("hibernate.default_schema", "ventas")
                .property("jakarta.persistence.validation.mode", "none")
                .createEntityManagerFactory();
        em = fabrica.createEntityManager();
        ventas = new VentaRepositoryJpa();
        ventas.em = em;
        entradas = new EntradaRepositoryJpa();
        entradas.em = em;
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
    }

    /** Corre el paso en una transacción propia, como lo haría el contenedor con cada mensaje. */
    private <T> T enTransaccion(Supplier<T> paso) {
        em.getTransaction().begin();
        T resultado = paso.get();
        em.getTransaction().commit();
        return resultado;
    }

    /** Una venta CONFIRMADA con dos entradas en PENDIENTE_EMISION: lo que deja el pago aprobado. */
    private void ventaConfirmadaConDosEntradas() {
        enTransaccion(() -> {
            Venta venta = new Venta(VENTA_ID, "U-comprador", "E-evento", UUID.randomUUID().toString(),
                    AHORA.minusMinutes(5), AHORA.plusMinutes(5));
            venta.agregarItem("S-campo", "Campo", 55_000, 2);
            ventas.guardar(venta);
            VentaItem item = venta.getItems().get(0);
            entradas.guardar(List.of(
                    new Entrada("T-00000001", item, "Ana Gómez", "30111222", 1),
                    new Entrada("T-00000002", item, "Luis Pérez", "30333444", 2)));
            return ventas.confirmar(VENTA_ID, "P-598c58a0", AHORA);
        });
    }

    /** Lo que guardó la base, leído por JDBC: id de la entrada → estado y código. */
    private Map<String, String> entradasEnLaBase() throws SQLException {
        Map<String, String> filas = new LinkedHashMap<>();
        try (Connection conexion = base.getConnection();
                PreparedStatement consulta = conexion.prepareStatement(
                        "select id, estado, codigo_qr from ventas.entrada where venta_id = ? order by orden")) {
            consulta.setString(1, VENTA_ID);
            try (ResultSet fila = consulta.executeQuery()) {
                while (fila.next()) {
                    filas.put(fila.getString(1), fila.getString(2) + "|" + fila.getString(3));
                }
            }
        }
        return filas;
    }

    private String estadoDeLaVenta() throws SQLException {
        try (Connection conexion = base.getConnection();
                PreparedStatement consulta = conexion.prepareStatement("select estado from ventas.venta where id = ?")) {
            consulta.setString(1, VENTA_ID);
            try (ResultSet fila = consulta.executeQuery()) {
                assertTrue(fila.next());
                return fila.getString(1);
            }
        }
    }

    @Test
    void emitirDosVecesLaMismaVentaNoCambiaNadaLaSegundaVez() throws Exception {
        ventaConfirmadaConDosEntradas();

        // Primera entrega del mensaje: emite las dos entradas y pasa la venta a EMITIDA.
        int primeras = enTransaccion(() -> entradas.emitirPendientes(VENTA_ID, AHORA));
        boolean primeraVenta = enTransaccion(() -> ventas.marcarEmitida(VENTA_ID));
        Map<String, String> despuesDeLaPrimera = entradasEnLaBase();

        assertEquals(2, primeras);
        assertTrue(primeraVenta);
        assertEquals("EMITIDA", estadoDeLaVenta());
        despuesDeLaPrimera.values().forEach(fila -> {
            assertTrue(fila.startsWith("VALIDA|"), fila);
            assertFalse(fila.endsWith("|null"), fila);
        });

        // El broker entrega el mismo mensaje otra vez: los UPDATE no encuentran filas que cumplan la condición.
        int segundas = enTransaccion(() -> entradas.emitirPendientes(VENTA_ID, AHORA.plusSeconds(2)));
        boolean segundaVenta = enTransaccion(() -> ventas.marcarEmitida(VENTA_ID));

        assertEquals(0, segundas);
        assertFalse(segundaVenta);
        // Los códigos QR son los de la primera emisión: la segunda no generó otros.
        assertEquals(despuesDeLaPrimera, entradasEnLaBase());
        assertEquals("EMITIDA", estadoDeLaVenta());
    }
}
