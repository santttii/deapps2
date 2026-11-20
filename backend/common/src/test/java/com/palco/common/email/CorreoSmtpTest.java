package com.palco.common.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;

class CorreoSmtpTest {

    /** Reemplaza la entrega de verdad: así el test no necesita un servidor SMTP. */
    private static final class SmtpDePrueba extends CorreoSmtp {
        final List<MimeMessage> entregados = new ArrayList<>();
        MessagingException falla;

        SmtpDePrueba() {
            host = "smtp.palco.test";
            remitente = "no-responder@palco.test";
            usuario = null;
        }

        @Override
        void entregar(MimeMessage mensaje) throws MessagingException {
            if (falla != null) {
                throw falla;
            }
            entregados.add(mensaje);
        }
    }

    @Test
    void elMensajeLlevaRemitenteDestinatarioAsuntoYCuerpo() throws Exception {
        SmtpDePrueba smtp = new SmtpDePrueba();

        smtp.enviar(new Email("ana@palco.test", "Tu código de Palco", "Tu código es 123456."));

        assertEquals(1, smtp.entregados.size());
        MimeMessage mensaje = smtp.entregados.get(0);
        assertEquals("no-responder@palco.test", mensaje.getFrom()[0].toString());
        assertEquals("ana@palco.test", mensaje.getRecipients(Message.RecipientType.TO)[0].toString());
        assertEquals("Tu código de Palco", mensaje.getSubject());
        assertEquals("Tu código es 123456.", mensaje.getContent());
    }

    @Test
    void elAsuntoConAcentosViajaEnUtf8() throws Exception {
        SmtpDePrueba smtp = new SmtpDePrueba();

        smtp.enviar(new Email("ana@palco.test", "Confirmación de tu compra", "Música"));

        MimeMessage mensaje = smtp.entregados.get(0);
        assertEquals("Confirmación de tu compra", mensaje.getSubject());
        // En la cabecera va codificado, no en bytes crudos.
        assertTrue(mensaje.getHeader("Subject")[0].startsWith("=?UTF-8?"), mensaje.getHeader("Subject")[0]);
    }

    @Test
    void siElServidorNoRespondeElAvisoNoRompeLaOperacionQueLoDisparo() {
        // Un email que no sale no puede deshacer un registro ni una compra ya cobrada.
        SmtpDePrueba smtp = new SmtpDePrueba();
        smtp.falla = new MessagingException("conexión rechazada");

        assertDoesNotThrow(() -> smtp.enviar(new Email("ana@palco.test", "Asunto", "Cuerpo")));
        assertTrue(smtp.entregados.isEmpty());
    }

    @Test
    void elWarningDeUnEnvioQueFallaNoTraeElEmailDeNadieNiLaTraza() {
        // Así contesta un servidor que rechaza al destinatario: la dirección viene en la excepción de adentro.
        SmtpDePrueba smtp = new SmtpDePrueba();
        smtp.falla = new SendFailedException("Invalid Addresses",
                new MessagingException("550 5.1.1 <ana.perez@palco.test>: Recipient address rejected"));
        Logger log = Logger.getLogger(CorreoSmtp.class.getName());
        List<LogRecord> registros = new ArrayList<>();
        Handler capturador = new Handler() {
            @Override
            public void publish(LogRecord registro) {
                registros.add(registro);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(capturador);
        log.setUseParentHandlers(false);
        try {
            smtp.enviar(new Email("ana.perez@palco.test", "Tu código de Palco", "Tu código es 123456."));
        } finally {
            log.removeHandler(capturador);
            log.setUseParentHandlers(true);
        }

        assertEquals(1, registros.size());
        LogRecord registro = registros.get(0);
        assertEquals(Level.WARNING, registro.getLevel());
        String renglon = registro.getMessage();
        assertTrue(renglon.startsWith("No se pudo mandar el email a a***@palco.test. Motivo: "), renglon);
        assertTrue(renglon.contains("<a***@palco.test>: Recipient address rejected"), "queda el motivo: " + renglon);
        assertFalse(renglon.contains("ana.perez"), renglon);
        assertFalse(renglon.contains("123456"), renglon);
        assertFalse(renglon.contains("\n"), renglon);
        assertNull(registro.getThrown(), "sin la traza, que repetiría la dirección");
    }

    @Test
    void laSesionTieneTiempoMaximoParaConectarLeerYEscribir() throws Exception {
        // Sin estos valores Jakarta Mail espera para siempre a un servidor que no contesta.
        SmtpDePrueba smtp = new SmtpDePrueba();

        smtp.enviar(new Email("ana@palco.test", "Asunto", "Cuerpo"));

        java.util.Properties propiedades = smtp.entregados.get(0).getSession().getProperties();
        assertEquals("5000", propiedades.getProperty("mail.smtp.connectiontimeout"));
        assertEquals("5000", propiedades.getProperty("mail.smtp.timeout"));
        assertEquals("5000", propiedades.getProperty("mail.smtp.writetimeout"));
    }

    @Test
    void variosDestinatariosSeparadosPorComaLleganTodos() throws Exception {
        SmtpDePrueba smtp = new SmtpDePrueba();

        smtp.enviar(new Email("ana@palco.test, juan@palco.test", "Asunto", "Cuerpo"));

        assertEquals(2, smtp.entregados.get(0).getRecipients(Message.RecipientType.TO).length);
    }
}
