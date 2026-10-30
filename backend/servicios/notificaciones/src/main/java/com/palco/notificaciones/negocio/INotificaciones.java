package com.palco.notificaciones.negocio;

import jakarta.ejb.Local;

import com.palco.common.mensajeria.CompraAnuladaMensaje;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;

/**
 * Interfaz de ServicioDeNotificaciones (Entrega 1): los avisos que el sistema
 * le manda a una persona. Nadie la llama por REST: la usa el MDB que escucha
 * el tópico de compras confirmadas.
 *
 * {@code enviarRecordatorio}, que también figura en la Entrega 1, se declara
 * cuando se implemente.
 */
@Local
public interface INotificaciones {

    /** Le avisa al comprador que su compra quedó confirmada. */
    void enviarConfirmacion(PagoConfirmadoMensaje mensaje);

    /** Le avisa al comprador que su compra en la reventa quedó confirmada ({@code REVENTA_CONFIRMADA}). */
    void enviarConfirmacionDeReventa(PagoConfirmadoMensaje mensaje);

    /**
     * Le avisa al comprador que se canceló el evento y su compra quedó anulada
     * ({@code COMPRA_ANULADA}). El texto cambia según si Pagos ya devolvió el
     * dinero: no se le promete una devolución que todavía no se hizo.
     */
    void enviarAlertaCancelacion(CompraAnuladaMensaje mensaje);
}
