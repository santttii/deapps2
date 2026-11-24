package com.palco.usuarios.datos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
import com.palco.usuarios.dominio.CodigoVerificacion;
import com.palco.usuarios.dominio.IntentoLogin;
import com.palco.usuarios.dominio.Rol;
import com.palco.usuarios.dominio.Usuario;

/**
 * Los mismos casos que {@link RepositoriosEnH2Test}, contra un PostgreSQL de
 * verdad: la imagen del servicio {@code db} del docker-compose, levantada en un
 * contenedor con Testcontainers. Las consultas son las de los repositorios de
 * Usuarios, con Hibernate, sobre el esquema que crean las migraciones de Flyway
 * (incluidas las cuentas de prueba y el borrado en cascada de los códigos).
 *
 * Lo corre failsafe en "mvn verify" (ver backend/pom.xml). Sin Docker se
 * saltea con un aviso.
 */
@EnabledIfSystemProperty(named = "palco.pruebas.integracion", matches = "true",
        disabledReason = "Lo corre failsafe en mvn verify (-Dpalco.pruebas.integracion=true)")
class RepositoriosEnPostgresIT {

    private static final String EMAIL = "a".repeat(64); // un SHA-256 en hexadecimal ocupa 64 caracteres
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 23, 9, 0);

    /** Un contenedor para todos los tests de la clase; cada test migra un esquema nuevo. */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private PGSimpleDataSource base;
    private EntityManagerFactory fabrica;
    private EntityManager em;
    private UsuarioRepositoryJpa usuarios;
    private CodigoVerificacionRepositoryJpa codigos;
    private IntentoLoginRepositoryJpa intentos;

    @BeforeAll
    static void levantarPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Sin Docker no hay PostgreSQL en contenedor: se saltea (los mismos casos corren en RepositoriosEnH2Test)");
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
            sql.execute("drop schema if exists usuarios cascade");
        }
        BaseDeDatos.migrar(base, "usuarios");

        fabrica = new PersistenceConfiguration("usuarios-postgresql")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Usuario.class)
                .managedClass(CodigoVerificacion.class)
                .managedClass(IntentoLogin.class)
                .property(PersistenceConfiguration.JDBC_URL, POSTGRES.getJdbcUrl())
                .property(PersistenceConfiguration.JDBC_USER, POSTGRES.getUsername())
                .property(PersistenceConfiguration.JDBC_PASSWORD, POSTGRES.getPassword())
                // Las tablas las crea Flyway, igual que en el servidor.
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "none")
                .property("hibernate.default_schema", "usuarios")
                .property("jakarta.persistence.validation.mode", "none")
                .createEntityManagerFactory();
        em = fabrica.createEntityManager();
        usuarios = new UsuarioRepositoryJpa();
        usuarios.em = em;
        codigos = new CodigoVerificacionRepositoryJpa();
        codigos.em = em;
        intentos = new IntentoLoginRepositoryJpa();
        intentos.em = em;
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
    }

    /** Corre el paso en una transacción propia, como lo haría el contenedor con cada pedido. */
    private <T> T enTransaccion(Supplier<T> paso) {
        em.getTransaction().begin();
        T resultado = paso.get();
        em.getTransaction().commit();
        em.clear();
        return resultado;
    }

    private void enTransaccion(Runnable paso) {
        enTransaccion(() -> {
            paso.run();
            return null;
        });
    }

    // --- Intentos de login ---

    @Test
    void recientesDevuelveLosDeLaVentanaDelMasNuevoAlMasViejoYComoMuchoElMaximo() {
        enTransaccion(() -> {
            intentos.registrar(EMAIL, AHORA.minusMinutes(20), AHORA.minusMinutes(30));
            intentos.registrar(EMAIL, AHORA.minusMinutes(10), AHORA.minusMinutes(30));
            intentos.registrar(EMAIL, AHORA.minusMinutes(5), AHORA.minusMinutes(30));
            intentos.registrar(EMAIL, AHORA.minusMinutes(1), AHORA.minusMinutes(30));
            intentos.registrar("b".repeat(64), AHORA.minusMinutes(1), AHORA.minusMinutes(30));
        });

        List<LocalDateTime> recientes = enTransaccion(() -> intentos.recientes(EMAIL, AHORA.minusMinutes(15), 2));

        assertEquals(List.of(AHORA.minusMinutes(1), AHORA.minusMinutes(5)), recientes);
    }

    @Test
    void registrarBorraLosFallidosQueYaSalieronDeLaVentana() throws Exception {
        enTransaccion(() -> intentos.registrar(EMAIL, AHORA.minusMinutes(20), AHORA.minusMinutes(30)));
        enTransaccion(() -> intentos.registrar(EMAIL, AHORA, AHORA.minusMinutes(15)));

        assertEquals(1, filas("select count(*) from usuarios.intento_login where email_hash = '" + EMAIL + "'"));
    }

    @Test
    void olvidarBorraSoloLosDeEseEmail() throws Exception {
        enTransaccion(() -> {
            intentos.registrar(EMAIL, AHORA, AHORA.minusMinutes(15));
            intentos.registrar("b".repeat(64), AHORA, AHORA.minusMinutes(15));
        });

        enTransaccion(() -> intentos.olvidar(EMAIL));

        assertEquals(1, filas("select count(*) from usuarios.intento_login"));
    }

    // --- Cuentas y códigos ---

    @Test
    void lasCuentasDePruebaSeEncuentranPorEmailYPorDni() {
        Usuario porEmail = enTransaccion(() -> usuarios.buscarPorEmail("titular@palco.test")).orElseThrow();
        Usuario porDni = enTransaccion(() -> usuarios.buscarPorDni(porEmail.getDni())).orElseThrow();
        Usuario porId = enTransaccion(() -> usuarios.buscarPorId(porEmail.getId())).orElseThrow();

        assertEquals(porEmail.getId(), porDni.getId());
        assertEquals(porEmail.getId(), porId.getId());
        assertTrue(enTransaccion(() -> usuarios.buscarPorEmail("nadie@palco.test")).isEmpty());
    }

    @Test
    void borrarUnaCuentaSinVerificarDejaGuardarOtraConElMismoEmailEnLaMismaTransaccion() throws Exception {
        enTransaccion(() -> {
            Usuario abandonada = cuenta("user-viejo01");
            usuarios.guardar(abandonada);
            codigos.guardar(codigo(abandonada));
        });

        // Lo que hace el registro: borra la cuenta abandonada y guarda la nueva, todo junto.
        enTransaccion(() -> {
            usuarios.borrar(usuarios.buscarPorEmail("ana@palco.test").orElseThrow());
            Usuario nueva = cuenta("user-nuevo01");
            usuarios.guardar(nueva);
            codigos.guardar(codigo(nueva));
        });

        assertEquals("user-nuevo01", enTransaccion(() -> usuarios.buscarPorEmail("ana@palco.test")).orElseThrow()
                .getId());
        // El código de la cuenta borrada se fue con ella (on delete cascade).
        assertEquals(1, filas("select count(*) from usuarios.codigo_verificacion"));
    }

    @Test
    void elIntentoSumadoAlCodigoQuedaGuardadoYElCodigoSePuedeBorrar() throws Exception {
        enTransaccion(() -> {
            Usuario usuario = cuenta("user-nuevo01");
            usuarios.guardar(usuario);
            codigos.guardar(codigo(usuario));
        });

        em.getTransaction().begin();
        CodigoVerificacion codigo = codigos.buscarPorUsuario("user-nuevo01").orElseThrow();
        codigo.sumarIntento();
        codigos.guardarCambios(codigo);
        em.getTransaction().commit();
        em.clear();

        assertEquals(1, filas("select intentos from usuarios.codigo_verificacion where usuario_id = 'user-nuevo01'"));
        enTransaccion(() -> codigos.borrar(codigos.buscarPorUsuario("user-nuevo01").orElseThrow()));
        assertTrue(enTransaccion(() -> codigos.buscarPorUsuario("user-nuevo01")).isEmpty());
    }

    private static Usuario cuenta(String id) {
        return new Usuario(id, "Ana", "Gómez", "40123456", "ana@palco.test", null, LocalDate.of(1995, 5, 17),
                "hash-de-prueba", Rol.USER);
    }

    private static CodigoVerificacion codigo(Usuario usuario) {
        CodigoVerificacion codigo = new CodigoVerificacion(usuario.getId());
        codigo.renovar("c".repeat(64), AHORA, AHORA.plusMinutes(15));
        return codigo;
    }

    private long filas(String sql) throws SQLException {
        try (Connection conexion = base.getConnection();
                PreparedStatement consulta = conexion.prepareStatement(sql);
                ResultSet fila = consulta.executeQuery()) {
            assertTrue(fila.next());
            return fila.getLong(1);
        }
    }
}
