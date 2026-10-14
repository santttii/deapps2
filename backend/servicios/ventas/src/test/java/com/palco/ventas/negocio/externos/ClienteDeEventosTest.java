package com.palco.ventas.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.seguridad.TokenDeServicio;
import com.palco.ventas.negocio.externos.ClienteDeEventos.Sector;

/**
 * Qué hace Ventas con cada respuesta de Eventos. No necesita servidor: la
 * llamada HTTP se reemplaza por respuestas preparadas, y el token de servicio
 * es un doble.
 */
class ClienteDeEventosTest {

    private static final String SECTOR_JSON = """
            {"id":"sec-1-4","nombre":"Platea B","precio":27500,"cupo":2000,"vendidas":1442}""";

    /** En vez de llamar a Eventos entrega, en orden, las respuestas que le cargó el test, y anota cada llamada. */
    private static class EventosSinRed extends ClienteDeEventos {
        final Deque<Respuesta> respuestas = new ArrayDeque<>();
        final List<String> llamadas = new ArrayList<>();
        boolean caido;

        void responde(int estado, String cuerpo) {
            respuestas.add(new Respuesta(estado, cuerpo));
        }

        @Override
        Respuesta enviar(String eventoId, String sectorId, String cuerpo, String token) {
            llamadas.add(eventoId + " " + sectorId + " " + cuerpo + " " + token);
            if (caido) {
                throw new ErroresDeNegocio.SinRespuesta("El servicio de eventos no responde. Probá de nuevo.");
            }
            return respuestas.removeFirst();
        }
    }

    private final TokenDeServicio token = mock(TokenDeServicio.class);
    private final EventosSinRed eventos = new EventosSinRed();

    @BeforeEach
    void armarElCliente() {
        eventos.tokenDeServicio = token;
        when(token.obtener()).thenReturn("token-1", "token-2");
    }

    @Test
    void reservarMandaLaCantidadConElTokenDeServicioYDevuelveElSector() {
        eventos.responde(200, SECTOR_JSON);

        Sector sector = eventos.reservarCupo("ev-1", "sec-1-4", 2);

        assertEquals(new Sector("sec-1-4", "Platea B", 27_500, 2000, 1442), sector);
        assertEquals(List.of("ev-1 sec-1-4 {\"cantidad\":2} token-1"), eventos.llamadas);
    }

    @Test
    void liberarMandaLaCantidadEnNegativo() {
        eventos.responde(200, SECTOR_JSON);

        assertTrue(eventos.liberarCupo("V-3f9a1c2b", "ev-1", "sec-1-4", 2));

        assertEquals(List.of("ev-1 sec-1-4 {\"cantidad\":-2} token-1"), eventos.llamadas);
    }

    @Test
    void unSectorQueEventosNoConoceEsUnaReglaIncumplida() {
        eventos.responde(404, null);

        ErroresDeNegocio.ReglaIncumplida error = assertThrows(ErroresDeNegocio.ReglaIncumplida.class,
                () -> eventos.reservarCupo("ev-1", "sec-no-existe", 2));

        assertEquals("sector-inexistente", error.tipo());
    }

    @Test
    void sinLugaresEsUnConflictoDeCupoAgotado() {
        eventos.responde(409, null);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> eventos.reservarCupo("ev-1", "sec-1-3", 2));

