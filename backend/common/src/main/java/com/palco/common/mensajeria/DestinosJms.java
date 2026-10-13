package com.palco.common.mensajeria;

/**
 * Nombres con los que se publica y se consume {@code PAGO_CONFIRMADO}. Están
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

    public static final String PAGO_CONFIRMADO = "PAGO_CONFIRMADO";

    /** Filtro de los consumidores ({@code messageSelector}). */
    public static final String SELECTOR_PAGO_CONFIRMADO = "tipoEvento = 'PAGO_CONFIRMADO'";

    private DestinosJms() {
    }
}
