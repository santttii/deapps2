package com.palco.notificaciones.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.palco.common.Textos;
import com.palco.common.email.Correos;
import com.palco.common.email.Email;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Entrada;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Titular;
import com.palco.notificaciones.Ejemplos;
import com.palco.notificaciones.LogCapturado;
import com.palco.notificaciones.negocio.externos.ClienteDeUsuarios;

/** Qué dice el aviso al comprador y por dónde sale. No necesita servidor. */
class ServicioDeNotificacionesTest {

    @Test
    void elAvisoNombraLaVentaAlCompradorYACadaTitular() {
        String aviso = ServicioDeNotificaciones.textoDelAviso(Ejemplos.pagoConfirmado(), false);

        assertTrue(aviso.contains("V-3f9a1c2b"), aviso);
        assertTrue(aviso.contains("user-titular"), aviso);
        assertTrue(aviso.contains("ev-1"), aviso);
        assertTrue(aviso.contains("2 entradas: Platea B (Ana Gómez), Platea B (Luis Pérez)"), aviso);
        assertTrue(aviso.contains("$60500"), aviso);
    }

    @Test
    void elAvisoNoLlevaElDniDeNingunTitular() {
        String aviso = ServicioDeNotificaciones.textoDelAviso(Ejemplos.pagoConfirmado(), false);

        assertFalse(aviso.contains(Ejemplos.DNI_DE_ANA), aviso);
        assertFalse(aviso.contains(Ejemplos.DNI_DE_LUIS), aviso);
    }

    @Test
    void elAvisoSaleEnUnSoloRenglonAunqueElNombreDelTitularTraigaUnaLineaDeLogFalsa() {
        // Lo que logró una revisión contra el servidor: un renglón falso en server.log, metido con un
        // salto de línea dentro del nombre de un titular.
        String renglonFalso = "2026-10-13 14:00:00,000 INFO  [com.palco.ventas.mensajeria.EmisionDeEntradasMDB]"
                + " (falso) LINEA FALSA DE LA REVISION";
        PagoConfirmadoMensaje conSaltos = new PagoConfirmadoMensaje("V-3f9a1c2b", "user-titular", "ev-1",
                List.of(new Entrada("T-9c41d07e", "Campo\r\nsector falso",
                        new Titular("Rev Linea\n" + renglonFalso, Ejemplos.DNI_DE_ANA))),
                30250, Instant.parse("2026-10-16T21:30:00Z"));

        String aviso = ServicioDeNotificaciones.textoDelAviso(conSaltos, false);

        assertTrue(aviso.chars().noneMatch(Textos::esDeControl), aviso);
        // No se pierde lo que llegó: queda dentro del renglón del aviso, que empieza como siempre.
        assertTrue(aviso.startsWith("Aviso al comprador user-titular: tu compra V-3f9a1c2b"), aviso);
        assertTrue(aviso.contains("(Rev Linea " + renglonFalso + ")"), aviso);
    }

    @Test
    void conUnaSolaEntradaLoDiceEnSingular() {
        PagoConfirmadoMensaje deUna = new PagoConfirmadoMensaje("V-3f9a1c2b", "user-titular", "ev-1",
                List.of(new Entrada("T-9c41d07e", "Campo", new Titular("Ana Gómez", Ejemplos.DNI_DE_ANA))),
                30250, Instant.parse("2026-10-16T21:30:00Z"));

        String aviso = ServicioDeNotificaciones.textoDelAviso(deUna, false);

        assertTrue(aviso.contains("1 entrada: Campo (Ana Gómez)"), aviso);
    }

    @Test
    void elAvisoDeUnaReventaDiceQueFueEnLaReventaOficial() {
        String aviso = ServicioDeNotificaciones.textoDelAviso(Ejemplos.pagoConfirmado(), true);

        assertTrue(aviso.contains("tu compra en la reventa oficial V-3f9a1c2b quedó confirmada"), aviso);
    }

