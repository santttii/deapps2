package com.palco.notificaciones;

import org.eclipse.microprofile.auth.LoginConfig;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** ServicioDeNotificaciones. Confirmaciones, recordatorios y alertas, de forma asincrónica. La API queda en {@code /api/notificaciones}. */
// Acepta el token JWT de ServicioDeUsuarios (header Authorization: Bearer ...).
@LoginConfig(authMethod = "MP-JWT", realmName = "palco")
@ApplicationPath("/")
public class NotificacionesApplication extends Application {
}
