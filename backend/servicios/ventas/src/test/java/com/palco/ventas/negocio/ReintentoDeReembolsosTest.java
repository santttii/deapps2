package com.palco.ventas.negocio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.ejb.DependsOn;
import jakarta.ejb.EJBException;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;

import org.junit.jupiter.api.Test;

import com.palco.ventas.LogCapturado;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.negocio.externos.Cobros;
import com.palco.ventas.negocio.externos.Cobros.Devolucion;

/**
 * El barrido que vuelve a pedir los reembolsos que Pagos no contestó al
 * cancelarse un evento: cuándo saca la marca y cuándo la deja para la corrida
 * siguiente. Sin servidor: el repositorio y el cobro son dobles. Que repetir
 * un reembolso no devuelva dos veces lo comprueban los tests de Pagos; acá,
 * que se pide siempre el mismo pago, de la misma venta ante Pagos y por el
 * mismo monto que pidió la anulación.
 */
class ReintentoDeReembolsosTest {

    private final VentaRepository ventas = mock(VentaRepository.class);
    private final EntradaRepository entradas = mock(EntradaRepository.class);
    private final Cobros cobros = mock(Cobros.class);
    private final ReintentoDeReembolsos barrido = new ReintentoDeReembolsos();

    ReintentoDeReembolsosTest() {
        barrido.ventas = ventas;
        barrido.entradas = entradas;
        barrido.cobros = cobros;
    }

    /**
     * Una venta anulada por la cancelación de su evento, de 60.500 (dos entradas de 27.500 más el 10 %), con
     * su pago. El monto y el pago se ponen por reflexión, como en los UPDATE.
     */
    private static Venta anulada(String id, String pagoId) {
        return conClave(id, pagoId, "clave-de-" + id);
    }

