package com.palco.asistente.negocio.externos;

/**
 * La interfaz propia del modelo de lenguaje (patrón Adapter). El asistente solo conoce esta
 * interfaz: cada proveedor (el simulado hoy, uno real después) es una implementación que
 * traduce su API a este método. Cambiar de proveedor no toca el resto del servicio.
 */
public interface ModeloDeLenguaje {

    /**
     * Responde una pregunta ya revisada por los guardrails.
     *
     * @param instrucciones las reglas fijas del sistema, separadas del texto del usuario
     * @param pregunta la pregunta, con los datos personales enmascarados
     * @return el texto de la respuesta
     */
    String completar(String instrucciones, String pregunta);

    /** Nombre y versión del modelo, para la ficha y el registro. */
    String nombre();
}
