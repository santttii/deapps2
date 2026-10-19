package com.palco.facturacion;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Entrada;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Titular;
import com.palco.facturacion.dominio.Factura;

/** Datos de ejemplo para los tests: el mismo mensaje que muestra el contrato de PAGO_CONFIRMADO. */
public final class Ejemplos {

    public static final String VENTA = "V-3f9a1c2b";
    public static final String COMPRADOR = "user-titular";

    private Ejemplos() {
    }

    /**
     * Confirmada el 17/10 a la 01:30 UTC, que en Argentina todavía es el 16/10
     * a las 22:30: la fecha de la factura tiene que ser la del 16.
     */
    public static PagoConfirmadoMensaje pagoConfirmado() {
        return pagoConfirmado(VENTA);
    }

    public static PagoConfirmadoMensaje pagoConfirmado(String ventaId) {
        return new PagoConfirmadoMensaje(ventaId, COMPRADOR, "ev-1",
                List.of(new Entrada("T-9c41d07e", "Platea B", new Titular("Ana Gómez", "30123456")),
                        new Entrada("T-51b7a2f0", "Platea B", new Titular("Luis Pérez", "31234567"))),
                60500, Instant.parse("2026-10-17T01:30:00Z"));
    }

    /** Una factura ya guardada de esa venta y ese comprador. */
    public static Factura factura(String ventaId, String compradorId) {
        return new Factura("F-0a1b2c3d", ventaId, compradorId, "ev-1", 6, 1, 7, "75123456789012",
                LocalDate.of(2026, 10, 26), 60500, LocalDate.of(2026, 10, 16),
                LocalDateTime.of(2026, 10, 17, 1, 30, 5));
    }
}
