package com.palco.app.errores;

import java.util.Map;

/**
 * Cuerpo de todas las respuestas de error de la API.
 *
 * @param estado  código HTTP
 * @param mensaje explicación para mostrar al usuario
 * @param campos  errores de validación por campo (vacío si no aplica)
 */
public record ErrorRespuesta(int estado, String mensaje, Map<String, String> campos) {

    public ErrorRespuesta(int estado, String mensaje) {
        this(estado, mensaje, Map.of());
    }
}
