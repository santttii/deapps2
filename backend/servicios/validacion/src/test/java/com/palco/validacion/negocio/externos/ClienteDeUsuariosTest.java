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

/** Qué hace Validación con cada respuesta de Usuarios al preguntar el rol de una cuenta. Sin red. */
class ClienteDeUsuariosTest {

    private static class UsuariosSinRed extends ClienteDeUsuarios {
        final Deque<Respuesta> respuestas = new ArrayDeque<>();
        final List<String> llamadas = new ArrayList<>();

        void responde(int estado, String cuerpo) {
            respuestas.add(new Respuesta(estado, cuerpo));
        }

        @Override
        Respuesta enviar(String email, String token) {
            llamadas.add(email + " " + token);
            return respuestas.removeFirst();
        }
    }

    private final TokenDeServicio token = mock(TokenDeServicio.class);
    private final UsuariosSinRed usuarios = new UsuariosSinRed();

    @BeforeEach
    void armar() {
        usuarios.tokenDeServicio = token;
        when(token.obtener()).thenReturn("token-1", "token-2");
    }

    @Test
    void conUn200DevuelveElRolDeLaCuenta() {
        usuarios.responde(200, "{\"email\":\"puerta@palco.test\",\"rol\":\"staff\"}");

        assertEquals("staff", usuarios.rolDe("puerta@palco.test"));
        assertEquals(List.of("puerta@palco.test token-1"), usuarios.llamadas);
    }

    @Test
    void elNoEncontradoDeUsuariosEsQueNoHayCuentaYUnoSinSuErrorEsQueNoSeSabe() {
        usuarios.responde(404, "{\"type\":\"/problemas/no-encontrado\"}");
        usuarios.responde(404, "<html>Not Found</html>");

        assertNull(usuarios.rolDe("nadie@palco.test"));
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> usuarios.rolDe("nadie@palco.test"));
    }

    @Test
    void conUn401PideOtroTokenYRepite() {
        usuarios.responde(401, null);
        usuarios.responde(200, "{\"email\":\"puerta@palco.test\",\"rol\":\"staff\"}");

        assertEquals("staff", usuarios.rolDe("puerta@palco.test"));
        verify(token).invalidar();
        assertEquals(List.of("puerta@palco.test token-1", "puerta@palco.test token-2"), usuarios.llamadas);
    }

    @Test
    void cualquierOtraRespuestaEsSinRespuesta() {
        usuarios.responde(500, "{}");
        usuarios.responde(200, "no es json");

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> usuarios.rolDe("puerta@palco.test"));
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> usuarios.rolDe("puerta@palco.test"));
    }
}
