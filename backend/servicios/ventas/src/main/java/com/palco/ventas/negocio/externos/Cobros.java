package com.palco.ventas.negocio.externos;

import java.util.Optional;

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

    /**
     * Cómo terminó el cobro que se pidió para esa venta con esa clave, sin
     * cobrar nada. Lo usa la conciliación después de un cobro sin resultado.
     *
     * Vacío solo si quien cobra contestó con certeza que con esa clave no hay
     * ningún cobro. Si no contesta, o contesta algo que no permite saberlo
     * (un estado que todavía no es aprobado ni rechazado, un 404 que no es el
     * suyo), lanza {@code ErroresDeNegocio.SinRespuesta}: ante la duda no se
     * libera un cupo que pudo haberse cobrado.
     *
     * @param claveIdempotencia la misma que se pasó a {@link #cobrar}
     */
    Optional<ResultadoDeCobro> buscarCobro(String ventaId, String claveIdempotencia);
}
