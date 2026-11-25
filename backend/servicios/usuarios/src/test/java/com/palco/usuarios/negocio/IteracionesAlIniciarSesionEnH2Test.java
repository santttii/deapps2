package com.palco.usuarios.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
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
import com.palco.usuarios.api.Dtos.LoginDto;
import com.palco.usuarios.api.Dtos.SesionDto;
import com.palco.usuarios.datos.IntentoLoginRepositoryJpa;
import com.palco.usuarios.datos.UsuarioRepositoryJpa;
import com.palco.usuarios.dominio.IntentoLogin;
import com.palco.usuarios.dominio.Rol;
import com.palco.usuarios.dominio.Usuario;
import com.palco.usuarios.seguridad.ClavesJwt;
import com.palco.usuarios.seguridad.Contrasenas;

/**
 * DEF-44: una cuenta cuyo hash tiene 310.000 iteraciones, como las de una base de antes
 * del 16/10, queda con 600.000 la primera vez que inicia sesión con la contraseña correcta.
 * Contra H2 en modo PostgreSQL, con las migraciones y los repositorios de verdad: el hash
 * se lee de la tabla con JDBC, así se ve lo que quedó guardado y no lo que tiene la
 * entidad en memoria. La transacción la abre el test y no el contenedor.
 */
class IteracionesAlIniciarSesionEnH2Test {

    private static final String CLAVE = "palco1234";
    /** El de titular@palco.test en V2__usuarios_de_prueba.sql, con 310.000 iteraciones. */
    private static final String HASH_DE_310_MIL =
            "pbkdf2-sha256$310000$FpGLULULLzHBk0RIDgfaWQ==$sgFFg9euagg7EzRgMuR9tu4KCEmXJIRT8l//mZkMA/Q=";
    private static final String VIEJA = "vieja@palco.test.ar";
    private static final String NUEVA = "nueva@palco.test.ar";

    private JdbcDataSource base;
    private EntityManagerFactory fabrica;
    private EntityManager em;
    private ServicioDeUsuarios servicio;
    private String hashDeLaNueva;

    @BeforeEach
    void crearBase() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "usuarios");

        fabrica = new PersistenceConfiguration("usuarios-h2-iteraciones")
                .transactionType(PersistenceUnitTransactionType.RESOURCE_LOCAL)
                .managedClass(Usuario.class)
                .managedClass(IntentoLogin.class)
                .property(PersistenceConfiguration.JDBC_URL, url)
                // Las tablas las crea Flyway, igual que en el servidor.
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "none")
                .property("hibernate.default_schema", "usuarios")
                .property("jakarta.persistence.validation.mode", "none")
                .createEntityManagerFactory();
        em = fabrica.createEntityManager();
        UsuarioRepositoryJpa usuarios = new UsuarioRepositoryJpa();
        IntentoLoginRepositoryJpa intentos = new IntentoLoginRepositoryJpa();
        conEntityManager(usuarios, em);
        conEntityManager(intentos, em);

        ClavesJwt claves = mock(ClavesJwt.class);
        KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
        generador.initialize(2048);
        when(claves.privada()).thenReturn((RSAPrivateKey) generador.generateKeyPair().getPrivate());
        when(claves.kid()).thenReturn("clave-de-prueba");

        servicio = new ServicioDeUsuarios();
        servicio.usuarios = usuarios;
        servicio.intentos = intentos;
        servicio.claves = claves;

        hashDeLaNueva = Contrasenas.hashear(CLAVE);
        em.getTransaction().begin();
        usuarios.guardar(cuenta("user-vieja001", "40123456", VIEJA, HASH_DE_310_MIL));
        usuarios.guardar(cuenta("user-nueva001", "40123457", NUEVA, hashDeLaNueva));
        em.getTransaction().commit();
        em.clear();
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
    }

    @Test
    void unaCuentaCon310MilIteracionesIngresaYQuedaCon600Mil() throws Exception {
        assertNotNull(login(VIEJA, CLAVE).token());

        String guardado = hashGuardado(VIEJA);
        assertTrue(guardado.startsWith("pbkdf2-sha256$600000$"), guardado);
        assertTrue(Contrasenas.verificar(CLAVE, guardado), "el hash nuevo es de la misma contraseña");
        assertNotNull(login(VIEJA, CLAVE).token(), "y con él la cuenta sigue entrando");
    }

    @Test
    void conLaContrasenaEquivocadaElHashNoCambia() throws Exception {
        assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> login(VIEJA, "otra-clave1"));

        assertEquals(HASH_DE_310_MIL, hashGuardado(VIEJA));
    }

    @Test
    void unHashQueYaTiene600MilNoSeVuelveACalcular() throws Exception {
        assertNotNull(login(NUEVA, CLAVE).token());

        assertEquals(hashDeLaNueva, hashGuardado(NUEVA));
    }

    private static Usuario cuenta(String id, String dni, String email, String hash) {
        Usuario usuario = new Usuario(id, "Ana", "Gómez", dni, email, null, LocalDate.of(1995, 5, 17), hash, Rol.USER);
        usuario.marcarEmailVerificado();
        return usuario;
    }

    /** El login en su propia transacción. Se confirma aunque falle, como en el servidor: el intento queda guardado. */
    private SesionDto login(String email, String clave) {
        em.getTransaction().begin();
        try {
            return servicio.iniciarSesion(new LoginDto(email, clave));
        } finally {
            em.getTransaction().commit();
            em.clear();
        }
    }

    private String hashGuardado(String email) throws Exception {
        try (Connection conexion = base.getConnection();
                PreparedStatement consulta = conexion.prepareStatement(
                        "select password_hash from usuarios.usuario where email = ?")) {
            consulta.setString(1, email);
            try (ResultSet fila = consulta.executeQuery()) {
                assertTrue(fila.next(), "no está la cuenta " + email);
                return fila.getString(1);
            }
        }
    }

    /** El campo {@code em} de los repositorios es del paquete datos: desde acá se lo pone por reflexión. */
    private static void conEntityManager(Object repositorio, EntityManager em) {
        try {
            var campo = repositorio.getClass().getDeclaredField("em");
            campo.setAccessible(true);
            campo.set(repositorio, em);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
