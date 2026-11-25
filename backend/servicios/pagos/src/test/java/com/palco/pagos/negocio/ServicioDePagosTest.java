package com.palco.pagos.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.pagos.api.Dtos.NuevoPagoDto;
import com.palco.pagos.api.Dtos.PagoDto;
import com.palco.pagos.api.Dtos.ReembolsoDto;
import com.palco.pagos.datos.PagoRepositoryEnMemoria;
import com.palco.pagos.dominio.EstadoPago;
import com.palco.pagos.dominio.Pago;
import com.palco.pagos.pasarela.PagosFake;
import com.palco.pagos.pasarela.ResultadoPasarela;

/**
 * La idempotencia y el guardado de ServicioDePagos, sin servidor ni base: el
 * repositorio es uno en memoria que rechaza las claves repetidas como la base,
 * y la pasarela es la simulada de verdad, con la cuenta de los cobros.
 */
class ServicioDePagosTest {

    private static final String CLAVE = "5d3c1f0e-8a4b-4c7d-9e2f-1a2b3c4d5e6f";
    private static final String OTRA_CLAVE = "0b9a8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d";

    private final PagoRepositoryEnMemoria repositorio = new PagoRepositoryEnMemoria();
    private final PasarelaContada pasarela = new PasarelaContada();
    private final ServicioDePagos servicio = new ServicioDePagos();

    @BeforeEach
    void armarElServicio() {
        servicio.pagos = repositorio;
        servicio.pasarela = pasarela;
        // El servidor corre en hora argentina: el reloj fijo comprueba que igual se guarda en UTC.
        servicio.reloj = Clock.fixed(Instant.parse("2026-10-16T21:30:07.900Z"), ZoneOffset.ofHours(-3));
    }

    @Test
    void unPagoAprobadoSeGuardaConSuIdYLaPasarelaQueLoCobro() {
        PagoDto pago = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE);

        assertTrue(pago.pagoId().matches("P-[0-9a-f]{8}"), pago.pagoId());
        assertEquals("V-3f9a1c2b", pago.ventaId());
        assertEquals(60500, pago.monto());
        assertEquals("APROBADO", pago.estado());
        assertNull(pago.motivo());
        assertEquals("fake-" + CLAVE, pago.idExterno());

