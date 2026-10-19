package com.palco.facturacion.negocio.externos;

import java.time.LocalDate;

/**
 * ARCA, el sistema externo que autoriza las facturas. Esta interfaz es
 * nuestra: dice qué le pide Palco, con los nombres de Palco.
 * {@code ServicioDeFacturacion} conoce solo esto; no sabe que del otro lado
 * hay un servicio SOAP.
 *
 * La implementa {@link ArcaSoap}, un Adapter: traduce el contrato ajeno (el
 * WSDL, sus nombres y sus códigos de resultado) a esta interfaz. La crea
 * {@link FabricaDeArca}.
 */
public interface Arca {

    /**
     * Pide el número y el CAE de un comprobante.
     *
     * Si ARCA no contesta a tiempo, no se le puede hablar o responde con un
     * error, lanza {@code ErroresDeNegocio.SinRespuesta}: no se sabe si
     * autorizó, y se puede volver a pedir. Si ARCA lo rechaza, lanza
     * {@code ErroresDeNegocio.ReglaIncumplida} con el tipo
     * {@code comprobante-rechazado}.
     */
    ComprobanteAutorizado autorizar(SolicitudDeCae solicitud);

    /**
     * Lo que se le pide a ARCA.
     *
     * @param tipo         código del comprobante (6: factura B)
     * @param importe      total en pesos enteros
     * @param fecha        fecha del comprobante
     * @param ventaId      la venta de Palco que se factura
     */
    record SolicitudDeCae(int tipo, int puntoDeVenta, long importe, LocalDate fecha, String ventaId) {
    }

    /** Lo que ARCA autorizó. */
    record ComprobanteAutorizado(long numero, String cae, LocalDate vencimientoCae) {
    }
}
