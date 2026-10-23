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
import com.palco.common.mensajeria.DestinosJms;
import com.palco.common.mensajeria.MensajeInvalido;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.notificaciones.negocio.IAuditoria;

/**
 * Suscriptor del tópico de compras confirmadas que deja registro de cada
 * mensaje recibido.
 *
 * Es un punto de entrada delgado, como el recurso REST: lee el mensaje y
 * delega en {@link IAuditoria}. Como todo MDB, no guarda nada entre un mensaje
 * y el siguiente.
 *
 * Suscripción durable: el broker le guarda los mensajes en una cola con nombre
 * fijo ({@code clientId} + {@code subscriptionName}) mientras este MDB está
 * detenido, y se los entrega cuando vuelve. Auditoría no puede perder eventos.
 * Ese par de nombres no se repite en ningún otro MDB.
 *
 * {@code maxSession = 1}: un solo consumidor, que procesa los mensajes de a
 * uno y en el orden en que el broker se los entrega. Con las 15 sesiones por
 * defecto sería más rápido, pero las filas se insertarían en cualquier orden.
 * Ese orden no queda guardado en una columna: cada fila lleva solo la hora en
 * que se registró, truncada a segundos.
 *
 * La transacción la maneja el contenedor: recibir el mensaje y guardar la fila
 * se confirman juntos. Si algo falla, el mensaje vuelve al broker, que lo
 * reintenta y al quinto intento lo pasa a la cola de mensajes fallidos (DLQ).
 */
@MessageDriven(name = "AuditoriaDeCompras", activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = DestinosJms.TOPICO_COMPRA),
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "jakarta.jms.Topic"),
        @ActivationConfigProperty(propertyName = "messageSelector", propertyValue = DestinosJms.SELECTOR_COMPRA_CONFIRMADA),
        @ActivationConfigProperty(propertyName = "subscriptionDurability", propertyValue = "Durable"),
        @ActivationConfigProperty(propertyName = "clientId", propertyValue = "palco-auditoria"),
        @ActivationConfigProperty(propertyName = "subscriptionName", propertyValue = "compras-confirmadas"),
        @ActivationConfigProperty(propertyName = "maxSession", propertyValue = "1") })
public class AuditoriaDeComprasMDB implements MessageListener {

    private static final Logger LOG = Logger.getLogger(AuditoriaDeComprasMDB.class.getName());

    @Inject
    IAuditoria auditoria;

    @Resource
    MessageDrivenContext contexto;

    @Override
    public void onMessage(Message recibido) {
        try {
            String tipoEvento = DestinosJms.tipoEventoDe(recibido);
            String cuerpo = LectorDeMensajes.texto(recibido);
            PagoConfirmadoMensaje mensaje = PagoConfirmadoMensaje.desdeJson(cuerpo);
            auditoria.registrar(recibido.getJMSMessageID(), tipoEvento, mensaje, cuerpo);
            // Dice "procesado" y no "registrada": si es una reentrega, registrar encontró la
            // fila que ya estaba y no insertó nada. En los dos casos la fila existe: si el
            // INSERT hubiera fallado, registrar habría lanzado y esta línea no se escribiría.
            // El id viene en el mensaje: va al log en un solo renglón (ver Textos.enUnaLinea).
            LOG.info("AuditoriaDeCompras: procesado el mensaje de la venta " + Textos.enUnaLinea(mensaje.ventaId())
                    + ".");
        } catch (MensajeInvalido | JMSException e) {
            // No se puede registrar lo que no se entiende, pero tampoco se lo puede perder.
            // Se deshace la transacción en lugar de lanzar una excepción (que dejaría una
            // traza por intento): el broker reintenta y después lo guarda en la DLQ.
            LOG.warning("AuditoriaDeCompras: mensaje inválido (entrega " + LectorDeMensajes.numeroDeEntrega(recibido)
                    + "), vuelve al broker. " + Textos.enUnaLinea(e.getMessage()));
            contexto.setRollbackOnly();
        }
    }
}
