package com.palco.ventas.negocio;

/**
 * Los "tipos" de problema que responde Ventas, en un solo lugar. Cada uno sale
 * en el error como {@code "type": "/problemas/<tipo>"} y es parte del contrato
 * de la API: el frontend decide qué mostrar según el tipo, no según el texto.
 * Son los que documenta {@code docs/api/openapi.yaml}.
 *
 * Fuera de esta lista hay dos tipos que arman los repositorios cuando la base
 * rechaza una clave repetida: {@code clave-repetida} y
 * {@code entrada-repetida}. Son la última red y no deberían llegar al
 * cliente: el negocio valida antes el pedido, y ante una clave de idempotencia
 * repetida {@code ComprasEnCurso} devuelve la venta que ya existía.
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

    /** 409: el evento ya empezó o terminó; Eventos no reserva más lugares. */
    public static final String EVENTO_FINALIZADO = "evento-finalizado";

    /**
     * 409: la clave de idempotencia ya abrió otra compra, con otro evento u
     * otros renglones. Repetir el mismo pedido no es esto: devuelve la venta.
     */
    public static final String CLAVE_REUTILIZADA = "clave-reutilizada";

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

    /**
     * 409: hay otra llamada o un pago en curso sobre la misma compra. También
     * cuando quedó un pago del que no se supo el resultado y ya no se puede
     * repetir: el texto avisa que no se pague de nuevo.
     */
    public static final String OPERACION_EN_CURSO = "operacion-en-curso";

    /** 409: la entrada ya se usó para entrar y no vuelve a servir. */
    public static final String ENTRADA_YA_USADA = "entrada-ya-usada";

    /** 409: la entrada es de otro evento que el que controla la puerta. No se marca como usada. */
    public static final String ENTRADA_DE_OTRO_EVENTO = "entrada-de-otro-evento";

    /** 409: la entrada no sirve para entrar: está publicada o vendida en la reventa, o quedó anulada. */
    public static final String ENTRADA_NO_HABILITADA = "entrada-no-habilitada";

    private TiposDeProblema() {
    }
}
