package com.palco.usuarios.seguridad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContrasenasTest {

    @Test
    void laContrasenaCorrectaVerificaYLaIncorrectaNo() {
        String hash = Contrasenas.hashear("palco1234");
        assertTrue(Contrasenas.verificar("palco1234", hash));
        assertFalse(Contrasenas.verificar("palco12345", hash));
    }

    @Test
    void dosHashesDeLaMismaContrasenaSonDistintosPorLaSal() {
        assertNotEquals(Contrasenas.hashear("palco1234"), Contrasenas.hashear("palco1234"));
    }

    @Test
    void verificaLosHashesDeLasCuentasDePrueba() {
        // El de titular@palco.test en V2__usuarios_de_prueba.sql
        String hash = "pbkdf2-sha256$310000$FpGLULULLzHBk0RIDgfaWQ==$sgFFg9euagg7EzRgMuR9tu4KCEmXJIRT8l//mZkMA/Q=";
        assertTrue(Contrasenas.verificar("palco1234", hash));
    }

    @Test
    void losHashesNuevosUsan600MilIteracionesYLosViejosSiguenVerificando() {
        assertEquals(600_000, Contrasenas.ITERACIONES);
        String nuevo = Contrasenas.hashear("palco1234");
        assertTrue(nuevo.startsWith("pbkdf2-sha256$600000$"), nuevo);
        assertTrue(Contrasenas.verificar("palco1234", nuevo));

        // Uno armado con 310.000, como los de las cuentas de prueba: se verifica con las suyas.
        String viejo = "pbkdf2-sha256$310000$FpGLULULLzHBk0RIDgfaWQ==$sgFFg9euagg7EzRgMuR9tu4KCEmXJIRT8l//mZkMA/Q=";
        assertTrue(Contrasenas.verificar("palco1234", viejo));
        assertFalse(Contrasenas.verificar("palco12345", viejo));
    }

    @Test
    void unHashMalFormadoNoVerificaNiRompe() {
        assertFalse(Contrasenas.verificar("palco1234", "texto-cualquiera"));
        assertFalse(Contrasenas.verificar("palco1234", "pbkdf2-sha256$abc$$"));
        assertFalse(Contrasenas.verificar(null, "x"));
    }
}
