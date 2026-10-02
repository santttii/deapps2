package com.palco.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReglasDeNegocioTest {

    @Test
    void topeReventaEsLoPagadoMasDiezPorCientoRedondeadoHaciaAbajo() {
        assertEquals(19_800, ReglasDeNegocio.topeReventa(18_000));
        assertEquals(1_100, ReglasDeNegocio.topeReventa(1_000));
        assertEquals(1_101, ReglasDeNegocio.topeReventa(1_001)); // 1101.1 → 1101
    }

    @Test
    void minimoReventaEsLaMitadRedondeadaHaciaArriba() {
        assertEquals(9_000, ReglasDeNegocio.minimoReventa(18_000));
        assertEquals(501, ReglasDeNegocio.minimoReventa(1_001)); // 500.5 → 501
    }

    @Test
    void cargoServicioEsElDiezPorCientoDelSubtotal() {
        assertEquals(3_600, ReglasDeNegocio.cargoServicio(36_000));
        assertEquals(100, ReglasDeNegocio.cargoServicio(1_004)); // 100.4 → 100
        assertEquals(101, ReglasDeNegocio.cargoServicio(1_005)); // 100.5 → 101
    }
}
