package com.palco.notificaciones.mensajeria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import jakarta.ejb.ActivationConfigProperty;
import jakarta.ejb.EJBException;
import jakarta.ejb.MessageDriven;
import jakarta.ejb.MessageDrivenContext;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.Test;

import com.palco.common.Textos;
import com.palco.common.mensajeria.CompraAnuladaMensaje;
import com.palco.notificaciones.Ejemplos;
import com.palco.notificaciones.LogCapturado;
import com.palco.notificaciones.negocio.IAuditoria;

/**
 * Qué hace la auditoría de cancelaciones con cada mensaje: registra la compra
 * anulada y devuelve al broker lo que no entiende, para que lo reintente y
 * después lo guarde en la DLQ. Como en {@link AuditoriaDeComprasMDBTest}, el
 * mensaje JMS, el contexto del MDB y el servicio son dobles.
 */
class AuditoriaDeCancelacionesMDBTest {

    private static final String ID_DEL_MENSAJE = "ID:5a6b7c8d-0000-1111-2222-333344445555";

    private final IAuditoria auditoria = mock(IAuditoria.class);
    private final MessageDrivenContext contexto = mock(MessageDrivenContext.class);
    private final AuditoriaDeCancelacionesMDB mdb = new AuditoriaDeCancelacionesMDB();

    AuditoriaDeCancelacionesMDBTest() {
        mdb.auditoria = auditoria;
        mdb.contexto = contexto;
    }

    private static TextMessage mensajeDeTexto(String texto) throws JMSException {
        TextMessage recibido = mock(TextMessage.class);
        when(recibido.getText()).thenReturn(texto);
        when(recibido.getStringProperty("tipoEvento")).thenReturn("COMPRA_ANULADA");
        when(recibido.getJMSMessageID()).thenReturn(ID_DEL_MENSAJE);
        return recibido;
    }

    private void assertVolvioAlBrokerSinRegistrar() {
        verify(contexto).setRollbackOnly();
        verify(auditoria, never()).registrarAnulacion(any(), any(), any());
    }

    @Test
    void unaAnulacionValidaSeRegistraConSuIdYConElTextoTalComoLlego() throws JMSException {
        CompraAnuladaMensaje mensaje = Ejemplos.compraAnulada(true);
        String json = mensaje.aJson();

        try (LogCapturado log = new LogCapturado(AuditoriaDeCancelacionesMDB.class)) {
            mdb.onMessage(mensajeDeTexto(json));

            assertEquals(List.of("AuditoriaDeCancelaciones: procesado el mensaje de la venta anulada "
                    + "V-3f9a1c2b."), log.lineas());
        }
        verify(auditoria).registrarAnulacion(ID_DEL_MENSAJE, mensaje, json);
        verify(contexto, never()).setRollbackOnly();
    }

    @Test
    void siRegistrarFallaLaFallaSaleDelMDB() throws JMSException {
        CompraAnuladaMensaje mensaje = Ejemplos.compraAnulada(false);
        String json = mensaje.aJson();
        // Así le llega al MDB una falla de la base dentro del Session Bean.
        doThrow(new EJBException("la base no responde")).when(auditoria).registrarAnulacion(ID_DEL_MENSAJE, mensaje, json);
        TextMessage recibido = mensajeDeTexto(json);

        // No la atrapa: con una excepción que sale de onMessage, el contenedor deshace la
        // transacción y el broker reentrega el mensaje. El rastro de una anulación no se pierde.
        assertThrows(EJBException.class, () -> mdb.onMessage(recibido));
    }

    @Test
    void unTextoQueNoEsUnaCompraAnuladaVuelveAlBroker() throws JMSException {
        mdb.onMessage(mensajeDeTexto("esto no es una COMPRA_ANULADA"));

        assertVolvioAlBrokerSinRegistrar();
    }

    @Test
    void unaAnulacionSinMontoVuelveAlBroker() throws JMSException {
        // El JSON se entiende, pero la fila de auditoría necesita el monto que se había pagado.
        mdb.onMessage(mensajeDeTexto("{\"ventaId\":\"V-3f9a1c2b\",\"compradorId\":\"user-titular\","
                + "\"eventoId\":\"ev-6\",\"titulo\":\"Marea Baja + invitados\",\"timestamp\":\"2026-11-05T14:20:31Z\"}"));

        assertVolvioAlBrokerSinRegistrar();
    }

