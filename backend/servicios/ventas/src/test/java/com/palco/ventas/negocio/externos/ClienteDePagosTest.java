package com.palco.ventas.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.bind.JsonbBuilder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.seguridad.TokenDeServicio;
import com.palco.ventas.negocio.externos.ClienteDePagos.Pedido;

/**
 * Qué hace Ventas con cada respuesta de Pagos. No necesita servidor: la
 * llamada HTTP se reemplaza por respuestas preparadas, y el token de servicio
 * es un doble.
 */
class ClienteDePagosTest {

    private static final String CLAVE = "5d3c1f0e-8a4b-4c7d-9e2f-1a2b3c4d5e6f";

    /** La que le llega a Pagos: el id de la venta adelante de la clave del comprador. */
    private static final String CLAVE_EN_PAGOS = "V-3f9a1c2b:" + CLAVE;

    private static final String APROBADO_JSON = """
            {"estado":"APROBADO","idExterno":"fake-5d3c1f0e","monto":60500,"motivo":null,
             "pagoId":"P-598c58a0","ventaId":"V-3f9a1c2b"}""";

    private static final String RECHAZADO_JSON = """
            {"estado":"RECHAZADO","idExterno":"fake-5d3c1f0e","monto":60500,"motivo":"fondos_insuficientes",
             "pagoId":"P-bfb417fa","ventaId":"V-3f9a1c2b"}""";

    /** El 409 de Pagos cuando la clave ya se usó con otra venta u otro monto: no cobró. */
    private static final String CLAVE_REUTILIZADA_JSON = """
            {"type":"/problemas/clave-reutilizada","title":"Conflicto","status":409,
             "detail":"Esa clave de idempotencia ya se usó para otra venta o con otro monto."}""";

    /**
     * El 409 de Pagos cuando dos pedidos con la misma clave chocaron al guardar y
     * no pudo releer el pago que ganó: ese otro pedido sí pudo haber cobrado.
     */
    private static final String PAGO_DUPLICADO_JSON = """
            {"type":"/problemas/pago-duplicado","title":"Conflicto","status":409,
             "detail":"Ya existe un pago con esos datos."}""";

    /** Una llamada a Pagos, como la recibió el doble. */
    private record Llamada(String cuerpo, String claveIdempotencia, String tokenDeServicio) {
    }

    /** En vez de llamar a Pagos entrega, en orden, las respuestas que le cargó el test, y anota cada llamada. */
    private static class PagosSinRed extends ClienteDePagos {
        final Deque<Respuesta> respuestas = new ArrayDeque<>();
        final List<Llamada> llamadas = new ArrayList<>();
        boolean caido;

        void responde(int estado, String cuerpo) {
            respuestas.add(new Respuesta(estado, cuerpo));
        }

        @Override
        Respuesta enviar(String cuerpo, String claveIdempotencia, String tokenDeServicio) {
            llamadas.add(new Llamada(cuerpo, claveIdempotencia, tokenDeServicio));
            if (caido) {
                throw new ErroresDeNegocio.SinRespuesta("El servicio de pagos no responde. Probá de nuevo.");
            }
            return respuestas.removeFirst();
        }
    }

    private final TokenDeServicio token = mock(TokenDeServicio.class);
    private final PagosSinRed pagos = new PagosSinRed();

    @BeforeEach
    void armarElCliente() {
        pagos.tokenDeServicio = token;
        when(token.obtener()).thenReturn("token-1", "token-2");
    }

    private ResultadoDeCobro cobrar() {
        return pagos.cobrar("V-3f9a1c2b", 60_500, "tarjeta", "tok_test", CLAVE);
    }

    /**
     * Comprueba que el cobro termina en un {@code SinRespuesta} a secas, y no
     * en un {@link CobroNoRealizado}: ante la duda, la venta tiene que quedar
     * marcada con el intento de pago.
     */
    private void noSeSabeSiSeCobro() {
        ErroresDeNegocio.SinRespuesta error = assertThrows(ErroresDeNegocio.SinRespuesta.class, this::cobrar);
        assertEquals(ErroresDeNegocio.SinRespuesta.class, error.getClass());
    }

