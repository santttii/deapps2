package com.palco.ventas.negocio.externos;

/**
 * Cómo terminó un cobro. Es un {@code record} porque es un dato que no cambia
 * después de creado.
 *
 * @param pagoId el id del pago ("P-" más 8 caracteres); null en un rechazo que no llegó a guardarse como pago
 * @param estado {@link #APROBADO} o {@link #RECHAZADO}
 * @param motivo por qué se rechazó; null si se aprobó
 */
public record ResultadoDeCobro(String pagoId, String estado, String motivo) {

    public static final String APROBADO = "APROBADO";
    public static final String RECHAZADO = "RECHAZADO";

    /**
     * El motivo de un rechazo sin pago porque quien cobra no aceptó la clave de
     * idempotencia del intento. No es un problema del medio de pago: se
     * reintenta con una clave nueva.
     */
    public static final String MOTIVO_CLAVE_REUTILIZADA = "clave_reutilizada";

    /** Solo un cobro aprobado confirma la venta. */
    public boolean aprobado() {
        return APROBADO.equals(estado);
    }
}
