package com.palco.notificaciones.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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

import com.palco.common.Textos;
import com.palco.common.mensajeria.CompraAnuladaMensaje;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.notificaciones.Ejemplos;
import com.palco.notificaciones.LogCapturado;
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

    // --- El DNI de los titulares no se guarda (DEF-37) ---

    @Test
    void unPagoConfirmadoPublicadoConElDniSeGuardaSinElDniDeNingunTitular() {
        // Un mensaje de antes del 24/11 que llega tarde (de la suscripción durable o de la DLQ).
        String llego = Ejemplos.PAGO_CONFIRMADO_CON_DNI;

        servicio.registrar(ID_DEL_MENSAJE, "PAGO_CONFIRMADO", PagoConfirmadoMensaje.desdeJson(llego), llego);

        String guardado = filaGuardada().getCuerpo();
        assertFalse(guardado.contains(Ejemplos.DNI_DE_ANA) || guardado.contains(Ejemplos.DNI_DE_LUIS), guardado);
        assertFalse(guardado.contains("\"dni\""), guardado);
        // Queda igual que el de un mensaje nuevo: el mismo texto, con los campos en el mismo orden.
        assertEquals(Ejemplos.pagoConfirmado().aJson(), guardado);
    }

    @Test
    void unaReventaPublicadaConElDniTambienSeGuardaSinEl() {
        String llego = Ejemplos.PAGO_CONFIRMADO_CON_DNI;

        servicio.registrar(ID_DEL_MENSAJE, "REVENTA_CONFIRMADA", PagoConfirmadoMensaje.desdeJson(llego), llego);

        String guardado = filaGuardada().getCuerpo();
        assertFalse(guardado.contains("\"dni\""), guardado);
    }

    @Test
    void unCuerpoSinDniSeGuardaConElMismoTextoQueLlego() {
        // Con espacios y saltos de línea, como lo escribiría otro publicador: no se vuelve a escribir.
        String llego = "{ \"entradas\": [ { \"entradaId\": \"T-1\", \"titular\": { \"nombre\": \"Ana Gómez\" } } ],\n"
                + "  \"canal\": \"web\" }";

        assertSame(llego, ServicioDeAuditoria.sinElDniDeLosTitulares(llego));
    }

    @Test
    void saleElDniDeCadaTitularQueLoTraeYElRestoQuedaComoEstaba() {
        String llego = "{\"canal\":\"web\",\"entradas\":["
                + "{\"entradaId\":\"T-1\",\"titular\":{\"dni\":\"30123456\",\"nombre\":\"Ana Gómez\"}},"
                + "{\"entradaId\":\"T-2\",\"titular\":{\"nombre\":\"Luis Pérez\"}},"
                + "{\"entradaId\":\"T-3\",\"titular\":{\"nombre\":\"Eva Ruiz\",\"dni\":\"3123456\"}}],\"ventaId\":\"V-1\"}";

        assertEquals("{\"canal\":\"web\",\"entradas\":["
                + "{\"entradaId\":\"T-1\",\"titular\":{\"nombre\":\"Ana Gómez\"}},"
                + "{\"entradaId\":\"T-2\",\"titular\":{\"nombre\":\"Luis Pérez\"}},"
                + "{\"entradaId\":\"T-3\",\"titular\":{\"nombre\":\"Eva Ruiz\"}}],\"ventaId\":\"V-1\"}",
                ServicioDeAuditoria.sinElDniDeLosTitulares(llego));
    }

    @Test
    void unaAnulacionSeGuardaTalComoLlego() {
        // COMPRA_ANULADA no trae titulares: su cuerpo no se toca.
        CompraAnuladaMensaje mensaje = Ejemplos.compraAnulada(true);
        String llego = mensaje.aJson();

        servicio.registrarAnulacion(ID_DEL_MENSAJE, mensaje, llego);

        assertSame(llego, filaGuardada().getCuerpo());
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

    @Test
    void sinRegistrosElListadoQuedaVacio() {
        when(registros.ultimos(50)).thenReturn(List.of());

        assertEquals(List.of(), servicio.listar());
    }

    // --- Las compras anuladas por la cancelación del evento ---

    @Test
    void laAnulacionQuedaComoCompraAnuladaConElMontoQueSeHabiaPagado() {
        CompraAnuladaMensaje mensaje = Ejemplos.compraAnulada(true);
        servicio.reloj = Clock.fixed(Instant.parse("2026-11-05T14:20:32.500Z"),
                ZoneId.of("America/Argentina/Buenos_Aires"));

        servicio.registrarAnulacion(ID_DEL_MENSAJE, mensaje, mensaje.aJson());

        RegistroDeAuditoria fila = filaGuardada();
        assertEquals(ID_DEL_MENSAJE, fila.getMensajeId());
        assertEquals("COMPRA_ANULADA", fila.getTipoEvento());
        assertEquals("V-3f9a1c2b", fila.getVentaId());
        assertEquals("user-titular", fila.getCompradorId());
        assertEquals("ev-6", fila.getEventoId());
        assertEquals(33000, fila.getMontoTotal());
        assertEquals(mensaje.aJson(), fila.getCuerpo());
        // La misma hora que una compra: en UTC y truncada a segundos.
        assertEquals(LocalDateTime.parse("2026-11-05T14:20:32"), fila.getRecibidoEn());
    }

    @Test
    void siElBrokerReentregaLaMismaAnulacionNoLaRegistraDosVecesYLoDejaEnElLog() {
        when(registros.existe(ID_DEL_MENSAJE)).thenReturn(true);

        try (LogCapturado log = new LogCapturado(ServicioDeAuditoria.class)) {
            servicio.registrarAnulacion(ID_DEL_MENSAJE, Ejemplos.compraAnulada(true), "{}");

            assertEquals(List.of("El mensaje " + ID_DEL_MENSAJE
                    + " ya estaba registrado (reentrega del broker): no se repite."), log.lineas());
        }
        verify(registros, never()).guardar(any());
    }

    @Test
    void siLaBaseRechazaElInsertDeUnaAnulacionLaFallaSaleDeRegistrarAnulacion() {
        doThrow(new PersistenceException("la base no responde")).when(registros).guardar(any());

        assertThrows(PersistenceException.class,
                () -> servicio.registrarAnulacion(ID_DEL_MENSAJE, Ejemplos.compraAnulada(false), "{}"));
    }

    @Test
    void elAvisoDeLaReentregaSaleEnUnSoloRenglonAunqueElIdTraigaUnSaltoDeLinea() {
        // El id lo pone el broker, pero lo que se escribe en el log no depende de eso.
        String conSalto = "ID:1\n2026-11-05 14:20:31,000 INFO  [falso] LINEA FALSA";
        when(registros.existe(conSalto)).thenReturn(true);

        try (LogCapturado log = new LogCapturado(ServicioDeAuditoria.class)) {
            servicio.registrar(conSalto, "PAGO_CONFIRMADO", Ejemplos.pagoConfirmado(), "{}");
            servicio.registrarAnulacion(conSalto, Ejemplos.compraAnulada(true), "{}");

            assertEquals(2, log.lineas().size(), log.lineas().toString());
            for (String linea : log.lineas()) {
                assertTrue(linea.chars().noneMatch(Textos::esDeControl), linea);
            }
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
