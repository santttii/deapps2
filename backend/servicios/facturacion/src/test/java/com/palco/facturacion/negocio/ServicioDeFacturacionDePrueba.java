package com.palco.facturacion.negocio;

import java.time.Clock;

import com.palco.facturacion.datos.FacturaRepository;
import com.palco.facturacion.negocio.externos.Arca;

/**
 * Arma el {@link ServicioDeFacturacion} de verdad para los tests de otros
 * paquetes (el MDB y el recurso), con lo que el servidor le inyectaría
 * reemplazado por dobles.
 */
public final class ServicioDeFacturacionDePrueba {

    private ServicioDeFacturacionDePrueba() {
    }

    public static IFacturacion con(FacturaRepository facturas, Arca arca, Clock reloj) {
        ServicioDeFacturacion servicio = new ServicioDeFacturacion();
        servicio.facturas = facturas;
        servicio.arca = arca;
        servicio.reloj = reloj;
        return servicio;
    }
}
