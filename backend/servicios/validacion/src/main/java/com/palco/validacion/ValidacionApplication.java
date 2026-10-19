package com.palco.validacion;

import org.eclipse.microprofile.auth.LoginConfig;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * ServicioDeValidación. Valida en la puerta que la entrada exista, sea del
 * evento y se use una sola vez (stateless). La API queda en
 * {@code /api/v1/validacion}, con el recurso {@code escaneos}.
 */
// Acepta el token JWT de ServicioDeUsuarios (header Authorization: Bearer ...).
@LoginConfig(authMethod = "MP-JWT", realmName = "palco")
@ApplicationPath("/")
public class ValidacionApplication extends Application {
}
