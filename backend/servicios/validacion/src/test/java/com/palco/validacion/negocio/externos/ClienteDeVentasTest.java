package com.palco.validacion.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.seguridad.TokenDeServicio;
import com.palco.validacion.dominio.ResultadoEscaneo;
import com.palco.validacion.negocio.externos.ClienteDeVentas.Uso;

/**
 * Qué hace Validación con cada respuesta de Ventas. No necesita servidor: la
 * llamada HTTP se reemplaza por respuestas preparadas, y el token de servicio
 * es un doble.
 */
class ClienteDeVentasTest {

    private static final String ENTRADA_JSON = """
            {"id":"T-9c41d07e","ventaId":"V-3f9a1c2b","eventoId":"ev-1","sectorId":"sec-1-4","sector":"Platea B",
             "titular":{"nombre":"Ana Gómez","dni":"30123456"},"precioPagado":27500,"estado":"USADA",
             "codigoQr":"qr-1","emitida":"2026-10-19T21:00:07Z",
             "_links":{"self":{"href":"/api/v1/entradas/T-9c41d07e","method":"GET"}}}""";

    /** En vez de llamar a Ventas entrega, en orden, las respuestas que le cargó el test, y anota cada llamada. */
    private static class VentasSinRed extends ClienteDeVentas {
        final Deque<Respuesta> respuestas = new ArrayDeque<>();
        final List<String> llamadas = new ArrayList<>();

        void responde(int estado, String cuerpo) {
            respuestas.add(new Respuesta(estado, cuerpo));
        }

        @Override
        Respuesta enviar(String cuerpo, String token) {
            llamadas.add(cuerpo + " " + token);
            return respuestas.removeFirst();
        }
    }

    private final TokenDeServicio token = mock(TokenDeServicio.class);
    private final VentasSinRed ventas = new VentasSinRed();

    @BeforeEach
    void armarElCliente() {
        ventas.tokenDeServicio = token;
        when(token.obtener()).thenReturn("token-1", "token-2");
    }

    private static String problema(String tipo) {
        return "{\"type\":\"/problemas/" + tipo + "\",\"title\":\"Conflicto\",\"status\":409}";
    }

    @Test
    void unaEntradaUsadaVuelveValidaConElTitularYMandaElCodigoConElTokenDeServicio() {
        ventas.responde(200, ENTRADA_JSON);

        Uso uso = ventas.usar("qr-1", "ev-1");

        assertEquals(ResultadoEscaneo.VALIDA, uso.resultado());
        assertEquals("T-9c41d07e", uso.entrada().id());
        assertEquals("Ana Gómez", uso.entrada().titular().nombre());
        assertEquals("30123456", uso.entrada().titular().dni());
        assertEquals("Platea B", uso.entrada().sector());
        // El orden de los campos del JSON no importa: se comprueba que viajen los dos y el token.
        String llamada = ventas.llamadas.get(0);
        assertEquals(1, ventas.llamadas.size());
        assertEquals(true, llamada.contains("\"codigoQr\":\"qr-1\""), llamada);
        assertEquals(true, llamada.contains("\"eventoId\":\"ev-1\""), llamada);
        assertEquals(true, llamada.endsWith(" token-1"), llamada);
    }

    @Test
    void un404EsUnCodigoInexistente() {
        ventas.responde(404, null);

        Uso uso = ventas.usar("qr-1", "ev-1");

        assertEquals(ResultadoEscaneo.INEXISTENTE, uso.resultado());
        assertNull(uso.entrada());
    }

    @Test
    void cadaTipoDe409SeTraduceASuResultado() {
        ventas.responde(409, problema("entrada-ya-usada"));
        ventas.responde(409, problema("entrada-de-otro-evento"));
        ventas.responde(409, problema("entrada-no-habilitada"));

        assertEquals(ResultadoEscaneo.YA_USADA, ventas.usar("qr-1", "ev-1").resultado());
        assertEquals(ResultadoEscaneo.OTRO_EVENTO, ventas.usar("qr-1", "ev-1").resultado());
        assertEquals(ResultadoEscaneo.NO_HABILITADA, ventas.usar("qr-1", "ev-1").resultado());
    }

    @Test
    void un409QueNoSeConoceNoDejaEntrar() {
        ventas.responde(409, problema("otro-problema"));
        ventas.responde(409, "esto no es JSON");

        assertEquals(ResultadoEscaneo.NO_HABILITADA, ventas.usar("qr-1", "ev-1").resultado());
        assertEquals(ResultadoEscaneo.NO_HABILITADA, ventas.usar("qr-1", "ev-1").resultado());
    }

    @Test
    void conUn401PideOtroTokenYReintentaUnaSolaVez() {
        ventas.responde(401, null);
        ventas.responde(200, ENTRADA_JSON);

        Uso uso = ventas.usar("qr-1", "ev-1");

        assertEquals(ResultadoEscaneo.VALIDA, uso.resultado());
        verify(token).invalidar();
        assertEquals(2, ventas.llamadas.size());
        assertEquals(true, ventas.llamadas.get(1).endsWith(" token-2"));
    }

    @Test
    void dosVeces401EsSinRespuesta() {
        ventas.responde(401, null);
        ventas.responde(401, null);

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> ventas.usar("qr-1", "ev-1"));
        assertEquals(2, ventas.llamadas.size());
    }

    @Test
    void unErrorDeVentasEsSinRespuesta() {
        ventas.responde(500, null);

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> ventas.usar("qr-1", "ev-1"));
    }

    @Test
    void un200SinElTitularEsSinRespuesta() {
        ventas.responde(200, "{\"id\":\"T-9c41d07e\"}");
        ventas.responde(200, "no es JSON");

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> ventas.usar("qr-1", "ev-1"));
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> ventas.usar("qr-1", "ev-1"));
    }
}
