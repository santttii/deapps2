package com.palco.pagos.pasarela;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.pagos.dominio.EstadoPago;

/** Los tres caminos de la pasarela simulada. No necesita servidor ni base. */
class PagosFakeTest {

    private static final String CLAVE = "5d3c1f0e-8a4b-4c7d-9e2f-1a2b3c4d5e6f";

    private final PagosFake pasarela = new PagosFake();

    @Test
    void conUnTokenCualquieraAprueba() {
        ResultadoPasarela resultado = pasarela.cobrar("V-3f9a1c2b", 60500, "tarjeta", "tok_test", CLAVE);

        assertEquals(EstadoPago.APROBADO, resultado.estado());
        assertNull(resultado.motivo());
        assertEquals("fake-" + CLAVE, resultado.idExterno());
    }

    @Test
    void conElTokenDeRechazoRechazaYDiceElMotivo() {
        ResultadoPasarela resultado = pasarela.cobrar("V-3f9a1c2b", 60500, "tarjeta", "tok_rechazado", CLAVE);

        assertEquals(EstadoPago.RECHAZADO, resultado.estado());
        assertEquals("fondos_insuficientes", resultado.motivo());
        assertEquals("fake-" + CLAVE, resultado.idExterno());
    }

    @Test
    void conElTokenSinRespuestaNoContesta() {
        assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> pasarela.cobrar("V-3f9a1c2b", 60500, "tarjeta", "tok_sin_respuesta", CLAVE));
    }

    @Test
    void elMismoPedidoDaSiempreElMismoResultado() {
        ResultadoPasarela primero = pasarela.cobrar("V-3f9a1c2b", 60500, "tarjeta", "tok_rechazado", CLAVE);
        ResultadoPasarela segundo = pasarela.cobrar("V-3f9a1c2b", 60500, "tarjeta", "tok_rechazado", CLAVE);

        assertEquals(primero, segundo);
    }

    @Test
    void seLlamaFakeQueEsElValorDeLaConfiguracion() {
        assertEquals("fake", pasarela.nombre());
    }
}
