package com.palco.facturacion.mensajeria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.ejb.ActivationConfigProperty;
import jakarta.ejb.EJBException;
import jakarta.ejb.MessageDriven;
import jakarta.ejb.MessageDrivenContext;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.Test;

import com.palco.common.mensajeria.DestinosJms;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.facturacion.Ejemplos;
import com.palco.facturacion.datos.FacturaRepositoryEnMemoria;
import com.palco.facturacion.negocio.IFacturacion;
import com.palco.facturacion.negocio.ServicioDeFacturacionDePrueba;
import com.palco.facturacion.negocio.externos.ArcaEnMemoria;

/**
 * Qué hace el consumidor con cada mensaje. El mensaje JMS y el contexto del
 * MDB son dobles; el negocio es el de verdad, con el repositorio y ARCA en
 * memoria: así se ve de punta a punta que una compra se factura una vez, que
 * un mensaje repetido no factura otra y que con ARCA caído el mensaje vuelve
 * al broker para el reintento. "Volver al broker" es {@code setRollbackOnly()}:
 * el contenedor deshace la transacción y Artemis reentrega.
 */
class FacturacionDeComprasMDBTest {

    private final FacturaRepositoryEnMemoria repositorio = new FacturaRepositoryEnMemoria();
    private final ArcaEnMemoria arca = new ArcaEnMemoria();
    private final MessageDrivenContext contexto = mock(MessageDrivenContext.class);
    private final FacturacionDeComprasMDB mdb = new FacturacionDeComprasMDB();

    FacturacionDeComprasMDBTest() {
        mdb.facturacion = ServicioDeFacturacionDePrueba.con(repositorio, arca, Clock.systemUTC());
        mdb.contexto = contexto;
    }

    private static TextMessage mensajeDeTexto(String texto, int entrega) throws JMSException {
        TextMessage recibido = mock(TextMessage.class);
        when(recibido.getText()).thenReturn(texto);
        when(recibido.getIntProperty("JMSXDeliveryCount")).thenReturn(entrega);
        return recibido;
    }

    @Test
    void unaCompraConfirmadaSeFacturaUnaVez() throws JMSException {
        mdb.onMessage(mensajeDeTexto(Ejemplos.pagoConfirmado().aJson(), 1));

        assertEquals(1, repositorio.cantidad());
        assertEquals(1, arca.pedidos.size());
        verify(contexto, never()).setRollbackOnly();
    }

    @Test
    void elMismoMensajeEntregadoDosVecesNoFacturaDosVeces() throws JMSException {
        String json = Ejemplos.pagoConfirmado().aJson();

        mdb.onMessage(mensajeDeTexto(json, 1));
        // La reentrega del broker trae el mismo texto.
        mdb.onMessage(mensajeDeTexto(json, 2));

        assertEquals(1, repositorio.cantidad());
        // La segunda vez ni siquiera se le pregunta a ARCA.
        assertEquals(1, arca.pedidos.size());
        verify(contexto, never()).setRollbackOnly();
    }

    @Test
    void conArcaCaidoElMensajeVuelveAlBrokerYLaReentregaFactura() throws JMSException {
        String json = Ejemplos.pagoConfirmado().aJson();
        arca.modo = ArcaEnMemoria.Modo.NO_RESPONDE;

        mdb.onMessage(mensajeDeTexto(json, 1));

        verify(contexto).setRollbackOnly();
        assertEquals(0, repositorio.cantidad());

        // ARCA vuelve antes de que se agoten los reintentos: la entrega siguiente factura.
        reset(contexto);
        arca.modo = ArcaEnMemoria.Modo.RESPONDE;
        mdb.onMessage(mensajeDeTexto(json, 2));

        verify(contexto, never()).setRollbackOnly();
        assertEquals(1, repositorio.cantidad());
    }

    @Test
    void unComprobanteRechazadoTambienVuelveAlBrokerParaTerminarEnLaDlq() throws JMSException {
        arca.modo = ArcaEnMemoria.Modo.RECHAZA;

        mdb.onMessage(mensajeDeTexto(Ejemplos.pagoConfirmado().aJson(), 1));

        verify(contexto).setRollbackOnly();
        assertEquals(0, repositorio.cantidad());
    }

    @Test
    void unTextoQueNoEsUnPagoConfirmadoVuelveAlBrokerSinPreguntarleAArca() throws JMSException {
        mdb.onMessage(mensajeDeTexto("esto no es un PAGO_CONFIRMADO", 1));

        verify(contexto).setRollbackOnly();
        assertEquals(0, arca.pedidos.size());
    }

    @Test
    void unMensajeQueNoEsDeTextoVuelveAlBroker() {
        mdb.onMessage(mock(Message.class));

        verify(contexto).setRollbackOnly();
        assertEquals(0, arca.pedidos.size());
    }

    @Test
    void unaFallaInesperadaSaleDelMdbYElContenedorDeshaceLaTransaccion() throws JMSException {
        IFacturacion conLaBaseCaida = mock(IFacturacion.class);
        PagoConfirmadoMensaje mensaje = Ejemplos.pagoConfirmado();
        // Así le llega al MDB una falla de la base dentro de un Session Bean.
        when(conLaBaseCaida.emitirFactura(mensaje)).thenThrow(new EJBException("la base no responde"));
        mdb.facturacion = conLaBaseCaida;
        TextMessage recibido = mensajeDeTexto(mensaje.aJson(), 1);

        assertThrows(EJBException.class, () -> mdb.onMessage(recibido));
        verifyNoInteractions(contexto);
    }

    @Test
    void escuchaElTopicoConSuscripcionDurablePropiaYElMismoSelectorQueLosDemas() {
        Map<String, String> configuracion = Arrays.stream(
                FacturacionDeComprasMDB.class.getAnnotation(MessageDriven.class).activationConfig())
                .collect(Collectors.toMap(ActivationConfigProperty::propertyName, ActivationConfigProperty::propertyValue));

        assertEquals(DestinosJms.TOPICO_COMPRA, configuracion.get("destinationLookup"));
        assertEquals("jakarta.jms.Topic", configuracion.get("destinationType"));
        assertEquals("tipoEvento = 'PAGO_CONFIRMADO'", configuracion.get("messageSelector"));
        assertEquals("Durable", configuracion.get("subscriptionDurability"));
        // Distinto del de auditoría (palco-auditoria / compras-confirmadas): cada suscriptor durable tiene su par.
        assertEquals("palco-facturacion", configuracion.get("clientId"));
        assertEquals("facturacion-compras-confirmadas", configuracion.get("subscriptionName"));
        assertEquals("1", configuracion.get("maxSession"));
    }
}
