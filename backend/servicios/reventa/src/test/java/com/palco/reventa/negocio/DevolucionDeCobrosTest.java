package com.palco.reventa.negocio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import jakarta.ejb.EJBException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.palco.common.ErroresDeNegocio;
import com.palco.reventa.datos.ReembolsoRepository;
import com.palco.reventa.datos.ReembolsoRepositoryJpa;
import com.palco.reventa.dominio.EstadoReembolso;
import com.palco.reventa.dominio.Reembolso;
import com.palco.reventa.negocio.externos.ClienteDePagos;
import com.palco.reventa.negocio.externos.ClienteDePagos.Devolucion;

/**
 * La devolución de los cobros de reventa sin servidor: el repositorio y el
 * cliente de Pagos son dobles y el reloj está fijo.
 */
class DevolucionDeCobrosTest {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 20, 15, 0);

    private DevolucionDeCobros devoluciones;
    private ReembolsoRepository reembolsos;
    private ClienteDePagos pagos;

    @BeforeEach
    void armar() {
        devoluciones = new DevolucionDeCobros();
        reembolsos = mock(ReembolsoRepository.class);
        pagos = mock(ClienteDePagos.class);
        devoluciones.reembolsos = reembolsos;
        devoluciones.pagos = pagos;
        devoluciones.reloj = Clock.fixed(AHORA.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    @Test
    void anotaElReembolsoPendienteConElMontoYElMotivo() {
        when(reembolsos.anotar(any())).thenReturn(true);

        devoluciones.anotar("R-1", "U-comprador", "P-1", 60_500, DevolucionDeCobros.motivo("T-1"));

        ArgumentCaptor<Reembolso> anotado = ArgumentCaptor.forClass(Reembolso.class);
        verify(reembolsos).anotar(anotado.capture());
        assertEquals("P-1", anotado.getValue().getPagoId());
        assertEquals("R-1", anotado.getValue().getPublicacionId());
        assertEquals("U-comprador", anotado.getValue().getCompradorId());
        assertEquals(60_500, anotado.getValue().getMonto());
        assertEquals(EstadoReembolso.PENDIENTE, anotado.getValue().getEstado());
        assertEquals(AHORA, anotado.getValue().getAnotado());
    }

    @Test
    void elMotivoEntraEnLoQueAceptaPagos() {
        String motivo = DevolucionDeCobros.motivo("T-" + "x".repeat(38));
        assertTrue(motivo.length() <= 200, motivo);
    }

    @Test
    void siYaEstabaAnotadoOOtroLoAnotoALaVezNoEsUnError() {
        when(reembolsos.anotar(any())).thenReturn(false);
        assertDoesNotThrow(() -> devoluciones.anotar("R-1", "U-c", "P-1", 1, "m"));

        when(reembolsos.anotar(any())).thenThrow(
                new ErroresDeNegocio.Conflicto(ReembolsoRepositoryJpa.REEMBOLSO_YA_ANOTADO, "ya"));
        assertDoesNotThrow(() -> devoluciones.anotar("R-1", "U-c", "P-1", 1, "m"));
    }

    @Test
    void unaFallaDeLaBaseAlAnotarSale() {
        // Quien llama no anula la publicación si el reembolso no quedó anotado.
        when(reembolsos.anotar(any())).thenThrow(new EJBException("la base no responde"));
        assertThrows(EJBException.class, () -> devoluciones.anotar("R-1", "U-c", "P-1", 1, "m"));
    }

    @Test
    void cadaRespuestaDePagosDejaElReembolsoComoCorresponde() {
        when(pagos.reembolsar("P-1", "R-1", "m")).thenReturn(Devolucion.HECHA);
        when(pagos.reembolsar("P-2", "R-2", "m")).thenReturn(Devolucion.RECHAZADA);
        when(pagos.reembolsar("P-3", "R-3", "m")).thenReturn(Devolucion.SIN_RESPUESTA);

        assertEquals(Devolucion.HECHA, devoluciones.intentar("P-1", "R-1", "m"));
        assertEquals(Devolucion.RECHAZADA, devoluciones.intentar("P-2", "R-2", "m"));
        assertEquals(Devolucion.SIN_RESPUESTA, devoluciones.intentar("P-3", "R-3", "m"));

        verify(reembolsos).resolver("P-1", EstadoReembolso.HECHO, AHORA);
        verify(reembolsos).resolver("P-2", EstadoReembolso.RECHAZADO, AHORA);
        verify(reembolsos, never()).resolver(org.mockito.ArgumentMatchers.eq("P-3"), any(), any());
    }

    @Test
    void siLaBaseFallaDespuesDeDevolverQuedaPendienteYNoLanza() {
        // El barrido le vuelve a preguntar a Pagos, que contesta HECHA sin devolver dos veces.
        when(pagos.reembolsar(anyString(), anyString(), anyString())).thenReturn(Devolucion.HECHA);
        when(reembolsos.resolver(any(), any(), any())).thenThrow(new EJBException("la base no responde"));

        assertEquals(Devolucion.SIN_RESPUESTA, devoluciones.intentar("P-1", "R-1", "m"));
    }

    @Test
    void elBarridoReintentaLosPendientesYUnoQueFallaNoFrenaALosDemas() {
        when(reembolsos.buscarPendientes()).thenReturn(List.of(
                new Reembolso("P-1", "R-1", "U-c", 1, "m1", AHORA), new Reembolso("P-2", "R-2", "U-c", 1, "m2", AHORA)));
        when(pagos.reembolsar("P-1", "R-1", "m1")).thenThrow(new IllegalStateException("algo inesperado"));
        when(pagos.reembolsar("P-2", "R-2", "m2")).thenReturn(Devolucion.HECHA);

        devoluciones.reintentarPendientes();

        verify(reembolsos).resolver("P-2", EstadoReembolso.HECHO, AHORA);
    }

    @Test
    void siLaBaseNoRespondeElBarridoSeCortaSinLanzar() {
        when(reembolsos.buscarPendientes()).thenThrow(new EJBException("la base no responde"));
        assertDoesNotThrow(devoluciones::reintentarPendientes);
    }
}
