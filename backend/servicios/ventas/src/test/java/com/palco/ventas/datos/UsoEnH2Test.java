package com.palco.ventas.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.datos.BaseDeDatos;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.EstadoEntrada;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;

/**
 * El uso único de una entrada lo garantiza la condición del UPDATE, y eso lo
 * decide la base. Igual que {@code EmisionEnH2Test}, corre contra H2 en modo
 * PostgreSQL con el esquema que crea Flyway y las consultas de
 * {@link EntradaRepositoryJpa}.
 */
class UsoEnH2Test {

    private static final String VENTA_ID = "V-3f9a1c2b";
    private static final String ENTRADA_ID = "T-00000001";
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 20, 21, 0, 7);

    private EntityManagerFactory fabrica;
    private EntityManager em;
    private EntradaRepositoryJpa entradas;

    @BeforeEach
    void crearBase() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        JdbcDataSource base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "ventas");

        fabrica = new PersistenceConfiguration("ventas-h2-uso")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Venta.class)
                .managedClass(VentaItem.class)
                .managedClass(Entrada.class)
                .property(PersistenceConfiguration.JDBC_URL, url)
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
