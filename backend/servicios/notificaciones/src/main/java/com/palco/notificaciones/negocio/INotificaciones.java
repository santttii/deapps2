package com.palco.notificaciones.negocio;

import jakarta.ejb.Local;

import com.palco.common.mensajeria.PagoConfirmadoMensaje;

/**
 * Interfaz de ServicioDeNotificaciones (Entrega 1): los avisos que el sistema
 * le manda a una persona. Nadie la llama por REST: la usa el MDB que escucha
 * el tópico de compras confirmadas.
 *
 * {@code enviarRecordatorio} y {@code enviarAlertaCancelacion}, que también
 * figuran en la Entrega 1, se declaran cuando se implementen.
 */
@Local
public interface INotificaciones {

    /** Le avisa al comprador que su compra quedó confirmada. */
    void enviarConfirmacion(PagoConfirmadoMensaje mensaje);
}
