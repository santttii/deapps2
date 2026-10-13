package com.palco.notificaciones.mensajeria;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

import com.palco.common.mensajeria.MensajeInvalido;

/** Lo que los dos MDB de este servicio hacen igual al recibir un mensaje JMS. */
final class LectorDeMensajes {

    private LectorDeMensajes() {
    }

    /**
     * El texto del mensaje. Lanza {@link MensajeInvalido} si no es un
     * {@code TextMessage} o si no se puede leer: {@code PAGO_CONFIRMADO} viaja
     * siempre como texto JSON.
     */
    static String texto(Message recibido) {
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
     * primer reintento. Sale de {@code JMSXDeliveryCount}, una propiedad
     * estándar de JMS. Solo se usa para el log; si no se puede leer, devuelve 0.
     */
    static int numeroDeEntrega(Message recibido) {
        try {
            return recibido.getIntProperty("JMSXDeliveryCount");
        } catch (JMSException | NumberFormatException e) {
            return 0;
        }
    }
}
