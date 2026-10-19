package com.palco.reventa.negocio;

/**
 * Los "tipos" de problema que responde Reventa, en un solo lugar. Cada uno
 * sale como {@code "type": "/problemas/<tipo>"} y es parte del contrato de la
 * API: el frontend decide qué mostrar según el tipo, no según el texto.
 * Además de estos, Reventa responde los generales: {@code datos-invalidos}
 * (400), {@code no-encontrado} (404), {@code pago-rechazado} (402) y
 * {@code sin-respuesta} (504).
 */
public final class TiposDeProblema {

    /** 409: la entrada no está VALIDA (ya publicada, usada, vendida o sin emitir). */
    public static final String ENTRADA_NO_DISPONIBLE = "entrada-no-disponible";

    /** 422: el precio está fuera del rango de ReglasDeNegocio (del 50% al 110% de lo pagado). */
    public static final String PRECIO_FUERA_DE_RANGO = "precio-fuera-de-rango";

    /** 409: faltan menos de 3 horas para el evento (o ya empezó): no se publica ni se compra. */
    public static final String REVENTA_CERRADA = "reventa-cerrada";

    /** 409: la publicación ya se vendió o se retiró, o la está comprando otra persona. */
    public static final String PUBLICACION_NO_ACTIVA = "publicacion-no-activa";

    /** 409: nadie compra su propia publicación. */
    public static final String COMPRA_PROPIA = "compra-propia";

    private TiposDeProblema() {
    }
}
