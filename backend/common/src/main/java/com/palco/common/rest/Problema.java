package com.palco.common.rest;

import java.util.Map;

import jakarta.json.bind.annotation.JsonbNillable;
import jakarta.json.bind.annotation.JsonbPropertyOrder;

/**
 * Cuerpo de todas las respuestas de error de la API, en el formato estándar
 * Problem Details (RFC 9457). Se manda con {@code Content-Type: application/problem+json}.
 *
 * Los nombres de los campos están en inglés porque los fija el estándar.
 *
 * @param type     identifica el problema: {@code /problemas/cupo-agotado}
 * @param title    texto fijo por clase de error: "Conflicto"
 * @param status   código HTTP
 * @param detail   explicación de este caso, para mostrar al usuario; no va cuando el
 *                 problema es solo el código HTTP (un 405, por ejemplo)
 * @param instance ruta del pedido que falló
 * @param errores  errores de validación por campo (extensión propia); solo aparece si hay alguno
 * @param idError  id del error inesperado (extensión propia, solo en los 500): el mismo que queda
 *                 en el log del servidor junto con la traza, para encontrarla cuando alguien
 *                 reporta el error. Los demás errores no lo llevan
 */
// El orden es el del estándar; sin esta anotación JSON-B los escribe alfabéticamente.
@JsonbPropertyOrder({ "type", "title", "status", "detail", "instance", "errores", "idError" })
// Los campos sin valor no se escriben, aunque JsonProvider incluya los null en el resto de la API.
@JsonbNillable(false)
public record Problema(String type, String title, int status, String detail, String instance,
        Map<String, String> errores, String idError) {

    /** Problema sin id de error: así salen todos menos el 500 inesperado. */
    public Problema(String type, String title, int status, String detail, String instance,
            Map<String, String> errores) {
        this(type, title, status, detail, instance, errores, null);
    }

    /** Lo que antecede al tipo de problema en {@code type}. */
    public static final String PREFIJO_TIPO = "/problemas/";

    /** Valor de {@code type} que el estándar indica cuando el problema es solo el código HTTP. */
    public static final String TIPO_SIN_DETALLE = "about:blank";

    /** Problema sin errores por campo. {@code tipo} va sin prefijo: "cupo-agotado". */
    public static Problema de(int estado, String tipo, String titulo, String detalle, String instancia) {
        return de(estado, tipo, titulo, detalle, instancia, Map.of());
    }

    /** Problema con errores por campo; si el mapa está vacío, {@code errores} no se escribe. */
    public static Problema de(int estado, String tipo, String titulo, String detalle, String instancia,
            Map<String, String> errores) {
        return new Problema(PREFIJO_TIPO + tipo, titulo, estado, detalle, instancia,
                errores == null || errores.isEmpty() ? null : errores);
    }

    /** El mismo problema, con el id con el que quedó registrado en el log. */
    public Problema conIdError(String id) {
        return new Problema(type, title, status, detail, instance, errores, id);
    }
}
