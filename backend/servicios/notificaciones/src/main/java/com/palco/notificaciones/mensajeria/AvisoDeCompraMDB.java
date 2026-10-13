package com.palco.notificaciones.mensajeria;

import java.util.logging.Logger;

import jakarta.ejb.ActivationConfigProperty;
import jakarta.ejb.MessageDriven;
import jakarta.inject.Inject;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;

import com.palco.common.mensajeria.DestinosJms;
import com.palco.common.mensajeria.MensajeInvalido;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.notificaciones.negocio.INotificaciones;

/**
 * Suscriptor del tópico de compras confirmadas que le avisa al comprador.
 *
 * Es un punto de entrada delgado, como el recurso REST: lee el mensaje y
 * delega en {@link INotificaciones}. Como todo MDB, no guarda nada entre un
 * mensaje y el siguiente, y el contenedor atiende con un pool de instancias.
 *
 * Suscripción no durable: el broker le entrega solo lo que se publica mientras
 * está escuchando. Si el servicio está caído, ese aviso se pierde, y se
 * acepta: un aviso viejo no le sirve a nadie. Por lo mismo, un mensaje que no
 * se entiende se descarta con una advertencia en lugar de reintentarse: la
 * copia que va a la cola de mensajes fallidos es la de auditoría.
 */
@MessageDriven(name = "AvisoDeCompra", activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = DestinosJms.TOPICO_COMPRA),
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "jakarta.jms.Topic"),
        @ActivationConfigProperty(propertyName = "messageSelector", propertyValue = DestinosJms.SELECTOR_PAGO_CONFIRMADO) })
public class AvisoDeCompraMDB implements MessageListener {

    private static final Logger LOG = Logger.getLogger(AvisoDeCompraMDB.class.getName());

    @Inject
    INotificaciones notificaciones;

    @Override
    public void onMessage(Message recibido) {
        PagoConfirmadoMensaje mensaje;
        try {
            mensaje = PagoConfirmadoMensaje.desdeJson(LectorDeMensajes.texto(recibido));
        } catch (MensajeInvalido e) {
            // Sin excepción ni rollback: el mensaje queda consumido y no vuelve.
            LOG.warning("AvisoDeCompra: se descarta un mensaje inválido. " + e.getMessage());
            return;
        }
        notificaciones.enviarConfirmacion(mensaje);
        LOG.info("AvisoDeCompra: aviso enviado por la venta " + mensaje.ventaId() + ".");
    }
}
