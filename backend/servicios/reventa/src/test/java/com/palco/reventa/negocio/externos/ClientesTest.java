package com.palco.reventa.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.reventa.negocio.TiposDeProblema;
import com.palco.reventa.negocio.externos.ClienteRest.Respuesta;

/** Cómo traducen los clientes las respuestas de Pagos, Ventas y Eventos. La llamada HTTP es un doble. */
class ClientesTest {

    private ClienteRest rest;

    @BeforeEach
    void armar() {
        rest = mock(ClienteRest.class);
    }

    private ClienteDePagos pagos() {
        ClienteDePagos cliente = new ClienteDePagos();
        cliente.rest = rest;
        return cliente;
    }

    private void pagosResponde(Respuesta primera, Respuesta... siguientes) {
        when(rest.llamar(eq("POST"), eq(ClienteDePagos.RUTA), anyMap(), anyMap(), any()))
                .thenReturn(primera, siguientes);
    }

    @Test
    void elCobroViajaConLaClaveDerivadaYSeLeeAprobado() {
        pagosResponde(new Respuesta(201,
                "{\"pagoId\":\"P-1\",\"ventaId\":\"R-1\",\"monto\":60500,\"estado\":\"APROBADO\"}"));

        ClienteDePagos.Cobro cobro = pagos().cobrar("R-1", 60_500, "tarjeta", "tok", "clave");

        assertTrue(cobro.aprobado());
        assertEquals("P-1", cobro.pagoId());
        verify(rest).llamar(eq("POST"), eq(ClienteDePagos.RUTA), eq(Map.of()),
                eq(Map.of("Idempotency-Key", "R-1:clave")), any());
    }

    @Test
    void unPagoDuplicadoSeRepiteUnaVezYUnaClaveReutilizadaEsRechazo() {
        pagosResponde(new Respuesta(409, "{\"type\":\"/problemas/pago-duplicado\"}"),
                new Respuesta(201, "{\"pagoId\":\"P-1\",\"estado\":\"RECHAZADO\",\"motivo\":\"fondos\"}"));
        ClienteDePagos.Cobro cobro = pagos().cobrar("R-1", 60_500, "tarjeta", "tok", "clave");
        assertFalse(cobro.aprobado());
        verify(rest, times(2)).llamar(eq("POST"), eq(ClienteDePagos.RUTA), anyMap(), anyMap(), any());

        armar();
        pagosResponde(new Respuesta(409, "{\"type\":\"/problemas/clave-reutilizada\"}"));
        assertFalse(pagos().cobrar("R-1", 60_500, "tarjeta", "tok", "clave").aprobado());
    }

    @Test
    void unCuatroCientosCuatroEsPedidoNoEnviadoYUnQuinientosEsSinRespuesta() {
        pagosResponde(new Respuesta(404, null));
        assertThrows(PedidoNoEnviado.class, () -> pagos().cobrar("R-1", 1, "tarjeta", "tok", "clave"));

        armar();
        pagosResponde(new Respuesta(500, null));
        ErroresDeNegocio.SinRespuesta error = assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> pagos().cobrar("R-1", 1, "tarjeta", "tok", "clave"));
        assertFalse(error instanceof PedidoNoEnviado);
    }

    @Test
    void ventasTraduceNoEncontradoYConflicto() {
        ClienteDeVentas ventas = new ClienteDeVentas();
        ventas.rest = rest;
        when(rest.llamar(eq("POST"), eq(ClienteDeVentas.RAIZ + "/publicaciones"), anyMap(), anyMap(), any()))
                .thenReturn(new Respuesta(404, "{\"type\":\"/problemas/no-encontrado\"}"),
                        new Respuesta(409, "{\"type\":\"/problemas/entrada-no-disponible\"}"),
                        new Respuesta(200, "{\"entradaId\":\"T-1\",\"eventoId\":\"ev-1\",\"precioPagado\":50000,"
                                + "\"estado\":\"PUBLICADA\"}"));

        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> ventas.publicar("T-1", "U-1"));
        ErroresDeNegocio.Conflicto conflicto = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> ventas.publicar("T-1", "U-1"));
        assertEquals(TiposDeProblema.ENTRADA_NO_DISPONIBLE, conflicto.tipo());
        assertEquals(50_000, ventas.publicar("T-1", "U-1").precioPagado());
    }

    @Test
    void eventosBuscaLaFechaDelEventoEnElCatalogo() {
        ClienteDeEventos eventos = new ClienteDeEventos();
        eventos.rest = rest;
        when(rest.llamar(eq("GET"), eq(ClienteDeEventos.RUTA), anyMap(), anyMap(), any()))
                .thenReturn(new Respuesta(200, "[{\"id\":\"ev-1\",\"slug\":\"a\",\"fechaISO\":\"2026-11-20T21:00:00\"},"
                        + "{\"id\":\"ev-2\",\"fechaISO\":\"2026-12-01T20:30:00\"}]"));

        assertEquals(LocalDateTime.of(2026, 12, 1, 20, 30), eventos.fechaDelEvento("ev-2"));
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> eventos.fechaDelEvento("ev-9"));
    }
}
