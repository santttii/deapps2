package com.palco.common.datos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class BaseDeDatosTest {

    @Test
    void reconoceLaClaveDuplicadaAunqueVengaEnvuelta() {
        SQLException duplicada = new SQLException("Unique index or primary key violation", "23505");

        assertTrue(BaseDeDatos.esClaveDuplicada(duplicada));
        // Así llega desde JPA: la excepción de la base queda dos o tres niveles adentro.
        assertTrue(BaseDeDatos.esClaveDuplicada(
                new IllegalStateException("flush", new RuntimeException("constraint", duplicada))));
    }

    @Test
    void otraFallaDeLaBaseNoEsClaveDuplicada() {
        // 23502: columna obligatoria en null. 08001: no se pudo conectar.
        assertFalse(BaseDeDatos.esClaveDuplicada(new RuntimeException(new SQLException("not null", "23502"))));
        assertFalse(BaseDeDatos.esClaveDuplicada(new SQLException("sin conexión", "08001")));
        assertFalse(BaseDeDatos.esClaveDuplicada(new SQLException("sin código")));
    }

    @Test
    void unaExcepcionQueNoVieneDeLaBaseTampoco() {
        assertFalse(BaseDeDatos.esClaveDuplicada(new IllegalArgumentException("23505")));
        assertFalse(BaseDeDatos.esClaveDuplicada(null));
    }
}
