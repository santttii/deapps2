package com.palco.ventas.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.jwt.JsonWebToken;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.ventas.api.Dtos.EntradaDeVentaDto;
import com.palco.ventas.api.Dtos.ItemDto;
import com.palco.ventas.api.Dtos.TitularDto;
import com.palco.ventas.api.Dtos.VentaDto;
import com.palco.ventas.negocio.ComprasEnCurso;

/**
 * Lo que el recurso decide por su cuenta, sin servidor: la revisión de la
 * {@code Idempotency-Key}, de quién dice el token que es el pedido y que cada
 * operación declare su rol. Lo que hace el servidor (el 201 con su
 * {@code Location}, el 204, el 401 y el 403) se comprueba con {@code curl}.
 */
class VentasResourceTest {

    private static final String CLAVE = "5d3c1f0e-8a4b-4c7d-9e2f-1a2b3c4d5e6f";

    // ---- La clave de idempotencia ----

    @Test
    void unUuidEsUnaClaveValida() {
        assertEquals(CLAVE, VentasResource.claveValida(CLAVE));
    }

    @Test
    void elMismoUuidEnMayusculasEsLaMismaClave() {
        assertEquals(CLAVE, VentasResource.claveValida("  5D3C1F0E-8A4B-4C7D-9E2F-1A2B3C4D5E6F "));
    }

