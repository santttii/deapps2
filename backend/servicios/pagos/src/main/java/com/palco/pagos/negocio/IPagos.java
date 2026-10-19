package com.palco.pagos.negocio;

import jakarta.ejb.Local;

import com.palco.pagos.api.Dtos.NuevoPagoDto;
import com.palco.pagos.api.Dtos.PagoDto;

/**
 * Interfaz de ServicioDePagos (Entrega 1): todo lo que el resto del sistema
 * puede pedirle. ServicioDeVentas la consume por REST, nunca contra la
 * implementación.
 *
 * {@code confirmarPago} (el aviso de la pasarela) y {@code reembolsar} se
 * declaran cuando se implementen.
 */
@Local
public interface IPagos {

    /**
     * Cobra una venta contra la pasarela y guarda el resultado. Un rechazo no
     * es un error: vuelve un pago en estado {@code RECHAZADO}.
     *
     * Es idempotente por {@code claveIdempotencia}: repetir el pedido con la
     * misma clave devuelve el mismo pago sin volver a cobrar, también si ese
     * pago fue un rechazo: para intentar de nuevo hace falta otra clave. Usar
     * la clave con otra venta u otro monto es un {@code Conflicto}, y ese
     * pedido no se cobra. Si la pasarela no contesta lanza
     * {@code SinRespuesta} y no guarda nada, así que se puede reintentar con
     * la misma clave.
     */
    PagoDto procesarPago(NuevoPagoDto pago, String claveIdempotencia);

    /** El pago con ese id, o {@code NoEncontrado}. */
    PagoDto obtener(String pagoId);

    /**
     * El pago que se guardó con esa clave de idempotencia, o
     * {@code NoEncontrado} si con esa clave no hay ninguno. Es la consulta de
     * la conciliación de Ventas: después de un cobro sin resultado, Ventas
     * pregunta por la misma clave que mandó al cobrar ({@code <ventaId>:<clave>}).
     *
     * Un {@code NoEncontrado} es una respuesta segura: la clave es única y el
     * pago se guarda antes de responder el cobro, así que si no está, esa
     * clave no cobró. La excepción es un cobro que todavía espera a la
     * pasarela; por eso Ventas no pregunta hasta que pasó un margen desde el
     * intento.
     */
    PagoDto buscarPorClave(String claveIdempotencia);
}
