package com.palco.validacion.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.seguridad.TokenDeServicio;

/** Qué hace Validación con cada respuesta de Eventos al preguntar el dueño de un evento. Sin red. */
class ClienteDeEventosTest {

    private static class EventosSinRed extends ClienteDeEventos {
        final Deque<Respuesta> respuestas = new ArrayDeque<>();
        final List<String> llamadas = new ArrayList<>();

        void responde(int estado, String cuerpo) {
            respuestas.add(new Respuesta(estado, cuerpo));
        }

        @Override
        Respuesta enviar(String eventoId, String token) {
            llamadas.add(eventoId + " " + token);
            return respuestas.removeFirst();
        }
    }

    private final TokenDeServicio token = mock(TokenDeServicio.class);
    private final EventosSinRed eventos = new EventosSinRed();

    @BeforeEach
    void armar() {
        eventos.tokenDeServicio = token;
        when(token.obtener()).thenReturn("token-1", "token-2");
    }

    @Test
    void conUn200DevuelveElOrganizadorOElNullDeUnEventoSinDueno() {
        eventos.responde(200, "{\"eventoId\":\"ev-1\",\"organizadorId\":\"user-organizador\"}");
        eventos.responde(200, "{\"eventoId\":\"ev-2\",\"organizadorId\":null}");

        assertEquals("user-organizador", eventos.organizadorDe("ev-1"));
        assertNull(eventos.organizadorDe("ev-2"));
        assertEquals(List.of("ev-1 token-1", "ev-2 token-2"), eventos.llamadas);
    }

    @Test
    void elNoEncontradoDeEventosEsNoEncontradoYUnoSinSuErrorEsQueNoSeSabe() {
        eventos.responde(404, "{\"type\":\"/problemas/no-encontrado\"}");
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> eventos.organizadorDe("ev-9"));

        eventos.responde(404, "<html>Not Found</html>");
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.organizadorDe("ev-9"));
        eventos.responde(500, null);
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.organizadorDe("ev-9"));
        // Un 200 de otro evento tampoco sirve.
        eventos.responde(200, "{\"eventoId\":\"ev-2\",\"organizadorId\":\"user-organizador\"}");
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.organizadorDe("ev-1"));
    }

    @Test
    void anteUn401PideOtroTokenYRepiteUnaVez() {
        eventos.responde(401, null);
        eventos.responde(200, "{\"eventoId\":\"ev-1\",\"organizadorId\":\"user-organizador\"}");

        assertEquals("user-organizador", eventos.organizadorDe("ev-1"));
        verify(token).invalidar();
        assertEquals(List.of("ev-1 token-1", "ev-1 token-2"), eventos.llamadas);
    }
}
