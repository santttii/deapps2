package com.palco.eventos.dominio;

import java.util.Arrays;
import java.util.Optional;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Categorías del catálogo. La etiqueta es lo que se guarda y lo que ve el frontend. */
public enum Categoria {
    FUTBOL("Fútbol"),
    MUSICA("Música"),
    FIESTA("Fiesta"),
    TEATRO("Teatro");

    private final String etiqueta;

    Categoria(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public static Optional<Categoria> desdeEtiqueta(String etiqueta) {
        return Arrays.stream(values()).filter(c -> c.etiqueta.equals(etiqueta)).findFirst();
    }

    /** Guarda la etiqueta ("Fútbol") en la columna, no el nombre del enum. */
    @Converter(autoApply = true)
    public static class ConversorJpa implements AttributeConverter<Categoria, String> {
        @Override
        public String convertToDatabaseColumn(Categoria categoria) {
            return categoria == null ? null : categoria.etiqueta;
        }

        @Override
        public Categoria convertToEntityAttribute(String etiqueta) {
            return etiqueta == null ? null : desdeEtiqueta(etiqueta)
                    .orElseThrow(() -> new IllegalStateException("Categoría desconocida en la base: " + etiqueta));
        }
    }
}
