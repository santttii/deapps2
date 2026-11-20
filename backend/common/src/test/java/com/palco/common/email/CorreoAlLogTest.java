package com.palco.common.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Qué deja en el log un email sin SMTP: por defecto, nada personal; en el modo de desarrollo, el
 * renglón entero del que las pruebas E2E, scripts/pruebas y el CI sacan el código.
 */
class CorreoAlLogTest {

    private static final Email CODIGO = new Email("ana@palco.test", "Tu código de Palco",
            "Hola Ana: tu código para verificar el email es 123456. Vence en 15 minutos.");

    private final Logger log = Logger.getLogger(CorreoAlLog.class.getName());
    private final List<LogRecord> registros = new ArrayList<>();
    private final Handler capturador = new Handler() {
        @Override
        public void publish(LogRecord registro) {
            registros.add(registro);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void escucharElLog() {
        log.addHandler(capturador);
        log.setUseParentHandlers(false);
    }

    @AfterEach
    void dejarDeEscuchar() {
        log.removeHandler(capturador);
        log.setUseParentHandlers(true);
    }

    private static CorreoAlLog correo(boolean completo) {
        CorreoAlLog correo = new CorreoAlLog();
        correo.completo = completo;
        return correo;
    }

    @Test
    void sinLaVariableElModoEsElQueNoEscribeDatosPersonales() {
        assumeTrue(System.getenv(CorreoAlLog.VARIABLE_COMPLETO) == null, "la variable está definida en esta máquina");

        assertFalse(new CorreoAlLog().completo);
    }

    @Test
    void porDefectoQuedanElDestinatarioEnmascaradoYElAsuntoPeroNiElCuerpoNiElCodigo() {
        correo(false).enviar(CODIGO);

        assertEquals(1, registros.size());
        assertEquals(Level.INFO, registros.get(0).getLevel());
        String renglon = registros.get(0).getMessage();
        assertEquals("Email para a***@palco.test (Tu código de Palco), sin el cuerpo: lo escribe "
                + "PALCO_CORREO_LOG_COMPLETO=true.", renglon);
        assertFalse(renglon.contains("ana@palco.test"), renglon);
        assertFalse(renglon.contains("Ana"), renglon);
        assertFalse(renglon.contains("123456"), renglon);
    }

    @Test
    void enElModoCompletoElRenglonEsElQueLeenLasPruebas() {
        correo(true).enviar(CODIGO);

        String renglon = registros.get(0).getMessage();
        assertEquals("Email para ana@palco.test (Tu código de Palco): Hola Ana: tu código para verificar el email "
                + "es 123456. Vence en 15 minutos.", renglon);
        // frontend/e2e/apoyo.ts (codigoDeVerificacion) y scripts/pruebas/escenarios.mjs.
        assertEquals("123456", primerGrupo("Email para ana@palco\\.test .*el email es (\\d{6})\\.", renglon));
        // El sed de los "Pasos manuales" del job api en .github/workflows/ci.yml.
        Email registro = new Email("postman-1a2b3c@palco.test", "Tu código de Palco",
                "Hola Postman: tu código para verificar el email es 654321. Vence en 15 minutos.");
        correo(true).enviar(registro);
        String deLaColeccion = registros.get(1).getMessage();
        assertEquals("postman-1a2b3c@palco.test",
                primerGrupo(".*Email para (postman-[0-9a-z]+@palco\\.test) .*", deLaColeccion));
        assertEquals("654321",
                primerGrupo(".*Email para postman-1a2b3c@palco.test \\(.*el email es ([0-9]{6})\\..*", deLaColeccion));
    }

    @Test
    void enElModoCompletoUnSaltoDeLineaEnElCuerpoNoCortaElRenglon() {
        correo(true).enviar(new Email("ana@palco.test", "Aviso", "Entradas:\n  - Platea (Ana)\r\n  - Platea (Juan)"));

        String renglon = registros.get(0).getMessage();
        assertFalse(renglon.contains("\n") || renglon.contains("\r"), renglon);
        assertTrue(renglon.endsWith("Entradas:   - Platea (Ana)    - Platea (Juan)"), renglon);
    }

    private static String primerGrupo(String expresion, String texto) {
        Matcher encontrado = Pattern.compile(expresion).matcher(texto);
        assertTrue(encontrado.find(), "no encontró " + expresion + " en: " + texto);
        return encontrado.group(1);
    }
}
