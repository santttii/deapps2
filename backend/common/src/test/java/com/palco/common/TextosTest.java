package com.palco.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextosTest {

    @Test
    void normalizarSacaAcentosYPasaAMinusculas() {
        assertEquals("musica", Textos.normalizar("Música"));
        assertEquals("el metodo gronholm", Textos.normalizar("  El método Grönholm "));
        assertEquals("", Textos.normalizar(null));
    }

    @Test
    void slugReemplazaTodoLoQueNoEsLetraONumeroPorGuiones() {
        assertEquals("el-metodo-gronholm", Textos.slug("El método Grönholm"));
        assertEquals("kiroshi-tour-continental", Textos.slug("Kiroshi — Tour Continental"));
        assertEquals("subsuelo-noche-09", Textos.slug("Subsuelo · Noche 09"));
        assertEquals("evento", Textos.slug("¡¡!!"));
    }
}
