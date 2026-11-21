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
import java.util.Optional;

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

    // ---- Consulta del cobro por clave (la conciliación) ----

    private void consultaResponde(Respuesta respuesta) {
        when(rest.llamar(eq("GET"), eq(ClienteDePagos.RUTA), anyMap(), anyMap(), any())).thenReturn(respuesta);
    }

    @Test
    void laConsultaPreguntaPorLaMismaClaveDelCobroYLeeElPago() {
        consultaResponde(new Respuesta(200,
                "{\"pagoId\":\"P-1\",\"ventaId\":\"R-1\",\"monto\":60500,\"estado\":\"APROBADO\"}"));

        Optional<ClienteDePagos.Cobro> cobro = pagos().buscarCobro("R-1", "clave");

        assertTrue(cobro.orElseThrow().aprobado());
        assertEquals("P-1", cobro.get().pagoId());
        // La misma clave que mandó cobrar(): así Pagos encuentra ese cobro y no otro.
        verify(rest).llamar(eq("GET"), eq(ClienteDePagos.RUTA), eq(Map.of("claveIdempotencia", "R-1:clave")),
                eq(Map.of()), eq(null));
    }

    @Test
    void unPagoRechazadoSeLeeComoRechazado() {
        consultaResponde(new Respuesta(200, "{\"pagoId\":\"P-1\",\"ventaId\":\"R-1\",\"estado\":\"RECHAZADO\","
                + "\"motivo\":\"fondos_insuficientes\"}"));

        ClienteDePagos.Cobro cobro = pagos().buscarCobro("R-1", "clave").orElseThrow();

        assertFalse(cobro.aprobado());
        assertEquals("fondos_insuficientes", cobro.motivo());
    }

    @Test
    void elNoEncontradoDePagosEsQueNoSeCobroYUnoSinSuErrorEsQueNoSeSabe() {
        consultaResponde(new Respuesta(404, "{\"type\":\"/problemas/no-encontrado\",\"status\":404}"));
        assertTrue(pagos().buscarCobro("R-1", "clave").isEmpty());

        // Un 404 del servidor (Pagos no está desplegado) no dice nada del cobro.
        armar();
        consultaResponde(new Respuesta(404, "<html>Not Found</html>"));
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> pagos().buscarCobro("R-1", "clave"));

        armar();
        consultaResponde(new Respuesta(500, null));
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> pagos().buscarCobro("R-1", "clave"));
    }

    @Test
    void unPagoDeOtraPublicacionOSinResultadoNoSirve() {
        consultaResponde(new Respuesta(200, "{\"pagoId\":\"P-1\",\"ventaId\":\"R-otra\",\"estado\":\"APROBADO\"}"));
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> pagos().buscarCobro("R-1", "clave"));

        armar();
        consultaResponde(new Respuesta(200, "{\"pagoId\":\"P-1\",\"ventaId\":\"R-1\",\"estado\":\"PENDIENTE\"}"));
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> pagos().buscarCobro("R-1", "clave"));
    }

    @Test
    void unPagoReembolsadoSeLeeComoNoAprobado() {
        consultaResponde(new Respuesta(200, "{\"pagoId\":\"P-1\",\"ventaId\":\"R-1\",\"estado\":\"REEMBOLSADO\"}"));

        ClienteDePagos.Cobro cobro = pagos().buscarCobro("R-1", "clave").orElseThrow();

        assertFalse(cobro.aprobado());
        assertEquals(ClienteDePagos.MOTIVO_REEMBOLSADO, cobro.motivo());
    }

    // ---- Reembolso (L2) ----

    private void reembolsoResponde(Respuesta respuesta) {
        when(rest.llamar(eq("POST"), eq(ClienteDePagos.RUTA + "/P-1/reembolso"), anyMap(), anyMap(), any()))
                .thenReturn(respuesta);
    }

    @Test
    void elReembolsoViajaConLaPublicacionYElMotivoYSeLeeHecho() {
        reembolsoResponde(new Respuesta(200, "{\"pagoId\":\"P-1\",\"ventaId\":\"R-1\",\"estado\":\"REEMBOLSADO\"}"));

        assertEquals(ClienteDePagos.Devolucion.HECHA, pagos().reembolsar("P-1", "R-1", "sin entrada"));
        verify(rest).llamar(eq("POST"), eq(ClienteDePagos.RUTA + "/P-1/reembolso"), eq(Map.of()), eq(Map.of()),
                eq("{\"motivo\":\"sin entrada\",\"ventaId\":\"R-1\"}"));
    }

    @Test
    void elReembolsoRechazadoPorPagosNoSeRepiteYLoDemasEsSinRespuesta() {
        reembolsoResponde(new Respuesta(409, "{\"type\":\"/problemas/pago-no-reembolsable\"}"));
        assertEquals(ClienteDePagos.Devolucion.RECHAZADA, pagos().reembolsar("P-1", "R-1", "x"));

        armar();
        reembolsoResponde(new Respuesta(404, "{\"type\":\"/problemas/no-encontrado\"}"));
        assertEquals(ClienteDePagos.Devolucion.RECHAZADA, pagos().reembolsar("P-1", "R-1", "x"));

        // Un 404 sin el error de Pagos (el WAR no está), un 500, un 200 que no es el reembolso o sin conexión: no se sabe.
        for (Respuesta respuesta : new Respuesta[] { new Respuesta(404, "<html>Not Found</html>"), new Respuesta(500, null),
                new Respuesta(200, "{\"pagoId\":\"P-1\",\"ventaId\":\"R-1\",\"estado\":\"APROBADO\"}"),
                new Respuesta(200, "{\"pagoId\":\"P-1\",\"ventaId\":\"R-otra\",\"estado\":\"REEMBOLSADO\"}") }) {
            armar();
            reembolsoResponde(respuesta);
            assertEquals(ClienteDePagos.Devolucion.SIN_RESPUESTA, pagos().reembolsar("P-1", "R-1", "x"),
                    respuesta.toString());
        }
        armar();
        when(rest.llamar(eq("POST"), eq(ClienteDePagos.RUTA + "/P-1/reembolso"), anyMap(), anyMap(), any()))
                .thenThrow(new ErroresDeNegocio.SinRespuesta("sin conexión"));
        assertEquals(ClienteDePagos.Devolucion.SIN_RESPUESTA, pagos().reembolsar("P-1", "R-1", "x"));
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
    void elControlDeLaCompraDevuelveElTopeYElDniConElTipoYElDetalleDeVentas() {
        ClienteDeVentas ventas = new ClienteDeVentas();
        ventas.rest = rest;
        String ruta = ClienteDeVentas.RAIZ + "/controles-de-compra";
        String tope = "Cada persona puede tener hasta 6 entradas por evento, sumando todas sus compras. Ya tenés 6:"
                + " no podés comprar más para este evento.";
        String dni = "El DNI 40111222 ya tiene una entrada para este evento. Cada persona puede tener una sola"
                + " entrada por evento.";
        when(rest.llamar(eq("POST"), eq(ruta), anyMap(), anyMap(), any()))
                .thenReturn(new Respuesta(204, null),
                        new Respuesta(409, "{\"type\":\"/problemas/tope-por-evento\",\"detail\":\"" + tope + "\"}"),
                        new Respuesta(409, "{\"type\":\"/problemas/dni-con-entrada\",\"detail\":\"" + dni + "\"}"),
                        new Respuesta(409, "{\"type\":\"/problemas/dni-con-entrada\"}"),
                        new Respuesta(404, "{\"type\":\"/problemas/no-encontrado\"}"),
                        new Respuesta(500, null));
        ClienteDeVentas.Titular titular = new ClienteDeVentas.Titular("Bruno Díaz", "40111222");

        // 204: puede comprar.
        ventas.controlarCompra("T-1", "U-vendedor", "U-comprador", titular);

        // 409 de una regla: el mismo tipo y el mismo texto que da Ventas.
        ErroresDeNegocio.Conflicto llegoAlTope = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> ventas.controlarCompra("T-1", "U-vendedor", "U-comprador", titular));
        assertEquals(TiposDeProblema.TOPE_POR_EVENTO, llegoAlTope.tipo());
        assertEquals(tope, llegoAlTope.getMessage());
        ErroresDeNegocio.Conflicto conEntrada = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> ventas.controlarCompra("T-1", "U-vendedor", "U-comprador", titular));
        assertEquals(TiposDeProblema.DNI_CON_ENTRADA, conEntrada.tipo());
        assertEquals(dni, conEntrada.getMessage());
        // Sin detalle, el tipo sigue siendo el de la regla y el texto lo arma Reventa con el DNI pedido.
        ErroresDeNegocio.Conflicto sinDetalle = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> ventas.controlarCompra("T-1", "U-vendedor", "U-comprador", titular));
        assertEquals(TiposDeProblema.DNI_CON_ENTRADA, sinDetalle.tipo());
        assertTrue(sinDetalle.getMessage().contains("40111222"), sinDetalle.getMessage());

        // La entrada ya no es del vendedor: la publicación quedó vieja. Y un 500 es que Ventas no responde.
        ErroresDeNegocio.Conflicto vieja = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> ventas.controlarCompra("T-1", "U-vendedor", "U-comprador", titular));
        assertEquals(TiposDeProblema.PUBLICACION_NO_ACTIVA, vieja.tipo());
        assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> ventas.controlarCompra("T-1", "U-vendedor", "U-comprador", titular));

        // El DNI viaja en el cuerpo, no en la URL.
        org.mockito.ArgumentCaptor<String> cuerpo = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rest, times(6)).llamar(eq("POST"), eq(ruta), eq(Map.of()), anyMap(), cuerpo.capture());
        for (String campo : new String[] {"\"entradaId\":\"T-1\"", "\"vendedorId\":\"U-vendedor\"",
                "\"compradorId\":\"U-comprador\"", "\"dni\":\"40111222\""}) {
            assertTrue(cuerpo.getValue().contains(campo), cuerpo.getValue());
        }
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

    @Test
    void eventosBuscaElPrecioDeListaDelSectorEnElCatalogo() {
        ClienteDeEventos eventos = new ClienteDeEventos();
        eventos.rest = rest;
        when(rest.llamar(eq("GET"), eq(ClienteDeEventos.RUTA), anyMap(), anyMap(), any()))
                .thenReturn(new Respuesta(200, "[{\"id\":\"ev-1\",\"fechaISO\":\"2026-11-20T21:00:00\",\"sectores\":["
                        + "{\"id\":\"sec-1\",\"nombre\":\"Campo\",\"precio\":50000,\"cupo\":100,\"vendidas\":3},"
                        + "{\"id\":\"sec-2\",\"nombre\":\"Platea\",\"precio\":80000,\"cupo\":50,\"vendidas\":0},"
                        + "{\"id\":\"sec-3\",\"nombre\":\"Sin precio\"}]}]"));

        assertEquals(80_000, eventos.precioDelSector("ev-1", "sec-2"));
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> eventos.precioDelSector("ev-1", "sec-9"));
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> eventos.precioDelSector("ev-9", "sec-1"));
        // Sin precio no hay tope que calcular: es una respuesta que no se entiende, no un tope de 0.
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.precioDelSector("ev-1", "sec-3"));
    }

    @Test
    void siEventosNoEntregaElCatalogoElPrecioEsSinRespuestaComoLaFecha() {
        ClienteDeEventos eventos = new ClienteDeEventos();
        eventos.rest = rest;
        when(rest.llamar(eq("GET"), eq(ClienteDeEventos.RUTA), anyMap(), anyMap(), any()))
                .thenReturn(new Respuesta(503, null));

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.fechaDelEvento("ev-1"));
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.precioDelSector("ev-1", "sec-1"));
    }
}
