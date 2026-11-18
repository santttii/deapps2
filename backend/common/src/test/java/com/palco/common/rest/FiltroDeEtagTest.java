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
}
