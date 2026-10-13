package com.palco.ventas.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;

import org.eclipse.microprofile.jwt.JsonWebToken;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.ventas.api.Dtos.EnlaceDto;
import com.palco.ventas.api.Dtos.EntradaDto;
import com.palco.ventas.api.Dtos.TitularDto;
import com.palco.ventas.negocio.ComprasEnCurso;

/**
 * Lo que el recurso de entradas decide por su cuenta, sin servidor: de quién
 * dice el token que es el pedido, el enlace que le suma a la respuesta y que
 * la operación declare su rol. El 200, el 401 y el 403 que arma el servidor se
 * comprueban con {@code curl}.
 */
class EntradasResourceTest {

    private final ComprasEnCurso compras = mock(ComprasEnCurso.class);
    private final EntradasResource recurso = new EntradasResource();

    @Test
    void consultarPideLaEntradaANombreDelUsuarioDelTokenYLeSumaSuEnlace() {
        EntradaDto sinEnlaces = new EntradaDto("T-9c41d07e", "V-3f9a1c2b", "ev-1", "sec-1-4", "Platea B",
                new TitularDto("Ana Gómez", "30123456"), 27_500, "PENDIENTE_EMISION", null, null, Map.of());
        // El comprador es el "sub" del token: no viaja en la ruta.
        when(compras.obtenerEntrada("user-titular", "T-9c41d07e")).thenReturn(sinEnlaces);
        recurso.compras = compras;

        EntradaDto entrada = recurso.obtener("T-9c41d07e", sesionDe("user-titular"), pedidoEnLaRaiz());

        assertEquals(Map.of("self", new EnlaceDto("/api/v1/entradas/T-9c41d07e", "GET")), entrada.enlaces());
        // Lo demás sale como lo devolvió el negocio.
        assertEquals(sinEnlaces.conEnlaces(entrada.enlaces()), entrada);
    }

    @Test
    void sinUnTokenDeUsuarioNoSeConsultaNada() {
        recurso.compras = compras;
        SecurityContext sinSesion = mock(SecurityContext.class);

        assertThrows(ErroresDeNegocio.NoAutenticado.class,
                () -> recurso.obtener("T-9c41d07e", sinSesion, pedidoEnLaRaiz()));
        verifyNoInteractions(compras);
    }

    @Test
    void laEntradaEsSoloParaCompradores() throws NoSuchMethodException {
        RolesAllowed roles = EntradasResource.class
                .getMethod("obtener", String.class, SecurityContext.class, UriInfo.class)
                .getAnnotation(RolesAllowed.class);

        assertNotNull(roles, "obtener no declara quién la puede llamar");
        assertArrayEquals(new String[] { "user" }, roles.value());
    }

    // ---------------------------------------------------------------------------------------------

    /** El pedido de un usuario con sesión: el servidor deja su token ya validado en el contexto de seguridad. */
    private static SecurityContext sesionDe(String usuarioId) {
        JsonWebToken token = mock(JsonWebToken.class);
        when(token.getSubject()).thenReturn(usuarioId);
        SecurityContext seguridad = mock(SecurityContext.class);
        when(seguridad.getUserPrincipal()).thenReturn(token);
        return seguridad;
    }

    private static UriInfo pedidoEnLaRaiz() {
        UriInfo pedido = mock(UriInfo.class);
        when(pedido.getBaseUri()).thenReturn(URI.create("http://localhost:8080/api/v1/"));
        return pedido;
    }
}
