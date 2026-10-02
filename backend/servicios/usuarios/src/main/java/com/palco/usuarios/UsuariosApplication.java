package com.palco.usuarios;

import org.eclipse.microprofile.auth.LoginConfig;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** ServicioDeUsuarios. Registro, autenticación y roles (comprador, organizador, staff). La API queda en {@code /api/usuarios}. */
// Acepta el token JWT de ServicioDeUsuarios (header Authorization: Bearer ...).
@LoginConfig(authMethod = "MP-JWT", realmName = "palco")
@ApplicationPath("/")
public class UsuariosApplication extends Application {
}
