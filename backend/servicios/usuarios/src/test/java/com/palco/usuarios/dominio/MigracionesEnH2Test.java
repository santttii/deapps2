package com.palco.usuarios.dominio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.datos.BaseDeDatos;
import com.palco.usuarios.seguridad.Contrasenas;

/**
 * Corre las migraciones de db/migracion sobre H2 en modo PostgreSQL, con las mismas opciones que
 * el datasource de WildFly en desarrollo. Usa la tabla intento_login (V4) como la usan las
 * consultas de {@code IntentoLoginRepositoryJpa}, revisa los hashes de las cuentas de prueba (V5)
 * y comprueba la tabla reinicio_password con su borrado en cascada (V7).
 */
class MigracionesEnH2Test {

    private JdbcDataSource base;

    @BeforeEach
    void crearBase() {
        base = new JdbcDataSource();
        // Una base nueva por test: el nombre al azar evita que se compartan entre tests.
        base.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        BaseDeDatos.migrar(base, "usuarios");
    }

    @Test
    void guardaYCuentaLosFallidosDeUnEmailDentroDeLaVentana() throws Exception {
        String email = "a".repeat(64); // un SHA-256 en hexadecimal ocupa 64 caracteres
        LocalDateTime ahora = LocalDateTime.of(2026, 10, 23, 9, 0);
        try (Connection conexion = base.getConnection()) {
            insertar(conexion, email, ahora.minusMinutes(20)); // fuera de la ventana
            insertar(conexion, email, ahora.minusMinutes(10));
            insertar(conexion, email, ahora.minusMinutes(1));
            insertar(conexion, "b".repeat(64), ahora.minusMinutes(1)); // otro email

            try (PreparedStatement consulta = conexion.prepareStatement(
                    "select count(*) from usuarios.intento_login where email_hash = ? and momento > ?")) {
                consulta.setString(1, email);
                consulta.setTimestamp(2, Timestamp.valueOf(ahora.minusMinutes(15)));
                try (ResultSet fila = consulta.executeQuery()) {
                    assertTrue(fila.next());
                    assertEquals(2, fila.getInt(1));
                }
            }
        }
    }

    /**
     * V5: las cuentas de prueba quedan con las iteraciones de una cuenta nueva y la misma contraseña. Son
     * seis: las cuatro de V2 y V3 y las dos de V6, que ya nacen con 600.000.
     */
    @Test
    void lasCuentasDePruebaQuedanConLasIteracionesNuevasYLaMismaContrasena() throws Exception {
        int cuentas = 0;
        try (Connection conexion = base.getConnection();
                PreparedStatement consulta = conexion.prepareStatement(
                        "select email, password_hash from usuarios.usuario order by email");
                ResultSet fila = consulta.executeQuery()) {
            while (fila.next()) {
                cuentas++;
                String email = fila.getString(1);
                String hash = fila.getString(2);
                assertTrue(hash.startsWith("pbkdf2-sha256$" + Contrasenas.ITERACIONES + "$"), email + ": " + hash);
                assertTrue(Contrasenas.verificar("palco1234", hash), email + " tiene que seguir con palco1234");
            }
        }
        assertEquals(6, cuentas);
    }

    /**
     * V7: el código de reinicio es uno por cuenta y se va con ella. Si no cayera
     * por el ON DELETE CASCADE, borrar una cuenta sin verificar (lo hace el
     * registro) dejaría una fila huérfana que rompe la clave foránea.
     */
    @Test
    void elCodigoDeReinicioEsUnoPorCuentaYSeBorraConLaCuenta() throws Exception {
        try (Connection conexion = base.getConnection()) {
            String usuarioId = unUsuarioDePrueba(conexion);

            try (PreparedStatement insert = conexion.prepareStatement(
                    "insert into usuarios.reinicio_password (usuario_id, codigo_hash, expira, enviado)"
                            + " values (?, ?, ?, ?)")) {
                insert.setString(1, usuarioId);
                insert.setString(2, "c".repeat(64));
                insert.setTimestamp(3, Timestamp.valueOf(LocalDateTime.of(2026, 10, 30, 12, 15)));
                insert.setTimestamp(4, Timestamp.valueOf(LocalDateTime.of(2026, 10, 30, 12, 0)));
                assertEquals(1, insert.executeUpdate());
            }
            assertEquals(0, intentosDelCodigo(conexion, usuarioId), "intentos arranca en 0 por defecto");

            try (PreparedStatement borrar = conexion.prepareStatement(
                    "delete from usuarios.usuario where id = ?")) {
                borrar.setString(1, usuarioId);
                assertEquals(1, borrar.executeUpdate());
            }
            try (PreparedStatement consulta = conexion.prepareStatement(
                    "select count(*) from usuarios.reinicio_password where usuario_id = ?")) {
                consulta.setString(1, usuarioId);
                try (ResultSet fila = consulta.executeQuery()) {
                    assertTrue(fila.next());
                    assertEquals(0, fila.getInt(1), "el código tiene que irse con la cuenta");
                }
            }
        }
    }

    private static String unUsuarioDePrueba(Connection conexion) throws Exception {
        try (PreparedStatement consulta = conexion.prepareStatement(
                "select id from usuarios.usuario order by email limit 1");
                ResultSet fila = consulta.executeQuery()) {
            assertTrue(fila.next());
            return fila.getString(1);
        }
    }

    private static int intentosDelCodigo(Connection conexion, String usuarioId) throws Exception {
        try (PreparedStatement consulta = conexion.prepareStatement(
                "select intentos from usuarios.reinicio_password where usuario_id = ?")) {
            consulta.setString(1, usuarioId);
            try (ResultSet fila = consulta.executeQuery()) {
                assertTrue(fila.next());
                return fila.getInt(1);
            }
        }
    }

    private static void insertar(Connection conexion, String emailHash, LocalDateTime momento) throws Exception {
        try (PreparedStatement insert = conexion.prepareStatement(
                "insert into usuarios.intento_login (id, email_hash, momento) values (?, ?, ?)")) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, emailHash);
            insert.setTimestamp(3, Timestamp.valueOf(momento));
            assertEquals(1, insert.executeUpdate());
        }
    }
}
