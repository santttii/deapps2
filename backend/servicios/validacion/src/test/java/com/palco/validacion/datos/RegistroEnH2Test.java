package com.palco.validacion.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceUnitTransactionType;

import org.flywaydb.core.Flyway;
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

    private String url;
    private EntityManagerFactory fabrica;
    private EntityManager em;
    private EscaneoRepositoryJpa registro;

    @BeforeEach
    void crearBase() {
        url = baseNueva();
        BaseDeDatos.migrar(fuente(), "validacion");
        abrir();
    }

    /** Una base en memoria, vacía y con las opciones del datasource de desarrollo. */
    private static String baseNueva() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
    }

    private JdbcDataSource fuente() {
        JdbcDataSource base = new JdbcDataSource();
        base.setURL(url);
        return base;
    }

    /** El repositorio sobre la base de {@code url}, que ya tiene que estar migrada. */
    private void abrir() {
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
                resultado == ResultadoEscaneo.VALIDA ? "T-00000001" : null, "U-puerta", "puerta@palco.test",
                HORA.plusMinutes(minuto)));
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
    void dosEscaneosDelMismoSegundoQuedanEnElOrdenEnQueSeGuardaron() {
        // DEF-04: la hora no tiene fracción de segundo, así que estos dos empatan. Los ids van al revés
        // del orden en que se guardan, para que un desempate por id dé el orden equivocado.
        guardar("ESC-zzzzzzzz", "qr-1", "ev-1", ResultadoEscaneo.VALIDA, 0);
        guardar("ESC-aaaaaaaa", "qr-1", "ev-1", ResultadoEscaneo.YA_USADA, 0);
        em.clear();

        List<String> ids = registro.listarPorEvento("ev-1", 10).stream().map(Escaneo::getId).toList();

        assertEquals(List.of("ESC-aaaaaaaa", "ESC-zzzzzzzz"), ids);
        assertEquals("ESC-aaaaaaaa",
                registro.ultimoDelCodigo("qr-1", ResultadoEscaneo.YA_USADA).orElseThrow().getId());
    }

    @Test
    void elOrdenLoPoneLaBaseYSubeConCadaEscaneo() {
        guardar("ESC-00000001", "qr-1", "ev-1", ResultadoEscaneo.VALIDA, 0);
        guardar("ESC-00000002", "qr-1", "ev-1", ResultadoEscaneo.YA_USADA, 0);
        em.clear();

        long primero = registro.buscarPorId("ESC-00000001").orElseThrow().getOrden();
        long segundo = registro.buscarPorId("ESC-00000002").orElseThrow().getOrden();

        assertTrue(segundo > primero, primero + " " + segundo);
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
        assertEquals("puerta@palco.test", leido.getEscaneadoPorEmail());
        assertEquals(HORA.plusMinutes(1), leido.getEscaneado());
        assertTrue(registro.buscarPorId("ESC-nada").isEmpty());
    }

    @Test
    void unEscaneoGuardadoAntesDeV4SeSigueLeyendoConElEmailEnNull() throws SQLException {
        // DEF-32: V4 agrega el email de quien escanea. Se arma otra base con el esquema hasta V3, se guarda
        // un escaneo como se guardaban entonces, sin email, y recién después se corre V4.
        cerrar();
        url = baseNueva();
        Flyway.configure(BaseDeDatos.class.getClassLoader())
                .dataSource(fuente())
                .schemas("validacion")
                .createSchemas(true)
                .locations("classpath:db/migracion")
                .target("3")
                .load()
                .migrate();
        try (Connection conexion = fuente().getConnection(); Statement sql = conexion.createStatement()) {
            sql.executeUpdate("""
                    insert into validacion.escaneo
                        (id, codigo_qr, evento_id, resultado, entrada_id, escaneado_por, escaneado)
                    values ('ESC-00000001', 'qr-1', 'ev-1', 'VALIDA', 'T-00000001', 'user-staff',
                        timestamp '2026-11-19 21:00:00')""");
        }
        BaseDeDatos.migrar(fuente(), "validacion");
        abrir();

        Escaneo viejo = registro.buscarPorId("ESC-00000001").orElseThrow();
        assertEquals("user-staff", viejo.getEscaneadoPor());
        assertNull(viejo.getEscaneadoPorEmail());

        // Y el registro lo lista junto a uno nuevo, que ya trae el email.
        guardar("ESC-00000002", "qr-1", "ev-1", ResultadoEscaneo.YA_USADA, 0);
        em.clear();
        List<Escaneo> delEvento = registro.listarPorEvento("ev-1", 10);
        assertEquals(List.of("ESC-00000002", "ESC-00000001"), delEvento.stream().map(Escaneo::getId).toList());
        assertEquals("puerta@palco.test", delEvento.get(0).getEscaneadoPorEmail());
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
