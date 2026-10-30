package com.palco.validacion.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.palco.common.ErroresDeNegocio;
import com.palco.validacion.api.Dtos.EscaneoDto;
import com.palco.validacion.api.Dtos.PuertaAsignadaDto;
import com.palco.validacion.api.Dtos.ResultadoDeEscaneoDto;
import com.palco.validacion.datos.EscaneoRepository;
import com.palco.validacion.datos.PuertaAsignadaRepository;
import com.palco.validacion.dominio.Escaneo;
import com.palco.validacion.dominio.PuertaAsignada;
import com.palco.validacion.dominio.ResultadoEscaneo;
import com.palco.validacion.negocio.externos.ClienteDeEventos;
import com.palco.validacion.negocio.externos.ClienteDeVentas;
import com.palco.validacion.negocio.externos.ClienteDeVentas.Entrada;
import com.palco.validacion.negocio.externos.ClienteDeVentas.Titular;
import com.palco.validacion.negocio.externos.ClienteDeVentas.Uso;

/**
 * Las reglas de la puerta, sin servidor: Ventas y el registro son dobles. Que
 * dos escaneos simultáneos no usen dos veces la entrada lo decide Ventas y se
 * prueba allá ({@code UsoEnH2Test}).
 */
class ServicioDeValidacionTest {

    private static final String CODIGO = "5b0c7a5e-3c1f-4b8e-9d2a-8f6e1c0b7a11";
    private static final String EVENTO = "ev-1";
    private static final String PUERTA = "U-puerta";
    private static final Solicitante STAFF = Solicitante.staff(PUERTA, "puerta@palco.test");
    private static final Solicitante OTRO_STAFF = Solicitante.staff("U-otra", "otra.puerta@palco.test");
    private static final Solicitante DUENO = Solicitante.organizador("U-org", "organizador@palco.test");
    private static final Solicitante AJENO = Solicitante.organizador("U-otro-org", "otro.organizador@palco.test");
    private static final Instant AHORA = Instant.parse("2026-11-20T21:03:12.345Z");

    private final EscaneoRepository escaneos = mock(EscaneoRepository.class);
    private final ClienteDeVentas ventas = mock(ClienteDeVentas.class);
    private final PuertaAsignadaRepository puertas = mock(PuertaAsignadaRepository.class);
    private final ClienteDeEventos eventos = mock(ClienteDeEventos.class);
    private final ServicioDeValidacion validacion = new ServicioDeValidacion();

    @BeforeEach
    void conectar() {
        validacion.escaneos = escaneos;
        validacion.ventas = ventas;
        validacion.puertas = puertas;
        validacion.eventos = eventos;
        when(puertas.estaAsignada(EVENTO, "puerta@palco.test")).thenReturn(true);
        when(eventos.organizadorDe(EVENTO)).thenReturn("U-org");
        validacion.reloj = Clock.fixed(AHORA, ZoneOffset.UTC);
    }

    private static Entrada entradaDeAna() {
        return new Entrada("T-9c41d07e", EVENTO, "sec-1-4", "Platea B", new Titular("Ana Gómez", "30123456"), "USADA");
    }

    private Escaneo guardado() {
        ArgumentCaptor<Escaneo> captor = ArgumentCaptor.forClass(Escaneo.class);
        verify(escaneos).guardar(captor.capture());
        return captor.getValue();
    }

