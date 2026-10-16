package com.palco.common.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** El 401 que emite el servidor por un token que no se puede verificar, escrito como Problem Details. */
class ErrorDeAutenticacionServletTest {

    @Test
    void elCuerpoTieneLosCamposDelContratoEnOrden() {
        assertEquals("{\"type\":\"/problemas/token-invalido\",\"title\":\"No autenticado\",\"status\":401,"
                + "\"detail\":\"La sesión no es válida o venció. Iniciá sesión de nuevo.\","
                + "\"instance\":\"/api/v1/ventas\"}",
                ErrorDeAutenticacionServlet.cuerpo("/api/v1/ventas"));
    }

    @Test
    void elDesafioEsBearerConElErrorDelEstandar() {
        assertTrue(ErrorDeAutenticacionServlet.DESAFIO.startsWith("Bearer"));
        assertEquals("Bearer error=\"invalid_token\"", ErrorDeAutenticacionServlet.DESAFIO);
    }
}
