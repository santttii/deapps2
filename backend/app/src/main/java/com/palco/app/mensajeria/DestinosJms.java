package com.palco.app.mensajeria;

import jakarta.ejb.Singleton;
import jakarta.jms.JMSDestinationDefinition;
import jakarta.jms.JMSDestinationDefinitions;

/**
 * Cola y tópico del taller "Escenario de mensajería". Se crean en el broker
 * embebido de WildFly al desplegar la app, sin configuración manual.
 */
@JMSDestinationDefinitions({
        // Punto a punto: un único consumidor emite las entradas después del pago.
        @JMSDestinationDefinition(
                name = DestinosJms.COLA_EMISION_ENTRADAS,
                interfaceName = "jakarta.jms.Queue",
                destinationName = "cola.emision-entradas"),
        // Publicación/suscripción: Notificaciones y Auditoría reciben cada una su copia.
        @JMSDestinationDefinition(
                name = DestinosJms.TOPICO_COMPRA_CONFIRMADA,
                interfaceName = "jakarta.jms.Topic",
                destinationName = "topico.compra-confirmada")
})
@Singleton
public class DestinosJms {

    public static final String COLA_EMISION_ENTRADAS = "java:global/jms/cola/emisionEntradas";
    public static final String TOPICO_COMPRA_CONFIRMADA = "java:global/jms/topico/compraConfirmada";
}
