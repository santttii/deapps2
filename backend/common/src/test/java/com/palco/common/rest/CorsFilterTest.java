package com.palco.common.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals("Origin", h.get("Vary"));
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
