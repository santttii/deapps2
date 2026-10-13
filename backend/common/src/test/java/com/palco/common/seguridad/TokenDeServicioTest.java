package com.palco.common.seguridad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;

/** Cuándo se reutiliza el token guardado y cuándo se pide otro. No necesita servidor. */
class TokenDeServicioTest {

    private static final Instant INICIO = Instant.parse("2026-10-16T21:30:00Z");

    /** En vez de llamar a Usuarios devuelve un token numerado de 5 minutos y cuenta los pedidos. */
    private static class TokenSinRed extends TokenDeServicio {
        int pedidos;
        boolean usuariosCaido;

        TokenSinRed() {
            ponerHora(INICIO);
        }

        void ponerHora(Instant ahora) {
            reloj = Clock.fixed(ahora, ZoneOffset.UTC);
        }

        @Override
        Respuesta pedirAUsuarios() {
            if (usuariosCaido) {
                throw new ErroresDeNegocio.SinRespuesta("El servicio de usuarios no responde.");
            }
            pedidos++;
            return new Respuesta("token-" + pedidos, 300);
        }
    }

    @Test
    void guardaElTokenYNoVuelveAPedirlo() {
        TokenSinRed token = new TokenSinRed();

        assertEquals("token-1", token.obtener());
        assertEquals("token-1", token.obtener());
        assertEquals(1, token.pedidos);
    }

    @Test
    void pideOtroCuandoLeQuedanMenosDe30Segundos() {
        TokenSinRed token = new TokenSinRed();
        token.obtener(); // vence a los 300 segundos

        token.ponerHora(INICIO.plusSeconds(270)); // le quedan 30: todavía sirve
        assertEquals("token-1", token.obtener());

        token.ponerHora(INICIO.plusSeconds(271)); // le quedan 29: se renueva
        assertEquals("token-2", token.obtener());
        assertEquals(2, token.pedidos);
    }

    @Test
    void invalidarDescartaElTokenGuardado() {
        TokenSinRed token = new TokenSinRed();
        token.obtener();

        token.invalidar();

        assertEquals("token-2", token.obtener());
        assertEquals(2, token.pedidos);
    }

    @Test
    void siUsuariosNoRespondeAvisaYElProximoIntentoVuelveAPedir() {
        TokenSinRed token = new TokenSinRed();
        token.usuariosCaido = true;
        assertThrows(ErroresDeNegocio.SinRespuesta.class, token::obtener);

        token.usuariosCaido = false;
        assertEquals("token-1", token.obtener());
    }

    @Test
    void leeLaRespuestaDeUsuarios() {
        TokenDeServicio.Respuesta respuesta = TokenDeServicio
                .leerRespuesta("{\"token\":\"abc.def.ghi\",\"expiraEnSegundos\":300}");

        assertEquals("abc.def.ghi", respuesta.token());
        assertEquals(300, respuesta.expiraEnSegundos());
    }

    @Test
    void unaRespuestaSinTokenUtilizableEsSinRespuesta() {
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> TokenDeServicio.leerRespuesta("<html>error</html>"));
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> TokenDeServicio.leerRespuesta("{}"));
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> TokenDeServicio.leerRespuesta("null"));
        assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> TokenDeServicio.leerRespuesta("{\"token\":\"\",\"expiraEnSegundos\":300}"));
        assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> TokenDeServicio.leerRespuesta("{\"token\":\"abc\",\"expiraEnSegundos\":0}"));
    }

    @Test
    void laDireccionYElSecretoTienenValorAunqueNoEstenLasVariablesDeEntorno() {
        assertFalse(TokenDeServicio.URL_INTERNA.isBlank());
        assertFalse(TokenDeServicio.secretoCompartido().isBlank());
    }
}
