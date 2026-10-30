package com.palco.common.mensajeria;

import jakarta.ejb.Local;

/**
 * Publica los dos mensajes del tópico de cancelaciones. Quien cancela un
 * evento o anula una compra depende de esta interfaz y no de JMS: en un test
 * se la reemplaza por un doble.
 */
@Local
public interface PublicadorDeCancelaciones {

    /**
     * Manda {@code EVENTO_CANCELADO} al tópico de cancelaciones. Lo publica
     * Eventos. Hay que llamarlo dentro de una transacción: el mensaje sale
     * solo si esa transacción termina bien, así nadie reembolsa por un evento
     * que al final no quedó cancelado.
     */
    void publicarEventoCancelado(EventoCanceladoMensaje mensaje);

    /**
     * Manda {@code COMPRA_ANULADA} al tópico de cancelaciones, uno por compra.
     * Lo publica Ventas después de anular las entradas y pedir el reembolso.
     * Igual que el otro, hay que llamarlo dentro de una transacción.
     */
    void publicarCompraAnulada(CompraAnuladaMensaje mensaje);
}
