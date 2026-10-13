package com.palco.ventas.dominio;

/**
 * Estados de una entrada. El nombre es lo que se guarda en la columna y lo que
 * viaja en la API; la tabla no lleva un check con esta lista.
 *
 * Para la primera entrega se usan los dos primeros: la entrada nace
 * {@code PENDIENTE_EMISION} con el pago y pasa a {@code VALIDA} cuando se
 * emite. Los otros cuatro ya están porque son parte del contrato de la API;
 * los van a escribir la validación en puerta, la reventa y el reembolso.
 */
public enum EstadoEntrada {
    /** Se pagó, pero todavía no tiene código QR. */
    PENDIENTE_EMISION,
    /** Emitida: tiene código QR y sirve para entrar. */
    VALIDA,
    /** Ya se usó para entrar. */
    USADA,
    /** Está publicada en la reventa. */
    PUBLICADA,
    /** Se vendió en la reventa. */
    VENDIDA,
    /** Quedó sin efecto. */
    ANULADA
}
