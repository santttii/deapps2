package com.palco.common;

/**
 * Constantes y cálculos de negocio de Palco. Es la fuente de verdad del
 * sistema: el frontend tiene una copia en {@code frontend/src/types/index.ts}
 * que tiene que coincidir con estos valores.
 *
 * Los montos son pesos enteros, nunca {@code double}.
 */
public final class ReglasDeNegocio {

    /** Una entrada se revende como máximo a lo pagado + 10%. */
    public static final int TOPE_REVENTA_PORCENTAJE = 10;

    /** Una entrada se revende como mínimo al 50% de lo pagado. */
    public static final int MINIMO_REVENTA_PORCENTAJE = 50;

    /** Cargo de servicio sobre el subtotal del checkout. */
    public static final int TASA_SERVICIO_PORCENTAJE = 10;

    /** Máximo de entradas por compra, sumando todos los sectores. */
    public static final int MAX_ENTRADAS_POR_ORDEN = 6;

    /** La reventa de un evento cierra estas horas antes de que empiece. */
    public static final int HORAS_LIMITE_REVENTA = 3;

    /** Tiempo que se mantienen reservados los lugares durante el checkout. */
    public static final int MINUTOS_RESERVA = 10;

    private ReglasDeNegocio() {
    }

    /** Precio máximo de reventa, redondeado hacia abajo. */
    public static long topeReventa(long precioPagado) {
        return Math.floorDiv(precioPagado * (100 + TOPE_REVENTA_PORCENTAJE), 100);
    }

    /** Precio mínimo de reventa, redondeado hacia arriba. */
    public static long minimoReventa(long precioPagado) {
        return Math.ceilDiv(precioPagado * MINIMO_REVENTA_PORCENTAJE, 100);
    }

    /** Cargo de servicio sobre un subtotal, redondeado al peso más cercano. */
    public static long cargoServicio(long subtotal) {
        return Math.round(subtotal * TASA_SERVICIO_PORCENTAJE / 100.0);
    }
}