    @Test
    void unaEntradaValidaDejaEntrarYDevuelveElTitularParaElDocumento() {
        when(ventas.usar(CODIGO, EVENTO)).thenReturn(new Uso(ResultadoEscaneo.VALIDA, entradaDeAna()));

        ResultadoDeEscaneoDto resultado = validacion.escanear(CODIGO, EVENTO, STAFF);

        assertEquals("VALIDA", resultado.resultado());
        assertTrue(resultado.puedeEntrar());
        assertEquals("Ana Gómez", resultado.entrada().titular().nombre());
        assertEquals("30123456", resultado.entrada().titular().dni());
        assertEquals("Platea B", resultado.entrada().sector());
        assertNull(resultado.usoAnterior());
        assertEquals(PUERTA, resultado.escaneadoPor());
        // UTC, sin fracción de segundo.
        assertEquals("2026-11-20T21:03:12Z", resultado.escaneado());

        Escaneo escaneo = guardado();
        assertEquals(resultado.id(), escaneo.getId());
        assertTrue(escaneo.getId().startsWith("ESC-"));
        assertEquals(CODIGO, escaneo.getCodigoQr());
        assertEquals(EVENTO, escaneo.getEventoId());
        assertEquals(ResultadoEscaneo.VALIDA, escaneo.getResultado());
        assertEquals("T-9c41d07e", escaneo.getEntradaId());
        assertEquals(PUERTA, escaneo.getEscaneadoPor());
        assertEquals(LocalDateTime.of(2026, 11, 20, 21, 3, 12), escaneo.getEscaneado());
    }

    @Test
    void elCodigoTipeadoConEspaciosSeLimpiaAntesDePreguntar() {
        when(ventas.usar(CODIGO, EVENTO)).thenReturn(new Uso(ResultadoEscaneo.VALIDA, entradaDeAna()));

        validacion.escanear("  " + CODIGO + " ", " " + EVENTO, STAFF);

        verify(ventas).usar(CODIGO, EVENTO);
        assertEquals(CODIGO, guardado().getCodigoQr());
    }

    @Test
    void unCodigoInexistenteNoDejaEntrarYQuedaRegistrado() {
        when(ventas.usar(CODIGO, EVENTO)).thenReturn(new Uso(ResultadoEscaneo.INEXISTENTE, null));

        ResultadoDeEscaneoDto resultado = validacion.escanear(CODIGO, EVENTO, STAFF);

        assertEquals("INEXISTENTE", resultado.resultado());
        assertFalse(resultado.puedeEntrar());
        assertNull(resultado.entrada());
        assertEquals(ResultadoEscaneo.INEXISTENTE, guardado().getResultado());
        assertNull(guardado().getEntradaId());
    }

    @Test
    void unaEntradaDeOtroEventoNoDejaEntrar() {
        when(ventas.usar(CODIGO, EVENTO)).thenReturn(new Uso(ResultadoEscaneo.OTRO_EVENTO, null));

        ResultadoDeEscaneoDto resultado = validacion.escanear(CODIGO, EVENTO, STAFF);

        assertEquals("OTRO_EVENTO", resultado.resultado());
        assertFalse(resultado.puedeEntrar());
        assertEquals(ResultadoEscaneo.OTRO_EVENTO, guardado().getResultado());
    }

    @Test
    void unaEntradaNoHabilitadaNoDejaEntrar() {
        when(ventas.usar(CODIGO, EVENTO)).thenReturn(new Uso(ResultadoEscaneo.NO_HABILITADA, null));

        ResultadoDeEscaneoDto resultado = validacion.escanear(CODIGO, EVENTO, STAFF);

        assertEquals("NO_HABILITADA", resultado.resultado());
        assertFalse(resultado.puedeEntrar());
    }

