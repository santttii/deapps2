package com.palco.ventas.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.ventas.negocio.externos.ClienteDeUsuarios.Usuario;

/** Qué hace Ventas con cada respuesta de Usuarios. No necesita servidor: la llamada HTTP se reemplaza. */
class ClienteDeUsuariosTest {

    private static final String TITULAR_JSON = """
            {"id":"user-titular","nombre":"Ana","apellido":"Gómez","dni":"30123456",
             "email":"titular@palco.test","rol":"user","identidadVerificada":true}""";

    /** En vez de llamar a Usuarios responde lo que le cargó el test y anota con qué token lo llamaron. */
    private static class UsuariosSinRed extends ClienteDeUsuarios {
        Respuesta respuesta;
        String tokenRecibido;

        @Override
        Respuesta enviar(String tokenDelComprador) {
            tokenRecibido = tokenDelComprador;
            if (respuesta == null) {
                throw new ErroresDeNegocio.SinRespuesta("El servicio de usuarios no responde. Probá de nuevo.");
            }
            return respuesta;
        }
    }

    private final UsuariosSinRed usuarios = new UsuariosSinRed();

    @Test
    void consultaConElTokenDelCompradorYDevuelveSusDatos() {
        usuarios.respuesta = new ClienteDeUsuarios.Respuesta(200, TITULAR_JSON);

        Usuario usuario = usuarios.yo("token-del-comprador");

        assertEquals("token-del-comprador", usuarios.tokenRecibido);
        assertEquals("user-titular", usuario.id());
        assertEquals("Gómez", usuario.apellido());
        assertTrue(usuario.identidadVerificada());
    }

    @Test
    void siElCampoNoVieneLaIdentidadCuentaComoNoVerificada() {
        usuarios.respuesta = new ClienteDeUsuarios.Respuesta(200, "{\"id\":\"user-titular\"}");

        assertFalse(usuarios.yo("token-del-comprador").identidadVerificada());
    }

    @Test
    void siUsuariosRechazaElTokenHayQueIniciarSesionDeNuevo() {
        usuarios.respuesta = new ClienteDeUsuarios.Respuesta(401, null);

        assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> usuarios.yo("token-vencido"));
    }

    @Test
    void cualquierOtroCodigoEsSinRespuesta() {
        usuarios.respuesta = new ClienteDeUsuarios.Respuesta(500, null);

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> usuarios.yo("token-del-comprador"));
    }

    @Test
    void unDoscientosQueNoTraeUnUsuarioEsSinRespuesta() {
        usuarios.respuesta = new ClienteDeUsuarios.Respuesta(200, "<html>ok</html>");
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> usuarios.yo("token-del-comprador"));

        usuarios.respuesta = new ClienteDeUsuarios.Respuesta(200, "{}");
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> usuarios.yo("token-del-comprador"));
    }

    @Test
    void siUsuariosNoRespondeEsSinRespuesta() {
        usuarios.respuesta = null;

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> usuarios.yo("token-del-comprador"));
    }
}
