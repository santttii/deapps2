package com.palco.facturacion.negocio;

import jakarta.ejb.Local;

import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.facturacion.api.Dtos.FacturaDto;

/**
 * Interfaz de ServicioDeFacturación (Entrega 1): lo que el resto del sistema
 * puede pedirle. Nadie la llama directamente desde otro servicio: la factura
 * se emite al llegar {@code PAGO_CONFIRMADO} por el tópico y se consulta por
 * REST.
 *
 * {@code reintentar}, que preveía la Entrega 1, no se declara: los reintentos
 * son los del broker (5 entregas cada 2 segundos y después la DLQ).
 */
@Local
public interface IFacturacion {

    /**
     * Factura una compra confirmada: le pide el CAE a ARCA y guarda la
     * factura. Es idempotente por venta: si la venta ya tiene factura, la
     * devuelve sin volver a llamar a ARCA.
     *
     * Si ARCA no responde lanza {@code ErroresDeNegocio.SinRespuesta}, y si
     * rechaza el comprobante, {@code ErroresDeNegocio.ReglaIncumplida}
     * ({@code comprobante-rechazado}). En los dos casos no se guarda nada.
     */
    FacturaDto emitirFactura(PagoConfirmadoMensaje compra);

    /** La factura de esa venta, para una llamada de otro servicio. {@code NoEncontrado} si todavía no hay. */
    FacturaDto consultarFactura(String ventaId);

    /**
     * La factura de esa venta, si la compró ese usuario. Una factura ajena
     * responde {@code NoEncontrado}, lo mismo que una que no existe: no se
     * revela que la venta existe.
     */
    FacturaDto consultarFacturaDelComprador(String compradorId, String ventaId);
}
