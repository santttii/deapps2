package com.palco.eventos.negocio;

/**
 * Los "tipos" de problema propios de Eventos. Cada uno sale en el error como
 * {@code "type": "/problemas/<tipo>"} y es parte del contrato de la API: quien
 * llama decide según el tipo, no según el texto.
 */
public final class TiposDeProblema {

    /** 409: el evento ya empezó, así que no se reservan ni se venden más lugares. */
    public static final String EVENTO_FINALIZADO = "evento-finalizado";

    /**
     * 409: esa devolución de cupo ya se aplicó (otro pedido con la misma clave
     * llegó a la vez y se guardó primero). Para quien devuelve es un éxito: el
     * cupo ya está devuelto.
     */
    public static final String DEVOLUCION_YA_APLICADA = "devolucion-ya-aplicada";

    /** 409: la clave de devolución ya se usó para otro sector o con otra cantidad. */
    public static final String CLAVE_REUTILIZADA = "clave-reutilizada";

    /**
     * 409: el cambio choca con entradas que ya se vendieron (bajar el cupo por
     * debajo de las vendidas, mover el precio de un sector con ventas o quitar
     * un sector que tiene entradas).
     */
    public static final String SECTOR_CON_VENTAS = "sector-con-ventas";

    /** 409: el evento ya está cancelado, así que no se puede cambiar ni cancelar de nuevo. */
    public static final String EVENTO_CANCELADO = "evento-cancelado";

    private TiposDeProblema() {
    }
}
