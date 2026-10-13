package com.palco.pagos;

import org.eclipse.microprofile.auth.LoginConfig;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * ServicioDePagos. Cobra los pagos contra una pasarela (hoy simulada; Mercado
 * Pago después). La API queda en {@code /api/v1/pagos} y es interna: la llama
 * ServicioDeVentas con el rol {@code servicio}.
 */
// Acepta el token JWT de ServicioDeUsuarios (header Authorization: Bearer ...).
@LoginConfig(authMethod = "MP-JWT", realmName = "palco")
@ApplicationPath("/")
public class PagosApplication extends Application {
}
