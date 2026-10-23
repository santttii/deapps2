package com.palco.common.mensajeria;

import jakarta.annotation.Resource;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import jakarta.jms.JMSConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSProducer;
import jakarta.jms.Queue;
import jakarta.jms.Topic;

/**
 * Publica {@code PAGO_CONFIRMADO} en el broker del servidor (JMS).
 *
 * Stateless: publicar no depende de ninguna llamada anterior; todo lo que hace
 * falta viene en el mensaje, así que el contenedor reutiliza un pool de instancias.
 *
 * {@code MANDATORY}: exige que quien llama ya esté dentro de una transacción y
 * no abre una propia. Así el mensaje forma parte de la transacción de la venta:
 * si la venta se deshace, el mensaje no sale. Con {@code REQUIRED}, llamado sin
 * transacción abriría una y el mensaje saldría aunque la venta falle después.
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.MANDATORY)
public class PublicadorPagoConfirmadoJms implements PublicadorPagoConfirmado {

    @Inject
    @JMSConnectionFactory(DestinosJms.FABRICA_XA)
    JMSContext contexto;

    @Resource(lookup = DestinosJms.COLA_EMISION)
    Queue cola;

    @Resource(lookup = DestinosJms.TOPICO_COMPRA)
    Topic topico;

    @Override
    public void publicar(PagoConfirmadoMensaje mensaje) {
        mensaje.validar();
        // Se convierte una sola vez: a la cola y al tópico va exactamente el mismo texto.
        String cuerpo = mensaje.aJson();
        JMSProducer productor = contexto.createProducer()
                .setProperty(DestinosJms.PROPIEDAD_TIPO_EVENTO, DestinosJms.PAGO_CONFIRMADO);
        // Mandar un String crea un TextMessage; la entrega persistente es el valor por defecto de JMS.
        productor.send(cola, cuerpo);
        productor.send(topico, cuerpo);
    }

    @Override
    public void publicarReventa(PagoConfirmadoMensaje mensaje) {
        mensaje.validar();
        contexto.createProducer()
                .setProperty(DestinosJms.PROPIEDAD_TIPO_EVENTO, DestinosJms.REVENTA_CONFIRMADA)
                .send(topico, mensaje.aJson());
    }
}
