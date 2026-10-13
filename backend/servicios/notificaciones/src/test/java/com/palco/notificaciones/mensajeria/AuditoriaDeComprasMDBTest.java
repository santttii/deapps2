package com.palco.notificaciones.mensajeria;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ejb.EJBException;
import jakarta.ejb.MessageDrivenContext;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.Test;

import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.notificaciones.Ejemplos;
import com.palco.notificaciones.negocio.IAuditoria;

/**
 * Qué hace auditoría con cada mensaje: registra el válido y devuelve al broker
 * el que no entiende. El mensaje JMS, el contexto del MDB y el servicio son
 * dobles: no hay broker ni servidor.
 */
class AuditoriaDeComprasMDBTest {

    private static final String ID_DEL_MENSAJE = "ID:8f1c2d3e-0000-1111-2222-333344445555";

    private final IAuditoria auditoria = mock(IAuditoria.class);
    private final MessageDrivenContext contexto = mock(MessageDrivenContext.class);
    private final AuditoriaDeComprasMDB mdb = new AuditoriaDeComprasMDB();

    AuditoriaDeComprasMDBTest() {
        mdb.auditoria = auditoria;
        mdb.contexto = contexto;
    }

    private static TextMessage mensajeDeTexto(String texto) throws JMSException {
        TextMessage recibido = mock(TextMessage.class);
        when(recibido.getText()).thenReturn(texto);
        when(recibido.getJMSMessageID()).thenReturn(ID_DEL_MENSAJE);
        return recibido;
    }

    @Test
    void unMensajeValidoSeRegistraConSuIdYConElTextoTalComoLlego() throws JMSException {
        PagoConfirmadoMensaje mensaje = Ejemplos.pagoConfirmado();
        String json = mensaje.aJson();

        mdb.onMessage(mensajeDeTexto(json));

        verify(auditoria).registrar(ID_DEL_MENSAJE, mensaje, json);
        verify(contexto, never()).setRollbackOnly();
    }

    @Test
    void siRegistrarFallaLaFallaSaleDelMDB() throws JMSException {
        PagoConfirmadoMensaje mensaje = Ejemplos.pagoConfirmado();
        String json = mensaje.aJson();
        // Así le llega al MDB una falla de la base dentro del Session Bean.
        doThrow(new EJBException("la base no responde")).when(auditoria).registrar(ID_DEL_MENSAJE, mensaje, json);
        TextMessage recibido = mensajeDeTexto(json);

        // No la atrapa ni escribe que lo procesó: con una excepción que sale de onMessage,
        // el contenedor deshace la transacción y el broker reentrega el mensaje.
        assertThrows(EJBException.class, () -> mdb.onMessage(recibido));
    }

    @Test
    void unTextoQueNoEsUnPagoConfirmadoVuelveAlBrokerYNoSeRegistra() throws JMSException {
        mdb.onMessage(mensajeDeTexto("esto no es un PAGO_CONFIRMADO"));

        verify(contexto).setRollbackOnly();
        verify(auditoria, never()).registrar(any(), any(), any());
    }

    @Test
    void unJsonIncompletoTambienVuelveAlBroker() throws JMSException {
        mdb.onMessage(mensajeDeTexto("{\"ventaId\":\"V-3f9a1c2b\"}"));

        verify(contexto).setRollbackOnly();
        verify(auditoria, never()).registrar(any(), any(), any());
    }

    @Test
    void unMensajeQueNoEsDeTextoVuelveAlBroker() {
        mdb.onMessage(mock(Message.class));

        verify(contexto).setRollbackOnly();
        verify(auditoria, never()).registrar(any(), any(), any());
    }
}
