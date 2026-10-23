package com.palco.ventas.negocio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;

import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.negocio.externos.ClienteDeEventos;
import com.palco.ventas.negocio.externos.ClienteDeEventos.Devolucion;

/**
 * La devolución de cupo de una venta cerrada (cancelada o vencida): cuándo se
 * saca la marca de devolución pendiente y qué hace el barrido con las que
 * quedaron. Sin servidor: el repositorio y el cliente de Eventos son dobles, y
 * {@link ComprasEnCurso} es el de verdad. Que repetir una devolución no reste
 * dos veces lo comprueban los tests de Eventos (la clave); acá, que se manda
 * siempre la misma.
 */
class DevolucionDeCuposTest {

    private final VentaRepository ventas = mock(VentaRepository.class);
    private final ClienteDeEventos eventos = mock(ClienteDeEventos.class);
    private final ComprasEnCurso compras = new ComprasEnCurso();
    private final DevolucionDeCupos barrido = new DevolucionDeCupos();

    DevolucionDeCuposTest() {
        compras.ventas = ventas;
        compras.eventos = eventos;
        barrido.ventas = ventas;
        barrido.compras = compras;
    }

    private static Venta cerrada(String id) {
        LocalDateTime abierta = LocalDateTime.of(2026, 10, 23, 12, 0, 0);
        Venta venta = new Venta(id, "user-titular", "ev-1", "clave-de-" + id, abierta, abierta.plusMinutes(10));
        venta.agregarItem("sec-1-4", "Platea B", 27_500, 2);
        venta.agregarItem("sec-1-5", "Palcos", 60_000, 1);
        return venta;
    }

    // --- ComprasEnCurso.devolverCupo ---

    @Test
    void siEventosAceptaTodosLosSectoresSeSacaLaMarcaDespuesDeDevolver() {
        when(eventos.liberarCupo(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(Devolucion.HECHA);

        compras.devolverCupo(cerrada("V-11111111"));

        InOrder orden = inOrder(eventos, ventas);
        orden.verify(eventos).liberarCupo("V-11111111", "ev-1", "sec-1-4", 2);
        orden.verify(eventos).liberarCupo("V-11111111", "ev-1", "sec-1-5", 1);
        orden.verify(ventas).marcarCupoDevuelto("V-11111111");
    }

    @Test
    void siUnSectorQuedaSinRespuestaLaMarcaSigueParaElBarrido() {
        when(eventos.liberarCupo("V-11111111", "ev-1", "sec-1-4", 2)).thenReturn(Devolucion.HECHA);
        when(eventos.liberarCupo("V-11111111", "ev-1", "sec-1-5", 1)).thenReturn(Devolucion.SIN_RESPUESTA);

        compras.devolverCupo(cerrada("V-11111111"));

        verify(ventas, never()).marcarCupoDevuelto(anyString());
    }

    @Test
    void unRechazoDeEventosNoSeReintentaYTambienSacaLaMarca() {
        // El rechazo queda en un SEVERE para revisarlo a mano: reintentar cada minuto daría lo mismo.
        when(eventos.liberarCupo("V-11111111", "ev-1", "sec-1-4", 2)).thenReturn(Devolucion.RECHAZADA);
        when(eventos.liberarCupo("V-11111111", "ev-1", "sec-1-5", 1)).thenReturn(Devolucion.HECHA);

        compras.devolverCupo(cerrada("V-11111111"));

        verify(ventas).marcarCupoDevuelto("V-11111111");
    }

    @Test
    void siNoSePuedeSacarLaMarcaNoLanza() {
        // El cupo ya está devuelto: el barrido lo reintenta y Eventos reconoce las claves.
        when(eventos.liberarCupo(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(Devolucion.HECHA);
        when(ventas.marcarCupoDevuelto("V-11111111")).thenThrow(new EJBException("la base no responde"));

        assertDoesNotThrow(() -> compras.devolverCupo(cerrada("V-11111111")));
    }

    // --- El barrido ---

    @Test
    void elBarridoReintentaCadaVentaPendienteAunqueUnaVuelvaAFallar() {
        when(ventas.buscarDevolucionesPendientes()).thenReturn(List.of(cerrada("V-11111111"), cerrada("V-22222222")));
        when(eventos.liberarCupo(eq("V-11111111"), anyString(), anyString(),
                anyInt())).thenReturn(Devolucion.SIN_RESPUESTA);
        when(eventos.liberarCupo(eq("V-22222222"), anyString(), anyString(),
                anyInt())).thenReturn(Devolucion.HECHA);

        barrido.reintentarDevoluciones();

        // La segunda se resolvió; la primera sigue pendiente para el minuto siguiente.
        verify(eventos).liberarCupo("V-22222222", "ev-1", "sec-1-4", 2);
        verify(eventos).liberarCupo("V-22222222", "ev-1", "sec-1-5", 1);
        verify(ventas).marcarCupoDevuelto("V-22222222");
        verify(ventas, never()).marcarCupoDevuelto("V-11111111");
    }

    @Test
    void siNoHayPendientesNoLlamaAEventos() {
        when(ventas.buscarDevolucionesPendientes()).thenReturn(List.of());

        barrido.reintentarDevoluciones();

        verifyNoInteractions(eventos);
    }

    @Test
    void siLaBaseNoRespondeLaCorridaSeCortaSinLanzar() {
        when(ventas.buscarDevolucionesPendientes()).thenThrow(new EJBException("la base no responde"));

        assertDoesNotThrow(barrido::reintentarDevoluciones);
        verifyNoInteractions(eventos);
    }

    @Test
    void elBarridoEsUnSingletonQueCorreCadaMinutoEnElSegundo30SinTransaccionPropia() throws Exception {
        assertNotNull(DevolucionDeCupos.class.getAnnotation(Singleton.class));
        assertArrayEquals(new String[] { "MigracionesVentas" },
                DevolucionDeCupos.class.getAnnotation(DependsOn.class).value());
        assertEquals(TransactionAttributeType.NOT_SUPPORTED,
                DevolucionDeCupos.class.getAnnotation(TransactionAttribute.class).value());

        Schedule agenda = DevolucionDeCupos.class.getDeclaredMethod("reintentarDevoluciones")
                .getAnnotation(Schedule.class);
        assertEquals("*", agenda.minute());
        assertEquals("30", agenda.second());
        assertFalse(agenda.persistent());
    }
}
