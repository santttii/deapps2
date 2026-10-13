package com.palco.notificaciones.negocio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.palco.common.Textos;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Entrada;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Titular;
import com.palco.notificaciones.Ejemplos;

/** Qué dice el aviso al comprador. No necesita servidor. */
class ServicioDeNotificacionesTest {

    @Test
    void elAvisoNombraLaVentaAlCompradorYACadaTitular() {
        String aviso = ServicioDeNotificaciones.textoDelAviso(Ejemplos.pagoConfirmado());

        assertTrue(aviso.contains("V-3f9a1c2b"), aviso);
        assertTrue(aviso.contains("user-titular"), aviso);
        assertTrue(aviso.contains("ev-1"), aviso);
        assertTrue(aviso.contains("2 entradas: Platea B (Ana Gómez), Platea B (Luis Pérez)"), aviso);
        assertTrue(aviso.contains("$60500"), aviso);
    }

    @Test
    void elAvisoNoLlevaElDniDeNingunTitular() {
        String aviso = ServicioDeNotificaciones.textoDelAviso(Ejemplos.pagoConfirmado());

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

        String aviso = ServicioDeNotificaciones.textoDelAviso(conSaltos);

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

        String aviso = ServicioDeNotificaciones.textoDelAviso(deUna);

        assertTrue(aviso.contains("1 entrada: Campo (Ana Gómez)"), aviso);
    }
}
