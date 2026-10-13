package com.palco.common.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** El cuerpo de los errores, escrito con el mismo conversor que usa el servidor ({@link JsonProvider}). */
class ProblemaTest {

    @Test
    void saleConLosCamposYElOrdenDelContrato() {
        Problema problema = Problema.de(409, "cupo-agotado", "Conflicto",
                "No quedan suficientes entradas en Platea A.", "/api/v1/ventas");

        assertEquals("{\"type\":\"/problemas/cupo-agotado\",\"title\":\"Conflicto\",\"status\":409,"
                + "\"detail\":\"No quedan suficientes entradas en Platea A.\",\"instance\":\"/api/v1/ventas\"}",
                comoLoEscribeElServidor(problema));
    }

    @Test
    void erroresApareceSoloSiHayErroresPorCampo() {
        Problema conCampos = Problema.de(400, "datos-invalidos", "Datos inválidos", "Hay datos inválidos.",
                "/api/usuarios/registro", Map.of("email", "Ya hay una cuenta con ese email."));
        assertEquals("{\"type\":\"/problemas/datos-invalidos\",\"title\":\"Datos inválidos\",\"status\":400,"
                + "\"detail\":\"Hay datos inválidos.\",\"instance\":\"/api/usuarios/registro\","
                + "\"errores\":{\"email\":\"Ya hay una cuenta con ese email.\"}}",
                comoLoEscribeElServidor(conCampos));

        assertNull(Problema.de(400, "datos-invalidos", "Datos inválidos", "x", "/ruta", Map.of()).errores());
        assertNull(Problema.de(400, "datos-invalidos", "Datos inválidos", "x", "/ruta", null).errores());
    }

    @Test
    void unCampoSinValorNoSeEscribe() {
        Problema sinRuta = Problema.de(500, "error-inesperado", "Error inesperado", "Ocurrió un error inesperado.", null);

        assertEquals("{\"type\":\"/problemas/error-inesperado\",\"title\":\"Error inesperado\",\"status\":500,"
                + "\"detail\":\"Ocurrió un error inesperado.\"}",
                comoLoEscribeElServidor(sinRuta));
    }

    @Test
    void elProblemaQueEsSoloElCodigoHttpSaleSinDetail() {
        // Así lo arma el manejador para un 405 o un 415: no hay texto nuestro para el usuario,
        // y el mensaje interno del servidor ("RESTEASY003650: …") no tiene que llegar al cliente.
        Problema soloElCodigo = new Problema(Problema.TIPO_SIN_DETALLE, "Method Not Allowed", 405, null,
                "/api/usuarios/servicios/token", null);

        assertEquals("{\"type\":\"about:blank\",\"title\":\"Method Not Allowed\",\"status\":405,"
                + "\"instance\":\"/api/usuarios/servicios/token\"}",
                comoLoEscribeElServidor(soloElCodigo));
    }

    private static String comoLoEscribeElServidor(Problema problema) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        new JsonProvider().writeTo(problema, Problema.class, Problema.class, new Annotation[0], null, null, salida);
        return salida.toString(StandardCharsets.UTF_8);
    }
}
