package com.palco.ventas.negocio.externos;

/**
 * El cobro visto desde Ventas: lo único que la compra necesita saber de un
 * pago. Ventas depende de esta interfaz y no de quién cobra. Tiene dos
 * implementaciones: {@link ClienteDePagos}, que llama por REST al servicio de
 * Pagos, y {@link CobrosFake}, un simulador local. Cambiar una por otra no
 * toca el componente de la compra.
 *
 * Hay una sola implementación inyectable a la vez: la que elige
 * {@link FabricaDeCobros} según la configuración.
 */
public interface Cobros {

    /**
     * Cobra una venta. Un rechazo no es una excepción: es un resultado, con
     * estado {@code RECHAZADO} y su motivo. Si quien cobra no contesta, lanza
     * {@code ErroresDeNegocio.SinRespuesta}: no se sabe si se cobró. Si el
     * pedido se frenó antes de cobrar, lanza {@link CobroNoRealizado}, que es
     * un {@code SinRespuesta} del que sí se sabe que no cobró.
     *
     * @param monto             pesos enteros
     * @param token             el que entrega la pasarela al cargar la tarjeta; no se guarda
     * @param claveIdempotencia la del pedido de pago: repetirla no cobra dos veces
     */
    ResultadoDeCobro cobrar(String ventaId, long monto, String medioDePago, String token, String claveIdempotencia);
}
