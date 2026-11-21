package com.palco.notificaciones.negocio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.jms.JMSContext;
import jakarta.jms.JMSProducer;
import jakarta.jms.Topic;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.mensajeria.DestinosJms;
import com.palco.common.mensajeria.MensajeInvalido;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.mensajeria.PublicadorPagoConfirmado;

/**
 * Qué publican los endpoints de prueba. El publicador se reemplaza con una
 * lambda que anota lo publicado: que el mensaje no salga cuando la transacción
 * se deshace lo hace el servidor, y eso se prueba en vivo.
 */
class PruebasDeMensajeriaTest {

    private static final Instant AHORA = Instant.parse("2026-10-16T21:30:05.789Z");

    private final List<PagoConfirmadoMensaje> publicados = new ArrayList<>();
    private final PruebasDeMensajeria pruebas = new PruebasDeMensajeria();

    PruebasDeMensajeriaTest() {
        pruebas.publicador = new PublicadorPagoConfirmado() {
            @Override
            public void publicar(PagoConfirmadoMensaje mensaje) {
                publicados.add(mensaje);
            }

            @Override
            public void publicarReventa(PagoConfirmadoMensaje mensaje) {
                throw new AssertionError("Las pruebas de mensajería publican PAGO_CONFIRMADO.");
            }
        };
    }

    @Test
    void elEjemploPasaLaValidacionDelContrato() {
        // Si no la pasara, el publicador real lo rechazaría y la prueba no probaría nada.
        assertDoesNotThrow(() -> PruebasDeMensajeria.ejemplo(AHORA).validar());
    }

    @Test
    void elEjemploSeReconocePorSuVentaIdYLlevaLaHoraTruncadaASegundos() {
        PagoConfirmadoMensaje ejemplo = PruebasDeMensajeria.ejemplo(AHORA);

        assertTrue(ejemplo.ventaId().matches("prueba-[0-9a-f]{8}"), ejemplo.ventaId());
        assertEquals(Instant.parse("2026-10-16T21:30:05Z"), ejemplo.timestamp());
    }

    @Test
    void cadaEjemploTieneUnVentaIdDistinto() {
        assertNotEquals(PruebasDeMensajeria.ejemplo(AHORA).ventaId(), PruebasDeMensajeria.ejemplo(AHORA).ventaId());
    }

    @Test
    void publicarEjemploDevuelveLoMismoQuePublico() {
        PagoConfirmadoMensaje devuelto = pruebas.publicarEjemplo();

        assertEquals(List.of(devuelto), publicados);
    }

    @Test
    void elCasoConFallaPublicaYDespuesLanzaConflicto() {
        assertThrows(ErroresDeNegocio.Conflicto.class, pruebas::publicarYDespuesFallar);

        // Llegó a publicar antes de fallar: lo que se prueba en vivo es que ese mensaje no sale.
        assertEquals(1, publicados.size());
    }

    @Test
    void elMensajeInvalidoVaSoloAlTopicoConSuTipoDeEventoYNoPasaElContrato() {
        // El contexto JMS y el tópico son dobles: que el mensaje llegue a la DLQ se prueba en vivo.
        JMSContext contexto = mock(JMSContext.class);
        JMSProducer productor = mock(JMSProducer.class);
        when(contexto.createProducer()).thenReturn(productor);
        when(productor.setProperty(anyString(), anyString())).thenReturn(productor);
        pruebas.contexto = contexto;
        pruebas.topico = mock(Topic.class);

        pruebas.publicarMensajeInvalido();

        // Con el tipoEvento lo dejan pasar los filtros de los MDB: sin él no le llegaría a nadie.
        verify(productor).setProperty(DestinosJms.PROPIEDAD_TIPO_EVENTO, DestinosJms.PAGO_CONFIRMADO);
        ArgumentCaptor<String> texto = ArgumentCaptor.forClass(String.class);
        verify(productor).send(eq(pruebas.topico), texto.capture());
        // Tiene que ser algo que los consumidores rechacen: si no, no habría reintentos que mirar.
        assertThrows(MensajeInvalido.class, () -> PagoConfirmadoMensaje.desdeJson(texto.getValue()));
        // No pasa por el publicador real, que se negaría a mandarlo.
        assertTrue(publicados.isEmpty());
    }
}
