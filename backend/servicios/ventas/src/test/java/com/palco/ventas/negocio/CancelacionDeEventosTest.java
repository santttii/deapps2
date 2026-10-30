package com.palco.ventas.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.palco.common.mensajeria.CompraAnuladaMensaje;
import com.palco.common.mensajeria.EventoCanceladoMensaje;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.mensajeria.PublicadorDeCancelaciones;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.negocio.externos.Cobros;

/**
 * Lo que hace Ventas con un {@code EVENTO_CANCELADO}: anula las compras
 * pagadas del evento, pide sus reembolsos y publica un {@code COMPRA_ANULADA}
 * por compra. Sin servidor, sin base y sin broker.
 *
 * Lo que se cuida acá: que una segunda entrega del mensaje no reembolse ni
 * avise de nuevo, y que un reembolso que no se pudo hacer no impida anular la
 * entrada.
 */
class CancelacionDeEventosTest {

    private static final String EVENTO = "ev-1";
    private static final Instant AHORA = Instant.parse("2026-10-30T12:00:00Z");

    private final VentaRepository ventas = mock(VentaRepository.class);
    private final EntradaRepository entradas = mock(EntradaRepository.class);
    private final Cobros cobros = mock(Cobros.class);
    private final AnulacionesPublicadas publicadas = new AnulacionesPublicadas();
    private final CancelacionDeEventos cancelacion = new CancelacionDeEventos();

    CancelacionDeEventosTest() {
        cancelacion.ventas = ventas;
        cancelacion.entradas = entradas;
        cancelacion.cobros = cobros;
        cancelacion.cancelaciones = publicadas;
        cancelacion.reloj = Clock.fixed(AHORA, ZoneOffset.UTC);
        when(ventas.anularPorCancelacionDelEvento(anyString())).thenReturn(true);
        when(entradas.anularDeLaVenta(anyString())).thenReturn(2);
        when(cobros.reembolsar(anyString(), anyString(), anyString())).thenReturn(Cobros.Devolucion.HECHA);
    }

    @Test
    void anulaCadaCompraPagadaPideSuReembolsoYPublicaSuAviso() {
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, "P-1"),
                venta("V-2", "user-b", 30_250, "P-2")));

        assertEquals(2, cancelacion.anularComprasDelEvento(mensaje("Se lesionó el artista")));

        verify(entradas).anularDeLaVenta("V-1");
        verify(entradas).anularDeLaVenta("V-2");
        verify(cobros).reembolsar("P-1", "V-1", CancelacionDeEventos.MOTIVO);
        verify(cobros).reembolsar("P-2", "V-2", CancelacionDeEventos.MOTIVO);

        assertEquals(2, publicadas.anuladas.size());
        CompraAnuladaMensaje primera = publicadas.anuladas.get(0);
        assertEquals("V-1", primera.ventaId());
        assertEquals("user-a", primera.compradorId());
        assertEquals(EVENTO, primera.eventoId());
        assertEquals("Show", primera.titulo());
        assertEquals("Se lesionó el artista", primera.motivo());
        assertEquals(60_500, primera.monto());
        assertTrue(primera.reembolsado());
        assertEquals(AHORA, primera.timestamp());
    }

    @Test
    void unaVentaQueYaEstabaAnuladaNoSeReembolsaNiSeAvisaDeNuevo() {
        // Es el caso de la segunda entrega del mensaje: el UPDATE condicional no cambia nada.
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, "P-1")));
        when(ventas.anularPorCancelacionDelEvento("V-1")).thenReturn(false);

        assertEquals(0, cancelacion.anularComprasDelEvento(mensaje(null)));

        verify(entradas, never()).anularDeLaVenta(anyString());
        verify(cobros, never()).reembolsar(anyString(), anyString(), anyString());
        assertTrue(publicadas.anuladas.isEmpty());
    }

    @Test
    void siPagosNoDevuelveElDineroLaEntradaSeAnulaIgualYElAvisoLoDice() {
        // Lo contrario sería peor: entradas que siguen valiendo para un evento que no existe.
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, "P-1")));
        when(cobros.reembolsar("P-1", "V-1", CancelacionDeEventos.MOTIVO))
                .thenReturn(Cobros.Devolucion.SIN_RESPUESTA);

        assertEquals(1, cancelacion.anularComprasDelEvento(mensaje(null)));

        verify(entradas).anularDeLaVenta("V-1");
        assertFalse(publicadas.anuladas.get(0).reembolsado());
    }

    @Test
    void unReembolsoRechazadoTampocoFrenaLaAnulacion() {
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, "P-1")));
        when(cobros.reembolsar("P-1", "V-1", CancelacionDeEventos.MOTIVO))
                .thenReturn(Cobros.Devolucion.RECHAZADA);

        assertEquals(1, cancelacion.anularComprasDelEvento(mensaje(null)));

        assertFalse(publicadas.anuladas.get(0).reembolsado());
    }

    @Test
    void unaVentaSinPagoRegistradoSeAnulaSinPedirReembolso() {
        // No debería pasar en una venta CONFIRMADA, pero si pasa no se llama a Pagos con un null.
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, null)));

        assertEquals(1, cancelacion.anularComprasDelEvento(mensaje(null)));

        verify(cobros, never()).reembolsar(anyString(), anyString(), anyString());
        assertFalse(publicadas.anuladas.get(0).reembolsado());
    }

    @Test
    void unEventoSinComprasPagadasNoPublicaNada() {
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of());

        assertEquals(0, cancelacion.anularComprasDelEvento(mensaje(null)));

        assertTrue(publicadas.anuladas.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------

    private static EventoCanceladoMensaje mensaje(String motivo) {
        return new EventoCanceladoMensaje(EVENTO, "Show", motivo, AHORA);
    }

    /** Una venta pagada: el estado y el pago se ponen por reflexión, que es lo que hacen los UPDATE. */
    private static Venta venta(String id, String compradorId, long monto, String pagoId) {
        LocalDateTime ahora = LocalDateTime.ofInstant(AHORA, ZoneOffset.UTC);
        Venta venta = new Venta(id, compradorId, EVENTO, "clave-" + id, ahora, ahora.plusMinutes(10));
        fijar(venta, "montoTotal", monto);
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

    /** Lo que se publicó en el tópico. Ventas solo publica COMPRA_ANULADA. */
    private static final class AnulacionesPublicadas implements PublicadorDeCancelaciones {
        private final List<CompraAnuladaMensaje> anuladas = new ArrayList<>();

        @Override
        public void publicarEventoCancelado(EventoCanceladoMensaje mensaje) {
            throw new AssertionError("Ventas no publica EVENTO_CANCELADO: eso lo hace Eventos.");
        }

        @Override
        public void publicarCompraAnulada(CompraAnuladaMensaje mensaje) {
            mensaje.validar();
            anuladas.add(mensaje);
        }
    }
}
