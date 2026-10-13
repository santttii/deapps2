package com.palco.usuarios.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.palco.common.seguridad.TokenDeServicio;

/** Qué dice el token de rol servicio y con qué secreto se entrega. No necesita servidor ni base. */
class TokenDeServicioEmitidoTest {

    @Test
    void llevaElRolServicioYDuraCincoMinutos() {
        Instant ahora = Instant.parse("2026-10-16T21:30:00Z");

        Map<String, Object> claims = ServicioDeUsuarios.claimsDeServicio(ahora);

        assertEquals("palco", claims.get("iss"));
        assertEquals("servicio", claims.get("sub"));
        assertEquals("servicio@palco.interno", claims.get("upn"));
        assertEquals(List.of("servicio"), claims.get("groups"));
        assertEquals(Long.valueOf(ahora.getEpochSecond()), claims.get("iat"));
        assertEquals(Long.valueOf(ahora.getEpochSecond() + 300), claims.get("exp"));
    }

    @Test
    void cadaTokenTieneUnIdDistinto() {
        Instant ahora = Instant.parse("2026-10-16T21:30:00Z");

        assertNotEquals(ServicioDeUsuarios.claimsDeServicio(ahora).get("jti"),
                ServicioDeUsuarios.claimsDeServicio(ahora).get("jti"));
    }

    @Test
    void soloSeEntregaConElSecretoCompartido() {
        String secreto = TokenDeServicio.secretoCompartido();

        assertTrue(ServicioDeUsuarios.esElSecretoCompartido(secreto));
        assertFalse(ServicioDeUsuarios.esElSecretoCompartido(secreto + "x"));
        assertFalse(ServicioDeUsuarios.esElSecretoCompartido(secreto.substring(1)));
        assertFalse(ServicioDeUsuarios.esElSecretoCompartido(""));
        assertFalse(ServicioDeUsuarios.esElSecretoCompartido(null));
    }
}