        Pago guardado = repositorio.buscarPorId(pago.pagoId()).orElseThrow();
        assertEquals("fake", guardado.getProveedor());
        assertEquals("tarjeta", guardado.getMedioDePago());
        assertEquals(CLAVE, guardado.getClaveIdempotencia());
        assertEquals(LocalDateTime.of(2026, 10, 16, 21, 30, 7), guardado.getCreado());
        assertEquals(guardado.getCreado(), guardado.getActualizado());
    }

    @Test
    void unRechazoEsUnResultadoYTambienSeGuarda() {
        PagoDto pago = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_rechazado"), CLAVE);

        assertEquals("RECHAZADO", pago.estado());
        assertEquals("fondos_insuficientes", pago.motivo());
        assertEquals(1, repositorio.cantidad());
    }

    @Test
    void laMismaClaveDevuelveElMismoPagoSinVolverACobrar() {
        PagoDto primero = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE);
        PagoDto repetido = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE);

        assertEquals(primero, repetido);
        assertEquals(1, pasarela.cobros);
        assertEquals(1, repositorio.cantidad());
    }

    @Test
    void unRechazoRepetidoTampocoVuelveALaPasarela() {
        PagoDto primero = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_rechazado"), CLAVE);
        PagoDto repetido = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_rechazado"), CLAVE);

        assertEquals(primero, repetido);
        assertEquals(1, pasarela.cobros);
    }

    @Test
    void trasUnRechazoLaMismaClaveDevuelveEseRechazoAunqueLlegueOtroToken() {
        // El token no se guarda, así que no distingue un pedido de otro: con la misma clave,
        // venta y monto vale el pago guardado. Corregir la tarjeta no alcanza: hace falta otra clave.
        PagoDto rechazado = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_rechazado"), CLAVE);
        PagoDto conOtroToken = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE);

        assertEquals("RECHAZADO", conOtroToken.estado());
        assertEquals(rechazado, conOtroToken);
        assertEquals(1, pasarela.cobros);
        assertEquals(1, repositorio.cantidad());
    }

    @Test
    void laMismaClaveConOtroMontoEsUnConflicto() {
        servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE);

        ErroresDeNegocio.Conflicto conflicto = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.procesarPago(pedido("V-3f9a1c2b", 99000, "tok_test"), CLAVE));

        assertEquals("clave-reutilizada", conflicto.tipo());
        assertEquals(1, pasarela.cobros);
        assertEquals(1, repositorio.cantidad());
    }

    @Test
    void laMismaClaveConOtraVentaEsUnConflicto() {
        servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE);

        ErroresDeNegocio.Conflicto conflicto = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.procesarPago(pedido("V-otraventa", 60500, "tok_test"), CLAVE));

        assertEquals("clave-reutilizada", conflicto.tipo());
        assertEquals(1, pasarela.cobros);
    }

    @Test
    void elConflictoPorClaveReutilizadaNoCobraNiGuardaNada() {
        // Para quien llama, ese 409 significa "esto no se cobró": la clave sigue siendo del primer
        // pedido (acá, uno rechazado) y las ventas que la reutilizan no llegan a la pasarela.
        PagoDto rechazado = servicio.procesarPago(pedido("V-ventaA00", 60500, "tok_rechazado"), CLAVE);

        assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.procesarPago(pedido("V-ventaB00", 60500, "tok_test"), CLAVE));
        assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.procesarPago(pedido("V-ventaC00", 60500, "tok_test"), CLAVE));

        assertEquals(1, pasarela.cobros);
        assertEquals(1, repositorio.cantidad());
        assertEquals(rechazado, servicio.procesarPago(pedido("V-ventaA00", 60500, "tok_rechazado"), CLAVE));
    }

    @Test
    void conOtraClaveLaMismaVentaSeCobraDeNuevo() {
        // Es lo que pasa cuando el comprador reintenta después de un rechazo: otro intento, otra clave.
        // La clave nueva la genera el cliente (el checkout); Ventas reenvía la que recibe.
        PagoDto rechazado = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_rechazado"), CLAVE);
        PagoDto aprobado = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), OTRA_CLAVE);

        assertEquals("RECHAZADO", rechazado.estado());
        assertEquals("APROBADO", aprobado.estado());
        assertEquals(2, pasarela.cobros);
        assertEquals(2, repositorio.cantidad());
    }

    @Test
    void siLaPasarelaNoRespondeNoSeGuardaNadaYSePuedeReintentarConLaMismaClave() {
        assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_sin_respuesta"), CLAVE));
        assertEquals(0, repositorio.cantidad());

        PagoDto reintento = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE);

        assertEquals("APROBADO", reintento.estado());
        assertEquals(1, repositorio.cantidad());
    }

    @Test
    void siDosPedidosIgualesLleganALaVezSeDevuelveElPagoQueGanoAlGuardar() {
        // Mientras este pedido espera a la pasarela, otro igual termina y guarda primero.
        pasarela.mientrasCobra = () -> repositorio.guardar(pagoDeOtroPedido("P-ganador0", "V-3f9a1c2b", 60500));

        PagoDto pago = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE);

        assertEquals("P-ganador0", pago.pagoId());
        assertEquals(1, repositorio.cantidad());
    }

    @Test
    void siElQueGanoAlGuardarEraOtroPedidoEsUnConflicto() {
        pasarela.mientrasCobra = () -> repositorio.guardar(pagoDeOtroPedido("P-ganador0", "V-3f9a1c2b", 99000));

        ErroresDeNegocio.Conflicto conflicto = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE));

        assertEquals("clave-reutilizada", conflicto.tipo());
        assertEquals(1, repositorio.cantidad());
    }

    @Test
    void obtenerDevuelveElPagoGuardado() {
        PagoDto creado = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE);

        assertEquals(creado, servicio.obtener(creado.pagoId()));
    }

    @Test
    void obtenerUnPagoQueNoExisteEsNoEncontrado() {
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> servicio.obtener("P-noexiste"));
    }

    // ---- La consulta por clave, que usa la conciliación de Ventas ----

    @Test
    void buscarPorClaveDevuelveElPagoAprobadoQueSeGuardoConEsaClave() {
        PagoDto aprobado = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE);

        assertEquals(aprobado, servicio.buscarPorClave(CLAVE));
        // Consultar no cobra: la pasarela se llamó una sola vez, al procesar el pago.
        assertEquals(1, pasarela.cobros);
    }

    @Test
    void buscarPorClaveTambienDevuelveUnRechazo() {
        // Para la conciliación, un rechazo guardado es una respuesta: esa clave no cobró.
        servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_rechazado"), CLAVE);

        PagoDto encontrado = servicio.buscarPorClave(CLAVE);

        assertEquals("RECHAZADO", encontrado.estado());
        assertEquals("fondos_insuficientes", encontrado.motivo());
    }

    @Test
    void buscarPorClaveDevuelveElPagoDeEsaClaveYNoElDeOtraDeLaMismaVenta() {
        servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_rechazado"), CLAVE);
        PagoDto aprobado = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), OTRA_CLAVE);

        assertEquals(aprobado, servicio.buscarPorClave(OTRA_CLAVE));
        assertEquals("RECHAZADO", servicio.buscarPorClave(CLAVE).estado());
    }

    @Test
    void conUnaClaveQueNoCobroBuscarPorClaveEsNoEncontrado() {
        ErroresDeNegocio.NoEncontrado error = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> servicio.buscarPorClave(CLAVE));

        assertEquals("no-encontrado", error.tipo());
        assertEquals(0, pasarela.cobros);
    }

    @Test
    void siLaPasarelaNoRespondioLaClaveSigueSinPago() {
        // Es el caso que deja una venta sin resultado: Pagos no guardó nada, y la consulta lo dice.
        assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_sin_respuesta"), CLAVE));

        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> servicio.buscarPorClave(CLAVE));
    }

    // ---------------------------------------------------------------------------------------------

    private static NuevoPagoDto pedido(String ventaId, long monto, String token) {
        return new NuevoPagoDto(ventaId, monto, "tarjeta", token);
    }

    /** El pago que guardó el otro pedido simultáneo: misma clave y mismo id externo. */
    private static Pago pagoDeOtroPedido(String id, String ventaId, long monto) {
        return new Pago(id, ventaId, monto, "tarjeta", "fake", EstadoPago.APROBADO, null, CLAVE, "fake-" + CLAVE,
                LocalDateTime.of(2026, 10, 16, 21, 30, 7));
    }

    /**
     * La pasarela simulada de verdad, más dos cosas para el test: cuenta los
     * cobros y deja ejecutar algo mientras "contesta", que es el momento en
     * que otro pedido puede adelantarse.
     */
    // ---- Reembolsos (L2) ----

    @Test
    void unPagoAprobadoSeDevuelveYQuedaReembolsadoConElMotivoYLaHora() {
        PagoDto cobrado = servicio.procesarPago(pedido("R-3f9a1c2b", 60500, "tok_test"), CLAVE);

        PagoDto devuelto = servicio.reembolsar(cobrado.pagoId(),
                new ReembolsoDto("R-3f9a1c2b", "  sin entrada  ", null));

        assertEquals("REEMBOLSADO", devuelto.estado());
        assertEquals(cobrado.pagoId(), devuelto.pagoId());
        assertEquals(60500, devuelto.monto());
        assertEquals(1, pasarela.reembolsos);
        assertEquals("reembolso:" + cobrado.pagoId(), pasarela.ultimaClaveDeReembolso);
        Pago guardado = repositorio.buscarPorId(cobrado.pagoId()).orElseThrow();
        assertEquals("sin entrada", guardado.getMotivoReembolso());
        assertEquals(LocalDateTime.of(2026, 10, 16, 21, 30, 7), guardado.getReembolsado());
    }

    @Test
    void devolverDosVecesElMismoPagoLePideUnaSolaVezALaPasarela() {
        PagoDto cobrado = servicio.procesarPago(pedido("R-3f9a1c2b", 60500, "tok_test"), CLAVE);
        ReembolsoDto pedido = new ReembolsoDto("R-3f9a1c2b", "sin entrada", null);

        PagoDto primero = servicio.reembolsar(cobrado.pagoId(), pedido);
        PagoDto segundo = servicio.reembolsar(cobrado.pagoId(), pedido);

        assertEquals(primero, segundo);
        assertEquals(1, pasarela.reembolsos);
        // Y cobrar de nuevo con la misma clave devuelve el pago ya reembolsado, sin cobrar.
        assertEquals("REEMBOLSADO", servicio.procesarPago(pedido("R-3f9a1c2b", 60500, "tok_test"), CLAVE).estado());
        assertEquals(1, pasarela.cobros);
    }

    @Test
    void siOtroPedidoLoDevolvioMientrasTantoValeEseReembolso() {
        PagoDto cobrado = servicio.procesarPago(pedido("R-3f9a1c2b", 60500, "tok_test"), CLAVE);
        // El otro pedido termina mientras este espera a la pasarela: cuando este quiere marcarlo, ya está.
        pasarela.mientrasReembolsa = () -> repositorio.marcarReembolsado(cobrado.pagoId(), 60500, "el otro",
                LocalDateTime.of(2026, 10, 16, 21, 30, 0));

        PagoDto devuelto = servicio.reembolsar(cobrado.pagoId(), new ReembolsoDto("R-3f9a1c2b", "este", null));

        assertEquals("REEMBOLSADO", devuelto.estado());
        assertEquals("el otro", repositorio.buscarPorId(cobrado.pagoId()).orElseThrow().getMotivoReembolso());
    }

    @Test
    void unRechazadoODeOtraVentaNoSeDevuelveYNoLlegaALaPasarela() {
        PagoDto rechazado = servicio.procesarPago(pedido("R-3f9a1c2b", 60500, "tok_rechazado"), CLAVE);
        PagoDto aprobado = servicio.procesarPago(pedido("R-0b9a8c7d", 60500, "tok_test"), OTRA_CLAVE);

        ErroresDeNegocio.Conflicto noAprobado = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.reembolsar(rechazado.pagoId(), new ReembolsoDto("R-3f9a1c2b", "x", null)));
        ErroresDeNegocio.Conflicto ajeno = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.reembolsar(aprobado.pagoId(), new ReembolsoDto("R-3f9a1c2b", "x", null)));

        assertEquals(ServicioDePagos.PAGO_NO_REEMBOLSABLE, noAprobado.tipo());
        assertEquals(ServicioDePagos.PAGO_NO_REEMBOLSABLE, ajeno.tipo());
        assertEquals(0, pasarela.reembolsos);
        assertEquals("APROBADO", servicio.obtener(aprobado.pagoId()).estado());
        assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> servicio.reembolsar("P-noexiste", new ReembolsoDto("R-3f9a1c2b", "x", null)));
    }

    @Test
    void siLaPasarelaNoContestaAlDevolverElPagoSigueAprobadoYSePuedeRepetir() {
        PagoDto cobrado = servicio.procesarPago(pedido("R-3f9a1c2b", 60500, "tok_test"), CLAVE);
        pasarela.reembolsoSinRespuesta = true;

        assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> servicio.reembolsar(cobrado.pagoId(), new ReembolsoDto("R-3f9a1c2b", "sin entrada", null)));
        assertEquals("APROBADO", servicio.obtener(cobrado.pagoId()).estado());

        pasarela.reembolsoSinRespuesta = false;
        assertEquals("REEMBOLSADO",
                servicio.reembolsar(cobrado.pagoId(), new ReembolsoDto("R-3f9a1c2b", "sin entrada", null)).estado());
    }

    // ---- Reembolso de una parte: el evento se canceló y el comprador ya había revendido una entrada ----

    @Test
    void sinMontoSeDevuelveElPagoEnteroYQuedaAnotadoCuanto() {
        PagoDto cobrado = servicio.procesarPago(pedido("R-3f9a1c2b", 60500, "tok_test"), CLAVE);

        PagoDto devuelto = servicio.reembolsar(cobrado.pagoId(), new ReembolsoDto("R-3f9a1c2b", "sin entrada", null));

        assertEquals(60500, devuelto.montoReembolsado());
        assertEquals(60500, pasarela.ultimoMontoDeReembolso);
        assertEquals(60500, repositorio.buscarPorId(cobrado.pagoId()).orElseThrow().getMontoReembolsado());
    }

    @Test
    void conMontoSeDevuelveSoloEsaParteYRepetirloNoDevuelveDosVeces() {
        // Dos entradas de 27.500 más el cargo: 60.500. Una se revendió; se devuelve la otra con su cargo.
        PagoDto cobrado = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE);
        ReembolsoDto pedido = new ReembolsoDto("V-3f9a1c2b", "Evento cancelado", 30250L);

        PagoDto devuelto = servicio.reembolsar(cobrado.pagoId(), pedido);
        PagoDto repetido = servicio.reembolsar(cobrado.pagoId(), pedido);

        assertEquals("REEMBOLSADO", devuelto.estado());
        assertEquals(60500, devuelto.monto());
        assertEquals(30250, devuelto.montoReembolsado());
        assertEquals(devuelto, repetido);
        assertEquals(1, pasarela.reembolsos);
        assertEquals(30250, pasarela.ultimoMontoDeReembolso);
    }

    @Test
    void unPagoYaDevueltoNoSeDevuelveDeNuevoPorOtroMonto() {
        PagoDto cobrado = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE);
        servicio.reembolsar(cobrado.pagoId(), new ReembolsoDto("V-3f9a1c2b", "Evento cancelado", 30250L));

        // Otro monto no es un reintento sino otro pedido, y un pago se devuelve una sola vez.
        ErroresDeNegocio.Conflicto entero = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.reembolsar(cobrado.pagoId(), new ReembolsoDto("V-3f9a1c2b", "Evento cancelado", null)));

        assertEquals(ServicioDePagos.PAGO_NO_REEMBOLSABLE, entero.tipo());
        assertEquals(1, pasarela.reembolsos);
        assertEquals(30250, servicio.obtener(cobrado.pagoId()).montoReembolsado());
    }

    @Test
    void noSeDevuelveMasDeLoQueSeCobro() {
        PagoDto cobrado = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE);

        ErroresDeNegocio.Conflicto demas = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.reembolsar(cobrado.pagoId(),
                        new ReembolsoDto("V-3f9a1c2b", "Evento cancelado", 60501L)));

        assertEquals(ServicioDePagos.PAGO_NO_REEMBOLSABLE, demas.tipo());
        assertEquals(0, pasarela.reembolsos);
        PagoDto sigue = servicio.obtener(cobrado.pagoId());
        assertEquals("APROBADO", sigue.estado());
        assertNull(sigue.montoReembolsado());
    }

    // ---- Caracteres de control: PostgreSQL no acepta el carácter nulo (U+0000) en un texto ----

    /** La clave con la que el contrato (Schemathesis) obtuvo un 500 en GET /api/v1/pagos: trae el carácter nulo. */
    private static final String CLAVE_CON_NULO =
            URLDecoder.decode("%09%F3%9E%AF%82%C2%BC%00%F1%90%9A%81%27", StandardCharsets.UTF_8);

    @Test
    void buscarConLaClaveDelContratoEsUn400SinConsultarLaBase() {
        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.buscarPorClave(CLAVE_CON_NULO));

        assertEquals(Set.of("claveIdempotencia"), error.campos().keySet());
    }

    @Test
    void unCobroConUnCaracterDeControlEsUn400SinCobrarNiGuardar() {
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE_CON_NULO));
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.procesarPago(pedido("V-\u0000", 60500, "tok_test"), CLAVE));
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.procesarPago(new NuevoPagoDto("V-3f9a1c2b", 60500, "tarjeta\u0000", "tok_test"), CLAVE));

        assertEquals(0, pasarela.cobros);
        assertEquals(0, repositorio.cantidad());
    }

    @Test
    void unIdDePagoConElCaracterNuloEsUn404SinConsultarLaBase() {
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> servicio.obtener("P-\u0000"));
        assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> servicio.reembolsar("P-\u0000", new ReembolsoDto("V-3f9a1c2b", "Evento cancelado", null)));
    }

    @Test
    void unMotivoConElCaracterNuloEsUn400SinDevolverEnLaPasarela() {
        PagoDto cobrado = servicio.procesarPago(pedido("V-3f9a1c2b", 60500, "tok_test"), CLAVE);

        // Sin el control, la pasarela devolvía la plata y después fallaba el UPDATE: el pago seguía APROBADO.
        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.reembolsar(cobrado.pagoId(), new ReembolsoDto("V-3f9a1c2b", "Evento\u0000cancelado", null)));

        assertEquals(Set.of("motivo"), error.campos().keySet());
        assertEquals(0, pasarela.reembolsos);
        assertEquals("APROBADO", servicio.obtener(cobrado.pagoId()).estado());
    }

    private static class PasarelaContada extends PagosFake {
        int cobros;
        int reembolsos;
        String ultimaClaveDeReembolso;
        long ultimoMontoDeReembolso;
        boolean reembolsoSinRespuesta;
        Runnable mientrasCobra = () -> { };
        Runnable mientrasReembolsa = () -> { };

        @Override
        public void reembolsar(String idExterno, long monto, String claveIdempotencia) {
            if (reembolsoSinRespuesta) {
                throw new ErroresDeNegocio.SinRespuesta("La pasarela no respondió.");
            }
            reembolsos++;
            ultimaClaveDeReembolso = claveIdempotencia;
            ultimoMontoDeReembolso = monto;
            mientrasReembolsa.run();
        }

        @Override
        public ResultadoPasarela cobrar(String ventaId, long monto, String medioDePago, String token,
                String claveIdempotencia) {
            cobros++;
            ResultadoPasarela resultado = super.cobrar(ventaId, monto, medioDePago, token, claveIdempotencia);
            mientrasCobra.run();
            return resultado;
        }
    }
}
