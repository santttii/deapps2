package com.palco.reventa.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.palco.common.mensajeria.EventoCanceladoMensaje;
import com.palco.reventa.LogCapturado;
import com.palco.reventa.datos.PublicacionRepository;

/**
 * Lo que hace Reventa con un {@code EVENTO_CANCELADO}, sin servidor ni base:
 * el repositorio es un doble y el reloj está fijo. Que el UPDATE toque solo
 * las publicaciones {@code ACTIVA} sin una toma, y que repetirlo dé 0, lo
 * comprueba {@code PublicacionesEnH2Test} contra la base.
 */
class RetiroPorCancelacionTest {

    /** Con fracción de segundo, para ver que se descarta como en el resto de las fechas de Reventa. */
    private static final Instant AHORA = Instant.parse("2026-11-20T15:30:45.678Z");
    private static final LocalDateTime AHORA_EN_UTC = LocalDateTime.of(2026, 11, 20, 15, 30, 45);

    private final PublicacionRepository publicaciones = mock(PublicacionRepository.class);
    private final RetiroPorCancelacion retiro = new RetiroPorCancelacion();

    RetiroPorCancelacionTest() {
        retiro.publicaciones = publicaciones;
        // El servidor corre en hora argentina: el reloj en esa zona comprueba que igual se guarda en UTC.
        retiro.reloj = Clock.fixed(AHORA, ZoneId.of("America/Argentina/Buenos_Aires"));
    }

    private static EventoCanceladoMensaje cancelado(String eventoId) {
        return new EventoCanceladoMensaje(eventoId, "Show", "Se lesionó el artista", AHORA);
    }

    @Test
    void retiraLasPublicacionesDelEventoCanceladoConLaHoraEnUtcYDiceCuantas() {
        when(publicaciones.retirarPorCancelacionDelEvento("ev-1", AHORA_EN_UTC)).thenReturn(3);

        try (LogCapturado log = new LogCapturado(RetiroPorCancelacion.class)) {
            assertEquals(3, retiro.retirarDelEvento(cancelado("ev-1")));

            assertTrue(log.lineas().stream()
                    .anyMatch(l -> l.contains("ev-1") && l.contains("Publicaciones retiradas: 3")),
                    String.valueOf(log.lineas()));
        }
        // Un solo UPDATE para todo el evento, y nada más: ni se leen las publicaciones una por una.
        verify(publicaciones).retirarPorCancelacionDelEvento("ev-1", AHORA_EN_UTC);
        verifyNoMoreInteractions(publicaciones);
    }

    @Test
    void unMensajeRepetidoNoRetiraNadaYElLogLoDice() {
        // La segunda entrega del mismo mensaje: las publicaciones ya están RETIRADA y el UPDATE no cambia ninguna.
        when(publicaciones.retirarPorCancelacionDelEvento(anyString(), any())).thenReturn(0);

        try (LogCapturado log = new LogCapturado(RetiroPorCancelacion.class)) {
            assertEquals(0, retiro.retirarDelEvento(cancelado("ev-1")));

            assertTrue(log.lineas().stream().anyMatch(l -> l.contains("mensaje repetido")),
                    String.valueOf(log.lineas()));
        }
    }

    @Test
    void siLaBaseFallaElErrorSalePorqueElMensajeTieneQueVolverAlBroker() {
        // Corre en la transacción del mensaje: si el error se tragara acá, el mensaje se daría por procesado.
        IllegalStateException caida = new IllegalStateException("la base no responde");
        when(publicaciones.retirarPorCancelacionDelEvento(anyString(), any())).thenThrow(caida);

        assertSame(caida, assertThrows(IllegalStateException.class, () -> retiro.retirarDelEvento(cancelado("ev-1"))));
    }
}