    // --- Por dónde sale el aviso ---

    @Test
    void conElEmailDelCompradorElAvisoSalePorCorreoYNoAlLog() {
        Armado armado = new Armado("ana@palco.test", "Ana");

        try (LogCapturado log = new LogCapturado(ServicioDeNotificaciones.class)) {
            armado.servicio.enviarConfirmacion(Ejemplos.pagoConfirmado());

            assertTrue(log.lineas().isEmpty(), log.lineas().toString());
        }
        assertEquals(1, armado.correos.enviados.size());
        Email email = armado.correos.enviados.get(0);
        assertEquals("ana@palco.test", email.para());
        assertEquals("Tu compra en Palco está confirmada", email.asunto());
        assertTrue(email.cuerpo().contains("Hola Ana"), email.cuerpo());
        assertTrue(email.cuerpo().contains("V-3f9a1c2b"), email.cuerpo());
        assertTrue(email.cuerpo().contains("Platea B (Ana Gómez)"), email.cuerpo());
        assertTrue(email.cuerpo().contains("$60500"), email.cuerpo());
    }

    @Test
    void siUsuariosNoDaElEmailElAvisoQuedaEnElLogYElMensajeSeDaPorAtendido() {
        // Un aviso que no sale no puede hacer que el mensaje se reintente y la compra se avise dos veces.
        Armado armado = new Armado(null, null);

        try (LogCapturado log = new LogCapturado(ServicioDeNotificaciones.class)) {
            armado.servicio.enviarConfirmacion(Ejemplos.pagoConfirmado());

            assertEquals(1, log.lineas().size(), log.lineas().toString());
            assertTrue(log.lineas().get(0).contains("V-3f9a1c2b"), log.lineas().get(0));
        }
        assertTrue(armado.correos.enviados.isEmpty());
    }

    @Test
    void elEmailTampocoLlevaElDniDeNingunTitular() {
        Armado armado = new Armado("ana@palco.test", "Ana");

        armado.servicio.enviarConfirmacion(Ejemplos.pagoConfirmado());

        String cuerpo = armado.correos.enviados.get(0).cuerpo();
        assertFalse(cuerpo.contains(Ejemplos.DNI_DE_ANA), cuerpo);
        assertFalse(cuerpo.contains(Ejemplos.DNI_DE_LUIS), cuerpo);
    }

    @Test
    void elEmailDeUnaReventaLoDiceEnElAsunto() {
        Armado armado = new Armado("ana@palco.test", "Ana");

        armado.servicio.enviarConfirmacionDeReventa(Ejemplos.pagoConfirmado());

        assertEquals("Tu compra en la reventa de Palco está confirmada",
                armado.correos.enviados.get(0).asunto());
        assertTrue(armado.correos.enviados.get(0).cuerpo().contains("en la reventa oficial"),
                armado.correos.enviados.get(0).cuerpo());
    }

    // ---------------------------------------------------------------------------------------------

    /** El servicio con sus dos dependencias reemplazadas: el correo y la consulta a Usuarios. */
    private static final class Armado {
        private final CorreosEnMemoria correos = new CorreosEnMemoria();
        private final ServicioDeNotificaciones servicio = new ServicioDeNotificaciones();

        /** Con {@code email} en null, Usuarios no sabe quién es el comprador. */
        Armado(String email, String nombre) {
            servicio.correos = correos;
            servicio.usuarios = new ClienteDeUsuarios() {
                @Override
                public Optional<Usuario> buscar(String usuarioId) {
                    return email == null ? Optional.empty()
                            : Optional.of(new Usuario(usuarioId, nombre, email));
                }
            };
        }
    }

    private static final class CorreosEnMemoria implements Correos {
        private final List<Email> enviados = new ArrayList<>();

        @Override
        public void enviar(Email email) {
            enviados.add(email);
        }
    }
}
