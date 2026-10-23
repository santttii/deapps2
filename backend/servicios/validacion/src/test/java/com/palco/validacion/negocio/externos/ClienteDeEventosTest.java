package com.palco.validacion.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;

/**
 * Qué hace Validación con cada respuesta de {@code GET /api/eventos/mios}. Sin
 * servidor: la llamada HTTP se reemplaza por una respuesta preparada.
 */
class ClienteDeEventosTest {

    private static final String DOS_EVENTOS = """
            [{"id":"ev-1","slug":"atletico-norte-racing-sur","titulo":"Atlético Norte vs. Racing del Sur",
              "sectores":[{"id":"sec-1-1","nombre":"Popular norte","precio":18000,"cupo":4000,"vendidas":3680}]},
             {"id":"ev-4","slug":"el-metodo-gronholm","titulo":"El método Grönholm","sectores":[]}]""";

    /** Entrega la respuesta que le cargó el test y anota con qué token se llamó. */
    private static class EventosSinRed extends ClienteDeEventos {
        Respuesta respuesta;
        final List<String> tokens = new ArrayList<>();

        void responde(int estado, String cuerpo) {
            respuesta = new Respuesta(estado, cuerpo);
        }

        @Override
        Respuesta enviar(String token) {
            tokens.add(token);
            return respuesta;
        }
    }

    private final EventosSinRed eventos = new EventosSinRed();

    @Test
    void devuelveLosIdsDeLosEventosYPreguntaConElTokenDelOrganizador() {
        eventos.responde(200, DOS_EVENTOS);

        assertEquals(Set.of("ev-1", "ev-4"), eventos.eventosDelOrganizador("token-del-organizador"));
        assertEquals(List.of("token-del-organizador"), eventos.tokens);
    }

    @Test
    void unOrganizadorSinEventosRecibeUnaListaVacia() {
        eventos.responde(200, "[]");

        assertEquals(Set.of(), eventos.eventosDelOrganizador("token"));
    }

    @Test
    void cualquierOtroEstadoEsSinRespuesta() {
        for (int estado : new int[] { 401, 403, 500, 503 }) {
            eventos.responde(estado, null);

            assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.eventosDelOrganizador("token"),
                    "estado " + estado);
        }
    }

    @Test
    void unCuerpoQueNoEsUnaListaEsSinRespuesta() {
        for (String cuerpo : new String[] { null, "", "{\"id\":\"ev-1\"}", "no es json" }) {
            eventos.responde(200, cuerpo);

            assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.eventosDelOrganizador("token"),
                    "cuerpo " + cuerpo);
        }
    }
}
