package com.palco.ventas.mensajeria;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.ejb.ActivationConfigProperty;
import jakarta.ejb.EJBException;
import jakarta.ejb.MessageDriven;
import jakarta.ejb.MessageDrivenContext;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.palco.common.Textos;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.ventas.LogCapturado;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Venta;

/**
 * Qué hace el consumidor de la cola con cada mensaje, sin broker ni servidor:
 * el mensaje JMS, el contexto del MDB y los dos repositorios son dobles de
 * Mockito. Acá se prueba a quién delega y qué decide en cada caso. Que emitir
 * dos veces no cambie nada lo garantiza la base, con la condición de cada
 * UPDATE: eso se comprobó contra la base y en el servidor, no con dobles.
 */
class EmisionDeEntradasMDBTest {

    private static final String VENTA = "V-3f9a1c2b";

    /** El instante del reloj detenido, como lo tiene que anotar la emisión: en UTC y sin fracción de segundo. */
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 19, 21, 0, 7);

    private final VentaRepository ventas = mock(VentaRepository.class);
    private final EntradaRepository entradas = mock(EntradaRepository.class);
    private final MessageDrivenContext contexto = mock(MessageDrivenContext.class);
    private final EmisionDeEntradasMDB mdb = new EmisionDeEntradasMDB();

    EmisionDeEntradasMDBTest() {
        mdb.ventas = ventas;
        mdb.entradas = entradas;
        mdb.contexto = contexto;
        // El servidor corre en hora argentina: el reloj fijo comprueba que igual se anota en UTC.
        mdb.reloj = Clock.fixed(Instant.parse("2026-10-19T21:00:07.900Z"), ZoneOffset.ofHours(-3));
    }

    @Test
    void unMensajeValidoEmiteLasEntradasPendientesYDespuesPasaLaVentaAEmitida() throws JMSException {
        existeLaVenta();
        when(entradas.emitirPendientes(VENTA, AHORA)).thenReturn(2);
        when(ventas.marcarEmitida(VENTA)).thenReturn(true);

        mdb.onMessage(mensajeDeTexto(pagoConfirmado().aJson()));

        InOrder orden = inOrder(entradas, ventas);
        orden.verify(entradas).emitirPendientes(VENTA, AHORA);
        orden.verify(ventas).marcarEmitida(VENTA);
        verify(contexto, never()).setRollbackOnly();
    }

    @Test
    void siElMensajeLlegaOtraVezNoHayNadaPendienteYSeConfirmaSinError() throws JMSException {
        existeLaVenta();
        // Así responde la base a una entrega repetida: ningún UPDATE cambia una fila.
        when(entradas.emitirPendientes(VENTA, AHORA)).thenReturn(0);
        when(ventas.marcarEmitida(VENTA)).thenReturn(false);
        TextMessage repetido = mensajeDeTexto(pagoConfirmado().aJson());

        assertDoesNotThrow(() -> mdb.onMessage(repetido));

        // Sin rollback: el mensaje queda consumido y el broker no lo vuelve a entregar.
        verify(contexto, never()).setRollbackOnly();
    }

    @Test
    void elMensajeDeUnaVentaQueNoExisteSeDescartaSinEmitirNada() throws JMSException {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.empty());

        mdb.onMessage(mensajeDeTexto(pagoConfirmado().aJson()));

        verifyNoInteractions(entradas);
        verify(ventas, never()).marcarEmitida(anyString());
        // Tampoco vuelve al broker: reintentarlo no haría aparecer la venta.
        verify(contexto, never()).setRollbackOnly();
    }

    @Test
    void unTextoQueNoEsUnPagoConfirmadoVuelveAlBrokerYNoTocaLaBase() throws JMSException {
        mdb.onMessage(mensajeDeTexto("esto no es un PAGO_CONFIRMADO"));

        verify(contexto).setRollbackOnly();
        verifyNoInteractions(ventas, entradas);
    }

    @Test
    void unJsonIncompletoTambienVuelveAlBroker() throws JMSException {
        mdb.onMessage(mensajeDeTexto("{\"ventaId\":\"" + VENTA + "\"}"));

        verify(contexto).setRollbackOnly();
        verifyNoInteractions(ventas, entradas);
    }

    @Test
    void unMensajeQueNoEsDeTextoVuelveAlBroker() {
        mdb.onMessage(mock(Message.class));

        verify(contexto).setRollbackOnly();
        verifyNoInteractions(ventas, entradas);
    }

    @Test
    void siNoSePuedeLeerElTextoElMensajeVuelveAlBroker() throws JMSException {
        TextMessage ilegible = mock(TextMessage.class);
        when(ilegible.getText()).thenThrow(new JMSException("el broker cortó la conexión"));

        mdb.onMessage(ilegible);

        verify(contexto).setRollbackOnly();
        verifyNoInteractions(ventas, entradas);
    }

    @Test
    void siLaBaseFallaAlEmitirLaFallaSaleDelMDB() throws JMSException {
        existeLaVenta();
        // Así le llega al MDB una falla de la base dentro del repositorio, que es un Session Bean.
        when(entradas.emitirPendientes(VENTA, AHORA)).thenThrow(new EJBException("la base no responde"));
        TextMessage recibido = mensajeDeTexto(pagoConfirmado().aJson());

        // No la atrapa: con una excepción que sale de onMessage, el contenedor deshace la transacción
        // y el broker entrega el mensaje de nuevo. La venta no se toca.
        assertThrows(EJBException.class, () -> mdb.onMessage(recibido));
        verify(ventas, never()).marcarEmitida(anyString());
    }

    @Test
    void loQueEscribeEnElLogQuedaEnUnSoloRenglonAunqueElMensajeTraigaUnSaltoDeLinea() throws JMSException {
        // Inyección en el log: un id con un salto de línea pasa la validación del mensaje, que solo mira
        // el largo. Sin limpiarlo, quien publica podría hacer aparecer un renglón falso en server.log.
        String renglonFalso = "\n2026-10-13 14:00:00 INFO [falso] X";
        String descartado = new PagoConfirmadoMensaje("V-1" + renglonFalso, "user-titular", "ev-1",
                pagoConfirmado().entradas(), 60_500, Instant.parse("2026-10-16T21:30:00Z")).aJson();
        // Inválido porque la entrada no tiene titular: el motivo del rechazo nombra el id de la entrada.
        String invalido = new PagoConfirmadoMensaje(VENTA, "user-titular", "ev-1",
                List.of(new PagoConfirmadoMensaje.Entrada("T-1" + renglonFalso, "Platea B", null)),
                60_500, Instant.parse("2026-10-16T21:30:00Z")).aJson();
        when(ventas.buscarPorId("V-1" + renglonFalso)).thenReturn(Optional.empty());

        try (LogCapturado log = new LogCapturado(EmisionDeEntradasMDB.class)) {
            mdb.onMessage(mensajeDeTexto(descartado));
            mdb.onMessage(mensajeDeTexto(invalido));

            assertEquals(2, log.lineas().size());
            for (String linea : log.lineas()) {
                assertTrue(linea.chars().noneMatch(Textos::esDeControl), "Tiene un salto de línea: " + linea);
            }
            // No se pierde lo que llegó: queda dentro del renglón verdadero.
            assertTrue(log.lineas().get(0).contains("V-1 2026-10-13 14:00:00 INFO [falso] X"), log.lineas().get(0));
        }
    }

    @Test
    void laActivacionEsLaQueFijaElContratoDeMensajeria() {
        MessageDriven anotacion = EmisionDeEntradasMDB.class.getAnnotation(MessageDriven.class);
        assertNotNull(anotacion, "EmisionDeEntradasMDB no está declarado como MDB");
        Map<String, String> propiedades = new HashMap<>();
        for (ActivationConfigProperty propiedad : anotacion.activationConfig()) {
            propiedades.put(propiedad.propertyName(), propiedad.propertyValue());
        }

        assertEquals("EmisionDeEntradas", anotacion.name());
        // Una cola, no el tópico: es lo que hace que cada mensaje lo reciba un solo consumidor.
        assertEquals(Map.of(
                "destinationLookup", "java:/jms/queue/emisionEntradas",
                "destinationType", "jakarta.jms.Queue",
                "messageSelector", "tipoEvento = 'PAGO_CONFIRMADO'"), propiedades);
    }

    @Test
    void recibirElMensajeYEmitirVanEnUnaTransaccionDelContenedor() throws NoSuchMethodException {
        TransactionAttribute transaccion = EmisionDeEntradasMDB.class
                .getMethod("onMessage", Message.class)
                .getAnnotation(TransactionAttribute.class);

        assertNotNull(transaccion, "onMessage no declara su transacción");
        assertEquals(TransactionAttributeType.REQUIRED, transaccion.value());
    }

    // ---------------------------------------------------------------------------------------------

    private void existeLaVenta() {
        // El doble se crea antes y no dentro de thenReturn: Mockito no admite armar un doble en medio de otro.
        Venta venta = mock(Venta.class);
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(venta));
    }

    private static TextMessage mensajeDeTexto(String texto) throws JMSException {
        TextMessage recibido = mock(TextMessage.class);
        when(recibido.getText()).thenReturn(texto);
        return recibido;
    }

    /** El mensaje del ejemplo del contrato: una venta de dos entradas de Platea B. */
    private static PagoConfirmadoMensaje pagoConfirmado() {
        return new PagoConfirmadoMensaje(VENTA, "user-titular", "ev-1",
                List.of(new PagoConfirmadoMensaje.Entrada("T-9c41d07e", "Platea B",
                                new PagoConfirmadoMensaje.Titular("Ana Gómez", "30123456")),
                        new PagoConfirmadoMensaje.Entrada("T-51b7a2f0", "Platea B",
                                new PagoConfirmadoMensaje.Titular("Luis Pérez", "31234567"))),
                60_500, Instant.parse("2026-10-16T21:30:00Z"));
    }
}
