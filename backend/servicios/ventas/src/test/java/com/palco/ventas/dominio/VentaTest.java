package com.palco.ventas.dominio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/** Lo que la venta y la entrada resuelven solas, sin base ni servidor. */
class VentaTest {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 19, 21, 0, 0);
    private static final LocalDateTime VENCE = AHORA.plusMinutes(10);

    private static Venta ventaNueva() {
        return new Venta("V-3f9a1c2b", "user-titular", "ev-1", "clave-1", AHORA, VENCE);
    }

    @Test
    void unaVentaNuevaNaceEnCheckoutSinFacturaYConLosMontosEnCero() {
        Venta venta = ventaNueva();

        assertEquals(EstadoVenta.EN_CHECKOUT, venta.getEstado());
        assertEquals(EstadoFactura.NO_APLICA, venta.getFacturaEstado());
        assertEquals(0, venta.getSubtotal());
        assertEquals(0, venta.getCargoServicio());
        assertEquals(0, venta.getMontoTotal());
        assertEquals(AHORA, venta.getCreada());
        assertEquals(VENCE, venta.getVenceReserva());
        assertNull(venta.getClavePago());
        assertNull(venta.getPagoId());
        assertNull(venta.getConfirmada());
        assertTrue(venta.getItems().isEmpty());
        assertTrue(venta.getEntradas().isEmpty());
        assertFalse(venta.estaPaga());
    }

    @Test
    void agregarUnItemRecalculaSubtotalCargoDeServicioYTotal() {
        Venta venta = ventaNueva();

        venta.agregarItem("sec-1-4", "Platea B", 27_500, 2);

        assertEquals(55_000, venta.getSubtotal());
        assertEquals(5_500, venta.getCargoServicio()); // 10% del subtotal
        assertEquals(60_500, venta.getMontoTotal());
    }

    @Test
    void elCargoDeServicioSeCalculaSobreElSubtotalDeTodosLosItems() {
        Venta venta = ventaNueva();

        venta.agregarItem("sec-1-4", "Platea B", 1_001, 1);
        venta.agregarItem("sec-1-1", "Popular", 1_002, 2);

        // 1001 + 2004 = 3005; el 10% es 300,5 y se redondea a 301. Calculado renglón por renglón daría 300.
        assertEquals(3_005, venta.getSubtotal());
        assertEquals(301, venta.getCargoServicio());
        assertEquals(3_306, venta.getMontoTotal());
    }

    @Test
    void cadaItemQuedaNumeradoEnElOrdenDelPedidoYConElIdDeLaVenta() {
        Venta venta = ventaNueva();

        venta.agregarItem("sec-1-4", "Platea B", 27_500, 2);
        venta.agregarItem("sec-1-1", "Popular", 9_000, 1);

        VentaItem primero = venta.getItems().get(0);
        VentaItem segundo = venta.getItems().get(1);
        assertEquals("V-3f9a1c2b-1", primero.getId());
        assertEquals(1, primero.getOrden());
        assertEquals(55_000, primero.subtotal());
        assertSame(venta, primero.getVenta());
        assertEquals("V-3f9a1c2b-2", segundo.getId());
        assertEquals(2, segundo.getOrden());
        assertEquals("sec-1-1", segundo.getSectorId());
        assertEquals("Popular", segundo.getSector());
    }

    @Test
    void laReservaValeHastaElInstanteAnteriorAlVencimiento() {
        Venta venta = ventaNueva();

        assertFalse(venta.reservaVencida(AHORA));
        assertFalse(venta.reservaVencida(VENCE.minusSeconds(1)));
        assertTrue(venta.reservaVencida(VENCE)); // en el instante exacto ya venció
        assertTrue(venta.reservaVencida(VENCE.plusSeconds(1)));
    }

    @Test
    void unaEntradaNuevaTomaVentaSectorYPrecioDelItemYNaceSinEmitir() {
        Venta venta = ventaNueva();
        venta.agregarItem("sec-1-4", "Platea B", 27_500, 2);

        Entrada entrada = new Entrada("T-9c41d07e", venta.getItems().get(0), "Ana Gómez", "30123456", 1);

        assertSame(venta, entrada.getVenta());
        assertEquals("sec-1-4", entrada.getSectorId());
        assertEquals("Platea B", entrada.getSector());
        assertEquals(27_500, entrada.getPrecioPagado()); // el precio del sector, sin el cargo de servicio
        assertEquals("Ana Gómez", entrada.getTitularNombre());
        assertEquals("30123456", entrada.getTitularDni());
        assertEquals(1, entrada.getOrden());
        assertEquals(EstadoEntrada.PENDIENTE_EMISION, entrada.getEstado());
        assertNull(entrada.getCodigoQr());
        assertNull(entrada.getEmitida());
    }
}
