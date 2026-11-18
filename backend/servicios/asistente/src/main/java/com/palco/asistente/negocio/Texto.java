package com.palco.asistente.negocio;

import java.text.Normalizer;
import java.util.Locale;

/** Utilidades de texto del asistente: comparar sin mayúsculas ni tildes. */
public final class Texto {

    private Texto() {
    }

    /** Minúsculas y sin tildes, para buscar palabras sin depender de cómo se escribieron. */
    public static String normalizar(String texto) {
        String sinTildes = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT);
    }

    /** true si el texto normalizado contiene alguna de las palabras. */
    public static boolean contieneAlguna(String normalizado, String... palabras) {
        for (String p : palabras) {
            if (normalizado.contains(p)) {
                return true;
            }
        }
        return false;
    }
}
