package com.palco.common.email;

import java.util.Locale;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Factory: decide por dónde sale el correo. Es el único lugar que conoce las
 * dos implementaciones; quien avisa pide un {@link Correos} con
 * {@code @Inject} y recibe el que salga de acá. Es el mismo mecanismo con el
 * que Ventas elige quién cobra ({@code FabricaDeCobros}).
 *
 * Lo elige la variable de entorno {@code PALCO_CORREO}:
 *
 * <ul>
 * <li>{@code smtp}: {@link CorreoSmtp}, contra el servidor configurado;</li>
 * <li>{@code log}: {@link CorreoAlLog}, el email queda en el log del servidor.</li>
 * </ul>
 *
 * Sin {@code PALCO_CORREO} se mira si hay SMTP configurado: con
 * {@code PALCO_SMTP_HOST} se manda por SMTP, y sin él se escribe en el log,
 * porque no habría a dónde mandarlo. Así en desarrollo funciona sin configurar
 * nada y en el hosting basta con definir el servidor.
 *
 * Elegir {@code smtp} a mano sin servidor configurado no cae al log en
 * silencio: se respeta la elección, los envíos van a fallar y cada uno deja su
 * WARNING. Un valor que no se conoce tampoco cae al log: se decide como si no
 * estuviera, y queda un WARNING con los valores posibles.
 *
 * {@code @ApplicationScoped}, acá y en lo que produce: se elige una sola vez,
 * en el primer email, y vale hasta que se redespliega el servicio.
 */
@ApplicationScoped
public class FabricaDeCorreos {

    private static final Logger LOG = Logger.getLogger(FabricaDeCorreos.class.getName());

    static final String VARIABLE = "PALCO_CORREO";

    /**
     * Lo que CDI entrega donde alguien pide {@code @Inject Correos}. Los dos
     * candidatos llegan como parámetros: los crea el contenedor, y recién
     * cuando se usan.
     */
    @Produces
    @ApplicationScoped
    Correos correos(CorreoSmtp smtp, CorreoAlLog alLog) {
        return elegir(System.getenv(VARIABLE), CorreoSmtp.estaConfigurado(), smtp, alLog);
    }

    /** Aparte del método de arriba para poder probarlo sin servidor ni variables de entorno. */
    static Correos elegir(String elegido, boolean smtpConfigurado, Correos smtp, Correos alLog) {
        String nombre = elegido == null ? "" : elegido.strip().toLowerCase(Locale.ROOT);

        if (nombre.equals(CorreoSmtp.NOMBRE)) {
            if (!smtpConfigurado) {
                LOG.warning("Correo: " + VARIABLE + " vale \"" + CorreoSmtp.NOMBRE + "\" pero falta PALCO_SMTP_HOST."
                        + " Se respeta la elección y cada envío va a fallar con un WARNING.");
            }
            return smtp;
        }
        if (nombre.equals(CorreoAlLog.NOMBRE)) {
            LOG.info("Correo: los emails quedan en el log del servidor (" + VARIABLE + "="
                    + CorreoAlLog.NOMBRE + ").");
            return alLog;
        }
        if (!nombre.isEmpty()) {
            LOG.warning("Correo: " + VARIABLE + " vale \"" + elegido + "\" y ese valor no existe. Los posibles son \""
                    + CorreoSmtp.NOMBRE + "\" y \"" + CorreoAlLog.NOMBRE + "\". Se decide por PALCO_SMTP_HOST.");
        }
        if (smtpConfigurado) {
            LOG.info("Correo: se manda por SMTP al servidor de PALCO_SMTP_HOST.");
            return smtp;
        }
        LOG.warning("Correo: sin PALCO_SMTP_HOST, los emails quedan en el log del servidor y nadie los recibe."
                + " Para mandarlos de verdad hay que definir PALCO_SMTP_HOST (y el usuario y la contraseña).");
        return alLog;
    }
}
