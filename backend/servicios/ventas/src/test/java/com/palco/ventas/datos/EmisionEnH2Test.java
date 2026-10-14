package com.palco.ventas.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.datos.BaseDeDatos;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;

/**
 * La emisión de entradas es idempotente porque los UPDATE de los repositorios
 * llevan la condición adentro, y eso lo decide la base. Este test lo comprueba
 * contra una base de verdad: H2 en modo PostgreSQL, con las mismas opciones
 * que el datasource de WildFly en desarrollo y el esquema que crea la
 * migración de Flyway. Las consultas son las de los repositorios, con
 * Hibernate; lo único que cambia respecto del servidor es que la transacción
 * la abre el test y no el contenedor.
 */
class EmisionEnH2Test {

    private static final String VENTA_ID = "V-3f9a1c2b";
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 19, 21, 0, 7);

    private JdbcDataSource base;
    private EntityManagerFactory fabrica;
    private EntityManager em;
    private VentaRepositoryJpa ventas;
    private EntradaRepositoryJpa entradas;

    @BeforeEach
    void crearBase() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "ventas");

        fabrica = new PersistenceConfiguration("ventas-h2")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Venta.class)
                .managedClass(VentaItem.class)
                .managedClass(Entrada.class)
                .property(PersistenceConfiguration.JDBC_URL, url)
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
    private Map<String, String> entradasEnLaBase() throws Exception {
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

    private String estadoDeLaVenta() throws Exception {
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
