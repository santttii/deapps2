package com.palco.validacion.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.validacion.api.Dtos.NuevoEscaneoDto;
import com.palco.validacion.negocio.IValidacion;

/**
 * Lo que el recurso decide por su cuenta, sin servidor: que sin un token de
 * usuario no se escanea, el rol de cada operación y a quién se le controla
 * el dueño del evento. Lo que arma el servidor
 * (el 201 con su {@code Location}, el 400, el 401 y el 403) se comprueba con
 * {@code curl}, porque en los tests no hay una implementación de JAX-RS.
 */
class EscaneosResourceTest {

    private final IValidacion validacion = mock(IValidacion.class);
    private final EscaneosResource recurso = new EscaneosResource();

    @Test
    void sinUnTokenDeUsuarioNoSeEscanea() {
        recurso.validacion = validacion;

        assertThrows(ErroresDeNegocio.NoAutenticado.class,
                () -> recurso.escanear(new NuevoEscaneoDto("qr-1", "ev-1"), mock(SecurityContext.class), pedido()));
        verifyNoInteractions(validacion);
    }

    @Test
    void escanearEsSoloParaElPersonalDePuerta() throws NoSuchMethodException {
        assertArrayEquals(new String[] { "staff" },
                roles(EscaneosResource.class.getMethod("escanear", NuevoEscaneoDto.class, SecurityContext.class,
                        UriInfo.class)));
    }

    @Test
    void elRegistroLoVenLaPuertaYElOrganizador() throws NoSuchMethodException {
        assertArrayEquals(new String[] { "staff", "organizer" },
                roles(EscaneosResource.class.getMethod("listar", String.class, SecurityContext.class)));
        assertArrayEquals(new String[] { "staff", "organizer" },
                roles(EscaneosResource.class.getMethod("obtener", String.class, SecurityContext.class)));
    }

    @Test
    void alPersonalDePuertaNoSeLeControlaElDuenoDelEvento() {
        SecurityContext puerta = mock(SecurityContext.class);
        when(puerta.isUserInRole("staff")).thenReturn(true);

        assertNull(EscaneosResource.tokenDelOrganizador(puerta));
    }

    @Test
    void alOrganizadorSeLeControlaConSuPropioToken() {
        JsonWebToken token = mock(JsonWebToken.class);
        when(token.getRawToken()).thenReturn("token-del-organizador");
        SecurityContext organizador = mock(SecurityContext.class);
        when(organizador.isUserInRole("organizer")).thenReturn(true);
        when(organizador.getUserPrincipal()).thenReturn(token);

        assertEquals("token-del-organizador", EscaneosResource.tokenDelOrganizador(organizador));
    }

    // ---------------------------------------------------------------------------------------------

    private static String[] roles(Method operacion) {
        RolesAllowed roles = operacion.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, operacion.getName() + " no declara quién la puede llamar");
        return roles.value();
    }

    private static UriInfo pedido() {
        return mock(UriInfo.class);
    }
}
