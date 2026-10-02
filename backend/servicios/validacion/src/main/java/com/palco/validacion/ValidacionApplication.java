package com.palco.validacion;

import org.eclipse.microprofile.auth.LoginConfig;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** ServicioDeValidación. Valida autenticidad y uso único de la entrada en puerta (stateless). La API queda en {@code /api/validacion}. */
// Acepta el token JWT de ServicioDeUsuarios (header Authorization: Bearer ...).
@LoginConfig(authMethod = "MP-JWT", realmName = "palco")
@ApplicationPath("/")
public class ValidacionApplication extends Application {
}
