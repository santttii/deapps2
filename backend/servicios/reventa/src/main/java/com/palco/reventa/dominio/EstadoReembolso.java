package com.palco.reventa.dominio;

/**
 * Estados de un reembolso. El nombre es lo que se guarda en la columna; la
 * tabla no lleva un check con esta lista.
 */
public enum EstadoReembolso {
    /** Anotado y todavía sin la respuesta de Pagos: el barrido lo reintenta. */
    PENDIENTE,
    /** Pagos devolvió el pago. */
    HECHO,
    /** Pagos contestó que ese pago no se puede devolver: se resuelve a mano. */
    RECHAZADO
}
