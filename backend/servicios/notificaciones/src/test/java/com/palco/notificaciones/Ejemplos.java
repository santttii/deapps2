package com.palco.notificaciones;

import java.time.Instant;
import java.util.List;

import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Entrada;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Titular;

/** Datos de ejemplo para los tests: el mismo mensaje que muestra el contrato de PAGO_CONFIRMADO. */
public final class Ejemplos {

    public static final String DNI_DE_ANA = "30123456";
    public static final String DNI_DE_LUIS = "31234567";

    private Ejemplos() {
    }

    public static PagoConfirmadoMensaje pagoConfirmado() {
        return new PagoConfirmadoMensaje("V-3f9a1c2b", "user-titular", "ev-1",
                List.of(new Entrada("T-9c41d07e", "Platea B", new Titular("Ana Gómez", DNI_DE_ANA)),
                        new Entrada("T-51b7a2f0", "Platea B", new Titular("Luis Pérez", DNI_DE_LUIS))),
                60500, Instant.parse("2026-10-16T21:30:00Z"));
    }
}
