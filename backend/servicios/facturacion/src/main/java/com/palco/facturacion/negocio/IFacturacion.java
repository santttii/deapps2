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
 * son los del broker (5 entregas cada 2 segundos y después la DLQ), y solo
 * para cuando ARCA no responde.
 */
@Local
public interface IFacturacion {

    /**
     * Factura una compra confirmada: le pide el CAE a ARCA y guarda la
     * factura. Es idempotente por venta: si la venta ya tiene factura, la
     * devuelve sin volver a llamar a ARCA.
     *
     * Si ARCA no responde lanza {@code ErroresDeNegocio.SinRespuesta} y no
     * guarda nada: otro intento puede andar. Si rechaza el comprobante, anota
     * el rechazo con el motivo y lanza {@code ErroresDeNegocio.ReglaIncumplida}
     * ({@code comprobante-rechazado}); un mensaje repetido de esa venta lanza
     * lo mismo sin volver a llamar a ARCA. Si el rechazo no se puede anotar
     * (falla la base), sale esa falla y no la {@code ReglaIncumplida}: el
     * mensaje se reintenta, como con cualquier error técnico.
     */
    FacturaDto emitirFactura(PagoConfirmadoMensaje compra);

    /**
     * La factura de esa venta, para una llamada de otro servicio.
     * {@code NoEncontrado} si todavía no hay; {@code Conflicto}
     * ({@code comprobante-rechazado}) si ARCA la rechazó.
     */
    FacturaDto consultarFactura(String ventaId);

    /**
     * La factura de esa venta, si la compró ese usuario, con los mismos
     * errores que {@link #consultarFactura}. Una venta ajena responde
     * {@code NoEncontrado}, lo mismo que una que no existe, aunque ARCA la
     * haya rechazado: no se revela que la venta existe.
     */
    FacturaDto consultarFacturaDelComprador(String compradorId, String ventaId);
}
