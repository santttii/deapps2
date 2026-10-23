package com.palco.common.mensajeria;

import jakarta.jms.JMSException;
import jakarta.jms.Message;

/**
 * Nombres con los que se publica y se consume {@code PAGO_CONFIRMADO} (y
 * {@code REVENTA_CONFIRMADA}, la compra en la reventa). Están
 * acá, y no repetidos en cada servicio, para que quien publica y quien consume
 * no puedan escribirlos distinto. Son constantes de compilación porque las
 * anotaciones ({@code @Resource}, {@code @ActivationConfigProperty}) no admiten
 * otra cosa, y el servidor no reemplaza {@code ${…}} dentro de ellas.
 *
 * Los destinos los crea {@code backend/servidor/src/main/wildfly/configurar.cli}.
 */
public final class DestinosJms {

    /**
     * Fábrica de conexiones transaccional (XA) del servidor: lo que se manda con
     * ella sale recién cuando la transacción hace commit. Es la única que se usa
     * para publicar; {@code java:/ConnectionFactory} queda fuera de la transacción.
     */
    public static final String FABRICA_XA = "java:/JmsXA";

    /** Cola {@code cola.emision-entradas}: cada mensaje lo recibe un solo consumidor. */
    public static final String COLA_EMISION = "java:/jms/queue/emisionEntradas";

    /** Tópico {@code topico.compra-confirmada}: cada suscriptor recibe su copia. */
    public static final String TOPICO_COMPRA = "java:/jms/topic/compraConfirmada";

    /** Propiedad del mensaje que dice qué evento es; los consumidores filtran por ella. */
    public static final String PROPIEDAD_TIPO_EVENTO = "tipoEvento";

    /** Se aprobó el pago de una compra del catálogo: va a la cola de emisión y al tópico. */
    public static final String PAGO_CONFIRMADO = "PAGO_CONFIRMADO";

    /**
     * Se compró una entrada en la reventa oficial. Va solo al tópico: la
     * entrada nueva ya se emite en la transferencia, así que la cola de
     * emisión no tiene nada que hacer. El cuerpo es el mismo que el de
     * {@link #PAGO_CONFIRMADO} ({@link PagoConfirmadoMensaje}), con la venta
     * nueva del comprador.
     */
    public static final String REVENTA_CONFIRMADA = "REVENTA_CONFIRMADA";

    /** Filtro del consumidor de la cola de emisión: solo las compras del catálogo. */
    public static final String SELECTOR_PAGO_CONFIRMADO = "tipoEvento = 'PAGO_CONFIRMADO'";

    /**
     * Filtro de los suscriptores del tópico (aviso, auditoría y facturación):
     * las compras del catálogo y las de la reventa. Es el uso de
     * {@code tipoEvento} que previó el taller de mensajería: que un mismo
     * tópico agrupe más de un tipo de evento.
     */
    public static final String SELECTOR_COMPRA_CONFIRMADA =
            "tipoEvento IN ('PAGO_CONFIRMADO', 'REVENTA_CONFIRMADA')";

    private DestinosJms() {
    }

    /**
     * El {@code tipoEvento} de un mensaje recibido. Lanza {@link MensajeInvalido}
     * si no lo trae o si no es uno de los dos conocidos: el selector ya los
     * filtra, así que solo pasa con un mensaje armado a mano.
     */
    public static String tipoEventoDe(Message recibido) {
        String tipo;
        try {
            tipo = recibido.getStringProperty(PROPIEDAD_TIPO_EVENTO);
        } catch (JMSException e) {
            throw new MensajeInvalido("No se pudo leer el tipoEvento del mensaje.", e);
        }
        if (!PAGO_CONFIRMADO.equals(tipo) && !REVENTA_CONFIRMADA.equals(tipo)) {
            throw new MensajeInvalido("El mensaje no trae un tipoEvento conocido: " + tipo);
        }
        return tipo;
    }
}
