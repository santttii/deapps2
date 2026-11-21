package com.palco.facturacion.datos;

import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.facturacion.dominio.ComprobanteRechazado;
import com.palco.facturacion.dominio.Factura;

/**
 * Dónde se guardan y se buscan las facturas y los comprobantes que ARCA
 * rechazó. {@code ServicioDeFacturacion} conoce solo esta interfaz: en el
 * servidor la implementa {@link FacturaRepositoryJpa} contra la base, y en los
 * tests un repositorio en memoria.
 */
@Local
public interface FacturaRepository {

    Optional<Factura> buscarPorVenta(String ventaId);

    /**
     * Guarda una factura nueva. Lanza {@code ErroresDeNegocio.Conflicto} con
     * el tipo {@link #FACTURA_REPETIDA} si esa venta ya tiene factura: lo
     * impide la base, con su restricción única.
     */
    void guardar(Factura factura);

    /** El rechazo de ARCA de esa venta, si lo hubo. */
    Optional<ComprobanteRechazado> buscarRechazoPorVenta(String ventaId);

    /**
     * Anota un comprobante que ARCA rechazó. Lanza
     * {@code ErroresDeNegocio.Conflicto} con el tipo {@link #RECHAZO_REPETIDO}
     * si esa venta ya tiene uno anotado: lo impide la base, con su restricción
     * única.
     */
    void guardarRechazo(ComprobanteRechazado rechazo);

    /** El tipo del conflicto de {@link #guardar}. No llega al cliente: lo atrapa el negocio. */
    String FACTURA_REPETIDA = "factura-repetida";

    /** El tipo del conflicto de {@link #guardarRechazo}. Tampoco llega al cliente. */
    String RECHAZO_REPETIDO = "rechazo-repetido";
}
