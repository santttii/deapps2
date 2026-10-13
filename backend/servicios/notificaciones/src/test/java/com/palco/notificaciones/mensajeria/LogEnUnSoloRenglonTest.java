package com.palco.notificaciones.mensajeria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import jakarta.ejb.MessageDrivenContext;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.Test;

import com.palco.common.Textos;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Entrada;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Titular;
import com.palco.notificaciones.Ejemplos;
import com.palco.notificaciones.LogCapturado;
import com.palco.notificaciones.negocio.IAuditoria;

/**
 * Inyección en el log: los dos consumidores escriben en el log datos que
 * vienen en el mensaje. Si uno de esos datos trae un salto de línea, quien lo
 * mandó puede hacer aparecer un renglón falso en {@code server.log}, que es la
 * evidencia que se le muestra a la cátedra. Acá se comprueba que, llegue lo
 * que llegue, cada línea que escriben queda en un solo renglón.
 */
class LogEnUnSoloRenglonTest {

    /** Lo que quiere hacer aparecer quien ataca: un renglón con la forma de los del servidor. */
    private static final String RENGLON_FALSO = "\n2026-10-13 14:00:00 INFO [falso] X";

    /** Un id con un salto de línea adentro. Pasa la validación del mensaje, que solo mira el largo. */
    private static final String VENTA_CON_SALTO = "V-1" + RENGLON_FALSO;

    private static final Titular ANA = new Titular("Ana Gómez", Ejemplos.DNI_DE_ANA);

    /** Un mensaje válido cuyo id de venta trae el renglón falso. */
    private static String validoConSaltoEnElId() {
        return new PagoConfirmadoMensaje(VENTA_CON_SALTO, "user-titular", "ev-1",
                List.of(new Entrada("T-9c41d07e", "Campo", ANA)), 30250, Instant.parse("2026-10-16T21:30:00Z"))
                .aJson();
    }

    /** Un mensaje inválido (la entrada no tiene titular): el motivo del rechazo nombra el id de la entrada. */
    private static String invalidoConSaltoEnElIdDeLaEntrada() {
        return new PagoConfirmadoMensaje("V-3f9a1c2b", "user-titular", "ev-1",
                List.of(new Entrada("T-1" + RENGLON_FALSO, "Campo", null)), 30250,
                Instant.parse("2026-10-16T21:30:00Z"))
                .aJson();
    }

    private static TextMessage mensajeDeTexto(String texto) throws JMSException {
        TextMessage recibido = mock(TextMessage.class);
        when(recibido.getText()).thenReturn(texto);
        when(recibido.getJMSMessageID()).thenReturn("ID:8f1c2d3e-0000-1111-2222-333344445555");
        return recibido;
    }

    private static void assertCadaLineaEnUnSoloRenglon(List<String> lineas) {
        for (String linea : lineas) {
            assertTrue(linea.chars().noneMatch(Textos::esDeControl), "Tiene un salto de línea: " + linea);
        }
    }

    @Test
    void elAvisoEscribeUnSoloRenglonPorUnMensajeValidoConUnSaltoEnElId() throws JMSException {
        AvisoDeCompraMDB aviso = new AvisoDeCompraMDB();
        aviso.notificaciones = mensaje -> { };

        try (LogCapturado log = new LogCapturado(AvisoDeCompraMDB.class)) {
            aviso.onMessage(mensajeDeTexto(validoConSaltoEnElId()));

            assertEquals(1, log.lineas().size());
            assertCadaLineaEnUnSoloRenglon(log.lineas());
            // No se pierde lo que llegó: queda todo dentro del renglón verdadero.
            assertTrue(log.lineas().get(0).contains("V-1 2026-10-13 14:00:00 INFO [falso] X"), log.lineas().get(0));
        }
    }

    @Test
    void elAvisoEscribeUnSoloRenglonAlDescartarUnMensajeInvalido() throws JMSException {
        AvisoDeCompraMDB aviso = new AvisoDeCompraMDB();
        aviso.notificaciones = mensaje -> { };

        try (LogCapturado log = new LogCapturado(AvisoDeCompraMDB.class)) {
            aviso.onMessage(mensajeDeTexto(invalidoConSaltoEnElIdDeLaEntrada()));

            assertEquals(1, log.lineas().size());
            assertCadaLineaEnUnSoloRenglon(log.lineas());
        }
    }

    @Test
    void auditoriaEscribeUnSoloRenglonPorUnMensajeValidoConUnSaltoEnElId() throws JMSException {
        AuditoriaDeComprasMDB auditoria = new AuditoriaDeComprasMDB();
        auditoria.auditoria = mock(IAuditoria.class);
        auditoria.contexto = mock(MessageDrivenContext.class);

        try (LogCapturado log = new LogCapturado(AuditoriaDeComprasMDB.class)) {
            auditoria.onMessage(mensajeDeTexto(validoConSaltoEnElId()));

            assertEquals(1, log.lineas().size());
            assertCadaLineaEnUnSoloRenglon(log.lineas());
        }
    }

    @Test
    void auditoriaEscribeUnSoloRenglonAlDevolverUnMensajeInvalido() throws JMSException {
        AuditoriaDeComprasMDB auditoria = new AuditoriaDeComprasMDB();
        auditoria.auditoria = mock(IAuditoria.class);
        auditoria.contexto = mock(MessageDrivenContext.class);

        try (LogCapturado log = new LogCapturado(AuditoriaDeComprasMDB.class)) {
            auditoria.onMessage(mensajeDeTexto(invalidoConSaltoEnElIdDeLaEntrada()));

            assertEquals(1, log.lineas().size());
            assertCadaLineaEnUnSoloRenglon(log.lineas());
        }
    }
}
