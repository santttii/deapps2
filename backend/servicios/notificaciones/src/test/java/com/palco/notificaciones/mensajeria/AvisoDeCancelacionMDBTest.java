package com.palco.notificaciones.mensajeria;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.Test;

import com.palco.common.mensajeria.CompraAnuladaMensaje;
import com.palco.notificaciones.AvisosAnotados;
import com.palco.notificaciones.Ejemplos;
import com.palco.notificaciones.LogCapturado;

/**
 * Qué hace el aviso de cancelación con cada mensaje: le avisa al comprador por
 * la anulación válida y descarta lo que no entiende, sin reintentos, igual que
 * {@link AvisoDeCompraMDBTest}. El servicio es un doble que anota a quién se le
 * avisó.
 */
class AvisoDeCancelacionMDBTest {

    private final AvisosAnotados avisados = new AvisosAnotados();
    private final AvisoDeCancelacionMDB mdb = new AvisoDeCancelacionMDB();

    AvisoDeCancelacionMDBTest() {
        mdb.notificaciones = avisados;
    }

    private static TextMessage mensajeDeTexto(String texto) throws JMSException {
        TextMessage recibido = mock(TextMessage.class);
        when(recibido.getText()).thenReturn(texto);
        when(recibido.getStringProperty("tipoEvento")).thenReturn("COMPRA_ANULADA");
        return recibido;
    }

    @Test
    void unaAnulacionValidaDisparaLaAlertaAlComprador() throws JMSException {
        CompraAnuladaMensaje mensaje = Ejemplos.compraAnulada(false);

        mdb.onMessage(mensajeDeTexto(mensaje.aJson()));

        assertEquals(List.of(mensaje), avisados.cancelaciones);
        assertEquals(List.of(), avisados.compras);
    }

    @Test
    void elLogDeUnMensajeAtendidoNombraLaVentaYElEvento() throws JMSException {
        try (LogCapturado log = new LogCapturado(AvisoDeCancelacionMDB.class)) {
            mdb.onMessage(mensajeDeTexto(Ejemplos.compraAnulada(true).aJson()));

            assertEquals(List.of("AvisoDeCancelacion: atendido el aviso por la venta V-3f9a1c2b del evento "
                    + "cancelado ev-6."), log.lineas());
        }
    }

    @Test
    void unTextoQueNoEsUnaCompraAnuladaSeDescartaSinAvisarYSinLanzarNada() throws JMSException {
        TextMessage invalido = mensajeDeTexto("esto no es una COMPRA_ANULADA");

        try (LogCapturado log = new LogCapturado(AvisoDeCancelacionMDB.class)) {
            // Que no lance nada es lo que hace que el broker lo dé por consumido y no lo reentregue.
            assertDoesNotThrow(() -> mdb.onMessage(invalido));

            assertEquals(1, log.lineas().size(), log.lineas().toString());
            assertTrue(log.lineas().get(0).startsWith("AvisoDeCancelacion: se descarta un mensaje inválido."),
                    log.lineas().get(0));
        }
        assertTrue(avisados.cancelaciones.isEmpty());
    }

    @Test
    void unaAnulacionSinCompradorSeDescarta() throws JMSException {
        // Sin el comprador no hay a quién avisarle.
        CompraAnuladaMensaje sinComprador = new CompraAnuladaMensaje("V-3f9a1c2b", null, "ev-6",
                "Marea Baja + invitados", null, 33000, true, Ejemplos.compraAnulada(true).timestamp());

        assertDoesNotThrow(() -> mdb.onMessage(mensajeDeTexto(sinComprador.aJson())));

        assertTrue(avisados.cancelaciones.isEmpty());
    }

    @Test
    void unEventoCanceladoNoEsUnaAnulacionYSeDescarta() throws JMSException {
        // El selector deja afuera los EVENTO_CANCELADO; si llega uno armado a mano, no trae a quién avisarle.
        TextMessage recibido = mensajeDeTexto("{\"eventoId\":\"ev-6\",\"titulo\":\"Marea Baja + invitados\","
                + "\"timestamp\":\"2026-11-05T14:20:31Z\"}");
        when(recibido.getStringProperty("tipoEvento")).thenReturn("EVENTO_CANCELADO");

        assertDoesNotThrow(() -> mdb.onMessage(recibido));

        assertTrue(avisados.cancelaciones.isEmpty());
    }

    @Test
    void unMensajeSinTipoDeEventoConocidoSeDescartaSinAvisar() throws JMSException {
        TextMessage recibido = mensajeDeTexto(Ejemplos.compraAnulada(true).aJson());
        when(recibido.getStringProperty("tipoEvento")).thenReturn(null);

        assertDoesNotThrow(() -> mdb.onMessage(recibido));

        assertTrue(avisados.cancelaciones.isEmpty());
    }

    @Test
    void unMensajeQueNoEsDeTextoSeDescarta() throws JMSException {
        Message recibido = mock(Message.class);
        when(recibido.getStringProperty("tipoEvento")).thenReturn("COMPRA_ANULADA");

        assertDoesNotThrow(() -> mdb.onMessage(recibido));

        assertTrue(avisados.cancelaciones.isEmpty());
    }
}
