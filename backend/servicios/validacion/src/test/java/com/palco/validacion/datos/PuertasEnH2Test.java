package com.palco.validacion.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
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
import com.palco.validacion.dominio.PuertaAsignada;

/**
 * La migración V2 y el repositorio de las puertas asignadas contra H2 en modo
 * PostgreSQL: la cuenta de puerta de prueba trabaja en los seis eventos
 * iniciales, una cuenta se asigna una sola vez por evento y quitarla la saca.
 */
class PuertasEnH2Test {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 20, 21, 0);

    private EntityManagerFactory fabrica;
    private EntityManager em;
    private PuertaAsignadaRepositoryJpa puertas;

    @BeforeEach
    void crearBase() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        JdbcDataSource base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "validacion");

        fabrica = new PersistenceConfiguration("validacion-h2")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(PuertaAsignada.class)
                .property(PersistenceConfiguration.JDBC_URL, url)
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "none")
                .property("hibernate.default_schema", "validacion")
                .property("jakarta.persistence.validation.mode", "none")
                .createEntityManagerFactory();
        em = fabrica.createEntityManager();
        puertas = new PuertaAsignadaRepositoryJpa();
        puertas.em = em;
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
    }

    private void enTransaccion(Runnable paso) {
        em.getTransaction().begin();
        try {
            paso.run();
            em.getTransaction().commit();
        } catch (RuntimeException e) {
            em.getTransaction().rollback();
            throw e;
        }
    }

    @Test
    void laPuertaDePruebaTrabajaEnLosSeisEventosIniciales() {
        assertEquals(6, puertas.deLaCuenta("puerta@palco.test").size());
        assertEquals("ev-1", puertas.deLaCuenta("puerta@palco.test").get(0).getEventoId());
        assertTrue(puertas.estaAsignada("ev-3", "puerta@palco.test"));
        assertFalse(puertas.estaAsignada("ev-3", "otra.puerta@palco.test"));
        assertTrue(puertas.deLaCuenta("otra.puerta@palco.test").isEmpty());
    }

    @Test
    void unaCuentaSeAsignaUnaSolaVezPorEventoYQuitarlaLaSaca() {
        enTransaccion(() -> puertas.guardar(new PuertaAsignada("ev-1", "otra.puerta@palco.test", "U-org", AHORA)));
        em.clear();
        assertEquals(java.util.List.of("otra.puerta@palco.test", "puerta@palco.test"),
                puertas.delEvento("ev-1").stream().map(PuertaAsignada::getStaffEmail).toList());

        ErroresDeNegocio.Conflicto repetida = assertThrows(ErroresDeNegocio.Conflicto.class, () -> enTransaccion(
                () -> puertas.guardar(new PuertaAsignada("ev-1", "otra.puerta@palco.test", "U-org", AHORA))));
        assertEquals("puerta-ya-asignada", repetida.tipo());
        em.clear();

        em.getTransaction().begin();
        assertTrue(puertas.quitar("ev-1", "otra.puerta@palco.test"));
        assertFalse(puertas.quitar("ev-1", "otra.puerta@palco.test"));
        em.getTransaction().commit();
        assertFalse(puertas.estaAsignada("ev-1", "otra.puerta@palco.test"));
        assertTrue(puertas.estaAsignada("ev-1", "puerta@palco.test"));
    }
}
