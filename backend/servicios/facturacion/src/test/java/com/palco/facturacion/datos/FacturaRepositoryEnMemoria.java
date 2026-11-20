package com.palco.facturacion.datos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.palco.common.ErroresDeNegocio;
import com.palco.facturacion.dominio.ComprobanteRechazado;
import com.palco.facturacion.dominio.Factura;

/**
 * Repositorio para los tests: guarda las facturas y los rechazos en listas.
 * Está escrito a mano, y no con Mockito, porque necesita comportarse como la
 * base: rechaza una segunda factura o un segundo rechazo de la misma venta,
 * como las restricciones únicas. Que la base de verdad lo haga lo prueba
 * {@code FacturaEnH2Test}.
 */
public class FacturaRepositoryEnMemoria implements FacturaRepository {

    private final List<Factura> facturas = new ArrayList<>();
    private final List<ComprobanteRechazado> rechazos = new ArrayList<>();

    /** Si no es null, se corre antes de guardar: simula otra entrega que guardó primero. */
    public Runnable antesDeGuardar;

    /** Si no es null, el próximo {@link #guardarRechazo} la lanza y no guarda: simula la base caída. */
    public RuntimeException fallaAlGuardarRechazo;

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

    @Override
    public Optional<ComprobanteRechazado> buscarRechazoPorVenta(String ventaId) {
        return rechazos.stream().filter(r -> r.getVentaId().equals(ventaId)).findFirst();
    }

    @Override
    public void guardarRechazo(ComprobanteRechazado nuevo) {
        if (fallaAlGuardarRechazo != null) {
            RuntimeException falla = fallaAlGuardarRechazo;
            fallaAlGuardarRechazo = null;
            throw falla;
        }
        boolean repetido = rechazos.stream().anyMatch(r -> r.getId().equals(nuevo.getId())
                || r.getVentaId().equals(nuevo.getVentaId()));
        if (repetido) {
            throw new ErroresDeNegocio.Conflicto(RECHAZO_REPETIDO, "Esa venta ya tiene un rechazo de ARCA anotado.");
        }
        rechazos.add(nuevo);
    }

    public int cantidad() {
        return facturas.size();
    }

    public int cantidadDeRechazos() {
        return rechazos.size();
    }
}
