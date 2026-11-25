package com.palco.ventas.negocio.externos;

/**
 * Cómo terminó un cobro. Es un {@code record} porque es un dato que no cambia
 * después de creado.
 *
 * @param pagoId el id del pago ("P-" más 8 caracteres); null en un rechazo que no llegó a guardarse como pago
 * @param estado {@link #ESTADO_APROBADO} o {@link #ESTADO_RECHAZADO}
 * @param motivo por qué se rechazó; null si se aprobó
 */
public record ResultadoDeCobro(String pagoId, String estado, String motivo) {

    /**
     * Los valores de {@code estado}, los mismos que responde Pagos. Van con {@code ESTADO_} para que no
     * se confundan con el método {@link #aprobado()}, que es la pregunta y no el valor.
     */
    public static final String ESTADO_APROBADO = "APROBADO";
    public static final String ESTADO_RECHAZADO = "RECHAZADO";

    /**
     * El motivo de un rechazo sin pago porque quien cobra no aceptó la clave de
     * idempotencia del intento. No es un problema del medio de pago: se
     * reintenta con una clave nueva.
     */
    public static final String MOTIVO_CLAVE_REUTILIZADA = "clave_reutilizada";

    /** Solo un cobro aprobado confirma la venta. */
    public boolean aprobado() {
        return ESTADO_APROBADO.equals(estado);
    }
}