    private static Venta conClave(String id, String pagoId, String clave) {
        LocalDateTime creada = LocalDateTime.of(2026, 10, 23, 12, 0, 0);
        Venta venta = new Venta(id, "user-a", "ev-1", clave, creada, creada.plusMinutes(10));
        fijar(venta, "montoTotal", 60_500L);
        fijar(venta, "pagoId", pagoId);
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

    @Test
    void siPagosLoDevuelveSeSacaLaMarcaYQuedaEnElLogSinOtroAviso() {
        when(ventas.buscarReembolsosPendientes()).thenReturn(List.of(anulada("V-1", "P-1")));
        when(cobros.reembolsar("P-1", "V-1", 60_500, AnulacionDeCompras.MOTIVO)).thenReturn(Devolucion.HECHA);

        try (LogCapturado log = new LogCapturado(ReintentoDeReembolsos.class)) {
            barrido.reintentarReembolsos();

            // El barrido no tiene publicador: el comprador ya recibió el aviso de devolución en curso.
            assertTrue(log.lineas().stream().anyMatch(l -> l.contains("devolvió el pago P-1") && l.contains("V-1")),
                    String.valueOf(log.lineas()));
        }
        verify(ventas).marcarReembolsoResuelto("V-1");
    }

    @Test
    void unRechazoDePagosNoSeReintentaYTambienSacaLaMarca() {
        // Reintentar cada minuto daría lo mismo: queda en un SEVERE para revisarlo a mano.
        when(cobros.reembolsar("P-1", "V-1", 60_500, AnulacionDeCompras.MOTIVO)).thenReturn(Devolucion.RECHAZADA);

        try (LogCapturado log = new LogCapturado(ReintentoDeReembolsos.class)) {
            assertEquals(Devolucion.RECHAZADA, barrido.reintentar(anulada("V-1", "P-1")));

            assertTrue(log.lineas().stream().anyMatch(l -> l.contains("revisarlo a mano")),
                    String.valueOf(log.lineas()));
        }
        verify(ventas).marcarReembolsoResuelto("V-1");
    }

    @Test
    void siPagosSigueSinContestarLaMarcaQuedaYSeSigueConLaProxima() {
        when(ventas.buscarReembolsosPendientes()).thenReturn(List.of(anulada("V-1", "P-1"), anulada("V-2", "P-2")));
        when(cobros.reembolsar("P-1", "V-1", 60_500, AnulacionDeCompras.MOTIVO)).thenReturn(Devolucion.SIN_RESPUESTA);
        when(cobros.reembolsar("P-2", "V-2", 60_500, AnulacionDeCompras.MOTIVO)).thenReturn(Devolucion.HECHA);

        barrido.reintentarReembolsos();

        // La segunda se resolvió; la primera sigue pendiente para el minuto siguiente.
        verify(ventas, never()).marcarReembolsoResuelto("V-1");
        verify(ventas).marcarReembolsoResuelto("V-2");
    }

    @Test
    void unaFallaInesperadaConUnaVentaNoFrenaALasDemas() {
        when(ventas.buscarReembolsosPendientes()).thenReturn(List.of(anulada("V-1", "P-1"), anulada("V-2", "P-2")));
        when(cobros.reembolsar("P-1", "V-1", 60_500, AnulacionDeCompras.MOTIVO))
                .thenThrow(new IllegalStateException("raro"));
        when(cobros.reembolsar("P-2", "V-2", 60_500, AnulacionDeCompras.MOTIVO)).thenReturn(Devolucion.HECHA);

        assertDoesNotThrow(barrido::reintentarReembolsos);

        verify(ventas, never()).marcarReembolsoResuelto("V-1");
        verify(ventas).marcarReembolsoResuelto("V-2");
    }

    @Test
    void siNoSePuedeSacarLaMarcaNoLanza() {
        // Pagos ya contestó: la corrida siguiente lo pide otra vez y Pagos contesta lo mismo, sin devolver dos veces.
        when(cobros.reembolsar("P-1", "V-1", 60_500, AnulacionDeCompras.MOTIVO)).thenReturn(Devolucion.HECHA);
        when(ventas.marcarReembolsoResuelto("V-1")).thenThrow(new EJBException("la base no responde"));

        assertEquals(Devolucion.HECHA, assertDoesNotThrow(() -> barrido.reintentar(anulada("V-1", "P-1"))));
    }

    // --- El mismo pedido que hizo la anulación ---

    @Test
    void elDeUnaCompraEnLaReventaSeVuelveAPedirConLaPublicacion() {
        // Pagos guardó ese cobro con el id de la publicación: con el de la venta contestaría "es de otra venta".
        Venta enLaReventa = conClave("V-2", "P-2", ReventaDeEntradas.PREFIJO_CLAVE + "R-1a2b3c4d");
        when(ventas.buscarReembolsosPendientes()).thenReturn(List.of(enLaReventa));
        when(cobros.reembolsar("P-2", "R-1a2b3c4d", 60_500, AnulacionDeCompras.MOTIVO)).thenReturn(Devolucion.HECHA);

        barrido.reintentarReembolsos();

        verify(cobros).reembolsar("P-2", "R-1a2b3c4d", 60_500, AnulacionDeCompras.MOTIVO);
        verify(ventas).marcarReembolsoResuelto("V-2");
    }

    @Test
    void aQuienRevendioSeLeVuelveAPedirSoloLoQueTodaviaTenia() {
        when(entradas.precioDeLasRevendidas("V-1")).thenReturn(27_500L);
        when(cobros.reembolsar("P-1", "V-1", 30_250, AnulacionDeCompras.MOTIVO)).thenReturn(Devolucion.HECHA);

        assertEquals(Devolucion.HECHA, barrido.reintentar(anulada("V-1", "P-1")));

        verify(cobros, never()).reembolsar("P-1", "V-1", 60_500, AnulacionDeCompras.MOTIVO);
    }

    @Test
    void siNoHayPendientesNoLlamaAPagos() {
        when(ventas.buscarReembolsosPendientes()).thenReturn(List.of());

        barrido.reintentarReembolsos();

        verifyNoInteractions(cobros);
    }

    @Test
    void siLaBaseNoRespondeLaCorridaSeCortaSinLanzar() {
        when(ventas.buscarReembolsosPendientes()).thenThrow(new EJBException("la base no responde"));

        assertDoesNotThrow(barrido::reintentarReembolsos);
        verifyNoInteractions(cobros);
        verify(ventas, never()).marcarReembolsoResuelto(anyString());
    }

    @Test
    void elBarridoEsUnSingletonQueCorreCadaMinutoEnElSegundo45SinTransaccionPropia() throws Exception {
        assertNotNull(ReintentoDeReembolsos.class.getAnnotation(Singleton.class));
        assertArrayEquals(new String[] { "MigracionesVentas" },
                ReintentoDeReembolsos.class.getAnnotation(DependsOn.class).value());
        assertEquals(TransactionAttributeType.NOT_SUPPORTED,
                ReintentoDeReembolsos.class.getAnnotation(TransactionAttribute.class).value());

        Schedule agenda = ReintentoDeReembolsos.class.getDeclaredMethod("reintentarReembolsos")
                .getAnnotation(Schedule.class);
        assertEquals("*", agenda.minute());
        // Los otros barridos de Ventas corren en el 0 (reservas) y en el 30 (cupos y conciliación).
        assertEquals("45", agenda.second());
        assertFalse(agenda.persistent());
    }
}
