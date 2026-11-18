package com.palco.reventa.mensajeria;

import java.util.logging.Logger;

import jakarta.annotation.Resource;
import jakarta.ejb.ActivationConfigProperty;
import jakarta.ejb.MessageDriven;
import jakarta.ejb.MessageDrivenContext;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;

import com.palco.common.Textos;
import com.palco.common.mensajeria.DestinosJms;
import com.palco.common.mensajeria.EventoCanceladoMensaje;
import com.palco.common.mensajeria.MensajeInvalido;
import com.palco.reventa.negocio.RetiroPorCancelacion;

/**
 * Suscriptor del tópico de cancelaciones en Reventa: por cada
 * {@code EVENTO_CANCELADO} retira las publicaciones activas de ese evento. El
 * trabajo lo hace {@link RetiroPorCancelacion}; acá solo se lee el mensaje y
 * se delega.
 *
 * Es otro suscriptor del mismo tópico que ya escuchan Ventas y la auditoría,
 * con su propia suscripción durable: si Reventa está caída cuando se cancela
 * un evento, el mensaje la espera. Para sumarlo no hubo que tocar Eventos, que
 * publica sin saber quién escucha. El selector deja pasar solo
 * {@code EVENTO_CANCELADO}: al mismo tópico van los {@code COMPRA_ANULADA}.
 */
@MessageDriven(name = "CancelacionDeEventoEnReventa", activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = DestinosJms.TOPICO_CANCELACIONES),
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "jakarta.jms.Topic"),
        @ActivationConfigProperty(propertyName = "subscriptionDurability", propertyValue = "Durable"),
        @ActivationConfigProperty(propertyName = "subscriptionName", propertyValue = "reventa-cancelaciones"),
        @ActivationConfigProperty(propertyName = "clientId", propertyValue = "reventa-cancelaciones"),
        @ActivationConfigProperty(propertyName = "messageSelector", propertyValue = DestinosJms.SELECTOR_EVENTO_CANCELADO) })
public class CancelacionDeEventoMDB implements MessageListener {

    private static final Logger LOG = Logger.getLogger(CancelacionDeEventoMDB.class.getName());

    @Inject
    RetiroPorCancelacion retiro;

    @Resource
    MessageDrivenContext contexto;

    @Override
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public void onMessage(Message recibido) {
        EventoCanceladoMensaje mensaje;
        try {
            if (!(recibido instanceof TextMessage conTexto)) {
                throw new MensajeInvalido("El mensaje no es un TextMessage.");
            }
            mensaje = EventoCanceladoMensaje.desdeJson(conTexto.getText());
        } catch (MensajeInvalido | JMSException e) {
            // No se lo puede perder: se deshace la transacción, el broker reintenta y después lo deja en la DLQ.
            LOG.warning("Reventa: mensaje de cancelación inválido, vuelve al broker. " + Textos.enUnaLinea(e.getMessage()));
            contexto.setRollbackOnly();
            return;
        }
        retiro.retirarDelEvento(mensaje);
    }
}
