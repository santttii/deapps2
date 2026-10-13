package com.palco.ventas.negocio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

/**
 * Qué hace una corrida del barrido, sin servidor ni temporizador: el
 * repositorio y el cliente de Eventos son dobles de Mockito, y el reloj está
 * detenido. El índice de compras es el de verdad ({@link ComprasEnCurso}),
 * para comprobar la llamada que le llega a Eventos y no una intermedia.
 *
 * Que la base deje pasar a uno solo (el barrido, un pago o una cancelación)
 * lo garantiza la condición del UPDATE: acá se prueba qué hace el barrido
 * según lo que ese UPDATE le responde. Que el contenedor llame al método cada
 * minuto se comprueba en el servidor.
 */
class VencimientoDeReservasTest {

    /** El instante del reloj detenido, como lo tiene que usar el barrido: en UTC y sin fracción de segundo. */
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 19, 21, 11, 0);

    private final VentaRepository ventas = mock(VentaRepository.class);
    private final ClienteDeEventos eventos = mock(ClienteDeEventos.class);
    private final ComprasEnCurso compras = new ComprasEnCurso();
    private final VencimientoDeReservas barrido = new VencimientoDeReservas();

    VencimientoDeReservasTest() {
        compras.ventas = ventas;
        compras.eventos = eventos;
        barrido.ventas = ventas;
        barrido.compras = compras;
        // El servidor corre en hora argentina: el reloj fijo comprueba que igual se compara en UTC.
        barrido.reloj = Clock.fixed(Instant.parse("2026-10-19T21:11:00.400Z"), ZoneOffset.ofHours(-3));
    }

    @Test
    void unaReservaVencidaPasaAVencidaYRecienDespuesSeDevuelveElCupoDeCadaSector() {
        Venta venta = reservaVencida("V-3f9a1c2b");
        venta.agregarItem("sec-1-5", "Palcos", 60_000, 1);
        when(ventas.buscarReservasVencidas(AHORA)).thenReturn(List.of(venta));
        when(ventas.vencer("V-3f9a1c2b", AHORA)).thenReturn(true);

        barrido.vencerReservas();

        // Primero el cambio de estado, que queda confirmado en la base, y después la llamada a Eventos.
        InOrder orden = inOrder(ventas, eventos);
        orden.verify(ventas).vencer("V-3f9a1c2b", AHORA);
        orden.verify(eventos).liberarCupo("V-3f9a1c2b", "ev-1", "sec-1-4", 2);
        orden.verify(eventos).liberarCupo("V-3f9a1c2b", "ev-1", "sec-1-5", 1);
        verifyNoMoreInteractions(eventos);
    }

    @Test
    void siLaVentaSePagoOSeCanceloJustoAntesElCupoNoSeToca() {
        // La búsqueda la trajo, pero cuando llegó el UPDATE ya no estaba EN_CHECKOUT o tenía un pago en curso.
        when(ventas.buscarReservasVencidas(AHORA)).thenReturn(List.of(reservaVencida("V-3f9a1c2b")));
        when(ventas.vencer("V-3f9a1c2b", AHORA)).thenReturn(false);
        IVentas instancia = mock(IVentas.class);
        compras.enCurso.put("V-3f9a1c2b", instancia);

        barrido.vencerReservas();

        // El cupo lo devuelve solo quien cambió la fila: acá no fue el barrido.
        verifyNoInteractions(eventos);
        // Y la conversación de esa compra sigue en el índice: la puede estar usando el pago.
        assertSame(instancia, compras.enCurso.get("V-3f9a1c2b"));
    }

    @Test
    void siNoHayReservasVencidasNoTocaNada() {
        when(ventas.buscarReservasVencidas(AHORA)).thenReturn(List.of());

        barrido.vencerReservas();

        verify(ventas, never()).vencer(anyString(), any());
        verifyNoInteractions(eventos);
    }

    @Test
    void cadaVentaSeDecidePorSeparado() {
        when(ventas.buscarReservasVencidas(AHORA))
                .thenReturn(List.of(reservaVencida("V-11111111"), reservaVencida("V-22222222")));
        when(ventas.vencer("V-11111111", AHORA)).thenReturn(false);
        when(ventas.vencer("V-22222222", AHORA)).thenReturn(true);

        barrido.vencerReservas();

        verify(eventos).liberarCupo("V-22222222", "ev-1", "sec-1-4", 2);
        verifyNoMoreInteractions(eventos);
    }

    @Test
    void alVencerQuitaLaCompraDelIndiceSinLlamarASuInstancia() {
        // Pasa cuando un pago rechazado mantuvo viva la instancia más allá de la reserva.
        when(ventas.buscarReservasVencidas(AHORA)).thenReturn(List.of(reservaVencida("V-3f9a1c2b")));
        when(ventas.vencer("V-3f9a1c2b", AHORA)).thenReturn(true);
        IVentas instancia = mock(IVentas.class);
        compras.enCurso.put("V-3f9a1c2b", instancia);

        barrido.vencerReservas();

        assertFalse(compras.enCurso.containsKey("V-3f9a1c2b"));
        // A la instancia no se la llama: la destruye el contenedor por su cuenta, por inactividad.
        verifyNoInteractions(instancia);
    }

    @Test
    void siEventosNoRecibeLaDevolucionLaVentaQuedaVencidaYElBarridoSigueConLaSiguiente() {
        when(ventas.buscarReservasVencidas(AHORA))
                .thenReturn(List.of(reservaVencida("V-11111111"), reservaVencida("V-22222222")));
        when(ventas.vencer(anyString(), any())).thenReturn(true);
        // false: Eventos no respondió. El cliente no lanza; deja un aviso con los datos para devolverlo a mano.
        when(eventos.liberarCupo("V-11111111", "ev-1", "sec-1-4", 2)).thenReturn(false);
        when(eventos.liberarCupo("V-22222222", "ev-1", "sec-1-4", 2)).thenReturn(true);

        assertDoesNotThrow(barrido::vencerReservas);

        verify(ventas).vencer("V-11111111", AHORA);
        verify(ventas).vencer("V-22222222", AHORA);
        verify(eventos).liberarCupo("V-22222222", "ev-1", "sec-1-4", 2);
    }

    @Test
    void siLaBaseNoRespondeLaCorridaSeCortaSinLanzar() {
        // Así le llega al barrido una falla de la base dentro del repositorio, que es un Session Bean.
        when(ventas.buscarReservasVencidas(AHORA)).thenThrow(new EJBException("la base no responde"));

        // No sale ninguna excepción hacia el contenedor: en un minuto hay otra corrida.
        assertDoesNotThrow(barrido::vencerReservas);

        verifyNoInteractions(eventos);
    }

    @Test
    void siLaBaseFallaEnMedioDeLaCorridaLoQueYaVencioYaDevolvioSuCupo() {
        when(ventas.buscarReservasVencidas(AHORA))
                .thenReturn(List.of(reservaVencida("V-11111111"), reservaVencida("V-22222222")));
        when(ventas.vencer("V-11111111", AHORA)).thenReturn(true);
        when(ventas.vencer("V-22222222", AHORA)).thenThrow(new EJBException("la base no responde"));

        assertDoesNotThrow(barrido::vencerReservas);

        // La primera quedó cerrada del todo. La segunda sigue EN_CHECKOUT y la toma la corrida siguiente.
        verify(eventos).liberarCupo("V-11111111", "ev-1", "sec-1-4", 2);
        verifyNoMoreInteractions(eventos);
    }

    @Test
    void elContenedorLoLlamaUnaVezPorMinutoYElTemporizadorNoSeGuardaEnDisco() throws NoSuchMethodException {
        Schedule horario = VencimientoDeReservas.class.getDeclaredMethod("vencerReservas")
                .getAnnotation(Schedule.class);

        assertNotNull(horario, "vencerReservas no tiene temporizador");
        // Todas las horas, todos los minutos, en el segundo 0.
        assertEquals("* * 0", horario.hour() + " " + horario.minute() + " " + horario.second());
        assertFalse(horario.persistent());
    }

    @Test
    void esUnSoloBarridoSinTransaccionPropiaQueEsperaALasMigraciones() {
        Class<VencimientoDeReservas> clase = VencimientoDeReservas.class;

        assertNotNull(clase.getAnnotation(Singleton.class), "VencimientoDeReservas no es un Singleton");
        // Sin transacción propia: el cambio de cada venta queda confirmado antes de llamar a Eventos.
        assertEquals(TransactionAttributeType.NOT_SUPPORTED, clase.getAnnotation(TransactionAttribute.class).value());
        // El nombre es el del componente que crea las tablas: si se renombra, el despliegue falla.
        assertArrayEquals(new String[] { MigracionesVentas.class.getSimpleName() },
                clase.getAnnotation(DependsOn.class).value());
    }

    // ---------------------------------------------------------------------------------------------

    /** Una venta real, abierta a las 21:00:00 y con la reserva hasta las 21:10:00: un minuto antes del reloj. */
    private static Venta reservaVencida(String id) {
        LocalDateTime abierta = LocalDateTime.of(2026, 10, 19, 21, 0, 0);
        Venta venta = new Venta(id, "user-titular", "ev-1", "clave-de-" + id, abierta, abierta.plusMinutes(10));
        venta.agregarItem("sec-1-4", "Platea B", 27_500, 2);
        return venta;
    }
}
