package com.palco.notificaciones.negocio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
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
}