    @Test
    void unaEntradaYaUsadaMuestraElEscaneoQueLaUso() {
        Escaneo primero = new Escaneo("ESC-00000001", CODIGO, EVENTO, ResultadoEscaneo.VALIDA, "T-9c41d07e",
                "U-otra-puerta", LocalDateTime.of(2026, 11, 20, 20, 41, 5));
        when(ventas.usar(CODIGO, EVENTO)).thenReturn(new Uso(ResultadoEscaneo.YA_USADA, null));
        when(escaneos.ultimoDelCodigo(CODIGO, ResultadoEscaneo.VALIDA)).thenReturn(Optional.of(primero));

        ResultadoDeEscaneoDto resultado = validacion.escanear(CODIGO, EVENTO, STAFF);

        assertEquals("YA_USADA", resultado.resultado());
        assertFalse(resultado.puedeEntrar());
        assertEquals("ESC-00000001", resultado.usoAnterior().escaneoId());
        assertEquals("VALIDA", resultado.usoAnterior().resultado());
        assertEquals("U-otra-puerta", resultado.usoAnterior().escaneadoPor());
        assertEquals("2026-11-20T20:41:05Z", resultado.usoAnterior().escaneado());
        assertEquals("La entrada ya se usó. No puede entrar.", resultado.mensaje());
        // Con el escaneo que la usó alcanza: no se busca uno sin respuesta.
        verify(escaneos, never()).ultimoDelCodigo(CODIGO, ResultadoEscaneo.SIN_RESPUESTA);
    }

    @Test
    void siLaUsoUnEscaneoQueQuedoSinRespuestaLoAvisa() {
        Escaneo colgado = new Escaneo("ESC-00000002", CODIGO, EVENTO, ResultadoEscaneo.SIN_RESPUESTA, null,
                PUERTA, LocalDateTime.of(2026, 11, 20, 21, 2, 40));
        when(ventas.usar(CODIGO, EVENTO)).thenReturn(new Uso(ResultadoEscaneo.YA_USADA, null));
        when(escaneos.ultimoDelCodigo(CODIGO, ResultadoEscaneo.VALIDA)).thenReturn(Optional.empty());
        when(escaneos.ultimoDelCodigo(CODIGO, ResultadoEscaneo.SIN_RESPUESTA)).thenReturn(Optional.of(colgado));

        ResultadoDeEscaneoDto resultado = validacion.escanear(CODIGO, EVENTO, STAFF);

        assertEquals("SIN_RESPUESTA", resultado.usoAnterior().resultado());
        assertTrue(resultado.mensaje().contains("quedó sin respuesta"), resultado.mensaje());
        assertFalse(resultado.puedeEntrar());
    }

    @Test
    void unaEntradaYaUsadaSinEscaneoAnteriorConocidoVuelveSinUsoAnterior() {
        when(ventas.usar(CODIGO, EVENTO)).thenReturn(new Uso(ResultadoEscaneo.YA_USADA, null));
        when(escaneos.ultimoDelCodigo(any(), any())).thenReturn(Optional.empty());

        ResultadoDeEscaneoDto resultado = validacion.escanear(CODIGO, EVENTO, STAFF);

        assertNull(resultado.usoAnterior());
        assertEquals("La entrada ya se usó. No puede entrar.", resultado.mensaje());
    }

    @Test
    void siVentasNoRespondeSeRegistraElIntentoYSeAvisaConUn504() {
        ErroresDeNegocio.SinRespuesta caida = new ErroresDeNegocio.SinRespuesta("El servicio de ventas no responde.");
        when(ventas.usar(CODIGO, EVENTO)).thenThrow(caida);

        ErroresDeNegocio.SinRespuesta error = assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> validacion.escanear(CODIGO, EVENTO, STAFF));

