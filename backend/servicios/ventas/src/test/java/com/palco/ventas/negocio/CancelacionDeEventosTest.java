package com.palco.ventas.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jakarta.ejb.EJBException;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.palco.common.ReglasDeNegocio;
import com.palco.common.mensajeria.CompraAnuladaMensaje;
import com.palco.common.mensajeria.EventoCanceladoMensaje;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.mensajeria.PublicadorDeCancelaciones;
import com.palco.ventas.LogCapturado;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.negocio.externos.Cobros;

/**
 * Lo que hace Ventas con un {@code EVENTO_CANCELADO}: anula las compras
 * pagadas del evento, pide sus reembolsos y publica un {@code COMPRA_ANULADA}
 * por compra. Sin servidor, sin base y sin broker: {@link CancelacionDeEventos}
 * y {@link AnulacionDeCompras} son los de verdad, unidos a mano como los une
 * el contenedor.
 *
 * Lo que se cuida acá: que una segunda entrega del mensaje no reembolse ni
 * avise de nuevo, que un reembolso que no se pudo hacer no impida anular la
 * entrada (y quede marcado para el barrido si Pagos no contestó), que cada
 * compra se anule por otro bean, que es lo que hace que el contenedor le abra
 * su propia transacción, y que una entrada que pasó por la reventa se
 * devuelva una sola vez: a quien la compró, con el id de la publicación que
 * tiene Pagos, y no a quien la vendió.
 */
class CancelacionDeEventosTest {

    private static final String EVENTO = "ev-1";
    private static final Instant AHORA = Instant.parse("2026-10-30T12:00:00Z");

    private final VentaRepository ventas = mock(VentaRepository.class);
    private final EntradaRepository entradas = mock(EntradaRepository.class);
    private final Cobros cobros = mock(Cobros.class);
    private final AnulacionesPublicadas publicadas = new AnulacionesPublicadas();
    private final AnulacionDeCompras anulacion = new AnulacionDeCompras();
    private final CancelacionDeEventos cancelacion = new CancelacionDeEventos();

