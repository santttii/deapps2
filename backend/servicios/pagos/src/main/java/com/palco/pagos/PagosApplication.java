package com.palco.pagos;

import org.eclipse.microprofile.auth.LoginConfig;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** ServicioDePagos. Procesa, confirma y reembolsa pagos contra Mercado Pago. La API queda en {@code /api/pagos}. */
// Acepta el token JWT de ServicioDeUsuarios (header Authorization: Bearer ...).
@LoginConfig(authMethod = "MP-JWT", realmName = "palco")
@ApplicationPath("/")
public class PagosApplication extends Application {
}