    @Test
    void unPagoAprobadoDevuelveElIdQueGuardoPagos() {
        pagos.responde(201, APROBADO_JSON);

        ResultadoDeCobro resultado = cobrar();

        assertTrue(resultado.aprobado());
        assertEquals("P-598c58a0", resultado.pagoId());
        assertNull(resultado.motivo());
    }

    @Test
    void mandaLaVentaElMontoYElMedioDePagoConElTokenDeServicioYLaClaveAtadaALaVenta() {
        pagos.responde(201, APROBADO_JSON);

        cobrar();

        assertEquals(1, pagos.llamadas.size());
        Llamada llamada = pagos.llamadas.get(0);
        // El cuerpo se vuelve a leer como JSON: así el test no depende del orden de los campos.
        assertEquals(new Pedido("V-3f9a1c2b", 60_500, "tarjeta", "tok_test"),
                JsonbBuilder.create().fromJson(llamada.cuerpo(), Pedido.class));
        assertEquals(CLAVE_EN_PAGOS, llamada.claveIdempotencia());
        assertEquals("token-1", llamada.tokenDeServicio());
    }

    @Test
    void unPagoRechazadoEsUnResultadoConSuMotivoYNoUnaExcepcion() {
        pagos.responde(201, RECHAZADO_JSON);

        ResultadoDeCobro resultado = cobrar();

        assertFalse(resultado.aprobado());
        assertEquals("RECHAZADO", resultado.estado());
        assertEquals("fondos_insuficientes", resultado.motivo());
        assertEquals("P-bfb417fa", resultado.pagoId());
    }

    @Test
    void siPagosNoRespondeEsSinRespuesta() {
        pagos.caido = true;

        noSeSabeSiSeCobro();
    }

    @Test
    void siLaPasarelaNoLeRespondioAPagosEsSinRespuesta() {
        pagos.responde(504, null);

        noSeSabeSiSeCobro();
    }

    @Test
    void cualquierOtroCodigoEsSinRespuesta() {
        pagos.responde(500, null); // Pagos falló por dentro: pudo ser después de cobrar
        noSeSabeSiSeCobro();

        pagos.responde(503, null);
        noSeSabeSiSeCobro();
    }

    @Test
    void siPagosFrenaElPedidoAntesDeCobrarEsCobroNoRealizado() {
        pagos.responde(404, null); // Pagos no está desplegado
        assertThrows(CobroNoRealizado.class, this::cobrar);

        pagos.responde(403, null); // el token no tiene el rol servicio
        assertThrows(CobroNoRealizado.class, this::cobrar);
    }

    @Test
    void unCobroNoRealizadoLeLlegaAlCompradorComoSinRespuesta() {
        pagos.responde(404, null);

        // Es un SinRespuesta: sale por el mismo 504, con el mismo tipo. No es un rechazo del medio de pago.
        ErroresDeNegocio.SinRespuesta error = assertThrows(ErroresDeNegocio.SinRespuesta.class, this::cobrar);

        assertEquals("sin-respuesta", error.tipo());
    }

    @Test
    void unDoscientosUnoQueNoTraeUnPagoConResultadoEsSinRespuesta() {
        pagos.responde(201, "<html>ok</html>");
        noSeSabeSiSeCobro();

        pagos.responde(201, "{\"estado\":\"APROBADO\"}"); // sin id no hay con qué confirmar la venta
        noSeSabeSiSeCobro();

        pagos.responde(201, "{\"pagoId\":\"P-598c58a0\",\"estado\":\"PENDIENTE\"}"); // todavía sin resultado
        noSeSabeSiSeCobro();
    }

