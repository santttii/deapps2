package com.palco.asistente.negocio;

/**
 * Lo que devuelve el asistente.
 *
 * @param texto la respuesta para el usuario
 * @param origen de dónde salió: MODELO, FALLBACK (preguntas frecuentes o soporte) o BLOQUEADA (guardrail)
 * @param motivo por qué no la respondió el modelo: timeout, error-del-modelo, apagado, inyeccion,
 *               datos-personales, demasiado-larga, vacia, respuesta-vacia o salida-con-datos-personales;
 *               null si respondió
 * @param derivarASoporte true si conviene ofrecer hablar con una persona
 * @param milisegundos cuánto tardó, de punta a punta
 */
public record Respuesta(String texto, Origen origen, String motivo, boolean derivarASoporte, long milisegundos) {

    public enum Origen { MODELO, FALLBACK, BLOQUEADA }
}
