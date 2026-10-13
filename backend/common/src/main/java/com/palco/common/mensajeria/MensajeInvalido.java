package com.palco.common.mensajeria;

import jakarta.ejb.ApplicationException;

/**
 * El texto recibido no es un {@code PAGO_CONFIRMADO} válido, o el que se quiso
 * publicar está incompleto.
 *
 * {@code @ApplicationException(rollback = true)}: al publicar, deshace la
 * transacción de la venta (publicar un mensaje incompleto es un error de
 * programación de quien publica, y el cliente recibe un 500). Al consumir, cada
 * MDB decide qué hace con el mensaje.
 */
@ApplicationException(rollback = true)
public class MensajeInvalido extends RuntimeException {

    public MensajeInvalido(String motivo) {
        super(motivo);
    }

    public MensajeInvalido(String motivo, Throwable causa) {
        super(motivo, causa);
    }
}
