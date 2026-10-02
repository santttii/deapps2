package com.palco.validacion;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** ServicioDeValidación. Valida autenticidad y uso único de la entrada en puerta (stateless). La API queda en {@code /api/validacion}. */
@ApplicationPath("/")
public class ValidacionApplication extends Application {
}
