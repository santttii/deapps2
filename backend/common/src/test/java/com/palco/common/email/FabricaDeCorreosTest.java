package com.palco.common.email;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class FabricaDeCorreosTest {

    /** Dos dobles distinguibles: lo único que importa es cuál de los dos elige la factory. */
    private final Correos smtp = new Anotador();
    private final Correos alLog = new Anotador();

    @Test
    void sinConfiguracionYSinServidorElCorreoQuedaEnElLog() {
        // Es el caso de la máquina de desarrollo: no habría a dónde mandarlo.
        assertSame(alLog, FabricaDeCorreos.elegir(null, false, smtp, alLog));
    }

    @Test
    void sinConfiguracionPeroConServidorSeMandaPorSmtp() {
        // Con PALCO_SMTP_HOST definido alcanza: no hace falta escribir PALCO_CORREO en el hosting.
        assertSame(smtp, FabricaDeCorreos.elegir(null, true, smtp, alLog));
    }

    @Test
    void elegirSmtpSinServidorSeRespetaYNoCaeAlLogEnSilencio() {
        // Quien lo pidió a mano se entera por los WARNING de cada envío, no por emails que nadie recibe.
        assertSame(smtp, FabricaDeCorreos.elegir("smtp", false, smtp, alLog));
    }

    @Test
    void elegirLogSeRespetaAunqueHayaServidorConfigurado() {
        assertSame(alLog, FabricaDeCorreos.elegir("log", true, smtp, alLog));
    }

    @Test
    void unValorDesconocidoNoCaeAlLogPorLasDudasSinoQueDecidePorElServidor() {
        assertSame(smtp, FabricaDeCorreos.elegir("mailtrap", true, smtp, alLog));
        assertSame(alLog, FabricaDeCorreos.elegir("mailtrap", false, smtp, alLog));
    }

    @Test
    void elValorNoDistingueMayusculasNiEspacios() {
        assertSame(alLog, FabricaDeCorreos.elegir("  LOG  ", true, smtp, alLog));
        assertSame(smtp, FabricaDeCorreos.elegir(" Smtp ", false, smtp, alLog));
    }

    private static final class Anotador implements Correos {
        private final List<Email> enviados = new ArrayList<>();

        @Override
        public void enviar(Email email) {
            enviados.add(email);
        }
    }
}
