package com.palco.facturacion.api;

/** Lo que sale de la API de facturación. No hay nada que entre: las facturas nacen de los mensajes del tópico. */
public final class Dtos {

    private Dtos() {
    }

    /**
     * La factura de una venta.
     *
     * @param tipoComprobante código de ARCA: 6 es factura B
     * @param comprobante     punto de venta y número como se imprimen: {@code 00001-00000001}
     * @param cae             código de autorización electrónico, 14 dígitos
     * @param vencimientoCae  {@code aaaa-mm-dd}
     * @param importe         pesos enteros: el total cobrado
     * @param fecha           fecha del comprobante, {@code aaaa-mm-dd}, en hora argentina
     * @param emitida         instante en que se guardó, en UTC: {@code 2026-10-16T21:30:05Z}
     */
    public record FacturaDto(String ventaId, String compradorId, String eventoId, int tipoComprobante,
            int puntoDeVenta, long numero, String comprobante, String cae, String vencimientoCae, long importe,
            String fecha, String emitida) {
    }
}