    CancelacionDeEventosTest() {
        anulacion.ventas = ventas;
        anulacion.entradas = entradas;
        anulacion.cobros = cobros;
        anulacion.cancelaciones = publicadas;
        anulacion.reloj = Clock.fixed(AHORA, ZoneOffset.UTC);
        cancelacion.ventas = ventas;
        cancelacion.anulacion = anulacion;
        when(ventas.anularPorCancelacionDelEvento(anyString(), anyBoolean())).thenReturn(true);
        when(entradas.anularDeLaVenta(anyString())).thenReturn(2);
        when(cobros.reembolsar(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Cobros.Devolucion.HECHA);
    }

    @Test
    void anulaCadaCompraPagadaPideSuReembolsoYPublicaSuAviso() {
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, "P-1"),
                venta("V-2", "user-b", 30_250, "P-2")));

        assertEquals(2, cancelacion.anularComprasDelEvento(mensaje("Se lesionó el artista")));

        verify(entradas).anularDeLaVenta("V-1");
        verify(entradas).anularDeLaVenta("V-2");
        verify(cobros).reembolsar("P-1", "V-1", 60_500, AnulacionDeCompras.MOTIVO);
        verify(cobros).reembolsar("P-2", "V-2", 30_250, AnulacionDeCompras.MOTIVO);

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
        when(ventas.anularPorCancelacionDelEvento("V-1", true)).thenReturn(false);

        assertEquals(0, cancelacion.anularComprasDelEvento(mensaje(null)));

        verify(entradas, never()).anularDeLaVenta(anyString());
        verify(cobros, never()).reembolsar(anyString(), anyString(), anyLong(), anyString());
        assertTrue(publicadas.anuladas.isEmpty());
    }

    @Test
    void siPagosNoDevuelveElDineroLaEntradaSeAnulaIgualYElAvisoLoDice() {
        // Lo contrario sería peor: entradas que siguen valiendo para un evento que no existe.
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, "P-1")));
        when(cobros.reembolsar("P-1", "V-1", 60_500, AnulacionDeCompras.MOTIVO))
                .thenReturn(Cobros.Devolucion.SIN_RESPUESTA);

        assertEquals(1, cancelacion.anularComprasDelEvento(mensaje(null)));

        verify(entradas).anularDeLaVenta("V-1");
        assertFalse(publicadas.anuladas.get(0).reembolsado());
    }

    @Test
    void unReembolsoRechazadoTampocoFrenaLaAnulacion() {
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, "P-1")));
        when(cobros.reembolsar("P-1", "V-1", 60_500, AnulacionDeCompras.MOTIVO))
                .thenReturn(Cobros.Devolucion.RECHAZADA);

        assertEquals(1, cancelacion.anularComprasDelEvento(mensaje(null)));

        assertFalse(publicadas.anuladas.get(0).reembolsado());
    }

    @Test
    void unaVentaSinPagoRegistradoSeAnulaSinPedirReembolso() {
        // No debería pasar en una venta CONFIRMADA, pero si pasa no se llama a Pagos con un null.
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, null)));

        assertEquals(1, cancelacion.anularComprasDelEvento(mensaje(null)));

        // verifyNoInteractions y no never().reembolsar(anyString(), ...): anyString() no reconoce un null, y un
        // reembolso pedido con el pago null es justo lo que tiene que fallar acá.
        verifyNoInteractions(cobros);
        assertFalse(publicadas.anuladas.get(0).reembolsado());
    }

    @Test
    void unaVentaSinPagoQuedaEnUnSevereQueDiceQueNoTienePago() {
        // Para revisarla a mano hay que saber por qué no se devolvió: no es lo mismo que un rechazo de Pagos.
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, null)));

        try (LogCapturado log = new LogCapturado(AnulacionDeCompras.class)) {
            cancelacion.anularComprasDelEvento(mensaje(null));

            assertTrue(log.lineas().stream().anyMatch(l -> l.contains("V-1") && l.contains("no tiene un pago registrado")
                    && l.contains("revisarlo a mano")), String.valueOf(log.lineas()));
        }
    }

    @Test
    void unEventoSinComprasPagadasNoPublicaNada() {
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of());

        assertEquals(0, cancelacion.anularComprasDelEvento(mensaje(null)));

        assertTrue(publicadas.anuladas.isEmpty());
    }

    // --- El reembolso que Pagos no contestó queda marcado para ReintentoDeReembolsos ---

    @Test
    void siPagosNoContestaElReembolsoLaVentaQuedaMarcadaEnLaMismaAnulacion() {
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, "P-1")));
        when(cobros.reembolsar("P-1", "V-1", 60_500, AnulacionDeCompras.MOTIVO))
                .thenReturn(Cobros.Devolucion.SIN_RESPUESTA);

        assertEquals(1, cancelacion.anularComprasDelEvento(mensaje(null)));

        // La marca se pone después de anular y de pedir el reembolso, dentro de la misma anulación.
        InOrder orden = inOrder(ventas, cobros);
        orden.verify(ventas).anularPorCancelacionDelEvento("V-1", true);
        orden.verify(cobros).reembolsar("P-1", "V-1", 60_500, AnulacionDeCompras.MOTIVO);
        orden.verify(ventas).marcarReembolsoPendiente("V-1");
        // El aviso sale igual, diciendo que el dinero todavía no volvió.
        assertFalse(publicadas.anuladas.get(0).reembolsado());
    }

    @Test
    void unReembolsoHechoUnoRechazadoYUnaVentaSinPagoNoQuedanMarcados() {
        // Hecho no hay nada que reintentar; rechazado, reintentar daría lo mismo; sin pago, no hay qué devolver.
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, "P-1"),
                venta("V-2", "user-b", 30_250, "P-2"), venta("V-3", "user-c", 30_250, null)));
        when(cobros.reembolsar("P-2", "V-2", 30_250, AnulacionDeCompras.MOTIVO))
                .thenReturn(Cobros.Devolucion.RECHAZADA);

        assertEquals(3, cancelacion.anularComprasDelEvento(mensaje(null)));

        verify(ventas, never()).marcarReembolsoPendiente(anyString());
    }

    @Test
    void unRechazoDePagosQuedaEnUnSevereParaRevisarloAMano() {
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, "P-1")));
        when(cobros.reembolsar("P-1", "V-1", 60_500, AnulacionDeCompras.MOTIVO))
                .thenReturn(Cobros.Devolucion.RECHAZADA);

        try (LogCapturado log = new LogCapturado(AnulacionDeCompras.class)) {
            cancelacion.anularComprasDelEvento(mensaje(null));

            assertTrue(log.lineas().stream().anyMatch(l -> l.contains("V-1") && l.contains("revisarlo a mano")),
                    String.valueOf(log.lineas()));
        }
    }

    // --- Las compras que pasaron por la reventa ---

    @Test
    void laCompraEnLaReventaPideElReembolsoConLaPublicacionPorqueAsiLoGuardoPagos() {
        // Reventa cobró antes de que existiera la venta: en Pagos el cobro es de la publicación R-1a2b3c4d.
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(enLaReventa("V-2", "user-b", 30_250, "P-2",
                "R-1a2b3c4d")));

        assertEquals(1, cancelacion.anularComprasDelEvento(mensaje(null)));

        verify(cobros).reembolsar("P-2", "R-1a2b3c4d", 30_250, AnulacionDeCompras.MOTIVO);
        verify(cobros, never()).reembolsar(anyString(), eq("V-2"), anyLong(), anyString());
        CompraAnuladaMensaje aviso = publicadas.anuladas.get(0);
        assertEquals("V-2", aviso.ventaId());
        assertEquals("user-b", aviso.compradorId());
        assertEquals(30_250, aviso.monto());
        assertTrue(aviso.reembolsado());
    }

    @Test
    void laVentaQueNacioEnLaReventaNoDevuelveCupoYLaDelCatalogoSi() {
        // El lugar lo reservó la compra original: la de la reventa no reservó ninguno.
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, "P-1"),
                enLaReventa("V-2", "user-b", 30_250, "P-2", "R-1a2b3c4d")));

        assertEquals(2, cancelacion.anularComprasDelEvento(mensaje(null)));

        verify(ventas).anularPorCancelacionDelEvento("V-1", true);
        verify(ventas).anularPorCancelacionDelEvento("V-2", false);
    }

    @Test
    void siPagosNoContestaElReembolsoDeLaReventaQuedaMarcadoComoCualquierOtro() {
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(enLaReventa("V-2", "user-b", 30_250, "P-2",
                "R-1a2b3c4d")));
        when(cobros.reembolsar("P-2", "R-1a2b3c4d", 30_250, AnulacionDeCompras.MOTIVO))
                .thenReturn(Cobros.Devolucion.SIN_RESPUESTA);

        cancelacion.anularComprasDelEvento(mensaje(null));

        verify(ventas).marcarReembolsoPendiente("V-2");
        assertFalse(publicadas.anuladas.get(0).reembolsado());
    }

    @Test
    void aQuienRevendioUnaEntradaSeLeDevuelveSoloLaQueTodaviaTenia() {
        // Dos entradas de 27.500 más el 10 %: 60.500. Revendió una; se le devuelve la otra con su cargo.
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 60_500, "P-1")));
        when(entradas.precioDeLasRevendidas("V-1")).thenReturn(27_500L);

        assertEquals(1, cancelacion.anularComprasDelEvento(mensaje(null)));

        verify(cobros).reembolsar("P-1", "V-1", 30_250, AnulacionDeCompras.MOTIVO);
        assertEquals(30_250, publicadas.anuladas.get(0).monto());
        assertTrue(publicadas.anuladas.get(0).reembolsado());
        // Su venta sí devuelve el cupo entero: es la que lo reservó.
        verify(ventas).anularPorCancelacionDelEvento("V-1", true);
    }

    @Test
    void siLasRevendioTodasSeAnulaSinReembolsoNiAviso() {
        // No le queda ninguna entrada ni plata por devolver; el aviso exige un monto mayor que 0.
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(venta("V-1", "user-a", 30_250, "P-1")));
        when(entradas.anularDeLaVenta("V-1")).thenReturn(0);
        when(entradas.precioDeLasRevendidas("V-1")).thenReturn(27_500L);

        assertEquals(1, cancelacion.anularComprasDelEvento(mensaje(null)));

        verify(ventas).anularPorCancelacionDelEvento("V-1", true);
        verify(cobros, never()).reembolsar(anyString(), anyString(), anyLong(), anyString());
        verify(ventas, never()).marcarReembolsoPendiente(anyString());
        assertTrue(publicadas.anuladas.isEmpty());
    }

    @Test
    void loQueSeDevuelveEsLoPagadoMenosLasRevendidasConSuCargo() {
        assertEquals(60_500, AnulacionDeCompras.montoADevolver(60_500, 0));
        assertEquals(30_250, AnulacionDeCompras.montoADevolver(60_500, 27_500));
        assertEquals(0, AnulacionDeCompras.montoADevolver(60_500, 55_000));
        // Con redondeo: lo devuelto más lo que se queda la reventa suma siempre lo que se cobró.
        long total = 50_010 + ReglasDeNegocio.cargoServicio(50_010);
        long devuelto = AnulacionDeCompras.montoADevolver(total, 25_005);
        assertEquals(total, devuelto + 25_005 + ReglasDeNegocio.cargoServicio(25_005));
    }

    // --- Una transacción por compra: cada una entra por el bean de la anulación ---

    @Test
    void laTransaccionDeCadaCompraLaAbreOtroBeanPorqueUnaLlamadaSobreThisNoPasaPorElContenedor()
            throws Exception {
        // REQUIRES_NEW en un bean stateless aparte...
        assertNotNull(AnulacionDeCompras.class.getAnnotation(Stateless.class));
        Method anular = AnulacionDeCompras.class.getMethod("anular", AnulacionDeCompras.CompraPagada.class,
                EventoCanceladoMensaje.class);
        assertEquals(TransactionAttributeType.REQUIRES_NEW, anular.getAnnotation(TransactionAttribute.class).value());
        // ...que la cancelación recibe inyectado: la llamada pasa por el proxy del contenedor.
        Field campo = CancelacionDeEventos.class.getDeclaredField("anulacion");
        assertEquals(AnulacionDeCompras.class, campo.getType());
        assertNotNull(campo.getAnnotation(Inject.class));
        // Y la cancelación no tiene métodos propios con otra transacción: llamados sobre this, no se aplicaría.
        assertTrue(Arrays.stream(CancelacionDeEventos.class.getDeclaredMethods())
                .noneMatch(metodo -> metodo.isAnnotationPresent(TransactionAttribute.class)));
    }

    @Test
    void siFallaUnaCompraElErrorLlegaAlMdbYLaReentregaRetomaSoloLasQueFaltan() {
        AnulacionDeCompras queFalla = mock(AnulacionDeCompras.class);
        cancelacion.anulacion = queFalla;
        EventoCanceladoMensaje mensaje = mensaje(null);
        Venta primera = venta("V-1", "user-a", 60_500, "P-1");
        Venta segunda = venta("V-2", "user-b", 30_250, "P-2");
        Venta tercera = venta("V-3", "user-c", 30_250, "P-3");
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(primera, segunda, tercera));
        when(queFalla.anular(compra(primera), mensaje)).thenReturn(true);
        when(queFalla.anular(compra(segunda), mensaje)).thenThrow(new EJBException("la base no responde"));

        // El error llega al MDB, que deshace la recepción: el broker reentrega el mensaje.
        assertThrows(EJBException.class, () -> cancelacion.anularComprasDelEvento(mensaje));
        verify(queFalla, never()).anular(compra(tercera), mensaje);

        // V-1 quedó CANCELADA en su propia transacción: la reentrega ya no la encuentra entre las pagadas.
        when(ventas.pagadasDelEvento(EVENTO)).thenReturn(List.of(segunda, tercera));
        doReturn(true).when(queFalla).anular(compra(segunda), mensaje);
        when(queFalla.anular(compra(tercera), mensaje)).thenReturn(true);

        assertEquals(2, cancelacion.anularComprasDelEvento(mensaje));
        verify(queFalla, times(1)).anular(compra(primera), mensaje);
    }

    // ---------------------------------------------------------------------------------------------

    private static EventoCanceladoMensaje mensaje(String motivo) {
        return new EventoCanceladoMensaje(EVENTO, "Show", motivo, AHORA);
    }

    /** Una venta pagada: el estado y el pago se ponen por reflexión, que es lo que hacen los UPDATE. */
    private static Venta venta(String id, String compradorId, long monto, String pagoId) {
        return conClave(id, compradorId, monto, pagoId, "clave-" + id);
    }

    /** La venta que nace de una compra en la reventa: su clave es el prefijo más el id de la publicación. */
    private static Venta enLaReventa(String id, String compradorId, long monto, String pagoId, String publicacionId) {
        return conClave(id, compradorId, monto, pagoId, ReventaDeEntradas.PREFIJO_CLAVE + publicacionId);
    }

    private static Venta conClave(String id, String compradorId, long monto, String pagoId, String clave) {
        LocalDateTime ahora = LocalDateTime.ofInstant(AHORA, ZoneOffset.UTC);
        Venta venta = new Venta(id, compradorId, EVENTO, clave, ahora, ahora.plusMinutes(10));
        fijar(venta, "montoTotal", monto);
        fijar(venta, "pagoId", pagoId);
        return venta;
    }

    private static AnulacionDeCompras.CompraPagada compra(Venta venta) {
        return AnulacionDeCompras.CompraPagada.de(venta);
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
