package com.palco.ventas.negocio;

/**
 * Los "tipos" de problema que responde Ventas, en un solo lugar. Cada uno sale
 * en el error como {@code "type": "/problemas/<tipo>"} y es parte del contrato
 * de la API: el frontend decide qué mostrar según el tipo, no según el texto.
 * Son los de la sección 6 de {@code docs/planes/00-contratos-compartidos.md}.
 */
public final class TiposDeProblema {

    /** 422: la compra pide más entradas que el máximo por compra. */
    public static final String MAXIMO_DE_ENTRADAS = "maximo-de-entradas";

    /** 422: no hay exactamente un titular por entrada. */
    public static final String TITULARES_NO_COINCIDEN = "titulares-no-coinciden";

    /** 422: dos entradas de la misma compra tienen el mismo DNI. */
    public static final String DNI_REPETIDO = "dni-repetido";

    /** 422: Eventos no conoce ese sector en ese evento. */
    public static final String SECTOR_INEXISTENTE = "sector-inexistente";

    /** 409: no quedan lugares suficientes en el sector. */
    public static final String CUPO_AGOTADO = "cupo-agotado";

    /** 403: el comprador todavía no verificó su identidad. */
    public static final String IDENTIDAD_NO_VERIFICADA = "identidad-no-verificada";

    /**
     * 409: la compra ya no se puede pagar. La reserva venció, la compra se
     * canceló o el servicio perdió la conversación de esa compra (por ejemplo,
     * porque se redesplegó). Hay que empezar una compra nueva.
     */
    public static final String RESERVA_VENCIDA = "reserva-vencida";

    /** 409: la venta ya se pagó; no se cancela ni se paga de nuevo. */
    public static final String VENTA_YA_PAGA = "venta-ya-paga";

    /** 409: hay otra llamada o un pago en curso sobre la misma compra. */
    public static final String OPERACION_EN_CURSO = "operacion-en-curso";

    private TiposDeProblema() {
    }
}
