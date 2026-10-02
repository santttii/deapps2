package com.palco.eventos;

import org.eclipse.microprofile.auth.LoginConfig;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** ServicioDeEventos. Catálogo de eventos, tipos de entrada y cupos. La API queda en {@code /api/eventos}. */
// Acepta el token JWT de ServicioDeUsuarios (header Authorization: Bearer ...).
@LoginConfig(authMethod = "MP-JWT", realmName = "palco")
@ApplicationPath("/")
public class EventosApplication extends Application {
}
