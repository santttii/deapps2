package com.palco.ventas.negocio.externos;

/**
 * El cobro visto desde Ventas: lo único que la compra necesita saber de un
 * pago. Ventas depende de esta interfaz y no de quién cobra: hoy la implementa
 * un simulador ({@link CobrosFake}) y después la va a implementar un cliente
 * REST del servicio de Pagos, sin tocar el componente de la compra.
 *
 * Hay una sola implementación inyectable a la vez.
 */
public interface Cobros {

    /**
     * Cobra una venta. Un rechazo no es una excepción: es un resultado, con
     * estado {@code RECHAZADO} y su motivo. Si quien cobra no contesta, lanza
     * {@code ErroresDeNegocio.SinRespuesta}: no se sabe si se cobró.
     *
     * @param monto             pesos enteros
     * @param token             el que entrega la pasarela al cargar la tarjeta; no se guarda
     * @param claveIdempotencia la del pedido de pago: repetirla no cobra dos veces
     */
    ResultadoDeCobro cobrar(String ventaId, long monto, String medioDePago, String token, String claveIdempotencia);
}
