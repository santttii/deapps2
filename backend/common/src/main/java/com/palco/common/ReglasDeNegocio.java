package com.palco.common;

/**
 * Constantes y cálculos de negocio de Palco. Es la fuente de verdad del
 * sistema: el frontend tiene una copia en {@code frontend/src/types/index.ts}
 * que tiene que coincidir con estos valores.
 *
 * Los montos son pesos enteros, nunca {@code double}.
 */
public final class ReglasDeNegocio {

    /**
     * Una entrada se revende como máximo al precio de lista de su sector + 10%.
     * Es el precio de lista y no lo que pagó quien la vende: si fuera lo pagado,
     * cada reventa de la misma entrada subiría el tope otro 10% (DEF-16).
     */
    public static final int TOPE_REVENTA_PORCENTAJE = 10;

    /** Una entrada se revende como mínimo al 50% del precio de lista de su sector. */
    public static final int MINIMO_REVENTA_PORCENTAJE = 50;

    /** Cargo de servicio sobre el subtotal del checkout. */
    public static final int TASA_SERVICIO_PORCENTAJE = 10;

    /** Máximo de entradas por compra, sumando todos los sectores. */
    public static final int MAX_ENTRADAS_POR_ORDEN = 6;

    /**
     * Máximo de entradas que un mismo comprador puede tener para un evento,
     * sumando todas sus compras. Sin este tope, el de cada compra se esquivaba
     * haciendo varias compras seguidas. Cuentan las compras pagadas y las que
     * están en curso; no cuentan las canceladas, las vencidas ni las entradas
     * que ya revendió.
     */
    public static final int MAX_ENTRADAS_POR_EVENTO = 6;

    /** La reventa de un evento cierra estas horas antes de que empiece. */
    public static final int HORAS_LIMITE_REVENTA = 3;

    /** Tiempo que se mantienen reservados los lugares durante el checkout. */
    public static final int MINUTOS_RESERVA = 10;

    private ReglasDeNegocio() {
    }

    /** Precio máximo de reventa, redondeado hacia abajo, a partir del precio de lista del sector. */
    public static long topeReventa(long precioDeLista) {
        return Math.floorDiv(precioDeLista * (100 + TOPE_REVENTA_PORCENTAJE), 100);
    }

    /** Precio mínimo de reventa, redondeado hacia arriba, a partir del precio de lista del sector. */
    public static long minimoReventa(long precioDeLista) {
        return Math.ceilDiv(precioDeLista * MINIMO_REVENTA_PORCENTAJE, 100);
    }

    /**
     * Cargo de servicio sobre un subtotal, redondeado al peso más cercano (medio peso redondea hacia arriba). La
     * cuenta es entera, como todo lo que es dinero: sumar 50 antes de dividir por 100 es redondear al más cercano.
     */
    public static long cargoServicio(long subtotal) {
        return Math.floorDiv(subtotal * TASA_SERVICIO_PORCENTAJE + 50, 100);
    }

    /**
     * Un DNI son 7 u 8 dígitos y nada más: sin puntos ni espacios. La regla está
     * en un solo lugar para que todos apliquen la misma: el registro de usuarios,
     * el mensaje {@code PAGO_CONFIRMADO} y quien valide a los titulares de una
     * compra. Si cada uno la escribiera por su cuenta, uno podría aceptar un DNI
     * que otro rechaza después.
     * Quien recibe el DNI con puntos (un formulario) los quita antes de preguntar.
     * El frontend tiene la misma regla en {@code frontend/src/lib/validate.ts}.
     */
    public static boolean esDniValido(String dni) {
        return dni != null && dni.matches("\\d{7,8}");
    }
}