        assertSame(caida, error);
        Escaneo escaneo = guardado();
        assertEquals(ResultadoEscaneo.SIN_RESPUESTA, escaneo.getResultado());
        assertNull(escaneo.getEntradaId());
        assertEquals(PUERTA, escaneo.getEscaneadoPor());
    }

    @Test
    void elRegistroMuestraSoloElFinalDelCodigo() {
        Escaneo escaneo = new Escaneo("ESC-00000001", CODIGO, EVENTO, ResultadoEscaneo.VALIDA, "T-9c41d07e", PUERTA,
                LocalDateTime.of(2026, 11, 20, 20, 41, 5));
        when(escaneos.listarPorEvento(EVENTO, ServicioDeValidacion.MAXIMO_POR_CONSULTA)).thenReturn(List.of(escaneo));

        List<EscaneoDto> registro = validacion.listar(EVENTO, STAFF);

        assertEquals(List.of(new EscaneoDto("ESC-00000001", EVENTO, "…0b7a11", "VALIDA", "T-9c41d07e", PUERTA,
                "2026-11-20T20:41:05Z")), registro);
    }

    @Test
    void unCodigoCortoSeMuestraEntero() {
        assertEquals("abc", ServicioDeValidacion.parcial("abc"));
    }

    @Test
    void unEscaneoQueNoExisteEsUn404() {
        when(escaneos.buscarPorId("ESC-nada")).thenReturn(Optional.empty());

        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> validacion.obtener("ESC-nada", STAFF));
    }

    // ---- La puerta asignada y el dueño del evento (limitación L17) ----

    @Test
    void unaCuentaQueNoEstaAsignadaNoEscaneaNiQuedaEnElRegistro() {
        ErroresDeNegocio.SinPermiso error = assertThrows(ErroresDeNegocio.SinPermiso.class,
                () -> validacion.escanear(CODIGO, EVENTO, OTRO_STAFF));

        assertEquals(ServicioDeValidacion.PUERTA_NO_ASIGNADA, error.tipo());
        verifyNoInteractions(ventas);
        verify(escaneos, never()).guardar(any());
    }

    @Test
    void elRegistroLoVenElStaffDeEsaPuertaYElDuenoDelEvento() {
        when(escaneos.listarPorEvento(EVENTO, ServicioDeValidacion.MAXIMO_POR_CONSULTA)).thenReturn(List.of());

        assertEquals(List.of(), validacion.listar(EVENTO, STAFF));
        assertEquals(List.of(), validacion.listar(EVENTO, DUENO));
        assertThrows(ErroresDeNegocio.SinPermiso.class, () -> validacion.listar(EVENTO, OTRO_STAFF));
        // Para otro organizador el evento no existe: el mismo 404 que uno inexistente.
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> validacion.listar(EVENTO, AJENO));
    }

    @Test
    void unEventoQueNoExisteOSinDuenoOConUnIdRaroEsNoEncontradoParaElOrganizador() {
        when(eventos.organizadorDe("ev-9")).thenThrow(new ErroresDeNegocio.NoEncontrado("no existe"));
        when(eventos.organizadorDe("ev-sin-dueno")).thenReturn(null);

        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> validacion.listar("ev-9", DUENO));
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> validacion.listar("ev-sin-dueno", DUENO));
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> validacion.listar("../ev-1", DUENO));
        verify(eventos, never()).organizadorDe("../ev-1");
    }

    @Test
    void siEventosNoRespondeElOrganizadorRecibeSinRespuestaYLaPuertaSigueEscaneando() {
        when(eventos.organizadorDe(EVENTO)).thenThrow(new ErroresDeNegocio.SinRespuesta("Eventos no responde"));
        when(ventas.usar(CODIGO, EVENTO)).thenReturn(new Uso(ResultadoEscaneo.VALIDA, entradaDeAna()));

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> validacion.listar(EVENTO, DUENO));
        assertEquals("VALIDA", validacion.escanear(CODIGO, EVENTO, STAFF).resultado());
    }

    @Test
    void unEscaneoDeUnEventoAjenoSeVeComoUnoQueNoExiste() {
        Escaneo escaneo = new Escaneo("ESC-00000001", CODIGO, EVENTO, ResultadoEscaneo.VALIDA, "T-1", PUERTA,
                LocalDateTime.of(2026, 11, 20, 20, 41, 5));
        when(escaneos.buscarPorId("ESC-00000001")).thenReturn(Optional.of(escaneo));

        assertEquals("ESC-00000001", validacion.obtener("ESC-00000001", STAFF).id());
        assertEquals("ESC-00000001", validacion.obtener("ESC-00000001", DUENO).id());
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> validacion.obtener("ESC-00000001", OTRO_STAFF));
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> validacion.obtener("ESC-00000001", AJENO));
    }

    @Test
    void elDuenoAsignaUnaCuentaPorSuEmailEnMinusculasYRepetirloDevuelveLaMisma() {
        when(puertas.buscar(EVENTO, "otra.puerta@palco.test")).thenReturn(Optional.empty());

        PuertaAsignadaDto asignada = validacion.asignar(EVENTO, "  Otra.Puerta@Palco.test ", DUENO);

        assertEquals(new PuertaAsignadaDto(EVENTO, "otra.puerta@palco.test", "U-org", "2026-11-20T21:03:12Z"), asignada);
        ArgumentCaptor<PuertaAsignada> guardada = ArgumentCaptor.forClass(PuertaAsignada.class);
        verify(puertas).guardar(guardada.capture());
        assertEquals("otra.puerta@palco.test", guardada.getValue().getStaffEmail());

        PuertaAsignada existente = new PuertaAsignada(EVENTO, "otra.puerta@palco.test", "U-org",
                LocalDateTime.of(2026, 11, 1, 10, 0));
        when(puertas.buscar(EVENTO, "otra.puerta@palco.test")).thenReturn(Optional.of(existente));
        assertEquals("2026-11-01T10:00:00Z", validacion.asignar(EVENTO, "otra.puerta@palco.test", DUENO).asignada());
        verify(puertas).guardar(any());
    }

    @Test
    void dosAsignacionesIgualesALaVezDevuelvenLaQueSeGuardo() {
        PuertaAsignada laOtra = new PuertaAsignada(EVENTO, "otra.puerta@palco.test", "U-org",
                LocalDateTime.of(2026, 11, 20, 21, 3, 12));
        when(puertas.buscar(EVENTO, "otra.puerta@palco.test")).thenReturn(Optional.empty(), Optional.of(laOtra));
        org.mockito.Mockito.doThrow(new ErroresDeNegocio.Conflicto("puerta-ya-asignada", "ya")).when(puertas)
                .guardar(any());

        assertEquals("otra.puerta@palco.test", validacion.asignar(EVENTO, "otra.puerta@palco.test", DUENO).staffEmail());
    }

    @Test
    void otroOrganizadorNoVeNiCambiaLaPuertaDeUnEventoAjeno() {
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> validacion.equipoDePuerta(EVENTO, AJENO));
        assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> validacion.asignar(EVENTO, "otra.puerta@palco.test", AJENO));
        assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> validacion.quitar(EVENTO, "puerta@palco.test", AJENO));
        verify(puertas, never()).guardar(any());
        verify(puertas, never()).quitar(anyString(), anyString());
    }

    @Test
    void elDuenoVeElEquipoYQuitaUnaCuenta() {
        when(puertas.delEvento(EVENTO)).thenReturn(List.of(new PuertaAsignada(EVENTO, "puerta@palco.test", "U-org",
                LocalDateTime.of(2026, 11, 1, 10, 0))));

        assertEquals(List.of("puerta@palco.test"),
                validacion.equipoDePuerta(EVENTO, DUENO).stream().map(PuertaAsignadaDto::staffEmail).toList());
        validacion.quitar(EVENTO, "Puerta@Palco.test", DUENO);
        verify(puertas).quitar(EVENTO, "puerta@palco.test");
    }

    @Test
    void elStaffVeSusPuertas() {
        when(puertas.deLaCuenta("puerta@palco.test")).thenReturn(List.of(new PuertaAsignada(EVENTO,
                "puerta@palco.test", "U-org", LocalDateTime.of(2026, 11, 1, 10, 0))));

        assertEquals(List.of(EVENTO), validacion.misPuertas(STAFF).stream().map(PuertaAsignadaDto::eventoId).toList());
        assertEquals(List.of(), validacion.misPuertas(OTRO_STAFF));
    }
}