    @Test
    void conUn401PideOtroTokenYReintentaUnaSolaVezConLaMismaClave() {
        pagos.responde(401, null);
        pagos.responde(201, APROBADO_JSON);

        ResultadoDeCobro resultado = cobrar();

        assertTrue(resultado.aprobado());
        verify(token).invalidar();
        assertEquals(2, pagos.llamadas.size());
        assertEquals("token-1", pagos.llamadas.get(0).tokenDeServicio());
        assertEquals("token-2", pagos.llamadas.get(1).tokenDeServicio());
        // El reintento es el mismo pedido: mismo cuerpo y misma clave, para que Pagos no cobre dos veces.
        assertEquals(pagos.llamadas.get(0).cuerpo(), pagos.llamadas.get(1).cuerpo());
        assertEquals(CLAVE_EN_PAGOS, pagos.llamadas.get(1).claveIdempotencia());
    }

    @Test
    void siElTokenNuevoTampocoSirveNoInsisteYEsCobroNoRealizado() {
        pagos.responde(401, null);
        pagos.responde(401, null);

        // Los dos 401 son anteriores al cobro: se sabe que no se cobró.
        assertThrows(CobroNoRealizado.class, this::cobrar);

        assertEquals(2, pagos.llamadas.size());
        verify(token, times(1)).invalidar();
    }

    @Test
    void siNoSeConsigueElTokenDeServicioNoSeLlamaAPagosYEsCobroNoRealizado() {
        when(token.obtener()).thenThrow(new ErroresDeNegocio.SinRespuesta("El servicio de usuarios no responde."));

        assertThrows(CobroNoRealizado.class, this::cobrar);

        assertTrue(pagos.llamadas.isEmpty());
        verify(token, never()).invalidar();
    }

    @Test
    void siDespuesDeUn401NoSeConsigueOtroTokenEsCobroNoRealizado() {
        pagos.responde(401, null);
        when(token.obtener()).thenReturn("token-1")
                .thenThrow(new ErroresDeNegocio.SinRespuesta("El servicio de usuarios no responde."));

        // El único pedido que salió volvió con 401, y el segundo no llegó a salir.
        assertThrows(CobroNoRealizado.class, this::cobrar);

        assertEquals(1, pagos.llamadas.size());
    }

    @Test
    void unaClaveQueYaSeUsoParaOtraVentaEsUnRechazoSinPago() {
        pagos.responde(409, CLAVE_REUTILIZADA_JSON);

        ResultadoDeCobro resultado = cobrar();

        // No es SinRespuesta: se sabe que no se cobró, y la compra tiene que poder pagarse con otra clave.
        assertFalse(resultado.aprobado());
        assertEquals("clave_reutilizada", resultado.motivo());
        assertNull(resultado.pagoId());
    }

    @Test
    void siPagosNoAceptaLosDatosEsUnRechazoSinPago() {
        pagos.responde(400, null);

        ResultadoDeCobro resultado = cobrar();

        assertFalse(resultado.aprobado());
        assertEquals("datos_invalidos", resultado.motivo());
        assertNull(resultado.pagoId());
    }

    @Test
    void unPagoDuplicadoNoEsUnRechazoSinoElCobroQueYaGuardoPagos() {
        // El reintento de un cobro que quedó sin respuesta choca con el pedido original, que sí cobró.
        pagos.responde(409, PAGO_DUPLICADO_JSON);
        pagos.responde(201, APROBADO_JSON);

        ResultadoDeCobro resultado = cobrar();

        // Con la misma clave, Pagos devuelve el pago guardado: la venta se confirma con ese pago.
        assertTrue(resultado.aprobado());
        assertEquals("P-598c58a0", resultado.pagoId());
        assertEquals(2, pagos.llamadas.size());
        assertEquals(pagos.llamadas.get(0).cuerpo(), pagos.llamadas.get(1).cuerpo());
        assertEquals(CLAVE_EN_PAGOS, pagos.llamadas.get(1).claveIdempotencia());
    }

