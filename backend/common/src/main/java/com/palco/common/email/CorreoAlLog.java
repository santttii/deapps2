package com.palco.common.email;

import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import com.palco.common.Textos;

/**
 * Escribe el email en el log del servidor en lugar de mandarlo. Es lo que se
 * usa en desarrollo, donde no hay servidor SMTP: el código de verificación y
 * el aviso de compra se leen en la consola de WildFly.
 *
 * Sale todo en un solo renglón ({@link Textos#enUnaLinea}): el asunto y el
 * cuerpo llevan datos que escribió alguien (un nombre de titular, por ejemplo),
 * y con un salto de línea adentro aparecería un renglón falso en el log.
 *
 * {@code @ApplicationScoped}: no guarda nada, una instancia para todo el servicio.
 * {@code @Typed} hace que solo se pueda inyectar por su clase y no como
 * {@link Correos}: ese lo entrega {@link FabricaDeCorreos}.
 */
@ApplicationScoped
@Typed(CorreoAlLog.class)
public class CorreoAlLog implements Correos {

    /** El valor de {@code PALCO_CORREO} que elige esta implementación. */
    public static final String NOMBRE = "log";

    private static final Logger LOG = Logger.getLogger(CorreoAlLog.class.getName());

    @Override
    public void enviar(Email email) {
        LOG.info(Textos.enUnaLinea("Email para " + email.para() + " — " + email.asunto() + ": " + email.cuerpo()));
    }
}
