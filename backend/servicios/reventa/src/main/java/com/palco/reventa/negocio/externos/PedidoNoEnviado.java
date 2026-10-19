package com.palco.reventa.negocio.externos;

import jakarta.ejb.ApplicationException;

import com.palco.common.ErroresDeNegocio;

/**
 * El pedido a otro servicio no llegó a hacerse: no se consiguió el token de
 * servicio, o el servidor lo frenó antes de entrar al servicio (401, 403 o
 * 404 de una ruta que no está desplegada). Para quien compra es el mismo 504
 * que un {@code SinRespuesta}; para Reventa la diferencia importa en el cobro,
 * porque acá se sabe que no se cobró y la publicación se puede liberar.
 */
@ApplicationException(rollback = true)
public class PedidoNoEnviado extends ErroresDeNegocio.SinRespuesta {

    public PedidoNoEnviado(String mensaje) {
        super(mensaje);
    }
}
