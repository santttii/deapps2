package com.palco.common;

import java.text.Normalizer;
import java.util.Locale;

/** Utilidades de texto compartidas (búsquedas y slugs). */
public final class Textos {

    private Textos() {
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
