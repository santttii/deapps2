package com.palco.common.mensajeria;

import jakarta.annotation.Resource;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import jakarta.jms.JMSConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.Topic;

/**
 * Publica {@code EVENTO_CANCELADO} y {@code COMPRA_ANULADA} en el broker del
 * servidor (JMS).
 *
 * Stateless: publicar no depende de ninguna llamada anterior; todo lo que hace
 * falta viene en el mensaje.
 *
 * {@code MANDATORY}, igual que {@link PublicadorPagoConfirmadoJms}: exige que
 * quien llama ya esté en una transacción y no abre una propia. Así el mensaje
 * forma parte de la transacción que marcó el evento como cancelado o que anuló
 * la compra: si esa se deshace, el mensaje no sale.
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.MANDATORY)
public class PublicadorDeCancelacionesJms implements PublicadorDeCancelaciones {

    @Inject
    @JMSConnectionFactory(DestinosJms.FABRICA_XA)
    JMSContext contexto;

    @Resource(lookup = DestinosJms.TOPICO_CANCELACIONES)
    Topic topico;

    @Override
    public void publicarEventoCancelado(EventoCanceladoMensaje mensaje) {
        mensaje.validar();
        contexto.createProducer()
                .setProperty(DestinosJms.PROPIEDAD_TIPO_EVENTO, DestinosJms.EVENTO_CANCELADO)
                .send(topico, mensaje.aJson());
    }

    @Override
    public void publicarCompraAnulada(CompraAnuladaMensaje mensaje) {
        mensaje.validar();
        contexto.createProducer()
                .setProperty(DestinosJms.PROPIEDAD_TIPO_EVENTO, DestinosJms.COMPRA_ANULADA)
                .send(topico, mensaje.aJson());
    }
}
