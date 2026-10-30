package com.palco.common.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
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
    void variosDestinatariosSeparadosPorComaLleganTodos() throws Exception {
        SmtpDePrueba smtp = new SmtpDePrueba();

        smtp.enviar(new Email("ana@palco.test, juan@palco.test", "Asunto", "Cuerpo"));

        assertEquals(2, smtp.entregados.get(0).getRecipients(Message.RecipientType.TO).length);
    }
}
