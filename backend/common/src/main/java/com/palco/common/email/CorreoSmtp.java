package com.palco.common.email;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import com.palco.common.Textos;

/**
 * Manda el email por SMTP con Jakarta Mail. La API viene en la plataforma
 * (Jakarta EE 11) y la implementación la trae WildFly con la capa {@code mail}
 * (en {@code backend/servidor/pom.xml}), así que no hay que agregar ninguna
 * dependencia.
 *
 * No está atado a ningún proveedor: se configura por variables de entorno, así
 * que sirve igual con Mailtrap (que atrapa los emails en una bandeja web y es
 * lo que se pensó usar en desarrollo), con Gmail o con cualquier SMTP.
 *
 * <ul>
 * <li>{@code PALCO_SMTP_HOST}: el servidor. Sin esto no se puede mandar nada, y
 * {@link FabricaDeCorreos} elige {@link CorreoAlLog};</li>
 * <li>{@code PALCO_SMTP_PUERTO}: 587 por defecto (el de STARTTLS);</li>
 * <li>{@code PALCO_SMTP_USUARIO} y {@code PALCO_SMTP_PASSWORD}: si falta el
 * usuario se manda sin autenticar, que es lo que aceptan los servidores de
 * prueba locales;</li>
 * <li>{@code PALCO_SMTP_TLS}: {@code false} apaga STARTTLS. Por defecto está
 * prendido: un servidor de verdad pide la contraseña por un canal cifrado;</li>
 * <li>{@code PALCO_EMAIL_REMITENTE}: de quién sale. Por defecto
 * {@code no-responder@palco.test}.</li>
 * </ul>
 *
 * Las credenciales no están en el repo: van en {@code backend/.env}, que no se
 * versiona, y en el hosting como variables de entorno.
 *
 * {@code @ApplicationScoped}: la configuración se lee una vez y la sesión SMTP
 * no guarda nada de cada pedido, así que alcanza una instancia por servicio.
 * {@code @Typed} hace que solo se pueda inyectar por su clase y no como
 * {@link Correos}: ese lo entrega {@link FabricaDeCorreos}.
 */
@ApplicationScoped
@Typed(CorreoSmtp.class)
public class CorreoSmtp implements Correos {

    /** El valor de {@code PALCO_CORREO} que elige esta implementación. */
    public static final String NOMBRE = "smtp";

    static final String REMITENTE_POR_DEFECTO = "no-responder@palco.test";
    static final int PUERTO_POR_DEFECTO = 587;

    private static final Logger LOG = Logger.getLogger(CorreoSmtp.class.getName());

    /** No son privados para que un test los fije sin variables de entorno. */
    String host = entorno("PALCO_SMTP_HOST", null);
    int puerto = entornoEntero("PALCO_SMTP_PUERTO", PUERTO_POR_DEFECTO);
    String usuario = entorno("PALCO_SMTP_USUARIO", null);
    String password = entorno("PALCO_SMTP_PASSWORD", null);
    boolean tls = !"false".equalsIgnoreCase(entorno("PALCO_SMTP_TLS", "true"));
    String remitente = entorno("PALCO_EMAIL_REMITENTE", REMITENTE_POR_DEFECTO);

    /** Si hay servidor configurado. Lo mira {@link FabricaDeCorreos} antes de elegir. */
    public static boolean estaConfigurado() {
        return entorno("PALCO_SMTP_HOST", null) != null;
    }

    @Override
    public void enviar(Email email) {
        try {
            entregar(armar(email));
        } catch (MessagingException | RuntimeException e) {
            // Un aviso que no sale no puede deshacer la operación que lo disparó: ver la nota de Correos.
            LOG.log(Level.WARNING, "No se pudo mandar el email a " + Textos.enUnaLinea(email.para())
                    + ". Motivo: " + e.getMessage(), e);
        }
    }

    /** El mensaje MIME, ya con remitente, destinatario, asunto y cuerpo en UTF-8. */
    MimeMessage armar(Email email) throws MessagingException {
        MimeMessage mensaje = new MimeMessage(sesion());
        mensaje.setFrom(new InternetAddress(remitente));
        mensaje.setRecipients(Message.RecipientType.TO, InternetAddress.parse(email.para(), false));
        mensaje.setSubject(email.asunto(), "UTF-8");
        mensaje.setText(email.cuerpo(), "UTF-8");
        return mensaje;
    }

    /** La entrega de verdad. No es privada para que un test la reemplace y no necesite un servidor SMTP. */
    void entregar(MimeMessage mensaje) throws MessagingException {
        Transport.send(mensaje);
    }

    private Session sesion() {
        Properties propiedades = new Properties();
        propiedades.put("mail.smtp.host", host == null ? "localhost" : host);
        propiedades.put("mail.smtp.port", String.valueOf(puerto));
        propiedades.put("mail.smtp.starttls.enable", String.valueOf(tls));
        boolean autenticar = usuario != null && !usuario.isBlank();
        propiedades.put("mail.smtp.auth", String.valueOf(autenticar));
        if (!autenticar) {
            return Session.getInstance(propiedades);
        }
        return Session.getInstance(propiedades, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(usuario, password == null ? "" : password);
            }
        });
    }

    private static String entorno(String variable, String porDefecto) {
        String valor = System.getenv(variable);
        return valor == null || valor.isBlank() ? porDefecto : valor.strip();
    }

    private static int entornoEntero(String variable, int porDefecto) {
        String valor = entorno(variable, null);
        if (valor == null) {
            return porDefecto;
        }
        try {
            return Integer.parseInt(valor);
        } catch (NumberFormatException e) {
            LOG.warning(variable + " vale \"" + Textos.enUnaLinea(valor) + "\" y no es un número: se usa "
                    + porDefecto + ".");
            return porDefecto;
        }
    }
}
