package com.palco.ventas.negocio;

import jakarta.ejb.Local;

import com.palco.ventas.api.Dtos.NuevaVentaDto;
import com.palco.ventas.api.Dtos.NuevoPagoDto;
import com.palco.ventas.api.Dtos.VentaDto;

/**
 * Interfaz de ServicioDeVentas (Entrega 1): la conversación de una compra.
 *
 * Las tres operaciones son los pasos de una misma compra y se llaman sobre la
 * misma instancia: se inicia una vez y después se paga o se cancela. Por eso
 * {@code confirmarCompra} y {@code cancelarCompra} no reciben el id de la
 * venta: la instancia ya sabe cuál es la suya. Con cualquiera de las dos la
 * conversación termina y el servidor libera la instancia.
 *
 * El resto del sistema no usa esta interfaz directamente: los recursos REST
 * pasan por {@link ComprasEnCurso}, que ubica la instancia de cada compra.
 */
@Local
public interface IVentas {

    /**
     * Abre la compra: valida el pedido, guarda la venta en {@code EN_CHECKOUT}
     * y reserva el cupo de cada sector por {@code MINUTOS_RESERVA} minutos.
     *
     * @param compradorId       el usuario del token (claim "sub")
     * @param claveIdempotencia la {@code Idempotency-Key} del pedido: un mismo
     *                          comprador no puede abrir dos compras con la misma
     */
    VentaDto iniciarCompra(String compradorId, NuevaVentaDto nueva, String claveIdempotencia);

    /**
     * Cobra la compra abierta y, si el cobro se aprueba, la deja
     * {@code CONFIRMADA}, crea sus entradas y publica el mensaje
     * {@code PAGO_CONFIRMADO}, todo en una misma transacción.
     *
     * Lanza {@code PagoRechazado} si el cobro se rechaza: la compra sigue
     * abierta y se puede volver a pagar sobre esta misma instancia. Lanza
     * {@code Conflicto} si la compra ya no se puede pagar (reserva vencida,
     * cancelada o ya paga) y {@code SinRespuesta} si quien cobra no contesta.
     *
     * @param claveIdempotencia la {@code Idempotency-Key} del pedido de pago:
     *                          se le pasa a quien cobra, para que repetir el
     *                          pedido no cobre dos veces
     */
    VentaDto confirmarCompra(NuevoPagoDto pago, String claveIdempotencia);

    /**
     * Cancela la compra abierta. Devuelve {@code true} si esta llamada pasó la
     * venta a {@code CANCELADA}: solo en ese caso quien llama devuelve el cupo.
     * Devuelve {@code false} si no había nada que cancelar (la compra no llegó
     * a abrirse, o ya estaba cancelada o vencida).
     */
    boolean cancelarCompra();
}
