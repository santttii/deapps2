package com.palco.common.email;

/**
 * Mandar un email, visto desde quien avisa. Los servicios dependen de esta
 * interfaz y no de cómo sale el correo: igual que {@code Cobros} en Ventas,
 * tiene dos implementaciones ({@link CorreoSmtp} contra un servidor SMTP real y
 * {@link CorreoAlLog}, que lo escribe en el log) y la elige
 * {@link FabricaDeCorreos} según la configuración.
 *
 * Mandar un email nunca falla hacia arriba: {@link #enviar} no lanza
 * excepciones si el correo no sale. Es a propósito. El email es un aviso, no
 * parte de la operación: que el servidor SMTP no conteste no puede deshacer un
 * registro ni una compra ya cobrada. Cuando no sale, queda un WARNING en el
 * log con el destinatario y el motivo.
 */
public interface Correos {

    /**
     * Manda el email, o lo deja anotado en el log si no se puede. No lanza
     * excepciones: ver la nota de la interfaz.
     */
    void enviar(Email email);
}