    @Test
    void sinClaveOConUnaQueNoEsUnUuidElPedidoEsInvalido() {
        List<String> invalidas = Arrays.asList(
                null,                                           // falta la cabecera
                "",
                "compra-1",
                "1-1-1-1-1",                                    // UUID.fromString la aceptaría
                "5d3c1f0e8a4b4c7d9e2f1a2b3c4d5e6f",             // sin guiones
                CLAVE + "0",                                    // un dígito de más
                "5d3c1f0e-8a4b-4c7d-9e2f-1a2b3c4d5e6g",         // la g no es hexadecimal
                CLAVE + "," + CLAVE);

        for (String clave : invalidas) {
            ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                    () -> VentasResource.claveValida(clave), "clave: " + clave);
            // El error nombra la cabecera, no un parámetro de Java.
            assertTrue(error.campos().containsKey("Idempotency-Key"), "campos: " + error.campos());
        }
    }

    // ---- Consultar ----

    @Test
    void consultarPideLaVentaANombreDelUsuarioDelTokenYLeSumaLosEnlaces() {
        ComprasEnCurso compras = mock(ComprasEnCurso.class);
        VentaDto sinEnlaces = new VentaDto("V-3f9a1c2b", "EN_CHECKOUT", "NO_APLICA", "ev-1",
                List.of(new ItemDto("sec-1-4", "Platea B", 2, 27_500)), 55_000, 5_500, 60_500,
                "2026-10-19T21:00:00Z", "2026-10-19T21:10:00Z", null, List.of(), Map.of());
        // El comprador es el "sub" del token: no viaja en la ruta ni en el cuerpo.
        when(compras.obtener("user-titular", "V-3f9a1c2b")).thenReturn(sinEnlaces);
        VentasResource recurso = new VentasResource();
        recurso.compras = compras;

        VentaDto venta = recurso.obtener("V-3f9a1c2b", sesionDe("user-titular"), pedidoEnLaRaiz());

        assertEquals(60_500, venta.montoTotal());
        assertEquals("/api/v1/ventas/V-3f9a1c2b", venta.enlaces().get("self").href());
        assertEquals(List.of("self", "pagar", "cancelar"), List.copyOf(venta.enlaces().keySet()));
    }

    @Test
    void sinUnTokenDeUsuarioNoSeConsultaNada() {
        ComprasEnCurso compras = mock(ComprasEnCurso.class);
        VentasResource recurso = new VentasResource();
        recurso.compras = compras;
        SecurityContext sinSesion = mock(SecurityContext.class);

        assertThrows(ErroresDeNegocio.NoAutenticado.class,
                () -> recurso.obtener("V-3f9a1c2b", sinSesion, pedidoEnLaRaiz()));
    }

    // ---- Listar las compras propias ----

    @Test
    void listarPideLasComprasDelUsuarioDelTokenYLeSumaLosEnlacesACadaUna() {
        ComprasEnCurso compras = mock(ComprasEnCurso.class);
        VentaDto emitida = new VentaDto("V-3f9a1c2b", "EMITIDA", "NO_APLICA", "ev-1",
                List.of(new ItemDto("sec-1-4", "Platea B", 1, 27_500)), 27_500, 2_750, 30_250,
                "2026-10-19T21:00:00Z", "2026-10-19T21:10:00Z", "P-0a1b2c3d",
                List.of(new EntradaDeVentaDto("T-9c41d07e", "sec-1-4", "Platea B",
                        new TitularDto("Ana Gómez", "30123456"), 27_500, "VALIDA", "codigo-de-prueba", Map.of())),
                Map.of());
        VentaDto abierta = new VentaDto("V-7b2e4d10", "EN_CHECKOUT", "NO_APLICA", "ev-2", List.of(), 0, 0, 0,
                "2026-10-19T20:00:00Z", "2026-10-19T20:10:00Z", null, List.of(), Map.of());
        // De quién son las compras sale del "sub" del token: la operación no recibe ningún id de usuario.
        when(compras.listar("user-titular")).thenReturn(List.of(emitida, abierta));
        VentasResource recurso = new VentasResource();
        recurso.compras = compras;

        List<VentaDto> ventas = recurso.listar(sesionDe("user-titular"), pedidoEnLaRaiz());

        // Las mismas ventas y en el mismo orden en que las devolvió el negocio.
        assertEquals(List.of("V-3f9a1c2b", "V-7b2e4d10"), ventas.stream().map(VentaDto::id).toList());
        // Cada una con los enlaces de su estado, y cada entrada con el suyo.
        assertEquals(List.of("self"), List.copyOf(ventas.get(0).enlaces().keySet()));
        assertEquals("/api/v1/entradas/T-9c41d07e", ventas.get(0).entradas().get(0).enlaces().get("self").href());
        assertEquals(List.of("self", "pagar", "cancelar"), List.copyOf(ventas.get(1).enlaces().keySet()));
    }

    @Test
    void sinUnTokenDeUsuarioNoSeListaNada() {
        ComprasEnCurso compras = mock(ComprasEnCurso.class);
        VentasResource recurso = new VentasResource();
        recurso.compras = compras;
        SecurityContext sinSesion = mock(SecurityContext.class);

        assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> recurso.listar(sinSesion, pedidoEnLaRaiz()));
        verifyNoInteractions(compras);
    }

    // ---- Seguridad declarativa ----

    @Test
    void todasLasOperacionesSonSoloParaCompradores() {
        int operaciones = 0;
        for (Method metodo : VentasResource.class.getDeclaredMethods()) {
            if (!esUnaOperacionHttp(metodo)) {
                continue;
            }
            operaciones++;
            RolesAllowed roles = metodo.getAnnotation(RolesAllowed.class);
            assertNotNull(roles, metodo.getName() + " no declara quién la puede llamar");
            assertArrayEquals(new String[] { "user" }, roles.value(), metodo.getName());
        }
        // Abrir, listar las propias, consultar, pagar y cancelar.
        assertEquals(5, operaciones);
    }

    // ---------------------------------------------------------------------------------------------

    /** Un método es una operación si lleva @GET, @POST, @DELETE…: todas esas anotaciones están marcadas con @HttpMethod. */
    private static boolean esUnaOperacionHttp(Method metodo) {
        return Arrays.stream(metodo.getAnnotations())
                .anyMatch(anotacion -> anotacion.annotationType().isAnnotationPresent(HttpMethod.class));
    }

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
