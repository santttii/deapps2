package com.palco.reventa.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
import com.palco.common.datos.BaseDeDatos;
import com.palco.reventa.dominio.EstadoReembolso;
import com.palco.reventa.dominio.Reembolso;

/**
 * La migración V3 y el repositorio de reembolsos contra H2 en modo
 * PostgreSQL: un reembolso por pago, el barrido ve solo los pendientes y
 * resolver uno lo cambia una sola vez.
 */
class ReembolsosEnH2Test {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 20, 15, 0);

    private EntityManagerFactory fabrica;
    private EntityManager em;
    private ReembolsoRepositoryJpa repositorio;

    @BeforeEach
    void crearBase() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        JdbcDataSource base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "reventa");

        fabrica = new PersistenceConfiguration("reventa-h2")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Reembolso.class)
                .property(PersistenceConfiguration.JDBC_URL, url)
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "none")
                .property("hibernate.default_schema", "reventa")
                .property("jakarta.persistence.validation.mode", "none")
                .createEntityManagerFactory();
        em = fabrica.createEntityManager();
        repositorio = new ReembolsoRepositoryJpa();
        repositorio.em = em;
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
        } catch (RuntimeException e) {
            em.getTransaction().rollback();
            throw e;
        }
    }

    private static Reembolso reembolso(String pagoId, LocalDateTime anotado) {
        return new Reembolso(pagoId, "R-1", "U-comprador", 60_500, "Reventa: Ventas no transfirió la entrada T-1.",
                anotado);
    }

    @Test
    void seAnotaPendienteYElMismoPagoNoSeAnotaDosVeces() {
        assertTrue(enTransaccion(() -> repositorio.anotar(reembolso("P-1", AHORA))));
        assertFalse(enTransaccion(() -> repositorio.anotar(reembolso("P-1", AHORA.plusMinutes(1)))));

        em.clear();
        Reembolso guardado = repositorio.buscar("P-1").orElseThrow();
        assertEquals(EstadoReembolso.PENDIENTE, guardado.getEstado());
        assertEquals(AHORA, guardado.getAnotado());
        assertEquals(60_500, guardado.getMonto());
        assertNull(guardado.getResuelto());
    }

    @Test
    void siOtroLoAnotaEnElMismoInstanteLaClavePrimariaLoFrenaConUnConflicto() throws Exception {
        EntityManager otro = fabrica.createEntityManager();
        try {
            // El primero inserta y todavía no confirma: el segundo no lo ve en su find y llega al INSERT.
            em.getTransaction().begin();
            assertTrue(repositorio.anotar(reembolso("P-1", AHORA)));

            ReembolsoRepositoryJpa segundo = new ReembolsoRepositoryJpa();
            segundo.em = otro;
            CompletableFuture<Boolean> choque = CompletableFuture.supplyAsync(() -> {
                otro.getTransaction().begin();
                try {
                    return segundo.anotar(reembolso("P-1", AHORA));
                } finally {
                    otro.getTransaction().rollback();
                }
            });
            Thread.sleep(300);
            em.getTransaction().commit();

            ExecutionException error = assertThrows(ExecutionException.class, () -> choque.get(10, TimeUnit.SECONDS));
            ErroresDeNegocio.Conflicto conflicto = assertInstanceOf(ErroresDeNegocio.Conflicto.class, error.getCause());
            assertEquals(ReembolsoRepositoryJpa.REEMBOLSO_YA_ANOTADO, conflicto.tipo());
        } finally {
            otro.close();
        }
    }

    @Test
    void elBarridoVeSoloLosPendientesDelMasViejoAlMasNuevo() {
        enTransaccion(() -> repositorio.anotar(reembolso("P-2", AHORA)));
        enTransaccion(() -> repositorio.anotar(reembolso("P-1", AHORA.minusMinutes(3))));
        enTransaccion(() -> repositorio.anotar(reembolso("P-3", AHORA.minusMinutes(1))));
        enTransaccion(() -> repositorio.resolver("P-3", EstadoReembolso.HECHO, AHORA));

        var pendientes = enTransaccion(repositorio::buscarPendientes);

        assertEquals(2, pendientes.size());
        assertEquals("P-1", pendientes.get(0).getPagoId());
        assertEquals("P-2", pendientes.get(1).getPagoId());
    }

    @Test
    void resolverloLoCambiaUnaSolaVez() {
        enTransaccion(() -> repositorio.anotar(reembolso("P-1", AHORA)));

        assertTrue(enTransaccion(() -> repositorio.resolver("P-1", EstadoReembolso.HECHO, AHORA.plusMinutes(1))));
        assertFalse(enTransaccion(() -> repositorio.resolver("P-1", EstadoReembolso.RECHAZADO, AHORA.plusMinutes(2))));
        assertFalse(enTransaccion(() -> repositorio.resolver("P-otro", EstadoReembolso.HECHO, AHORA)));

        Reembolso resuelto = repositorio.buscar("P-1").orElseThrow();
        assertEquals(EstadoReembolso.HECHO, resuelto.getEstado());
        assertEquals(AHORA.plusMinutes(1), resuelto.getResuelto());
    }
}
