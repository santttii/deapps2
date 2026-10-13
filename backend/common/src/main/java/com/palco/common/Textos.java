package com.palco.common;

import java.text.Normalizer;
import java.util.Locale;

/** Utilidades de texto compartidas (búsquedas, slugs y textos que van al log). */
public final class Textos {

    private Textos() {
    }

    /**
     * Dice si un carácter corta el renglón o no se ve al escribirlo: los
     * caracteres de control (salto de línea, retorno de carro, tabulador y los
     * demás) y los dos separadores de línea de Unicode (U+2028 y U+2029), que
     * algunos programas también muestran como un renglón nuevo.
     *
     * La regla está escrita una sola vez: la usan Ventas, para rechazar un
     * nombre o un id que los traiga, y {@link #enUnaLinea}.
     */
    public static boolean esDeControl(int caracter) {
        return Character.isISOControl(caracter)
                || Character.getType(caracter) == Character.LINE_SEPARATOR
                || Character.getType(caracter) == Character.PARAGRAPH_SEPARATOR;
    }

    /**
     * El texto en un solo renglón, para escribirlo en el log: cada carácter de
     * control se cambia por un espacio. Un texto nulo queda vacío.
     *
     * Por qué: el log del servidor tiene un renglón por evento y se usa como
     * evidencia. Si un dato que viene de afuera (un nombre, un id, lo que trae
     * un mensaje) se escribiera con un salto de línea adentro, quien lo mandó
     * podría hacer aparecer un renglón falso, con fecha y nombre de componente
     * inventados. Con el salto cambiado por un espacio, todo lo que mandó queda
     * dentro del renglón verdadero. Se usa en cada línea de log que incluye un
     * dato que no generó el propio servicio.
     */
    public static String enUnaLinea(String texto) {
        if (texto == null) {
            return "";
        }
        StringBuilder enUnRenglon = new StringBuilder(texto.length());
        texto.codePoints().forEach(caracter -> enUnRenglon.appendCodePoint(esDeControl(caracter) ? ' ' : caracter));
        return enUnRenglon.toString();
    }

    /** Minúsculas y sin acentos: "Música" → "musica". Igual que normalizar() del frontend. */
    public static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    /** Texto apto para URL: "El método Grönholm" → "el-metodo-gronholm". */
    public static String slug(String texto) {
        String s = normalizar(texto)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return s.isEmpty() ? "evento" : s;
    }
}
