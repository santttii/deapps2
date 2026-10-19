package com.palco.validacion.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceUnitTransactionType;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.datos.BaseDeDatos;
import com.palco.validacion.dominio.Escaneo;
import com.palco.validacion.dominio.ResultadoEscaneo;

/**
 * La migración y las consultas del registro contra una base de verdad: H2 en
 * modo PostgreSQL, con las mismas opciones que el datasource de WildFly en
 * desarrollo y el esquema que crea Flyway. Lo único que cambia respecto del
 * servidor es que la transacción la abre el test y no el contenedor.
 */
class RegistroEnH2Test {

    private static final LocalDateTime HORA = LocalDateTime.of(2026, 11, 20, 21, 0, 0);

    private EntityManagerFactory fabrica;
    private EntityManager em;
    private EscaneoRepositoryJpa registro;

    @BeforeEach
    void crearBase() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        JdbcDataSource base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "validacion");

        fabrica = new PersistenceConfiguration("validacion-h2")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Escaneo.class)
                .property(PersistenceConfiguration.JDBC_URL, url)
                // Las tablas las crea Flyway, igual que en el servidor.
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "none")
                .property("hibernate.default_schema", "validacion")
                .property("jakarta.persistence.validation.mode", "none")
                .createEntityManagerFactory();
        em = fabrica.createEntityManager();
        registro = new EscaneoRepositoryJpa();
        registro.em = em;
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
    }

    private void guardar(String id, String codigo, String evento, ResultadoEscaneo resultado, int minuto) {
        em.getTransaction().begin();
        registro.guardar(new Escaneo(id, codigo, evento, resultado,
                resultado == ResultadoEscaneo.VALIDA ? "T-00000001" : null, "U-puerta", HORA.plusMinutes(minuto)));
        em.getTransaction().commit();
    }

    @Test
    void elRegistroDeUnEventoVieneDelMasNuevoAlMasViejoYSinLosDeOtros() {
        guardar("ESC-00000001", "qr-1", "ev-1", ResultadoEscaneo.VALIDA, 1);
        guardar("ESC-00000002", "qr-1", "ev-1", ResultadoEscaneo.YA_USADA, 3);
        guardar("ESC-00000003", "qr-9", "ev-2", ResultadoEscaneo.VALIDA, 2);
        guardar("ESC-00000004", "nada", "ev-1", ResultadoEscaneo.INEXISTENTE, 2);
        em.clear();

        List<String> ids = registro.listarPorEvento("ev-1", 10).stream().map(Escaneo::getId).toList();

        assertEquals(List.of("ESC-00000002", "ESC-00000004", "ESC-00000001"), ids);
        assertEquals(2, registro.listarPorEvento("ev-1", 2).size());
    }

    @Test
    void lasColumnasVuelvenComoSeGuardaron() {
        guardar("ESC-00000001", "qr-1", "ev-1", ResultadoEscaneo.VALIDA, 1);
        em.clear();

        Escaneo leido = registro.buscarPorId("ESC-00000001").orElseThrow();

        assertEquals("qr-1", leido.getCodigoQr());
        assertEquals("ev-1", leido.getEventoId());
        assertEquals(ResultadoEscaneo.VALIDA, leido.getResultado());
        assertEquals("T-00000001", leido.getEntradaId());
        assertEquals("U-puerta", leido.getEscaneadoPor());
        assertEquals(HORA.plusMinutes(1), leido.getEscaneado());
        assertTrue(registro.buscarPorId("ESC-nada").isEmpty());
    }

    @Test
    void elUltimoEscaneoDeUnCodigoSeBuscaPorResultado() {
        guardar("ESC-00000001", "qr-1", "ev-1", ResultadoEscaneo.SIN_RESPUESTA, 1);
        guardar("ESC-00000002", "qr-1", "ev-1", ResultadoEscaneo.SIN_RESPUESTA, 4);
        guardar("ESC-00000003", "qr-1", "ev-1", ResultadoEscaneo.YA_USADA, 5);
        em.clear();

        assertEquals("ESC-00000002",
                registro.ultimoDelCodigo("qr-1", ResultadoEscaneo.SIN_RESPUESTA).orElseThrow().getId());
        assertTrue(registro.ultimoDelCodigo("qr-1", ResultadoEscaneo.VALIDA).isEmpty());
        assertTrue(registro.ultimoDelCodigo("qr-2", ResultadoEscaneo.SIN_RESPUESTA).isEmpty());
    }
}
