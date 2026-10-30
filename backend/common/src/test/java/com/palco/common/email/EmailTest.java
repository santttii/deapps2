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
}
