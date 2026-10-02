package com.palco.reventa;

import org.eclipse.microprofile.auth.LoginConfig;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** ServicioDeReventa. Publicación y compra de reventa oficial con tope de precio. La API queda en {@code /api/reventa}. */
// Acepta el token JWT de ServicioDeUsuarios (header Authorization: Bearer ...).
@LoginConfig(authMethod = "MP-JWT", realmName = "palco")
@ApplicationPath("/")
public class ReventaApplication extends Application {
}
