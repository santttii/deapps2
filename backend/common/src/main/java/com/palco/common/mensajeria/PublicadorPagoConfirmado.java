package com.palco.common.mensajeria;

import jakarta.ejb.Local;

/**
 * Publica {@code PAGO_CONFIRMADO}. Quien confirma una venta depende de esta
 * interfaz y no de JMS: en un test se la reemplaza por una lambda.
 */
@Local
public interface PublicadorPagoConfirmado {

    /**
     * Manda el mensaje a la cola de emisión y al tópico de compras. Hay que
     * llamarlo dentro de una transacción: el mensaje sale solo si esa
     * transacción termina bien. Un mensaje incompleto lanza {@link MensajeInvalido}.
     */
    void publicar(PagoConfirmadoMensaje mensaje);
}
