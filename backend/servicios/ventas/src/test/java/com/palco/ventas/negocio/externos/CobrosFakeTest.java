package com.palco.ventas.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;

/** Los tres resultados del cobro simulado, que se eligen con el token. */
class CobrosFakeTest {

    private static final String CLAVE = "5d3c1f0e-8a4b-4c7d-9e2f-1a2b3c4d5e6f";
    private static final String OTRA_CLAVE = "0b9a8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d";

    private final Cobros cobros = new CobrosFake();

    @Test
    void cualquierTokenComunApruebaElCobro() {
        ResultadoDeCobro resultado = cobros.cobrar("V-3f9a1c2b", 60_500, "tarjeta", "tok_test", CLAVE);

        assertTrue(resultado.aprobado());
        assertEquals("APROBADO", resultado.estado());
        assertNull(resultado.motivo());
        assertTrue(resultado.pagoId().matches("P-[0-9a-f]{8}"), resultado.pagoId());
    }

    @Test
    void elTokenDeRechazoDevuelveUnRechazoConSuMotivo() {
        ResultadoDeCobro resultado = cobros.cobrar("V-3f9a1c2b", 60_500, "tarjeta", "tok_rechazado", CLAVE);

        assertFalse(resultado.aprobado());
        assertEquals("RECHAZADO", resultado.estado());
        assertEquals("fondos_insuficientes", resultado.motivo());
    }

    @Test
    void elTokenSinRespuestaLanzaSinRespuesta() {
        assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> cobros.cobrar("V-3f9a1c2b", 60_500, "tarjeta", "tok_sin_respuesta", CLAVE));
    }

    @Test
    void laMismaClaveDaElMismoPagoYOtraClaveDaOtro() {
        String primero = cobros.cobrar("V-3f9a1c2b", 60_500, "tarjeta", "tok_test", CLAVE).pagoId();
        String repetido = cobros.cobrar("V-3f9a1c2b", 60_500, "tarjeta", "tok_test", CLAVE).pagoId();
        String otro = cobros.cobrar("V-3f9a1c2b", 60_500, "tarjeta", "tok_test", OTRA_CLAVE).pagoId();

        assertEquals(primero, repetido);
        assertNotEquals(primero, otro);
    }

    @Test
    void elSimuladorNoGuardaLosCobrosYNoPuedeDecirComoTerminoUno() {
        cobros.cobrar("V-3f9a1c2b", 60_500, "tarjeta", "tok_test", CLAVE);

        // Ante la duda, la conciliación deja la venta como está: no la vence con un cobro que pudo hacerse.
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> cobros.buscarCobro("V-3f9a1c2b", CLAVE));
    }
}
