package com.palco.pagos;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/** ServicioDePagos. Procesa, confirma y reembolsa pagos contra Mercado Pago. La API queda en {@code /api/pagos}. */
@ApplicationPath("/")
public class PagosApplication extends Application {
}
