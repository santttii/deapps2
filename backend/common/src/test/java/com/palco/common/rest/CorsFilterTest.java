package com.palco.common.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Las cabeceras CORS que comparten el filtro de JAX-RS y la página de error del 401. */
class CorsFilterTest {

    private static final Set<String> PERMITIDOS = Set.of("http://localhost:5173");

    @Test
    void unOrigenPermitidoRecibeLasCabeceras() {
        Map<String, String> h = CorsFilter.cabecerasPara("http://localhost:5173", PERMITIDOS);

        assertEquals("http://localhost:5173", h.get("Access-Control-Allow-Origin"));
        assertEquals("true", h.get("Access-Control-Allow-Credentials"));
        assertTrue(h.get("Access-Control-Allow-Headers").contains("Authorization"));
        // Sin If-Match en la lista, el navegador no deja mandar una edición condicionada.
        assertTrue(h.get("Access-Control-Allow-Headers").contains("If-Match"));
        assertTrue(h.get("Access-Control-Expose-Headers").contains("ETag"));
        assertEquals("Origin", h.get("Vary"));
    }

    /**
     * DEF-14: el navegador solo le deja leer al frontend las cabeceras de la lista. Sin Retry-After, después de un 429
     * por demasiados ingresos la pantalla no puede decir cuánto esperar; sin Location, no sabe el id de lo que creó.
     */
    @Test
    void elFrontendPuedeLeerRetryAfterYLocation() {
        Map<String, String> h = CorsFilter.cabecerasPara("http://localhost:5173", PERMITIDOS);

        List<String> expuestas = Arrays.stream(h.get("Access-Control-Expose-Headers").split(","))
                .map(String::strip)
                .toList();
        assertTrue(expuestas.contains("Retry-After"), expuestas::toString);
        assertTrue(expuestas.contains("Location"), expuestas::toString);
    }

    @Test
    void sinOrigenOConOtroOrigenNoHayCabeceras() {
        assertTrue(CorsFilter.cabecerasPara(null, PERMITIDOS).isEmpty());
        assertTrue(CorsFilter.cabecerasPara("https://otro.sitio", PERMITIDOS).isEmpty());
    }

    @Test
    void laListaDeOrigenesSeLeeSeparadaPorComas() {
        assertEquals(Set.of("http://localhost:5173"), CorsFilter.origenes(null));
        assertEquals(Set.of("https://a.test", "https://b.test"), CorsFilter.origenes(" https://a.test, https://b.test ,"));
        assertTrue(CorsFilter.origenes("").isEmpty());
    }
}
