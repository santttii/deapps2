package com.palco.validacion.dominio;

/**
 * Lo que pasó con un código escaneado en la puerta. El nombre es lo que se
 * guarda en la columna y lo que viaja en la API; la tabla no lleva un check
 * con esta lista.
 */
public enum ResultadoEscaneo {
    /** La entrada era válida y este escaneo la usó: la persona puede entrar. */
    VALIDA,
    /** La entrada ya se había usado: no entra. */
    YA_USADA,
    /** Ningún código de Ventas coincide: no entra. */
    INEXISTENTE,
    /** La entrada es de otro evento: no entra, y la entrada sigue sirviendo en el suyo. */
    OTRO_EVENTO,
    /** La entrada existe pero no sirve para entrar: está en la reventa o anulada. */
    NO_HABILITADA,
    /**
     * Ventas no respondió. No se sabe si la entrada quedó usada: hay que
     * escanear de nuevo. Queda en el registro para explicar un
     * {@code YA_USADA} posterior del mismo código.
     */
    SIN_RESPUESTA;

    /** Solo con {@code VALIDA} la persona entra. */
    public boolean dejaEntrar() {
        return this == VALIDA;
    }
}
