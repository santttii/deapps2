package com.palco.ventas.negocio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import jakarta.ejb.DependsOn;
import jakarta.ejb.EJBException;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.palco.common.ErroresDeNegocio;
import com.palco.ventas.api.Dtos.TitularDto;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.TitularDeVenta;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.negocio.ConciliacionDePagos.Desenlace;
import com.palco.ventas.negocio.externos.ClienteDeEventos;
import com.palco.ventas.negocio.externos.Cobros;
import com.palco.ventas.negocio.externos.ResultadoDeCobro;

/**
 * Qué decide la conciliación con cada respuesta de Pagos, sin servidor ni
 * temporizador: el repositorio, el cliente de Eventos, el cobro y la
 * confirmación son dobles de Mockito, y el reloj está detenido. El índice de
 * compras es el de verdad ({@link ComprasEnCurso}), porque de él depende si
 * la venta tiene dueño y si hay otra llamada en curso.
 *
 * Que la base deje cerrar la venta a uno solo lo garantiza la condición de
 * cada UPDATE (eso se prueba contra H2 en {@code ConciliacionEnH2Test}): acá
 * se prueba qué hace la conciliación según lo que el UPDATE le responde.
 */
class ConciliacionDePagosTest {

    /** Las 21:20:30 en UTC: diez minutos después de vencida la reserva de {@link #ventaMarcada}. */
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 19, 21, 20, 30);
    private static final LocalDateTime INTENTADOS_HASTA = AHORA.minusMinutes(2);

    private static final String VENTA = "V-3f9a1c2b";
    private static final String CLAVE = "0b7e2a55-3c1d-4e8f-a9b0-c1d2e3f4a5b6";
    private static final String PAGO_ID = "P-598c58a0";

    private static final ResultadoDeCobro APROBADO = new ResultadoDeCobro(PAGO_ID, ResultadoDeCobro.APROBADO, null);
    private static final ResultadoDeCobro RECHAZADO =
            new ResultadoDeCobro(PAGO_ID, ResultadoDeCobro.RECHAZADO, "fondos_insuficientes");

    private final VentaRepository ventas = mock(VentaRepository.class);
    private final ClienteDeEventos eventos = mock(ClienteDeEventos.class);
    private final Cobros cobros = mock(Cobros.class);
    private final ConfirmacionConciliada confirmacion = mock(ConfirmacionConciliada.class);
    private final ComprasEnCurso compras = new ComprasEnCurso();
    private final ConciliacionDePagos conciliacion = new ConciliacionDePagos();

    ConciliacionDePagosTest() {
        compras.ventas = ventas;
        compras.eventos = eventos;
        conciliacion.ventas = ventas;
        conciliacion.compras = compras;
        conciliacion.cobros = cobros;
        conciliacion.confirmacion = confirmacion;
        // El servidor corre en hora argentina: el reloj fijo comprueba que igual se compara en UTC.
        conciliacion.reloj = Clock.fixed(Instant.parse("2026-10-19T21:20:30.600Z"), ZoneOffset.ofHours(-3));
    }

    // ---- Hubo cobro ----

    @Test
    void conUnPagoAprobadoConfirmaLaVentaConEsePagoYLosTitularesCopiados() {
        Venta venta = ventaMarcada(VENTA, vencida());
        when(cobros.buscarCobro(VENTA, CLAVE)).thenReturn(Optional.of(APROBADO));
        titularesCopiados(VENTA);
        when(confirmacion.confirmar(VENTA, CLAVE, PAGO_ID, titularesDto())).thenReturn(true);

        assertEquals(Desenlace.CONFIRMADA, conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA));

        verify(confirmacion).confirmar(VENTA, CLAVE, PAGO_ID, titularesDto());
        // Hubo cobro: ni se vence ni se devuelve el cupo.
        verify(ventas, never()).vencerPagoSinCobro(anyString(), anyString(), any());
        verifyNoInteractions(eventos);
    }

    @Test
    void alConfirmarQuitaLaCompraDelIndice() {
        Venta venta = ventaMarcada(VENTA, vencida());
        compras.enCurso.put(VENTA, mock(IVentas.class));
        when(cobros.buscarCobro(VENTA, CLAVE)).thenReturn(Optional.of(APROBADO));
        titularesCopiados(VENTA);
        when(confirmacion.confirmar(VENTA, CLAVE, PAGO_ID, titularesDto())).thenReturn(true);

        conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA);

        assertFalse(compras.tieneInstancia(VENTA));
    }

    @Test
    void siOtroYaConfirmoLaVentaNoSeHaceNadaMas() {
        // Por ejemplo, un reintento del comprador con la misma clave que terminó justo antes.
        Venta venta = ventaMarcada(VENTA, vencida());
        when(cobros.buscarCobro(VENTA, CLAVE)).thenReturn(Optional.of(APROBADO));
        titularesCopiados(VENTA);
        when(confirmacion.confirmar(VENTA, CLAVE, PAGO_ID, titularesDto())).thenReturn(false);

        assertEquals(Desenlace.CERRADA_POR_OTRO, conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA));

        verify(ventas, never()).vencerPagoSinCobro(anyString(), anyString(), any());
        verifyNoInteractions(eventos);
    }

    @Test
    void conUnPagoAprobadoYSinLosTitularesNoSeConfirmaNiSeVence() {
        // Una venta marcada antes de que existiera la copia: hay plata cobrada y no se sabe a nombre de quién.
        Venta venta = ventaMarcada(VENTA, vencida());
        when(cobros.buscarCobro(VENTA, CLAVE)).thenReturn(Optional.of(APROBADO));
        when(ventas.buscarTitulares(VENTA)).thenReturn(List.of());

        assertEquals(Desenlace.SIN_TITULARES, conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA));

        verifyNoInteractions(confirmacion, eventos);
        verify(ventas, never()).vencerPagoSinCobro(anyString(), anyString(), any());
    }

    // ---- No hubo cobro ----

    @Test
    void sinPagoConEsaClaveVenceLaVentaYRecienDespuesDevuelveElCupo() {
        Venta venta = ventaMarcada(VENTA, vencida());
        when(cobros.buscarCobro(VENTA, CLAVE)).thenReturn(Optional.empty());
        when(ventas.vencerPagoSinCobro(VENTA, CLAVE, INTENTADOS_HASTA)).thenReturn(true);

        assertEquals(Desenlace.VENCIDA, conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA));

        // Primero el UPDATE, que queda confirmado en la base, y después la llamada a Eventos.
        InOrder orden = inOrder(ventas, eventos);
        orden.verify(ventas).vencerPagoSinCobro(VENTA, CLAVE, INTENTADOS_HASTA);
        orden.verify(eventos).liberarCupo(VENTA, "ev-1", "sec-1-4", 2);
        verifyNoMoreInteractions(eventos);
        verifyNoInteractions(confirmacion);
    }

    @Test
    void conElPagoRechazadoTambienVenceLaVentaYDevuelveElCupo() {
        Venta venta = ventaMarcada(VENTA, vencida());
        when(cobros.buscarCobro(VENTA, CLAVE)).thenReturn(Optional.of(RECHAZADO));
        when(ventas.vencerPagoSinCobro(VENTA, CLAVE, INTENTADOS_HASTA)).thenReturn(true);

        assertEquals(Desenlace.VENCIDA, conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA));

        verify(eventos).liberarCupo(VENTA, "ev-1", "sec-1-4", 2);
        verifyNoInteractions(confirmacion);
    }

    @Test
    void siElUpdateNoVencioLaVentaElCupoNoSeToca() {
        // Llegó un reintento después de la pregunta a Pagos, o la venta ya la cerró otro.
        Venta venta = ventaMarcada(VENTA, vencida());
        when(cobros.buscarCobro(VENTA, CLAVE)).thenReturn(Optional.empty());
        when(ventas.vencerPagoSinCobro(VENTA, CLAVE, INTENTADOS_HASTA)).thenReturn(false);

        assertEquals(Desenlace.CERRADA_POR_OTRO, conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA));

        verifyNoInteractions(eventos);
    }

    @Test
    void alVencerQuitaLaCompraDelIndice() {
        Venta venta = ventaMarcada(VENTA, vencida());
        compras.enCurso.put(VENTA, mock(IVentas.class));
        when(cobros.buscarCobro(VENTA, CLAVE)).thenReturn(Optional.empty());
        when(ventas.vencerPagoSinCobro(VENTA, CLAVE, INTENTADOS_HASTA)).thenReturn(true);

        conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA);

        assertFalse(compras.tieneInstancia(VENTA));
    }

    // ---- No se sabe ----

    @Test
    void siPagosNoRespondeLaVentaQuedaComoEstaParaLaCorridaSiguiente() {
        Venta venta = ventaMarcada(VENTA, vencida());
        when(cobros.buscarCobro(VENTA, CLAVE)).thenThrow(new ErroresDeNegocio.SinRespuesta("Pagos no responde."));

        assertEquals(Desenlace.SIN_RESPUESTA, conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA));

        verify(ventas, never()).vencerPagoSinCobro(anyString(), anyString(), any());
        verifyNoInteractions(confirmacion, eventos);
        // Y la compra no queda tomada: la corrida siguiente (o un reintento) la puede tomar.
        assertTrue(compras.tomarParaConciliar(VENTA));
    }

    // ---- Qué ventas se tocan ----

    @Test
    void conLaInstanciaVivaYLaReservaVigenteLaVentaEsDelComprador() {
        // Un 504 recién: el comprador todavía puede reintentar con la misma clave, y eso la resuelve.
        Venta venta = ventaMarcada(VENTA, AHORA.plusMinutes(3));
        compras.enCurso.put(VENTA, mock(IVentas.class));

        assertEquals(Desenlace.NO_SE_TOCA, conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA));

        verifyNoInteractions(cobros, confirmacion, eventos);
    }

    @Test
    void conLaInstanciaVivaPeroLaReservaVencidaSeConcilia() {
        Venta venta = ventaMarcada(VENTA, vencida());
        IVentas instancia = mock(IVentas.class);
        compras.enCurso.put(VENTA, instancia);
        when(cobros.buscarCobro(VENTA, CLAVE)).thenReturn(Optional.empty());
        when(ventas.vencerPagoSinCobro(VENTA, CLAVE, INTENTADOS_HASTA)).thenReturn(true);

        assertEquals(Desenlace.VENCIDA, conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA));

        // A la instancia no se la llama: la destruye el contenedor por inactividad.
        verifyNoInteractions(instancia);
    }

    @Test
    void sinInstanciaSeConciliaAunqueLaReservaSigaVigente() {
        // Es lo que deja la falla después del cobro: el contenedor descartó la instancia y nadie puede
        // reintentar. No hace falta esperar a que venza la reserva.
        Venta venta = ventaMarcada(VENTA, AHORA.plusMinutes(3));
        when(cobros.buscarCobro(VENTA, CLAVE)).thenReturn(Optional.of(APROBADO));
        titularesCopiados(VENTA);
        when(confirmacion.confirmar(VENTA, CLAVE, PAGO_ID, titularesDto())).thenReturn(true);

        assertEquals(Desenlace.CONFIRMADA, conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA));
    }

    @Test
    void siHayOtraLlamadaEnCursoSobreLaCompraNoSeToca() {
        // Un pago del comprador tiene la compra tomada (la misma marca que usa ComprasEnCurso.pagar).
        Venta venta = ventaMarcada(VENTA, vencida());
        assertTrue(compras.tomarParaConciliar(VENTA));

        assertEquals(Desenlace.NO_SE_TOCA, conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA));

        verifyNoInteractions(cobros, confirmacion, eventos);
    }

    @Test
    void mientrasConciliaLaCompraQuedaTomadaYAlTerminarSeSuelta() {
        Venta venta = ventaMarcada(VENTA, vencida());
        when(cobros.buscarCobro(VENTA, CLAVE)).thenAnswer(llamada -> {
            // Un pago simultáneo del comprador no puede tomarla: ComprasEnCurso le responde operacion-en-curso.
            assertFalse(compras.tomarParaConciliar(VENTA));
            return Optional.empty();
        });
        when(ventas.vencerPagoSinCobro(VENTA, CLAVE, INTENTADOS_HASTA)).thenReturn(true);

        conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA);

        assertTrue(compras.tomarParaConciliar(VENTA));
    }

    // ---- La corrida ----

    @Test
    void laCorridaBuscaLasVentasCuyoUltimoIntentoTieneMasDeDosMinutos() {
        when(ventas.buscarPagosSinResultado(INTENTADOS_HASTA)).thenReturn(List.of());

        conciliacion.conciliarPagosSinResultado();

        verify(ventas).buscarPagosSinResultado(LocalDateTime.of(2026, 10, 19, 21, 18, 30));
        verifyNoInteractions(cobros);
    }

    @Test
    void unaVentaQueFallaNoFrenaALaSiguiente() {
        Venta primera = ventaMarcada("V-11111111", vencida());
        Venta segunda = ventaMarcada("V-22222222", vencida());
        when(ventas.buscarPagosSinResultado(INTENTADOS_HASTA)).thenReturn(List.of(primera, segunda));
        when(cobros.buscarCobro("V-11111111", CLAVE)).thenReturn(Optional.of(APROBADO));
        titularesCopiados("V-11111111");
        // La confirmación de la primera falla en la base: su transacción se deshace entera.
        when(confirmacion.confirmar("V-11111111", CLAVE, PAGO_ID, titularesDto()))
                .thenThrow(new EJBException("la base no responde"));
        when(cobros.buscarCobro("V-22222222", CLAVE)).thenReturn(Optional.empty());
        when(ventas.vencerPagoSinCobro("V-22222222", CLAVE, INTENTADOS_HASTA)).thenReturn(true);

        assertDoesNotThrow(conciliacion::conciliarPagosSinResultado);

        verify(eventos).liberarCupo("V-22222222", "ev-1", "sec-1-4", 2);
        verifyNoMoreInteractions(eventos);
        // La primera no quedó tomada: la corrida siguiente la vuelve a intentar.
        assertTrue(compras.tomarParaConciliar("V-11111111"));
    }

    @Test
    void siLaBaseNoRespondeLaCorridaSeCortaSinLanzar() {
        when(ventas.buscarPagosSinResultado(any())).thenThrow(new EJBException("la base no responde"));

        assertDoesNotThrow(conciliacion::conciliarPagosSinResultado);

        verifyNoInteractions(cobros, confirmacion, eventos);
    }

    @Test
    void elContenedorLaLlamaUnaVezPorMinutoEnElSegundo30() throws NoSuchMethodException {
        Schedule horario = ConciliacionDePagos.class.getDeclaredMethod("conciliarPagosSinResultado")
                .getAnnotation(Schedule.class);

        assertNotNull(horario, "conciliarPagosSinResultado no tiene temporizador");
        // En el segundo 30, para no coincidir con el barrido de reservas, que corre en el 0.
        assertEquals("* * 30", horario.hour() + " " + horario.minute() + " " + horario.second());
        assertFalse(horario.persistent());
    }

    @Test
    void esUnSoloBarridoSinTransaccionPropiaQueEsperaALasMigraciones() {
        Class<ConciliacionDePagos> clase = ConciliacionDePagos.class;

        assertNotNull(clase.getAnnotation(Singleton.class), "ConciliacionDePagos no es un Singleton");
        // Sin transacción propia: mientras espera a Pagos no retiene una conexión de la base.
        assertEquals(TransactionAttributeType.NOT_SUPPORTED, clase.getAnnotation(TransactionAttribute.class).value());
        assertArrayEquals(new String[] { MigracionesVentas.class.getSimpleName() },
                clase.getAnnotation(DependsOn.class).value());
    }

    @Test
    void conElPagoAprobadoNoSeLeLlamaAEventos() {
        // El cupo de una venta que se cobró nunca vuelve a Eventos, ni siquiera si la confirmación falla.
        Venta venta = ventaMarcada(VENTA, vencida());
        when(cobros.buscarCobro(VENTA, CLAVE)).thenReturn(Optional.of(APROBADO));
        titularesCopiados(VENTA);
        when(confirmacion.confirmar(anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new EJBException("falla"));

        // La falla sale hacia la corrida, que la anota y sigue con la venta siguiente.
        assertThrows(EJBException.class, () -> conciliacion.conciliar(venta, AHORA, INTENTADOS_HASTA));

        verify(eventos, never()).liberarCupo(anyString(), anyString(), anyString(), anyInt());
        verify(ventas, never()).vencerPagoSinCobro(anyString(), anyString(), any());
        assertTrue(compras.tomarParaConciliar(VENTA));
    }

    // ---------------------------------------------------------------------------------------------

    /** Una reserva que venció a las 21:10:30, diez minutos antes del reloj. */
    private static LocalDateTime vencida() {
        return AHORA.minusMinutes(10);
    }

    /**
     * Una venta real de dos entradas en Platea B, con la reserva hasta {@code venceReserva} y con la
     * clave del pago anotada. La clave no tiene "set" (la anota un UPDATE): se simula con un spy.
     */
    private static Venta ventaMarcada(String id, LocalDateTime venceReserva) {
        Venta real = new Venta(id, "user-titular", "ev-1", "clave-de-" + id, venceReserva.minusMinutes(10),
                venceReserva);
        real.agregarItem("sec-1-4", "Platea B", 27_500, 2);
        Venta venta = spy(real);
        doReturn(CLAVE).when(venta).getClavePago();
        return venta;
    }

    private void titularesCopiados(String ventaId) {
        when(ventas.buscarTitulares(ventaId)).thenReturn(List.of(
                new TitularDeVenta(ventaId, 1, "Ana Gómez", "30123456"),
                new TitularDeVenta(ventaId, 2, "Luis Pérez", "31234567")));
    }

    private static List<TitularDto> titularesDto() {
        return List.of(new TitularDto("Ana Gómez", "30123456"), new TitularDto("Luis Pérez", "31234567"));
    }
}
