package com.palco.ventas.mensajeria;

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
import com.palco.ventas.negocio.CancelacionDeEventos;

/**
 * Suscriptor del tópico de cancelaciones: por cada {@code EVENTO_CANCELADO}
 * que publica Eventos, anula las compras pagadas de ese evento y pide sus
 * reembolsos. El trabajo lo hace {@link CancelacionDeEventos}; acá solo se lee
 * el mensaje y se delega, como en el recurso REST.
 *
 * Está en Ventas porque Ventas es la dueña de las ventas y de las entradas:
 * Eventos no sabe quién compró qué, y por eso su mensaje lleva solo el id del
 * evento.
 *
 * El selector deja pasar únicamente {@code EVENTO_CANCELADO}: al mismo tópico
 * van los {@code COMPRA_ANULADA} que publica este mismo flujo, y sin el filtro
 * este consumidor se escucharía a sí mismo.
 *
 * Es un tópico y no una cola porque el mismo hecho les interesa a varios: hoy
 * Ventas y la auditoría. Que sea un tópico obliga a que el trabajo sea
 * idempotente, y lo es: las ventas se anulan con un UPDATE condicional, así
 * que una segunda entrega no cambia ninguna fila, no pide otro reembolso y no
 * vuelve a publicar el aviso.
 *
 * {@code REQUIRED} en {@code onMessage}: recibir el mensaje queda en una
 * transacción, pero cada compra se anula en la suya ({@code REQUIRES_NEW} en
 * {@link CancelacionDeEventos#anular}), así un fallo en la última no deshace
 * las anteriores.
 */
@MessageDriven(name = "CancelacionDeEvento", activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = DestinosJms.TOPICO_CANCELACIONES),
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "jakarta.jms.Topic"),
        @ActivationConfigProperty(propertyName = "subscriptionDurability", propertyValue = "Durable"),
        @ActivationConfigProperty(propertyName = "subscriptionName", propertyValue = "ventas-cancelaciones"),
        @ActivationConfigProperty(propertyName = "clientId", propertyValue = "ventas-cancelaciones"),
        @ActivationConfigProperty(propertyName = "messageSelector", propertyValue = DestinosJms.SELECTOR_EVENTO_CANCELADO) })
public class CancelacionDeEventoMDB implements MessageListener {

    private static final Logger LOG = Logger.getLogger(CancelacionDeEventoMDB.class.getName());

    @Inject
    CancelacionDeEventos cancelacion;

    @Resource
    MessageDrivenContext contexto;

    @Override
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public void onMessage(Message recibido) {
        EventoCanceladoMensaje mensaje;
        try {
            mensaje = EventoCanceladoMensaje.desdeJson(texto(recibido));
        } catch (MensajeInvalido e) {
            // No se puede reembolsar lo que no se entiende, pero tampoco se lo puede perder. Se deshace
            // la transacción en lugar de lanzar: el broker reintenta y después lo deja en la DLQ.
            LOG.warning("CancelacionDeEvento: mensaje inválido (entrega " + numeroDeEntrega(recibido)
                    + "), vuelve al broker. " + Textos.enUnaLinea(e.getMessage()));
            contexto.setRollbackOnly();
            return;
        }

        int anuladas = cancelacion.anularComprasDelEvento(mensaje);
        LOG.info("CancelacionDeEvento: procesado el mensaje del evento " + Textos.enUnaLinea(mensaje.eventoId())
                + ". " + (anuladas == 0
                        ? "No había compras pagadas por anular: o el evento no tenía ventas, o es un mensaje repetido."
                        : "Compras anuladas: " + anuladas + "."));
    }

    /** El texto del mensaje. {@code EVENTO_CANCELADO} viaja siempre como texto JSON. */
    private static String texto(Message recibido) {
        if (!(recibido instanceof TextMessage conTexto)) {
            throw new MensajeInvalido("El mensaje no es un TextMessage.");
        }
        try {
            return conTexto.getText();
        } catch (JMSException e) {
            throw new MensajeInvalido("No se pudo leer el texto del mensaje.", e);
        }
    }

    /**
     * Cuántas veces entregó el broker este mensaje: 1 la primera vez, 2 en el
     * primer reintento. Solo se usa para el log; si no se puede leer, devuelve 0.
     */
    private static int numeroDeEntrega(Message recibido) {
        try {
            return recibido.getIntProperty("JMSXDeliveryCount");
        } catch (JMSException | NumberFormatException e) {
            return 0;
        }
    }
}
