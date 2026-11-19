package com.palco.common.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FiltroDeEtagTest {

    @Test
    void elMismoCuerpoDaElMismoEtagYUnoDistintoOtro() {
        String a = FiltroDeEtag.etagDe("{\"id\":\"ev-1\",\"vendidas\":2}");

        assertEquals(a, FiltroDeEtag.etagDe("{\"id\":\"ev-1\",\"vendidas\":2}"));
        assertNotEquals(a, FiltroDeEtag.etagDe("{\"id\":\"ev-1\",\"vendidas\":3}"));
        assertTrue(a.startsWith("\"") && a.endsWith("\""), a);
    }

    @Test
    void ifNoneMatchCoincideConElMismoEtagConUnaListaODebilYConAsterisco() {
        String etag = FiltroDeEtag.etagDe("x");

        assertTrue(FiltroDeEtag.coincide(etag, etag));
        assertTrue(FiltroDeEtag.coincide("\"otro\", " + etag, etag));
        assertTrue(FiltroDeEtag.coincide("W/" + etag, etag));
        assertTrue(FiltroDeEtag.coincide("*", etag));
    }

    @Test
    void sinIfNoneMatchOConOtroValorNoCoincide() {
        String etag = FiltroDeEtag.etagDe("x");

        assertFalse(FiltroDeEtag.coincide(null, etag));
        assertFalse(FiltroDeEtag.coincide(" ", etag));
        assertFalse(FiltroDeEtag.coincide("\"otro\"", etag));
    }

    /** Un DTO cualquiera. Público porque JSON-B solo lee los campos de un tipo público, como los DTO de la API. */
    public record Sector(String id, int vendidas) {
    }

    /** Es lo que le permite al negocio comparar un If-Match con el mismo valor que entregó el GET. */
    @Test
    void elEtagDeUnaRepresentacionEsElDeSuJson() {
        assertEquals(FiltroDeEtag.etagDe("{\"id\":\"sec-1\",\"vendidas\":2}"),
                FiltroDeEtag.etagDeLaRepresentacion(new Sector("sec-1", 2)));
        assertNotEquals(FiltroDeEtag.etagDeLaRepresentacion(new Sector("sec-1", 2)),
                FiltroDeEtag.etagDeLaRepresentacion(new Sector("sec-1", 3)));
    }

    @Test
    void ifMatchCoincideConElMismoEtagConUnaListaYConAsterisco() {
        String etag = FiltroDeEtag.etagDe("x");

        assertTrue(FiltroDeEtag.coincideFuerte(etag, etag));
        assertTrue(FiltroDeEtag.coincideFuerte("\"otro\", " + etag, etag));
        assertTrue(FiltroDeEtag.coincideFuerte("*", etag));
    }

    /** RFC 9110, 13.1.1: If-Match usa la comparación fuerte, así que un ETag débil nunca coincide. */
    @Test
    void ifMatchDebilVacioOConOtroValorNoCoincide() {
        String etag = FiltroDeEtag.etagDe("x");

        assertFalse(FiltroDeEtag.coincideFuerte("W/" + etag, etag));
        assertFalse(FiltroDeEtag.coincideFuerte(etag, "W/" + etag));
        assertFalse(FiltroDeEtag.coincideFuerte("\"otro\"", etag));
        assertFalse(FiltroDeEtag.coincideFuerte("", etag));
        assertFalse(FiltroDeEtag.coincideFuerte(null, etag));
    }
}
