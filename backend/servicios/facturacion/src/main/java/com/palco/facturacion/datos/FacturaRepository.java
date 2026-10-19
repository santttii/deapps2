package com.palco.facturacion.datos;

import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.facturacion.dominio.Factura;

/**
 * Dónde se guardan y se buscan las facturas. {@code ServicioDeFacturacion}
 * conoce solo esta interfaz: en el servidor la implementa
 * {@link FacturaRepositoryJpa} contra la base, y en los tests un repositorio
 * en memoria.
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

    /** El tipo del conflicto de {@link #guardar}. No llega al cliente: lo atrapa el negocio. */
    String FACTURA_REPETIDA = "factura-repetida";
}