    @Test
    void siElPagoDuplicadoSeRepiteNoSeSabeSiSeCobro() {
        pagos.responde(409, PAGO_DUPLICADO_JSON);
        pagos.responde(409, PAGO_DUPLICADO_JSON);

        // Nunca un rechazo: la venta tiene que quedar marcada para reintentar con la misma clave.
        noSeSabeSiSeCobro();
        assertEquals(2, pagos.llamadas.size());
    }

    @Test
    void unCuatrocientosNueveSinTipoConocidoNoSeLeeComoRechazo() {
        pagos.responde(409, null);
        pagos.responde(409, null);

        noSeSabeSiSeCobro();
    }

    /**
     * Imita la regla de Pagos sobre la clave: es única en todo el servicio,
     * no por comprador. La misma clave con la misma venta devuelve el pago
     * guardado; con otra venta, 409 {@code clave-reutilizada}.
     */
    private static class PagosConClaveUnica extends ClienteDePagos {
        final Map<String, String> ventaPorClave = new HashMap<>();
        final List<String> clavesRecibidas = new ArrayList<>();

        @Override
        Respuesta enviar(String cuerpo, String claveIdempotencia, String tokenDeServicio) {
            clavesRecibidas.add(claveIdempotencia);
            String ventaId = JsonbBuilder.create().fromJson(cuerpo, Pedido.class).ventaId();
            String duenia = ventaPorClave.putIfAbsent(claveIdempotencia, ventaId);
            if (duenia != null && !duenia.equals(ventaId)) {
                return new Respuesta(409, CLAVE_REUTILIZADA_JSON);
            }
            return new Respuesta(201, """
                    {"estado":"APROBADO","monto":60500,"pagoId":"P-%s","ventaId":"%s"}"""
                    .formatted(ventaId.substring(2), ventaId));
        }
    }

    @Test
    void dosCompradoresConLaMismaClaveNoChocanEnPagos() {
        // En Ventas la clave es única por comprador: dos personas pueden mandar la misma, cada una para su venta.
        PagosConClaveUnica pagosReal = new PagosConClaveUnica();
        pagosReal.tokenDeServicio = token;

        ResultadoDeCobro deAna = pagosReal.cobrar("V-aaaaaaaa", 60_500, "tarjeta", "tok_test", CLAVE);
        ResultadoDeCobro deBeto = pagosReal.cobrar("V-bbbbbbbb", 60_500, "tarjeta", "tok_test", CLAVE);

        assertTrue(deAna.aprobado());
        assertTrue(deBeto.aprobado(), "el segundo comprador no tiene que chocar con la clave del primero");
        assertEquals("P-bbbbbbbb", deBeto.pagoId());
        // A Pagos le llegan dos claves distintas, cada una atada a su venta.
        assertEquals(List.of("V-aaaaaaaa:" + CLAVE, "V-bbbbbbbb:" + CLAVE), pagosReal.clavesRecibidas);
    }

    @Test
    void elMismoCobroRepetidoLlegaAPagosConLaMismaClave() {
        // El reintento después de un 504: misma venta y misma clave. Pagos tiene que reconocerla.
        PagosConClaveUnica pagosReal = new PagosConClaveUnica();
        pagosReal.tokenDeServicio = token;

        ResultadoDeCobro primero = pagosReal.cobrar("V-aaaaaaaa", 60_500, "tarjeta", "tok_test", CLAVE);
        ResultadoDeCobro reintento = pagosReal.cobrar("V-aaaaaaaa", 60_500, "tarjeta", "tok_test", CLAVE);

        assertEquals(primero.pagoId(), reintento.pagoId());
        assertEquals(pagosReal.clavesRecibidas.get(0), pagosReal.clavesRecibidas.get(1));
        assertEquals(1, pagosReal.ventaPorClave.size());
    }
}
