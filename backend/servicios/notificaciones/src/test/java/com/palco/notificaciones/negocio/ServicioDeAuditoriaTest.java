package com.palco.notificaciones.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import jakarta.json.bind.JsonbBuilder;
import jakarta.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.notificaciones.Ejemplos;
import com.palco.notificaciones.api.Dtos.RegistroDeAuditoriaDto;
import com.palco.notificaciones.datos.RegistroDeAuditoriaRepository;
import com.palco.notificaciones.dominio.RegistroDeAuditoria;

/** Qué guarda auditoría y cuándo no guarda. El repositorio es un doble: no hay base ni servidor. */
class ServicioDeAuditoriaTest {

    private static final String ID_DEL_MENSAJE = "ID:8f1c2d3e-0000-1111-2222-333344445555";

    private final RegistroDeAuditoriaRepository registros = mock(RegistroDeAuditoriaRepository.class);
    private final ServicioDeAuditoria servicio = new ServicioDeAuditoria();

    ServicioDeAuditoriaTest() {
        servicio.registros = registros;
    }

    @Test
    void registraUnaFilaConLosDatosDelMensaje() {
        PagoConfirmadoMensaje mensaje = Ejemplos.pagoConfirmado();

        servicio.registrar(ID_DEL_MENSAJE, "PAGO_CONFIRMADO", mensaje, mensaje.aJson());

        RegistroDeAuditoria fila = filaGuardada();
        assertEquals(ID_DEL_MENSAJE, fila.getMensajeId());
        assertEquals("PAGO_CONFIRMADO", fila.getTipoEvento());
        assertEquals("V-3f9a1c2b", fila.getVentaId());
        assertEquals("user-titular", fila.getCompradorId());
        assertEquals("ev-1", fila.getEventoId());
        assertEquals(60500, fila.getMontoTotal());
        assertEquals(mensaje.aJson(), fila.getCuerpo());
    }

    @Test
    void unaReventaQuedaRegistradaComoReventaConfirmada() {
        servicio.registrar(ID_DEL_MENSAJE, "REVENTA_CONFIRMADA", Ejemplos.pagoConfirmado(), "{}");

        assertEquals("REVENTA_CONFIRMADA", filaGuardada().getTipoEvento());
    }

    @Test
    void laHoraSeGuardaEnUtcYTruncadaASegundosAunqueElServidorEsteEnHoraArgentina() {
        // 18:30:05 en Buenos Aires es 21:30:05 en UTC.
        servicio.reloj = Clock.fixed(Instant.parse("2026-10-16T21:30:05.789Z"),
                ZoneId.of("America/Argentina/Buenos_Aires"));

        servicio.registrar(ID_DEL_MENSAJE, "PAGO_CONFIRMADO", Ejemplos.pagoConfirmado(), "{}");

        assertEquals(LocalDateTime.parse("2026-10-16T21:30:05"), filaGuardada().getRecibidoEn());
    }

    @Test
    void siLaBaseRechazaElInsertLaFallaSaleDeRegistrar() {
        // El repositorio manda el INSERT en el momento (lo prueba RegistroDeAuditoriaEnH2Test).
        doThrow(new PersistenceException("la base no responde")).when(registros).guardar(any());

        // Sale de registrar, y no recién al confirmar la transacción: el MDB no llega a
        // escribir en el log que procesó el mensaje.
        assertThrows(PersistenceException.class,
                () -> servicio.registrar(ID_DEL_MENSAJE, "PAGO_CONFIRMADO", Ejemplos.pagoConfirmado(), "{}"));
    }

    @Test
    void siElBrokerReentregaElMismoMensajeNoLoRegistraDosVeces() {
        when(registros.existe(ID_DEL_MENSAJE)).thenReturn(true);

        servicio.registrar(ID_DEL_MENSAJE, "PAGO_CONFIRMADO", Ejemplos.pagoConfirmado(), "{}");

        verify(registros, never()).guardar(any());
    }

    @Test
    void elListadoPideLasUltimasCincuentaYLasDevuelveEnElMismoOrden() {
        RegistroDeAuditoria nueva = new RegistroDeAuditoria("ID:2", "PAGO_CONFIRMADO", "V-2", "user-titular",
                "ev-1", 1000, "{}", LocalDateTime.parse("2026-10-16T21:31:00"));
        when(registros.ultimos(50)).thenReturn(List.of(nueva, filaDeEjemplo()));

        List<RegistroDeAuditoriaDto> listado = servicio.listar();

        assertEquals(List.of("ID:2", ID_DEL_MENSAJE), listado.stream().map(RegistroDeAuditoriaDto::mensajeId).toList());
    }

    @Test
    void haciaAfueraLaHoraSaleComoInstanteConZ() throws Exception {
        RegistroDeAuditoriaDto dto = ServicioDeAuditoria.aDto(filaDeEjemplo());

        assertEquals(Instant.parse("2026-10-16T21:30:05Z"), dto.recibidoEn());
        try (var jsonb = JsonbBuilder.create()) {
            String json = jsonb.toJson(dto);
            assertTrue(json.contains("\"recibidoEn\":\"2026-10-16T21:30:05Z\""), json);
            assertTrue(json.contains("\"ventaId\":\"V-3f9a1c2b\""), json);
        }
    }

    private RegistroDeAuditoria filaGuardada() {
        ArgumentCaptor<RegistroDeAuditoria> guardada = ArgumentCaptor.forClass(RegistroDeAuditoria.class);
        verify(registros).guardar(guardada.capture());
        return guardada.getValue();
    }

    private static RegistroDeAuditoria filaDeEjemplo() {
        return new RegistroDeAuditoria(ID_DEL_MENSAJE, "PAGO_CONFIRMADO", "V-3f9a1c2b", "user-titular", "ev-1",
                60500, "{}", LocalDateTime.parse("2026-10-16T21:30:05"));
    }
}
