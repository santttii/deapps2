package com.palco.usuarios.dominio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.palco.common.datos.BaseDeDatos;
import com.palco.usuarios.dominio.Usuario.ConversorRol;

/**
 * Cómo se guarda el rol en la columna usuario.rol: en minúsculas, el mismo
 * texto que viaja en el token y que piden los {@code @RolesAllowed}. La columna
 * no tiene un check con la lista de roles (ver V1__crear_tablas.sql): lo que
 * no es un rol lo frena el conversor al leer la fila.
 */
class ConversorRolTest {

    private final ConversorRol conversor = new ConversorRol();

    @ParameterizedTest
    @CsvSource({ "USER, user", "ORGANIZER, organizer", "STAFF, staff" })
    void cadaRolSeGuardaComoLoPidenLosRolesAllowedYSeLeeIgual(Rol rol, String columna) {
        assertEquals(columna, conversor.convertToDatabaseColumn(rol));
        assertEquals(rol, conversor.convertToEntityAttribute(columna));
    }

    @Test
    void unValorQueNoEsUnRolNoSeLeeComoNingunRol() {
        assertThrows(IllegalArgumentException.class, () -> conversor.convertToEntityAttribute("admin"));
        assertThrows(IllegalArgumentException.class, () -> conversor.convertToEntityAttribute("organizador"));
    }

    @Test
    void sinRolDevuelveNullEnLosDosSentidosYNoFalla() {
        // La columna es "not null": un usuario sin rol lo rechaza la base al guardarlo, no el conversor
        assertNull(conversor.convertToDatabaseColumn(null));
        assertNull(conversor.convertToEntityAttribute(null));
    }

    @Test
    void lasCuentasQueCreanLasMigracionesTienenRolesQueElConversorLee() throws Exception {
        JdbcDataSource base = new JdbcDataSource();
        base.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
        BaseDeDatos.migrar(base, "usuarios");

        List<String> roles = new ArrayList<>();
        try (Connection conexion = base.getConnection();
                Statement consulta = conexion.createStatement();
                ResultSet filas = consulta.executeQuery("select rol from usuarios.usuario")) {
            while (filas.next()) {
                roles.add(filas.getString(1));
            }
        }

        assertFalse(roles.isEmpty(), "las migraciones crean las cuentas de prueba");
        for (String rol : roles) {
            Rol leido = conversor.convertToEntityAttribute(rol);
            assertEquals(rol, conversor.convertToDatabaseColumn(leido), "el rol de la base está en minúsculas");
        }
    }
}
