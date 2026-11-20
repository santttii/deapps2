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
import java.util.LinkedHashMap;
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
 * La propiedad {@code palco.usuarios.cuentas-de-prueba} contra H2 en modo
 * PostgreSQL, con las cuentas que cargan las migraciones y los repositorios de
 * verdad. El inicio de sesión es el de {@link ServicioDeUsuarios}, así que "no
 * entra" quiere decir lo mismo que en el servidor: 401 con la contraseña del
 * README. La transacción la abre el test y no el contenedor.
 */
class DesactivacionDeCuentasDePruebaEnH2Test {

    private static final String CLAVE_DE_PRUEBA = "palco1234";
    /** Tiene "palco.test" en el email, pero no termina en @palco.test: no es de prueba. */
    private static final String EMAIL_NORMAL = "ana@palco.test.ar";
    private static final String CLAVE_NORMAL = "otra-clave-1";

    private JdbcDataSource base;
    private EntityManagerFactory fabrica;
    private EntityManager em;
    private UsuarioRepositoryJpa usuarios;
    private ServicioDeUsuarios servicio;
    private DesactivacionDeCuentasDePrueba desactivacion;

    @BeforeEach
    void crearBase() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        base = new JdbcDataSource();
        base.setURL(url);
        BaseDeDatos.migrar(base, "usuarios");

        fabrica = new PersistenceConfiguration("usuarios-h2-cuentas-de-prueba")
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
        usuarios = new UsuarioRepositoryJpa();
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
        desactivacion = new DesactivacionDeCuentasDePrueba();
        desactivacion.usuarios = usuarios;

        // Una cuenta registrada como cualquier otra, con el email verificado.
        enTransaccion(() -> {
            Usuario normal = new Usuario("user-normal01", "Ana", "Gómez", "40123456", EMAIL_NORMAL, null,
                    LocalDate.of(1995, 5, 17), Contrasenas.hashear(CLAVE_NORMAL), Rol.USER);
            normal.marcarEmailVerificado();
            usuarios.guardar(normal);
            return null;
        });
    }

    @AfterEach
    void cerrar() {
        em.close();
        fabrica.close();
    }

    @Test
    void conLaPropiedadEnFalseLaCuentaDePruebaNoEntraYUnaNormalSi() throws Exception {
        assertEquals(6, enTransaccion(() -> desactivacion.aplicar(false)));

        Map<String, String> hashes = hashesPorEmail();
        hashes.forEach((email, hash) -> {
            if (email.endsWith("@palco.test")) {
                assertEquals(Contrasenas.INUTILIZABLE, hash, email);
            }
        });
        assertTrue(Contrasenas.verificar(CLAVE_NORMAL, hashes.get(EMAIL_NORMAL)), "la cuenta normal no se toca");

        ErroresDeNegocio.NoAutenticado error = assertThrows(ErroresDeNegocio.NoAutenticado.class,
                () -> login("titular@palco.test", CLAVE_DE_PRUEBA));
        assertEquals("Revisá el email y la contraseña.", error.getMessage(), "el mismo texto que una clave mala");
        assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> login("organizador@palco.test", CLAVE_DE_PRUEBA));
        assertNotNull(login(EMAIL_NORMAL, CLAVE_NORMAL).token());
    }

    @Test
    void conLaPropiedadEnTrueNoCambiaNada() throws Exception {
        Map<String, String> antes = hashesPorEmail();

        assertEquals(0, enTransaccion(() -> desactivacion.aplicar(true)));

        assertEquals(antes, hashesPorEmail());
        assertNotNull(login("titular@palco.test", CLAVE_DE_PRUEBA).token());
        assertNotNull(login(EMAIL_NORMAL, CLAVE_NORMAL).token());
    }

    @Test
    void correrloDosVecesNoRompeNada() throws Exception {
        assertEquals(6, enTransaccion(() -> desactivacion.aplicar(false)));
        Map<String, String> despuesDeLaPrimera = hashesPorEmail();

        assertEquals(0, enTransaccion(() -> desactivacion.aplicar(false)), "la segunda vez no hay nada que hacer");

        assertEquals(despuesDeLaPrimera, hashesPorEmail());
        assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> login("puerta@palco.test", CLAVE_DE_PRUEBA));
        assertNotNull(login(EMAIL_NORMAL, CLAVE_NORMAL).token());
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

    private <T> T enTransaccion(Supplier<T> paso) {
        em.getTransaction().begin();
        T resultado = paso.get();
        em.getTransaction().commit();
        em.clear();
        return resultado;
    }

    private Map<String, String> hashesPorEmail() throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        try (Connection conexion = base.getConnection();
                PreparedStatement consulta = conexion.prepareStatement(
                        "select email, password_hash from usuarios.usuario order by email");
                ResultSet fila = consulta.executeQuery()) {
            while (fila.next()) {
                hashes.put(fila.getString(1), fila.getString(2));
            }
        }
        assertEquals(7, hashes.size(), "las seis de prueba y la normal");
        return hashes;
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
