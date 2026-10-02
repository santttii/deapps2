package com.palco.app;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** Todos los endpoints REST de Palco cuelgan de {@code /api}. */
@ApplicationPath("/api")
public class PalcoApplication extends Application {
}
