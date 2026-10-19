package com.palco.notificaciones.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.datos.BaseDeDatos;
import com.palco.notificaciones.dominio.RegistroDeAuditoria;

/**
 * Las consultas del repositorio de auditoría contra una base de verdad: H2 en
 * modo PostgreSQL, con las mismas opciones que el datasource de WildFly en
 * desarrollo y el esquema que crean las migraciones de Flyway. Lo único que
 * cambia respecto del servidor es que la transacción la abre el test y no el
 * contenedor del MDB.
 */
class RegistroDeAuditoriaEnH2Test {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 16, 21, 30, 5);

    private EntityManagerFactory fabrica;
    private EntityManager em;
    private RegistroDeAuditoriaRepositoryJpa registros;

    @BeforeEach
    void crearBase() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        JdbcDataSource base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "notificaciones");

        fabrica = new PersistenceConfiguration("notificaciones-h2")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(RegistroDeAuditoria.class)
                .property(PersistenceConfiguration.JDBC_URL, url)
                // Las tablas las crea Flyway, igual que en el servidor.
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "none")
                .property("hibernate.default_schema", "notificaciones")
                .property("jakarta.persistence.validation.mode", "none")
                .createEntityManagerFactory();
        em = fabrica.createEntityManager();
        registros = new RegistroDeAuditoriaRepositoryJpa();
        registros.em = em;
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
    }

    /** Guarda la fila en una transacción propia, como lo haría el MDB con cada mensaje. */
    private void guardarEnTransaccion(RegistroDeAuditoria registro) {
        em.getTransaction().begin();
        registros.guardar(registro);
        em.getTransaction().commit();
        em.clear();
    }

    @Test
    void unaFilaGuardadaExisteYLasDemasNo() {
        guardarEnTransaccion(fila("ID:1", AHORA));

        assertTrue(registros.existe("ID:1"));
        assertFalse(registros.existe("ID:2"));
    }

    @Test
    void elMismoMensajeDosVecesFallaDentroDeGuardarYNoAlConfirmar() {
        guardarEnTransaccion(fila("ID:1", AHORA));

        em.getTransaction().begin();
        // guardar hace flush: la clave primaria repetida la rechaza la base acá adentro.
        assertThrows(PersistenceException.class, () -> registros.guardar(fila("ID:1", AHORA.plusSeconds(1))));
        em.getTransaction().rollback();
    }

    @Test
    void losUltimosVanDelMasNuevoAlMasViejoYDesempataElMensajeId() {
        guardarEnTransaccion(fila("ID:a", AHORA));
        guardarEnTransaccion(fila("ID:b", AHORA));
        guardarEnTransaccion(fila("ID:c", AHORA.minusMinutes(1)));
        guardarEnTransaccion(fila("ID:d", AHORA.plusMinutes(1)));

        List<String> ids = registros.ultimos(3).stream().map(RegistroDeAuditoria::getMensajeId).toList();

        assertEquals(List.of("ID:d", "ID:b", "ID:a"), ids);
    }

    private static RegistroDeAuditoria fila(String mensajeId, LocalDateTime recibido) {
        return new RegistroDeAuditoria(mensajeId, "PAGO_CONFIRMADO", "V-3f9a1c2b", "user-titular", "ev-1", 60500,
                "{}", recibido);
    }
}
