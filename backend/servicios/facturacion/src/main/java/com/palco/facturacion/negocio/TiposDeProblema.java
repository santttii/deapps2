package com.palco.facturacion.negocio;

/**
 * Los "tipos" de problema propios de Facturación. Ninguno llega a la API: la
 * consulta de una factura solo responde {@code no-encontrado} (404). Estos
 * los usa el consumidor del tópico para decidir y para el log.
 */
public final class TiposDeProblema {

    /**
     * ARCA contestó y rechazó el comprobante. Reintentar no lo arregla, pero
     * la compra no se puede quedar sin factura: el mensaje termina en la DLQ
     * para que alguien lo revise.
     */
    public static final String COMPROBANTE_RECHAZADO = "comprobante-rechazado";

    private TiposDeProblema() {
    }
}