        assertEquals("cupo-agotado", error.tipo());
    }

    @Test
    void unEventoQueYaEmpezoEsUnConflictoDeEventoFinalizado() {
        eventos.responde(409, """
                {"type":"/problemas/evento-finalizado","title":"Conflicto","status":409,
                 "detail":"El evento ya empezó: no se venden más entradas."}""");

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> eventos.reservarCupo("ev-1", "sec-1-4", 2));

        assertEquals("evento-finalizado", error.tipo());
        assertEquals("Ese evento ya empezó o terminó", error.getMessage());
    }

    @Test
    void unConflictoDeCupoConCuerpoSigueSiendoCupoAgotado() {
        eventos.responde(409, """
                {"type":"/problemas/cupo-agotado","title":"Conflicto","status":409,"detail":"Sin lugar."}""");

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> eventos.reservarCupo("ev-1", "sec-1-4", 2));

        assertEquals("cupo-agotado", error.tipo());
    }

    @Test
    void cualquierOtroCodigoEsSinRespuesta() {
        eventos.responde(500, null);

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.reservarCupo("ev-1", "sec-1-4", 2));
    }

    @Test
    void siEventosRespondeOtraCosaElAvisoDiceEventoSectorYCantidad() {
        // No se sabe si Eventos aplicó el cambio: con esos tres datos el cupo se puede revisar a mano.
        List<String> avisos = new ArrayList<>();
        Handler anotador = new Handler() {
            @Override
            public void publish(LogRecord registro) {
                avisos.add(registro.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger log = Logger.getLogger(ClienteDeEventos.class.getName());
        log.addHandler(anotador);
        try {
            eventos.responde(500, null);
            assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.reservarCupo("ev-4", "sec-4-2", 3));
        } finally {
            log.removeHandler(anotador);
        }

        assertEquals(1, avisos.size());
        assertTrue(avisos.get(0).contains("evento ev-4, sector sec-4-2, cantidad 3"), avisos.get(0));
    }

    @Test
    void unDoscientosQueNoTraeUnSectorEsSinRespuesta() {
        eventos.responde(200, "<html>ok</html>");
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.reservarCupo("ev-1", "sec-1-4", 2));

        eventos.responde(200, "{\"id\":\"sec-1-4\"}"); // sin nombre ni precio no se puede armar la venta
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.reservarCupo("ev-1", "sec-1-4", 2));
    }

    @Test
    void conUn401PideOtroTokenYReintentaUnaSolaVez() {
        eventos.responde(401, null);
        eventos.responde(200, SECTOR_JSON);

        Sector sector = eventos.reservarCupo("ev-1", "sec-1-4", 2);

        assertEquals("Platea B", sector.nombre());
        verify(token).invalidar();
        assertEquals(List.of(
                "ev-1 sec-1-4 {\"cantidad\":2} token-1",
                "ev-1 sec-1-4 {\"cantidad\":2} token-2"), eventos.llamadas);
    }

    @Test
    void siElTokenNuevoTampocoSirveNoInsisteYEsSinRespuesta() {
        eventos.responde(401, null);
        eventos.responde(401, null);

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.reservarCupo("ev-1", "sec-1-4", 2));

        assertEquals(2, eventos.llamadas.size());
        verify(token, times(1)).invalidar();
    }

    @Test
    void siNoSeConsigueElTokenDeServicioNoSeLlamaAEventos() {
        when(token.obtener()).thenThrow(new ErroresDeNegocio.SinRespuesta("El servicio de usuarios no responde."));

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.reservarCupo("ev-1", "sec-1-4", 2));

        assertTrue(eventos.llamadas.isEmpty());
        verify(token, never()).invalidar();
    }

    @Test
    void siEventosNoRespondeAlReservarEsSinRespuesta() {
        eventos.caido = true;

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> eventos.reservarCupo("ev-1", "sec-1-4", 2));
    }

    @Test
    void liberarNoLanzaAunqueEventosNoRespondaNiAcepteLaDevolucion() {
        eventos.caido = true;
        assertFalse(eventos.liberarCupo("V-3f9a1c2b", "ev-1", "sec-1-4", 2));

        eventos.caido = false;
        eventos.responde(409, null); // Eventos dice que no tiene tantas vendidas
        assertFalse(eventos.liberarCupo("V-3f9a1c2b", "ev-1", "sec-1-4", 2));
    }

    @Test
    void alLiberarAlcanzaConElCodigoYNoSeMiraElCuerpo() {
        eventos.responde(200, null);

        assertTrue(eventos.liberarCupo("V-3f9a1c2b", "ev-1", "sec-1-4", 2));
    }
}