    @Test
    void unMensajeSinTipoDeEventoConocidoVuelveAlBroker() throws JMSException {
        TextMessage recibido = mensajeDeTexto(Ejemplos.compraAnulada(true).aJson());
        // Un PAGO_CONFIRMADO no es de este tópico: el selector ya lo filtra, solo llega uno armado a mano.
        when(recibido.getStringProperty("tipoEvento")).thenReturn("PAGO_CONFIRMADO");

        mdb.onMessage(recibido);

        assertVolvioAlBrokerSinRegistrar();
    }

    @Test
    void unMensajeQueNoEsDeTextoVuelveAlBroker() throws JMSException {
        Message recibido = mock(Message.class);
        when(recibido.getStringProperty("tipoEvento")).thenReturn("COMPRA_ANULADA");

        mdb.onMessage(recibido);

        assertVolvioAlBrokerSinRegistrar();
    }

    @Test
    void siElTextoNoSePuedeLeerVuelveAlBroker() throws JMSException {
        TextMessage recibido = mensajeDeTexto(null);
        when(recibido.getText()).thenThrow(new JMSException("la sesión se cerró"));

        mdb.onMessage(recibido);

        assertVolvioAlBrokerSinRegistrar();
    }

    @Test
    void siNoSePuedeLeerElIdDelMensajeVuelveAlBroker() throws JMSException {
        // Sin el id no hay clave para la fila ni forma de reconocer una reentrega.
        TextMessage recibido = mensajeDeTexto(Ejemplos.compraAnulada(true).aJson());
        when(recibido.getJMSMessageID()).thenThrow(new JMSException("la sesión se cerró"));

        mdb.onMessage(recibido);

        assertVolvioAlBrokerSinRegistrar();
    }

    @Test
    void elLogDelMensajeInvalidoDiceEnQueEntregaVaYEstaEnUnSoloRenglon() throws JMSException {
        TextMessage recibido = mensajeDeTexto("esto no es una COMPRA_ANULADA");
        when(recibido.getIntProperty("JMSXDeliveryCount")).thenReturn(3);

        try (LogCapturado log = new LogCapturado(AuditoriaDeCancelacionesMDB.class)) {
            mdb.onMessage(recibido);

            assertEquals(1, log.lineas().size(), log.lineas().toString());
            String linea = log.lineas().get(0);
            assertTrue(linea.startsWith("AuditoriaDeCancelaciones: mensaje inválido (entrega 3), vuelve al broker."),
                    linea);
            assertTrue(linea.chars().noneMatch(Textos::esDeControl), linea);
        }
    }

    @Test
    void siNoSePuedeLeerElNumeroDeEntregaElLogDiceCero() throws JMSException {
        // Es solo para el log: que no se pueda leer no cambia lo que se hace con el mensaje.
        TextMessage recibido = mensajeDeTexto("esto no es una COMPRA_ANULADA");
        when(recibido.getIntProperty("JMSXDeliveryCount")).thenThrow(new JMSException("sin la propiedad"));

        try (LogCapturado log = new LogCapturado(AuditoriaDeCancelacionesMDB.class)) {
            mdb.onMessage(recibido);

            assertTrue(log.lineas().get(0).contains("(entrega 0)"), log.lineas().get(0));
        }
        assertVolvioAlBrokerSinRegistrar();
    }

    @Test
    void escuchaSoloLasAnulacionesConUnaSuscripcionDurable() {
        ActivationConfigProperty[] configuracion =
                AuditoriaDeCancelacionesMDB.class.getAnnotation(MessageDriven.class).activationConfig();

        assertEquals("tipoEvento = 'COMPRA_ANULADA'", propiedad(configuracion, "messageSelector"));
        // Durable: si el servicio está caído, el broker guarda las anulaciones hasta que vuelva.
        assertEquals("Durable", propiedad(configuracion, "subscriptionDurability"));
        assertEquals("1", propiedad(configuracion, "maxSession"));
    }

    private static String propiedad(ActivationConfigProperty[] configuracion, String nombre) {
        return Arrays.stream(configuracion)
                .filter(p -> p.propertyName().equals(nombre))
                .findFirst().orElseThrow().propertyValue();
    }
}
