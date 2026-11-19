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
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.negocio.externos.Cobros;
import com.palco.ventas.negocio.externos.Cobros.Devolucion;

/**
 * El barrido que vuelve a pedir los reembolsos que Pagos no contestó al
 * cancelarse un evento: cuándo saca la marca y cuándo la deja para la corrida
 * siguiente. Sin servidor: el repositorio y el cobro son dobles. Que repetir
 * un reembolso no devuelva dos veces lo comprueban los tests de Pagos; acá,
 * que se pide siempre el mismo pago de la misma venta.
 */
class ReintentoDeReembolsosTest {

    private final VentaRepository ventas = mock(VentaRepository.class);
    private final Cobros cobros = mock(Cobros.class);
    private final ReintentoDeReembolsos barrido = new ReintentoDeReembolsos();

    ReintentoDeReembolsosTest() {
        barrido.ventas = ventas;
        barrido.cobros = cobros;
    }

    /** Una venta anulada por la cancelación de su evento, con su pago. El pago se pone por reflexión, como en los UPDATE. */
    private static Venta anulada(String id, String pagoId) {
        LocalDateTime creada = LocalDateTime.of(2026, 10, 23, 12, 0, 0);
        Venta venta = new Venta(id, "user-a", "ev-1", "clave-de-" + id, creada, creada.plusMinutes(10));
        try {
            var campo = Venta.class.getDeclaredField("pagoId");
            campo.setAccessible(true);
            campo.set(venta, pagoId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return venta;
    }

    @Test
    void siPagosLoDevuelveSeSacaLaMarcaYQuedaEnElLogSinOtroAviso() {
        when(ventas.buscarReembolsosPendientes()).thenReturn(List.of(anulada("V-1", "P-1")));
        when(cobros.reembolsar("P-1", "V-1", AnulacionDeCompras.MOTIVO)).thenReturn(Devolucion.HECHA);

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
        when(cobros.reembolsar("P-1", "V-1", AnulacionDeCompras.MOTIVO)).thenReturn(Devolucion.RECHAZADA);

        try (LogCapturado log = new LogCapturado(ReintentoDeReembolsos.class)) {
            assertEquals(Devolucion.RECHAZADA, barrido.reintentar("V-1", "P-1"));

            assertTrue(log.lineas().stream().anyMatch(l -> l.contains("revisarlo a mano")),
                    String.valueOf(log.lineas()));
        }
        verify(ventas).marcarReembolsoResuelto("V-1");
    }

    @Test
    void siPagosSigueSinContestarLaMarcaQuedaYSeSigueConLaProxima() {
        when(ventas.buscarReembolsosPendientes()).thenReturn(List.of(anulada("V-1", "P-1"), anulada("V-2", "P-2")));
        when(cobros.reembolsar("P-1", "V-1", AnulacionDeCompras.MOTIVO)).thenReturn(Devolucion.SIN_RESPUESTA);
        when(cobros.reembolsar("P-2", "V-2", AnulacionDeCompras.MOTIVO)).thenReturn(Devolucion.HECHA);

        barrido.reintentarReembolsos();

        // La segunda se resolvió; la primera sigue pendiente para el minuto siguiente.
        verify(ventas, never()).marcarReembolsoResuelto("V-1");
        verify(ventas).marcarReembolsoResuelto("V-2");
    }

    @Test
    void unaFallaInesperadaConUnaVentaNoFrenaALasDemas() {
        when(ventas.buscarReembolsosPendientes()).thenReturn(List.of(anulada("V-1", "P-1"), anulada("V-2", "P-2")));
        when(cobros.reembolsar("P-1", "V-1", AnulacionDeCompras.MOTIVO)).thenThrow(new IllegalStateException("raro"));
        when(cobros.reembolsar("P-2", "V-2", AnulacionDeCompras.MOTIVO)).thenReturn(Devolucion.HECHA);

        assertDoesNotThrow(barrido::reintentarReembolsos);

        verify(ventas, never()).marcarReembolsoResuelto("V-1");
        verify(ventas).marcarReembolsoResuelto("V-2");
    }

    @Test
    void siNoSePuedeSacarLaMarcaNoLanza() {
        // Pagos ya contestó: la corrida siguiente lo pide otra vez y Pagos contesta lo mismo, sin devolver dos veces.
        when(cobros.reembolsar("P-1", "V-1", AnulacionDeCompras.MOTIVO)).thenReturn(Devolucion.HECHA);
        when(ventas.marcarReembolsoResuelto("V-1")).thenThrow(new EJBException("la base no responde"));

        assertEquals(Devolucion.HECHA, assertDoesNotThrow(() -> barrido.reintentar("V-1", "P-1")));
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
