package com.palco.common.seguridad;

import org.eclipse.microprofile.jwt.JsonWebToken;

import jakarta.ws.rs.core.SecurityContext;

import com.palco.common.ErroresDeNegocio;

/** Datos del usuario que hace el pedido, sacados del token JWT. */
public final class Sesion {

    private Sesion() {
    }

    /**
     * Id del usuario (claim "sub"). Ojo: {@code getUserPrincipal().getName()}
     * devuelve el email, no el id.
     */
    public static String usuarioId(SecurityContext seguridad) {
        if (seguridad.getUserPrincipal() instanceof JsonWebToken token && token.getSubject() != null) {
            return token.getSubject();
        }
        throw new ErroresDeNegocio.NoAutenticado("Tenés que iniciar sesión.");
    }
}
