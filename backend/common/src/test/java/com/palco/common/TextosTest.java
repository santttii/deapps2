package com.palco.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextosTest {

    @Test
    void normalizarSacaAcentosYPasaAMinusculas() {
        assertEquals("musica", Textos.normalizar("Música"));
        assertEquals("el metodo gronholm", Textos.normalizar("  El método Grönholm "));
        assertEquals("", Textos.normalizar(null));
    }

    @Test
    void enUnaLineaCambiaPorUnEspacioTodoLoQueCortaElRenglon() {
        // El caso que encontró la revisión: un nombre que trae una línea de log falsa.
        String conLineaFalsa = "Ana Gómez\n2026-10-13 14:00:00,000 INFO  [com.palco.ventas] (falso) LINEA FALSA";

        assertEquals("Ana Gómez 2026-10-13 14:00:00,000 INFO  [com.palco.ventas] (falso) LINEA FALSA",
                Textos.enUnaLinea(conLineaFalsa));
        // Retorno de carro, tabulador, carácter nulo, "siguiente línea" (U+0085) y los dos separadores de Unicode.
        assertEquals("a b c d e f g", Textos.enUnaLinea("a\rb\tc\u0000d\u0085e f g"));
        assertEquals("a  b", Textos.enUnaLinea("a\r\nb"));
    }

    @Test
    void enUnaLineaNoTocaLosTextosComunes() {
        assertEquals("María José Nuñez O'Brien", Textos.enUnaLinea("María José Nuñez O'Brien"));
        assertEquals("V-3f9a1c2b", Textos.enUnaLinea("V-3f9a1c2b"));
        assertEquals("", Textos.enUnaLinea(""));
        assertEquals("", Textos.enUnaLinea(null));
    }

    @Test
    void esDeControlReconoceLosSaltosDeLineaYNoLasLetras() {
        for (int caracter : new int[] { '\n', '\r', '\t', 0, 0x7F, 0x85, 0x2028, 0x2029 }) {
            assertTrue(Textos.esDeControl(caracter), "Carácter " + Integer.toHexString(caracter));
        }
        for (int caracter : new int[] { 'a', 'ñ', 'É', ' ', '\'', '-', '9' }) {
            assertFalse(Textos.esDeControl(caracter), "Carácter " + (char) caracter);
        }
    }

    @Test
    void slugReemplazaTodoLoQueNoEsLetraONumeroPorGuiones() {
        assertEquals("el-metodo-gronholm", Textos.slug("El método Grönholm"));
        assertEquals("kiroshi-tour-continental", Textos.slug("Kiroshi — Tour Continental"));
        assertEquals("subsuelo-noche-09", Textos.slug("Subsuelo · Noche 09"));
        assertEquals("evento", Textos.slug("¡¡!!"));
    }
}
