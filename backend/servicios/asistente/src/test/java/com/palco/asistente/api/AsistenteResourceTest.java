package com.palco.asistente.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.SecurityContext;

import com.palco.asistente.negocio.IAsistente;
import com.palco.asistente.negocio.Respuesta;
import com.palco.asistente.negocio.Respuesta.Origen;

/** El recurso le pasa al asistente quién pregunta: el id interno del token, no el email. */
class AsistenteResourceTest {

    /** El token de una compradora ya validado por el servidor: "upn" es el email y "sub", el id. */
    private static final class Token implements JsonWebToken {
        private final Map<String, Object> claims = Map.of("sub", "user-titular", "upn", "titular@palco.test");

        @Override
        public String getName() {
            return (String) claims.get("upn");
        }

        @Override
        public Set<String> getClaimNames() {
            return claims.keySet();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getClaim(String nombre) {
            return (T) claims.get(nombre);
        }
    }

    private static SecurityContext sesion(Principal principal) {
        return new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return principal;
            }

            @Override
            public boolean isUserInRole(String rol) {
                return "user".equals(rol);
            }

            @Override
            public boolean isSecure() {
                return true;
            }

            @Override
            public String getAuthenticationScheme() {
                return "MP-JWT";
            }
        };
    }

    @Test
    void elAsistenteRecibeElIdInternoYNoElEmailDeQuienPregunta() {
        // El asistente escribe ese identificador en el log de cada consulta: el email no puede llegar ahí.
        AtomicReference<String> quien = new AtomicReference<>();
        AsistenteResource recurso = new AsistenteResource();
        recurso.asistente = (usuarioId, pregunta) -> {
            quien.set(usuarioId);
            return new Respuesta("Hasta 3 horas antes del evento.", Origen.MODELO, null, false, 1);
        };
        recurso.seguridad = sesion(new Token());

        AsistenteResource.RespuestaDto respuesta =
                recurso.preguntar(new AsistenteResource.PreguntaDto("¿Hasta cuándo puedo revender?"));

        assertEquals("user-titular", quien.get());
        assertEquals("MODELO", respuesta.origen());
    }
}
