package com.palco.ventas.dominio.estado;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.palco.common.ErroresDeNegocio;
import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.Venta;

/** Patrón State de la venta: qué permite cada estado y qué responde 409. */
class EstadoDeVentaTest {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 13, 9, 0, 0);

    private static Venta ventaQueVence(LocalDateTime vence) {
        return new Venta("V-1", "user-titular", "ev-1", "clave-1", AHORA.minusMinutes(1), vence);
    }

    @ParameterizedTest
    @EnumSource(EstadoVenta.class)
    void cadaValorGuardadoTieneSuClaseDeEstado(EstadoVenta codigo) {
        assertEquals(codigo, EstadoDeVenta.de(codigo).codigo());
    }

    @Test
    void unaVentaNuevaEstaEnElEstadoEnCheckout() {
        assertInstanceOf(EnCheckout.class, EstadoDeVenta.de(ventaQueVence(AHORA.plusMinutes(10)).getEstado()));
    }

    @Test
    void enCheckoutConLaReservaVigenteSePuedePagarYCancelar() {
        Venta venta = ventaQueVence(AHORA.plusMinutes(10));
        EstadoDeVenta estado = EstadoDeVenta.de(EstadoVenta.EN_CHECKOUT);

        assertDoesNotThrow(() -> estado.pagar(venta, AHORA));
        assertTrue(estado.cancelar());
    }

    @Test
    void enCheckoutConLaReservaVencidaPagarEsUnaTransicionInvalida() {
        Venta venta = ventaQueVence(AHORA);

        TransicionInvalida error = assertThrows(TransicionInvalida.class,
                () -> EstadoDeVenta.de(EstadoVenta.EN_CHECKOUT).pagar(venta, AHORA));

        assertEquals("reserva-vencida", error.tipo());
        assertEquals(EstadoVenta.EN_CHECKOUT, error.getDesde());
    }

    @ParameterizedTest
    @EnumSource(value = EstadoVenta.class, names = { "CONFIRMADA", "EMITIDA" })
    void unaVentaPagaNoSeVuelveAPagarNiSeCancela(EstadoVenta codigo) {
        EstadoDeVenta estado = EstadoDeVenta.de(codigo);
        Venta venta = ventaQueVence(AHORA.plusMinutes(10));

        TransicionInvalida alPagar = assertThrows(TransicionInvalida.class, () -> estado.pagar(venta, AHORA));
        TransicionInvalida alCancelar = assertThrows(TransicionInvalida.class, estado::cancelar);

        assertEquals("venta-ya-paga", alPagar.tipo());
        assertEquals("venta-ya-paga", alCancelar.tipo());
        assertEquals("cancelar", alCancelar.getOperacion());
        assertEquals(codigo, alCancelar.getDesde());
    }

    @ParameterizedTest
    @EnumSource(value = EstadoVenta.class, names = { "CANCELADA", "VENCIDA" })
    void unaVentaCerradaNoSePagaYCancelarlaDeNuevoNoHaceNada(EstadoVenta codigo) {
        EstadoDeVenta estado = EstadoDeVenta.de(codigo);
        Venta venta = ventaQueVence(AHORA.plusMinutes(10));

        TransicionInvalida error = assertThrows(TransicionInvalida.class, () -> estado.pagar(venta, AHORA));

        assertEquals("reserva-vencida", error.tipo());
        assertFalse(estado.cancelar());
    }

    @Test
    void unaTransicionInvalidaEsUnConflictoQueLaApiRespondeCon409() {
        TransicionInvalida error = new TransicionInvalida(EstadoVenta.EMITIDA, "pagar",
                TransicionInvalida.VENTA_YA_PAGA, "La compra ya está paga.");

        // ManejadoresDeErrores.Conflicto convierte todo ErroresDeNegocio.Conflicto en 409 problem+json.
        assertInstanceOf(ErroresDeNegocio.Conflicto.class, error);
        assertEquals("venta-ya-paga", error.tipo());
    }
}
