package com.palco.facturacion.negocio;

/**
 * Los "tipos" de problema propios de Facturación, los que no son de
 * {@code ErroresDeNegocio}.
 */
public final class TiposDeProblema {

    /**
     * ARCA contestó y rechazó el comprobante. Reintentar no lo arregla: el
     * pedido sería el mismo y ARCA lo volvería a rechazar. El negocio lo anota
     * con el motivo y la consulta de la factura de esa venta responde 409 con
     * este tipo ({@code /problemas/comprobante-rechazado}), para que el
     * comprador no lo confunda con una factura que todavía está en camino.
     */
    public static final String COMPROBANTE_RECHAZADO = "comprobante-rechazado";

    private TiposDeProblema() {
    }
}
