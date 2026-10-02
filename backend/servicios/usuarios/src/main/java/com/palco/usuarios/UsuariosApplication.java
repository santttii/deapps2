package com.palco.usuarios;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** ServicioDeUsuarios. Registro, autenticación y roles (comprador, organizador, staff). La API queda en {@code /api/usuarios}. */
@ApplicationPath("/")
public class UsuariosApplication extends Application {
}
