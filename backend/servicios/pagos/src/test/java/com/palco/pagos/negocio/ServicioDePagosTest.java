package com.palco.pagos.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.pagos.api.Dtos.NuevoPagoDto;
import com.palco.pagos.api.Dtos.PagoDto;
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
    private static class PasarelaContada extends PagosFake {
        int cobros;
        Runnable mientrasCobra = () -> { };

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
