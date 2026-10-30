package com.palco.notificaciones.mensajeria;

import java.util.logging.Logger;

import jakarta.ejb.ActivationConfigProperty;
import jakarta.ejb.MessageDriven;
import jakarta.inject.Inject;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;

import com.palco.common.Textos;
import com.palco.common.mensajeria.CompraAnuladaMensaje;
import com.palco.common.mensajeria.DestinosJms;
import com.palco.common.mensajeria.MensajeInvalido;
import com.palco.notificaciones.negocio.INotificaciones;

/**
 * Suscriptor del tópico de cancelaciones que le avisa al comprador que se
 * canceló el evento y su compra quedó anulada.
 *
 * Escucha {@code COMPRA_ANULADA} y no {@code EVENTO_CANCELADO}: el primero es
 * el que trae a quién avisarle y cuánto se le devolvió. El del evento lo
 * publica Eventos sin saber quién compró, y lo consume Ventas, que es la que
 * abre uno de estos por cada compra.
 *
 * Es un punto de entrada delgado, como el recurso REST: lee el mensaje y
 * delega en {@link INotificaciones}.
 *
 * Suscripción no durable, igual que {@link AvisoDeCompraMDB}: el broker le
 * entrega solo lo que se publica mientras está escuchando, y si el servicio
 * está caído ese aviso se pierde. Se acepta por la misma razón, y la copia que
 * queda guardada es la de auditoría, que sí es durable. Por lo mismo, un
 * mensaje que no se entiende se descarta con una advertencia en lugar de
 * reintentarse.
 */
@MessageDriven(name = "AvisoDeCancelacion", activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = DestinosJms.TOPICO_CANCELACIONES),
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "jakarta.jms.Topic"),
        @ActivationConfigProperty(propertyName = "messageSelector", propertyValue = DestinosJms.SELECTOR_COMPRA_ANULADA) })
public class AvisoDeCancelacionMDB implements MessageListener {

    private static final Logger LOG = Logger.getLogger(AvisoDeCancelacionMDB.class.getName());

    @Inject
    INotificaciones notificaciones;

    @Override
    public void onMessage(Message recibido) {
        CompraAnuladaMensaje mensaje;
        try {
            DestinosJms.tipoDeCancelacionDe(recibido);
            mensaje = CompraAnuladaMensaje.desdeJson(LectorDeMensajes.texto(recibido));
        } catch (MensajeInvalido e) {
            // Sin excepción ni rollback: el mensaje queda consumido y no vuelve.
            LOG.warning("AvisoDeCancelacion: se descarta un mensaje inválido. " + Textos.enUnaLinea(e.getMessage()));
            return;
        }
        notificaciones.enviarAlertaCancelacion(mensaje);
        LOG.info("AvisoDeCancelacion: atendido el aviso por la venta " + Textos.enUnaLinea(mensaje.ventaId())
                + " del evento cancelado " + Textos.enUnaLinea(mensaje.eventoId()) + ".");
    }
}
