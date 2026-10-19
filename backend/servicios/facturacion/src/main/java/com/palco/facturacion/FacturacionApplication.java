package com.palco.facturacion;

import org.eclipse.microprofile.auth.LoginConfig;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * ServicioDeFacturación. Factura cada compra confirmada pidiéndole el CAE a
 * ARCA (simulado) por SOAP. La API queda en {@code /api/v1/facturas}: la
 * consulta de la factura de una venta y el {@code health}.
 */
// Acepta el token JWT de ServicioDeUsuarios (header Authorization: Bearer ...).
@LoginConfig(authMethod = "MP-JWT", realmName = "palco")
@ApplicationPath("/")
public class FacturacionApplication extends Application {
}
