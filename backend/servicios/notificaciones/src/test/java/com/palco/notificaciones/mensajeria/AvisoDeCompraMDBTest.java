package com.palco.notificaciones.mensajeria;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.Test;

import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.notificaciones.AvisosAnotados;
import com.palco.notificaciones.Ejemplos;
import com.palco.notificaciones.LogCapturado;

/**
 * Qué hace el aviso con cada mensaje: avisa por el válido y descarta el que no
 * entiende, sin reintentos. El servicio se reemplaza con una lambda que anota
 * a quién se le avisó.
 */
class AvisoDeCompraMDBTest {

    private final AvisosAnotados avisados = new AvisosAnotados();
    private final AvisoDeCompraMDB mdb = new AvisoDeCompraMDB();

    AvisoDeCompraMDBTest() {
        mdb.notificaciones = avisados;
    }

    private static TextMessage mensajeDeTexto(String texto) throws JMSException {
        TextMessage recibido = mock(TextMessage.class);
        when(recibido.getText()).thenReturn(texto);
        when(recibido.getStringProperty("tipoEvento")).thenReturn("PAGO_CONFIRMADO");
        return recibido;
    }

    @Test
    void unMensajeValidoDisparaElAviso() throws JMSException {
        PagoConfirmadoMensaje mensaje = Ejemplos.pagoConfirmado();

        mdb.onMessage(mensajeDeTexto(mensaje.aJson()));

        assertEquals(List.of(mensaje), avisados.compras);
        assertEquals(List.of(), avisados.reventas);
    }

    @Test
    void unaReventaDisparaElAvisoDeReventa() throws JMSException {
        PagoConfirmadoMensaje mensaje = Ejemplos.pagoConfirmado();
        TextMessage recibido = mensajeDeTexto(mensaje.aJson());
        when(recibido.getStringProperty("tipoEvento")).thenReturn("REVENTA_CONFIRMADA");

        mdb.onMessage(recibido);

        assertEquals(List.of(mensaje), avisados.reventas);
        assertEquals(List.of(), avisados.compras);
    }

    @Test
    void unMensajeSinTipoDeEventoConocidoSeDescartaSinAvisar() throws JMSException {
        TextMessage recibido = mensajeDeTexto(Ejemplos.pagoConfirmado().aJson());
        when(recibido.getStringProperty("tipoEvento")).thenReturn("OTRO_EVENTO");

        mdb.onMessage(recibido);

        assertEquals(List.of(), avisados.compras);
        assertEquals(List.of(), avisados.reventas);
    }

    @Test
    void elLogDiceQueElAvisoNoSeEnviaPorqueNoHayProveedorDeCorreo() throws JMSException {
        PagoConfirmadoMensaje mensaje = Ejemplos.pagoConfirmado();

        try (LogCapturado log = new LogCapturado(AvisoDeCompraMDB.class)) {
            mdb.onMessage(mensajeDeTexto(mensaje.aJson()));

            assertEquals(List.of("AvisoDeCompra: aviso por la venta " + mensaje.ventaId()
                    + " registrado en el log; no se envía: no hay proveedor de correo."), log.lineas());
            assertFalse(log.lineas().get(0).contains("enviado"), log.lineas().get(0));
        }
    }

    @Test
    void unTextoQueNoEsUnPagoConfirmadoSeDescartaSinAvisarYSinLanzarNada() throws JMSException {
        TextMessage invalido = mensajeDeTexto("esto no es un PAGO_CONFIRMADO");

        // Que no lance nada es lo que hace que el broker lo dé por consumido y no lo reentregue.
        assertDoesNotThrow(() -> mdb.onMessage(invalido));

        assertTrue(avisados.compras.isEmpty());
    }

    @Test
    void unMensajeQueNoEsDeTextoSeDescarta() {
        assertDoesNotThrow(() -> mdb.onMessage(mock(Message.class)));

        assertTrue(avisados.compras.isEmpty());
    }
}
