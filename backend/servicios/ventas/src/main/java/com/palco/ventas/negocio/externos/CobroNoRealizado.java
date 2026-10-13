package com.palco.ventas.negocio.externos;

import jakarta.ejb.ApplicationException;

import com.palco.common.ErroresDeNegocio;

/**
 * El pedido de cobro se frenó antes de cobrar, y eso es seguro: no se
 * consiguió el token de servicio, o Pagos lo rechazó sin procesarlo (401, 403)
 * o no está desplegado (404).
 *
 * Es un {@code SinRespuesta}: para el comprador es lo mismo, un 504 con
 * "probá de nuevo", porque la falla es nuestra y no de su medio de pago. La
 * diferencia es para {@code ComprasEnCurso}. Ante un {@code SinRespuesta} a
 * secas no se sabe si se cobró, y la venta queda marcada con el intento de
 * pago. Ante esta excepción se sabe que no, y la marca se quita: la venta se
 * puede cancelar o vencer como cualquier otra.
 *
 * No se resuelve como un rechazo (402), que es lo que se hace con el 400 y el
 * 409 de Pagos: el comprador vería "pago rechazado" por una caída interna.
 */
@ApplicationException(rollback = true)
public class CobroNoRealizado extends ErroresDeNegocio.SinRespuesta {

    public CobroNoRealizado(String mensaje) {
        super(mensaje);
    }
}
