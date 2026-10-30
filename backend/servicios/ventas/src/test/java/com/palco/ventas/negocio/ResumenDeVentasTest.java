package com.palco.ventas.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.palco.common.ReglasDeNegocio;
import com.palco.ventas.api.Dtos.DiaDeVentasDto;
import com.palco.ventas.api.Dtos.LiquidacionDto;
import com.palco.ventas.api.Dtos.ResumenDeVentasDto;
import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.Venta;

/**
 * Las ventas por día y la liquidación: cómo se agrupa y qué le corresponde al
 * organizador. Sin base ni llamadas a Eventos.
 *
 * La cuenta que importa: el comprador paga subtotal + cargo de servicio, Palco
 * se queda el cargo y al organizador se le liquida el subtotal.
 */
class ResumenDeVentasTest {

    private static final String EVENTO = "ev-1";

    @Test
    void agrupaPorDiaDeConfirmacionYConservaElOrden() {
        ResumenDeVentasDto resumen = ResumenDeVentas.resumir(EVENTO, List.of(
                venta("V-1", "2026-10-28T10:00:00", 50_000, 2),
                venta("V-2", "2026-10-28T20:00:00", 25_000, 1),
                venta("V-3", "2026-10-30T09:00:00", 75_000, 3)));

        assertEquals(2, resumen.porDia().size());
        DiaDeVentasDto primero = resumen.porDia().get(0);
        assertEquals("2026-10-28", primero.fecha());
        assertEquals(2, primero.ventas());
        assertEquals(3, primero.entradas());
        // 50.000 x 2 de la primera más 25.000 x 1 de la segunda.
        assertEquals(125_000, primero.subtotal());
        assertEquals("2026-10-30", resumen.porDia().get(1).fecha());
    }

    @Test
    void laLiquidacionEsElSubtotalYElCargoQuedaParaPalco() {
        // Dos entradas de 50.000: 100.000 de lista más el 10 % de cargo = 110.000 que paga el comprador.
        long subtotal = 100_000;
        long cargo = ReglasDeNegocio.cargoServicio(subtotal);
        ResumenDeVentasDto resumen = ResumenDeVentas.resumir(EVENTO, List.of(
                venta("V-1", "2026-10-28T10:00:00", 50_000, 2)));

        LiquidacionDto liquidacion = resumen.liquidacion();
        assertEquals(1, liquidacion.ventas());
        assertEquals(2, liquidacion.entradas());
        assertEquals(subtotal + cargo, liquidacion.recaudadoDeLosCompradores());
        assertEquals(cargo, liquidacion.cargoDePalco());
        assertEquals(subtotal, liquidacion.aLiquidarAlOrganizador());
    }

    @Test
    void elTotalDeCadaDiaEsElSubtotalMasElCargo() {
        ResumenDeVentasDto resumen = ResumenDeVentas.resumir(EVENTO, List.of(
                venta("V-1", "2026-10-28T10:00:00", 50_000, 2)));

        DiaDeVentasDto dia = resumen.porDia().get(0);
        assertEquals(dia.subtotal() + dia.cargoServicio(), dia.total());
    }

    @Test
    void laSumaDeLosDiasEsIgualAlTotalDeLaLiquidacion() {
        ResumenDeVentasDto resumen = ResumenDeVentas.resumir(EVENTO, List.of(
                venta("V-1", "2026-10-28T10:00:00", 50_000, 2),
                venta("V-2", "2026-10-29T20:00:00", 25_000, 1),
                venta("V-3", "2026-10-30T09:00:00", 75_000, 3)));

        long subtotales = resumen.porDia().stream().mapToLong(DiaDeVentasDto::subtotal).sum();
        long entradas = resumen.porDia().stream().mapToLong(DiaDeVentasDto::entradas).sum();
        assertEquals(resumen.liquidacion().aLiquidarAlOrganizador(), subtotales);
        assertEquals(resumen.liquidacion().entradas(), entradas);
    }

    @Test
    void unEventoSinVentasPagadasDaUnResumenVacioYEnCero() {
        ResumenDeVentasDto resumen = ResumenDeVentas.resumir(EVENTO, List.of());

        assertEquals(EVENTO, resumen.eventoId());
        assertTrue(resumen.porDia().isEmpty());
        assertEquals(0, resumen.liquidacion().ventas());
        assertEquals(0, resumen.liquidacion().aLiquidarAlOrganizador());
    }

    @Test
    void unaVentaSinFechaDeConfirmacionSeCuentaPorLaDeCreacionYNoSePierde() {
        Venta sinConfirmar = venta("V-1", null, 50_000, 2);

        ResumenDeVentasDto resumen = ResumenDeVentas.resumir(EVENTO, List.of(sinConfirmar));

        assertEquals(1, resumen.porDia().size());
        assertEquals(1, resumen.liquidacion().ventas());
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Una venta pagada con un renglón. El cargo de servicio es el 10 % del
     * subtotal, que es lo que calcula la compra.
     *
     * @param confirmada fecha ISO, o null para una venta sin confirmar
     */
    private static Venta venta(String id, String confirmada, long precioUnitario, int cantidad) {
        LocalDateTime creada = LocalDateTime.parse("2026-10-27T08:00:00");
        Venta venta = new Venta(id, "user-a", EVENTO, "clave-" + id, creada, creada.plusMinutes(10));
        venta.agregarItem("sec-1", "Campo", precioUnitario, cantidad);
        fijar(venta, "estado", EstadoVenta.CONFIRMADA);
        if (confirmada != null) {
            fijar(venta, "confirmada", LocalDateTime.parse(confirmada));
        }
        return venta;
    }

    private static void fijar(Venta venta, String campo, Object valor) {
        try {
            var declarado = Venta.class.getDeclaredField(campo);
            declarado.setAccessible(true);
            declarado.set(venta, valor);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
