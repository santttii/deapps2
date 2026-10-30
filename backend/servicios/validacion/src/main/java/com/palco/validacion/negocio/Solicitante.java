package com.palco.validacion.negocio;

/**
 * Quién hace el pedido, sacado de su token: el id (claim "sub"), el email
 * (claim "upn", en minúsculas) y si es organizador o personal de puerta. Con
 * esto el negocio decide qué eventos le corresponden (limitación L17).
 */
public record Solicitante(String id, String email, boolean organizador) {

    public static Solicitante staff(String id, String email) {
        return new Solicitante(id, email, false);
    }

    public static Solicitante organizador(String id, String email) {
        return new Solicitante(id, email, true);
    }
}
