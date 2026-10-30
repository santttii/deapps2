package com.palco.notificaciones.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.seguridad.TokenDeServicio;

/**
 * Qué hace Notificaciones con cada respuesta de Usuarios al pedir el email del
 * comprador. Sin red.
 *
 * Lo que se cuida acá: que nunca lance. Un aviso que no se puede mandar no
 * tiene que hacer que el mensaje del tópico se reintente y la compra se avise
 * dos veces.
 */
class ClienteDeUsuariosTest {

    private static class UsuariosSinRed extends ClienteDeUsuarios {
        final Deque<Respuesta> respuestas = new ArrayDeque<>();
        final List<String> llamadas = new ArrayList<>();

        void responde(int estado, String cuerpo) {
            respuestas.add(new Respuesta(estado, cuerpo));
        }

        @Override
        Respuesta enviar(String usuarioId, String token) {
            llamadas.add(usuarioId + " " + token);
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
    void conUn200DevuelveElUsuarioConSuEmail() {
        usuarios.responde(200, "{\"id\":\"user-titular\",\"nombre\":\"Ana\",\"email\":\"ana@palco.test\"}");

        Optional<ClienteDeUsuarios.Usuario> encontrado = usuarios.buscar("user-titular");

        assertTrue(encontrado.isPresent());
        assertEquals("ana@palco.test", encontrado.get().email());
        assertEquals("Ana", encontrado.get().nombre());
        assertEquals(List.of("user-titular token-1"), usuarios.llamadas);
    }

    @Test
    void anteUn401PideOtroTokenYRepiteUnaVez() {
        usuarios.responde(401, null);
        usuarios.responde(200, "{\"id\":\"user-titular\",\"nombre\":\"Ana\",\"email\":\"ana@palco.test\"}");

        assertTrue(usuarios.buscar("user-titular").isPresent());
        verify(token).invalidar();
        assertEquals(List.of("user-titular token-1", "user-titular token-2"), usuarios.llamadas);
    }

    @Test
    void siUsuariosNoContestaOContestaOtraCosaDevuelveVacioYNoLanza() {
        usuarios.responde(0, null); // no hubo conexión
        assertTrue(usuarios.buscar("user-titular").isEmpty());

        usuarios.responde(404, null);
        assertTrue(usuarios.buscar("user-nadie").isEmpty());

        usuarios.responde(500, null);
        assertTrue(usuarios.buscar("user-titular").isEmpty());

        usuarios.responde(200, "<html>no es json</html>");
        assertTrue(usuarios.buscar("user-titular").isEmpty());
    }

    @Test
    void unUsuarioSinEmailNoSirveParaAvisar() {
        usuarios.responde(200, "{\"id\":\"user-titular\",\"nombre\":\"Ana\",\"email\":null}");
        assertTrue(usuarios.buscar("user-titular").isEmpty());

        usuarios.responde(200, "{\"id\":\"user-titular\",\"nombre\":\"Ana\",\"email\":\"  \"}");
        assertTrue(usuarios.buscar("user-titular").isEmpty());
    }
}
