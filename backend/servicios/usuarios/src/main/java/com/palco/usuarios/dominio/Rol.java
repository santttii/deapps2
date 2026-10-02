package com.palco.usuarios.dominio;

/**
 * Roles de Palco. El nombre en minúscula es lo que se guarda, lo que viaja en
 * el token (claim "groups") y lo que se usa en {@code @RolesAllowed}.
 */
public enum Rol {
    /** Comprador. */
    USER,
    /** Organizador de eventos. */
    ORGANIZER,
    /** Personal de puerta. */
    STAFF;

    public String nombre() {
        return name().toLowerCase();
    }
}
