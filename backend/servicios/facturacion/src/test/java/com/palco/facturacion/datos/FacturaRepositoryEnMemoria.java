package com.palco.facturacion.datos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.palco.common.ErroresDeNegocio;
import com.palco.facturacion.dominio.Factura;

/**
 * Repositorio para los tests: guarda las facturas en una lista. Está escrito a
 * mano, y no con Mockito, porque necesita comportarse como la base: rechaza
 * una segunda factura de la misma venta, como la restricción única. Que la
 * base de verdad lo haga lo prueba {@code FacturaEnH2Test}.
 */
public class FacturaRepositoryEnMemoria implements FacturaRepository {

    private final List<Factura> facturas = new ArrayList<>();

    /** Si no es null, se corre antes de guardar: simula otra entrega que guardó primero. */
    public Runnable antesDeGuardar;

    @Override
    public Optional<Factura> buscarPorVenta(String ventaId) {
        return facturas.stream().filter(f -> f.getVentaId().equals(ventaId)).findFirst();
    }

    @Override
    public void guardar(Factura nueva) {
        if (antesDeGuardar != null) {
            Runnable otra = antesDeGuardar;
            antesDeGuardar = null;
            otra.run();
        }
        boolean repetida = facturas.stream().anyMatch(f -> f.getId().equals(nueva.getId())
                || f.getVentaId().equals(nueva.getVentaId()));
        if (repetida) {
            throw new ErroresDeNegocio.Conflicto(FACTURA_REPETIDA, "Esa venta ya tiene factura.");
        }
        facturas.add(nueva);
    }

    public int cantidad() {
        return facturas.size();
    }
}
