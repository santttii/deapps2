package com.palco.notificaciones.mensajeria;

import java.util.logging.Logger;

import jakarta.annotation.Resource;
import jakarta.ejb.ActivationConfigProperty;
import jakarta.ejb.MessageDriven;
import jakarta.ejb.MessageDrivenContext;
import jakarta.inject.Inject;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;

import com.palco.common.Textos;
import com.palco.common.mensajeria.CompraAnuladaMensaje;
import com.palco.common.mensajeria.MensajeInvalido;
import com.palco.common.mensajeria.DestinosJms;
import com.palco.notificaciones.negocio.IAuditoria;

/**
 * Suscriptor durable del tópico de cancelaciones que deja el rastro de cada
 * compra anulada. Es el equivalente de {@link AuditoriaDeComprasMDB} para el
 * segundo proceso asincrónico.
 *
 * Suscripción durable, a diferencia del aviso al comprador: si el servicio
 * está caído, el broker guarda los mensajes y los entrega cuando vuelve. Un
 * aviso viejo no le sirve a nadie, pero el rastro de una anulación con su
 * reembolso no se puede perder.
 *
 * Escucha solo {@code COMPRA_ANULADA}: la fila de auditoría necesita venta,
 * comprador y monto, y el {@code EVENTO_CANCELADO} que publica Eventos no los
 * tiene. El rastro de la cancelación es el conjunto de estas filas.
 *
 * {@code maxSession = 1}: una suscripción durable de un tópico no admite más
 * de una sesión a la vez, igual que en la auditoría de compras.
 */
@MessageDriven(name = "AuditoriaDeCancelaciones", activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = DestinosJms.TOPICO_CANCELACIONES),
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "jakarta.jms.Topic"),
        @ActivationConfigProperty(propertyName = "messageSelector", propertyValue = DestinosJms.SELECTOR_COMPRA_ANULADA),
        @ActivationConfigProperty(propertyName = "subscriptionDurability", propertyValue = "Durable"),
        @ActivationConfigProperty(propertyName = "clientId", propertyValue = "palco-auditoria-cancelaciones"),
        @ActivationConfigProperty(propertyName = "subscriptionName", propertyValue = "compras-anuladas"),
        @ActivationConfigProperty(propertyName = "maxSession", propertyValue = "1") })
public class AuditoriaDeCancelacionesMDB implements MessageListener {

    private static final Logger LOG = Logger.getLogger(AuditoriaDeCancelacionesMDB.class.getName());

    @Inject
    IAuditoria auditoria;

    @Resource
    MessageDrivenContext contexto;

    @Override
    public void onMessage(Message recibido) {
        try {
            DestinosJms.tipoDeCancelacionDe(recibido);
            String cuerpo = LectorDeMensajes.texto(recibido);
            CompraAnuladaMensaje mensaje = CompraAnuladaMensaje.desdeJson(cuerpo);
            auditoria.registrarAnulacion(recibido.getJMSMessageID(), mensaje, cuerpo);
            // Dice "procesado" y no "registrada" por lo mismo que la auditoría de compras: si es
            // una reentrega, la fila ya estaba y no se insertó nada.
            LOG.info("AuditoriaDeCancelaciones: procesado el mensaje de la venta anulada "
                    + Textos.enUnaLinea(mensaje.ventaId()) + ".");
        } catch (MensajeInvalido | JMSException e) {
            // No se puede registrar lo que no se entiende, pero tampoco se lo puede perder:
            // el broker reintenta y después lo guarda en la DLQ.
            LOG.warning("AuditoriaDeCancelaciones: mensaje inválido (entrega "
                    + LectorDeMensajes.numeroDeEntrega(recibido) + "), vuelve al broker. "
                    + Textos.enUnaLinea(e.getMessage()));
            contexto.setRollbackOnly();
        }
    }
}
