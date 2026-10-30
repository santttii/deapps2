package com.palco.common.mensajeria;

import jakarta.jms.JMSException;
import jakarta.jms.Message;

/**
 * Nombres con los que se publica y se consume {@code PAGO_CONFIRMADO} (y
 * {@code REVENTA_CONFIRMADA}, la compra en la reventa), y los del tópico de
 * cancelaciones ({@code EVENTO_CANCELADO} y {@code COMPRA_ANULADA}). Están
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

    /**
     * Tópico {@code topico.eventos-cancelados}: la cancelación de un evento y
     * la anulación de cada compra. Es un tópico aparte del de compras porque
     * son otro proceso, con otros suscriptores y otro cuerpo de mensaje; si
     * fueran al mismo, cada consumidor tendría que filtrar los tipos del otro.
     */
    public static final String TOPICO_CANCELACIONES = "java:/jms/topic/eventosCancelados";

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

    /** El organizador dueño canceló un evento. Lo publica Eventos; lo consume Ventas. */
    public static final String EVENTO_CANCELADO = "EVENTO_CANCELADO";

    /**
     * Ventas anuló una compra porque se canceló su evento, y pidió el
     * reembolso. Uno por compra. Lo consumen el aviso al comprador y la
     * auditoría.
     */
    public static final String COMPRA_ANULADA = "COMPRA_ANULADA";

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

    /** Filtro de Ventas en el tópico de cancelaciones: solo el hecho del evento, no sus propias anulaciones. */
    public static final String SELECTOR_EVENTO_CANCELADO = "tipoEvento = 'EVENTO_CANCELADO'";

    /** Filtro del aviso al comprador: solo las anulaciones, que son las que traen a quién avisarle. */
    public static final String SELECTOR_COMPRA_ANULADA = "tipoEvento = 'COMPRA_ANULADA'";

    /** Filtro de la auditoría de cancelaciones: anota el hecho del evento y el de cada compra. */
    public static final String SELECTOR_CANCELACIONES =
            "tipoEvento IN ('EVENTO_CANCELADO', 'COMPRA_ANULADA')";

    private DestinosJms() {
    }

    /**
     * El {@code tipoEvento} de un mensaje del tópico de compras. Lanza
     * {@link MensajeInvalido} si no lo trae o si no es uno de los dos
     * conocidos: el selector ya los filtra, así que solo pasa con un mensaje
     * armado a mano.
     */
    public static String tipoEventoDe(Message recibido) {
        return exigirTipo(recibido, PAGO_CONFIRMADO, REVENTA_CONFIRMADA);
    }

    /**
     * El {@code tipoEvento} de un mensaje del tópico de cancelaciones. Es un
     * método aparte de {@link #tipoEventoDe} para que cada tópico acepte solo
     * sus tipos: un {@code PAGO_CONFIRMADO} que llegara acá sería un error, no
     * algo que haya que atender.
     */
    public static String tipoDeCancelacionDe(Message recibido) {
        return exigirTipo(recibido, EVENTO_CANCELADO, COMPRA_ANULADA);
    }

    private static String exigirTipo(Message recibido, String... conocidos) {
        String tipo;
        try {
            tipo = recibido.getStringProperty(PROPIEDAD_TIPO_EVENTO);
        } catch (JMSException e) {
            throw new MensajeInvalido("No se pudo leer el tipoEvento del mensaje.", e);
        }
        for (String conocido : conocidos) {
            if (conocido.equals(tipo)) {
                return tipo;
            }
        }
        throw new MensajeInvalido("El mensaje no trae un tipoEvento conocido: " + tipo);
    }
}
