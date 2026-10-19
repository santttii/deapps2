package com.palco.ventas.dominio;

/**
 * Estados de una venta. El nombre es lo que se guarda en la columna y lo que
 * viaja en la API. La tabla no lleva un check con esta lista: los valores
 * válidos los controla este enumerado, que es quien escribe la columna.
 *
 * Una venta nace {@code EN_CHECKOUT}. De ahí sale por un solo camino:
 * {@code CONFIRMADA} y después {@code EMITIDA}, o {@code CANCELADA}, o
 * {@code VENCIDA}. Cada cambio es un UPDATE condicional de
 * {@code VentaRepository}, para que solo siga quien cambió la fila.
 */
public enum EstadoVenta {
    /** El cupo está reservado y falta pagar. */
    EN_CHECKOUT,
    /** El pago se aprobó: las entradas existen, pero todavía no se emitieron. */
    CONFIRMADA,
    /** Las entradas ya tienen su código QR. */
    EMITIDA,
    /** El comprador la canceló antes de pagar y el cupo se devolvió. */
    CANCELADA,
    /**
     * Se cumplió el tiempo de reserva sin pago y el cupo se devolvió. También
     * la deja así la conciliación cuando comprueba que un intento de pago sin
     * resultado no cobró.
     */
    VENCIDA
}
