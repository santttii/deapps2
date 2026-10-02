package com.palco.ventas;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** ServicioDeVentas. Orquesta el checkout de punta a punta (stateful). La API queda en {@code /api/ventas}. */
@ApplicationPath("/")
public class VentasApplication extends Application {
}
