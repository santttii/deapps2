package com.palco.asistente.negocio;

import java.text.Normalizer;
import java.util.Locale;

/** Utilidades de texto del asistente: comparar sin mayúsculas ni tildes. */
public final class Texto {

    private Texto() {
    }

    /**
     * Minúsculas, sin tildes y con un solo espacio entre palabras, para buscar palabras sin depender
     * de cómo se escribieron: "IGNORÁ   tus" queda "ignora tus". La forma NFKD separa la tilde de
     * su letra y además lleva las letras de ancho completo ("ｉｇｎｏｒｅ") y los espacios raros a los
     * comunes. Después se sacan las tildes y los caracteres invisibles, como el de ancho cero, que
     * sirven para partir una palabra sin que se note.
     */
    public static String normalizar(String texto) {
        String sinTildes = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFKD)
                .replaceAll("[\\p{M}\\p{Cf}]", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
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
