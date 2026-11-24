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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
import com.palco.ventas.dominio.EstadoEntrada;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;

/**
 * Los mismos casos que {@link UsoEnH2Test}, contra un PostgreSQL de verdad: la
 * imagen del servicio {@code db} del docker-compose, levantada en un
 * contenedor con Testcontainers. El uso único de una entrada lo garantiza la
 * condición del UPDATE; con dos escaneos a la vez, el que espera es el bloqueo
 * de fila de PostgreSQL, el mismo que actúa en el sistema levantado.
 *
 * Lo corre failsafe en "mvn verify" (ver backend/pom.xml). Sin Docker se
 * saltea con un aviso.
 */
@EnabledIfSystemProperty(named = "palco.pruebas.integracion", matches = "true",
        disabledReason = "Lo corre failsafe en mvn verify (-Dpalco.pruebas.integracion=true)")
class UsoEnPostgresIT {

    private static final String VENTA_ID = "V-3f9a1c2b";
    private static final String ENTRADA_ID = "T-00000001";
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 20, 21, 0, 7);

    /** Un contenedor para todos los tests de la clase; cada test migra un esquema nuevo. */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private EntityManagerFactory fabrica;
    private EntityManager em;
    private EntradaRepositoryJpa entradas;

    @BeforeAll
    static void levantarPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Sin Docker no hay PostgreSQL en contenedor: se saltea (los mismos casos corren en UsoEnH2Test)");
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

        fabrica = new PersistenceConfiguration("ventas-postgresql-uso")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Venta.class)
                .managedClass(VentaItem.class)
                .managedClass(Entrada.class)
                .property(PersistenceConfiguration.JDBC_URL, POSTGRES.getJdbcUrl())
                .property(PersistenceConfiguration.JDBC_USER, POSTGRES.getUsername())
                .property(PersistenceConfiguration.JDBC_PASSWORD, POSTGRES.getPassword())
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "none")
                .property("hibernate.default_schema", "ventas")
                .property("jakarta.persistence.validation.mode", "none")
                .createEntityManagerFactory();
        em = fabrica.createEntityManager();
        entradas = repositorioCon(em);
        entradaEmitida();
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
    }

    private static EntradaRepositoryJpa repositorioCon(EntityManager em) {
        EntradaRepositoryJpa repositorio = new EntradaRepositoryJpa();
        repositorio.em = em;
        return repositorio;
    }

    private <T> T enTransaccion(EntityManager em, Supplier<T> paso) {
        em.getTransaction().begin();
        T resultado = paso.get();
        em.getTransaction().commit();
        return resultado;
    }

    /** Una venta confirmada con una entrada ya emitida: VALIDA y con su código. */
    private void entradaEmitida() {
        enTransaccion(em, () -> {
            VentaRepositoryJpa ventas = new VentaRepositoryJpa();
            ventas.em = em;
            Venta venta = new Venta(VENTA_ID, "U-comprador", "E-evento", UUID.randomUUID().toString(),
                    AHORA.minusMinutes(5), AHORA.plusMinutes(5));
            venta.agregarItem("S-campo", "Campo", 55_000, 1);
            ventas.guardar(venta);
            entradas.guardar(List.of(new Entrada(ENTRADA_ID, venta.getItems().get(0), "Ana Gómez", "30111222", 1)));
            ventas.confirmar(VENTA_ID, "P-598c58a0", AHORA);
            return entradas.emitirPendientes(VENTA_ID, AHORA);
        });
    }

    private String codigo() {
        return entradas.buscarPorId(ENTRADA_ID).orElseThrow().getCodigoQr();
    }

    @Test
    void elCodigoEncuentraLaEntradaConSuEvento() {
        Optional<Entrada> encontrada = entradas.buscarPorCodigoQr(codigo());

        assertTrue(encontrada.isPresent());
        assertEquals(ENTRADA_ID, encontrada.get().getId());
        assertEquals("E-evento", encontrada.get().getVenta().getEventoId());
        assertTrue(entradas.buscarPorCodigoQr("no-es-un-codigo").isEmpty());
    }

    @Test
    void laPrimeraVezLaUsaYLaSegundaNoCambiaNada() {
        boolean primera = enTransaccion(em, () -> entradas.marcarUsada(ENTRADA_ID));
        boolean segunda = enTransaccion(em, () -> entradas.marcarUsada(ENTRADA_ID));

        assertTrue(primera);
        assertFalse(segunda);
        assertEquals(EstadoEntrada.USADA, entradas.buscarPorId(ENTRADA_ID).orElseThrow().getEstado());
    }

    @Test
    void dosEscaneosALaVezUsanLaEntradaUnaSolaVez() throws Exception {
        EntityManager otraPuerta = fabrica.createEntityManager();
        try {
            // La primera puerta hace su UPDATE y todavía no confirma: la fila queda bloqueada.
            em.getTransaction().begin();
            boolean primera = entradas.marcarUsada(ENTRADA_ID);

            // La segunda puerta escanea en ese momento: su UPDATE espera a que se libere la fila.
            CompletableFuture<Boolean> segunda = CompletableFuture.supplyAsync(
                    () -> enTransaccion(otraPuerta, () -> repositorioCon(otraPuerta).marcarUsada(ENTRADA_ID)));
            // Se le da tiempo para que llegue a la base y quede esperando antes de que la primera confirme.
            assertFalse(esperaTerminar(segunda, 300), "el segundo UPDATE no esperó al primero");
            em.getTransaction().commit();

            assertTrue(primera);
            // Cuando la fila se libera, la segunda ya la ve USADA: no cambia ninguna fila.
            assertFalse(segunda.get(10, TimeUnit.SECONDS));
        } finally {
            otraPuerta.close();
        }
    }

    /** Si la tarea terminó dentro de ese tiempo; no la cancela. */
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
}
