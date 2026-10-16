package com.palco.common.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * El 500 inesperado lleva un id que también queda en el log, junto con la traza. Se prueba la parte
 * que arma el problema y escribe el log; la respuesta HTTP la arma JAX-RS con ese mismo problema.
 */
class ErrorInesperadoTest {

    private final Logger log = Logger.getLogger(ManejadoresDeErrores.Inesperado.class.getName());
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
        log.setUseParentHandlers(false); // la traza de prueba no ensucia la salida de los tests
    }

    @AfterEach
    void dejarDeEscuchar() {
        log.removeHandler(capturador);
        log.setUseParentHandlers(true);
    }

    @Test
    void elIdDeLaRespuestaEsElMismoQueQuedaEnElLogConLaTraza() {
        IllegalStateException error = new IllegalStateException("detalle interno que no tiene que salir");

        Problema problema = ManejadoresDeErrores.Inesperado.registrar(error, "/api/v1/ventas");

        assertNotNull(problema.idError());
        UUID.fromString(problema.idError()); // es un UUID: no dice nada del error
        assertEquals(1, registros.size());
        LogRecord registro = registros.get(0);
        assertEquals(Level.SEVERE, registro.getLevel());
        assertTrue(registro.getMessage().contains(problema.idError()), registro.getMessage());
        assertSame(error, registro.getThrown(), "la traza va al log");

        assertEquals("{\"type\":\"/problemas/error-inesperado\",\"title\":\"Error inesperado\",\"status\":500,"
                + "\"detail\":\"Ocurrió un error inesperado.\",\"instance\":\"/api/v1/ventas\","
                + "\"idError\":\"" + problema.idError() + "\"}",
                comoLoEscribeElServidor(problema));
    }

    @Test
    void cadaErrorTieneSuPropioId() {
        Problema uno = ManejadoresDeErrores.Inesperado.registrar(new RuntimeException(), "/x");
        Problema otro = ManejadoresDeErrores.Inesperado.registrar(new RuntimeException(), "/x");
        assertNotEquals(uno.idError(), otro.idError());
    }

    @Test
    void losDemasErroresNoLlevanId() {
        Problema conflicto = Problema.de(409, "cupo-agotado", "Conflicto", "No quedan entradas.", "/api/v1/ventas");
        assertFalse(comoLoEscribeElServidor(conflicto).contains("idError"));
    }

    private static String comoLoEscribeElServidor(Problema problema) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        new JsonProvider().writeTo(problema, Problema.class, Problema.class, new Annotation[0], null, null, salida);
        return salida.toString(StandardCharsets.UTF_8);
    }
}
