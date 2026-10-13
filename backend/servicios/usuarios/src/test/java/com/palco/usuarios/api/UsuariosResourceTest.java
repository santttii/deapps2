package com.palco.usuarios.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.eclipse.microprofile.jwt.JsonWebToken;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.SecurityContext;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.usuarios.api.Dtos.VerificacionDeIdentidadDto;
import com.palco.usuarios.negocio.IUsuarios;

/**
 * Lo que el recurso decide por su cuenta en la verificación de identidad, sin
 * servidor: de quién dice el token que es el pedido y qué roles declara la
 * operación. Que el servidor responda 401 sin token y 403 con el rol
 * {@code servicio} se comprueba con {@code curl}.
 */
class UsuariosResourceTest {

    private static final String USUARIO = "user-3f9a1c2b";

    private final IUsuarios usuarios = mock(IUsuarios.class);
    private final UsuariosResource recurso = new UsuariosResource();

    UsuariosResourceTest() {
        recurso.usuarios = usuarios;
    }

    @Test
    void verificaLaIdentidadDelUsuarioDelTokenYNoRecibeOtroId() throws NoSuchMethodException {
        VerificacionDeIdentidadDto respuesta = new VerificacionDeIdentidadDto(null, true, "Verificación simulada.");
        when(usuarios.verificarIdentidad(USUARIO)).thenReturn(respuesta);

        assertEquals(respuesta, recurso.verificarIdentidad(sesionDe(USUARIO)));

        // El único parámetro es el contexto de seguridad: no hay forma de nombrar a otro usuario.
        Method operacion = UsuariosResource.class.getMethod("verificarIdentidad", SecurityContext.class);
        assertEquals(1, operacion.getParameterCount());
    }

    @Test
    void sinUnTokenDeUsuarioNoSeVerificaANadie() {
        SecurityContext sinSesion = mock(SecurityContext.class);

        assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> recurso.verificarIdentidad(sinSesion));
        verifyNoInteractions(usuarios);
    }

    @Test
    void laPuedeLlamarCualquierPersonaConSesionYNoElRolServicio() throws NoSuchMethodException {
        Method operacion = UsuariosResource.class.getMethod("verificarIdentidad", SecurityContext.class);

        RolesAllowed roles = operacion.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, "verificarIdentidad no declara quién la puede llamar");
        assertArrayEquals(new String[] { "user", "organizer", "staff" }, roles.value());
        assertNull(operacion.getAnnotation(PermitAll.class), "verificarIdentidad no puede ser pública");
    }

    /** El pedido de un usuario con sesión: el servidor deja su token ya validado en el contexto de seguridad. */
    private static SecurityContext sesionDe(String usuarioId) {
        JsonWebToken token = mock(JsonWebToken.class);
        when(token.getSubject()).thenReturn(usuarioId);
        SecurityContext seguridad = mock(SecurityContext.class);
        when(seguridad.getUserPrincipal()).thenReturn(token);
        return seguridad;
    }
}
