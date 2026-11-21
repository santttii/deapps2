package com.palco.common.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EmailTest {

    @Test
    void elAsuntoNoPuedeCortarElRenglonYMeterCabecerasPropias() {
        // Una cabecera SMTP termina en el primer salto: con esto el asunto agregaría un Bcc.
        Email email = new Email("ana@palco.test", "Tu compra\r\nBcc: espia@otro.test", "Hola");

        assertEquals("Tu compra  Bcc: espia@otro.test", email.asunto());
    }

    @Test
    void elDestinatarioSeLimpiaYElCuerpoSeRespetaConSusSaltos() {
        Email email = new Email("  ana@palco.test ", "Asunto", "Primera\nSegunda");

        assertEquals("ana@palco.test", email.para());
        assertEquals("Primera\nSegunda", email.cuerpo());
    }

    @Test
    void sinDestinatarioNoHayEmail() {
        assertThrows(IllegalArgumentException.class, () -> new Email(null, "a", "b"));
        assertThrows(IllegalArgumentException.class, () -> new Email("  ", "a", "b"));
    }

    @Test
    void elAsuntoYElCuerpoVaciosSonTextoVacioYNoNull() {
        Email email = new Email("ana@palco.test", null, null);

        assertEquals("", email.asunto());
        assertEquals("", email.cuerpo());
    }

    @Test
    void elDestinatarioEnmascaradoDejaLaPrimeraLetraYElDominio() {
        assertEquals("a***@palco.test", new Email("ana.perez+compras@palco.test", "a", "b").paraEnmascarado());
        assertEquals("a***@palco.test, j***@palco.test",
                new Email("ana@palco.test, juan@palco.test", "a", "b").paraEnmascarado());
        assertEquals("p***@palco.test", new Email("postman-1a2b3c@palco.test", "a", "b").paraEnmascarado());
    }

    @Test
    void enUnTextoSeEnmascaraCadaDireccionYElRestoQuedaIgual() {
        // Así viene la respuesta de un servidor SMTP que rechaza al destinatario.
        assertEquals("550 5.1.1 <a***@palco.test>: Recipient address rejected; copia a j***@otro.test",
                Email.enmascarar("550 5.1.1 <ana@palco.test>: Recipient address rejected; copia a juan@otro.test"));
        assertEquals("Couldn't connect to host, port: smtp.palco.test, 587",
                Email.enmascarar("Couldn't connect to host, port: smtp.palco.test, 587"));
        assertEquals("", Email.enmascarar(null));
    }
}
