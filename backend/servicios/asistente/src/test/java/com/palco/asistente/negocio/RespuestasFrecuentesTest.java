package com.palco.asistente.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Las respuestas del fallback (preguntas frecuentes), al día con las reglas de Palco. */
class RespuestasFrecuentesTest {

    @Test
    void elFallbackDeEntradasDeFacturaYDeCancelarEstaAlDia() {
        assertEquals("Podés comprar hasta 6 entradas por compra y 6 por evento; cada DNI, una sola entrada por evento.",
                RespuestasFrecuentes.buscar("¿Cuántas entradas puedo comprar?").orElseThrow());
        assertTrue(RespuestasFrecuentes.buscar("¿Dónde está la factura?").orElseThrow().contains("Ver factura"));
        assertTrue(RespuestasFrecuentes.buscar("¿Puedo cancelar mi compra?").orElseThrow().contains("antes de pagarla"));
    }
}
