package com.palco.ventas;

import org.eclipse.microprofile.auth.LoginConfig;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * ServicioDeVentas. Orquesta el checkout de punta a punta (stateful). La API
 * queda en {@code /api/v1}, con los recursos {@code ventas} y {@code entradas}.
 */
// Acepta el token JWT de ServicioDeUsuarios (header Authorization: Bearer ...).
@LoginConfig(authMethod = "MP-JWT", realmName = "palco")
@ApplicationPath("/")
public class VentasApplication extends Application {
}
