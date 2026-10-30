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

    /**
     * Email del usuario (claim "upn", que es el nombre del principal). Va en
     * minúsculas: así se lo compara con un email tipeado a mano.
     */
    public static String email(SecurityContext seguridad) {
        if (seguridad.getUserPrincipal() != null && seguridad.getUserPrincipal().getName() != null) {
            return seguridad.getUserPrincipal().getName().strip().toLowerCase(java.util.Locale.ROOT);
        }
        throw new ErroresDeNegocio.NoAutenticado("Tenés que iniciar sesión.");
    }
}
