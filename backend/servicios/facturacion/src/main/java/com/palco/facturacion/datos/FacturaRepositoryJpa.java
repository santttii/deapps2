package com.palco.facturacion.datos;

import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.datos.BaseDeDatos;
import com.palco.facturacion.dominio.Factura;

/**
 * Stateless: no guarda nada entre llamadas; el {@code EntityManager} se lo da
 * el contenedor en cada una.
 *
 * Cada método corre en su propia transacción, corta, que el contenedor abre y
 * cierra (el comportamiento por defecto de un Session Bean): quien llama,
 * {@code ServicioDeFacturacion}, no tiene transacción, porque entre buscar y
 * guardar espera a ARCA. Así la conexión a la base se toma y se devuelve
 * dentro de cada método.
 */
@Stateless
public class FacturaRepositoryJpa implements FacturaRepository {

    @PersistenceContext(unitName = "facturacion")
    EntityManager em;

    @Override
    public Optional<Factura> buscarPorVenta(String ventaId) {
        return em.createQuery("select f from Factura f where f.ventaId = :venta", Factura.class)
                .setParameter("venta", ventaId)
                .getResultStream()
                .findFirst();
    }

    @Override
    public void guardar(Factura factura) {
        try {
            em.persist(factura);
            // Como el id lo asigna el código, el INSERT sale recién acá. Se fuerza ahora para
            // que una venta repetida falle dentro de este método y no al cerrar la transacción.
            em.flush();
        } catch (PersistenceException e) {
            // Solo una clave repetida es un conflicto; cualquier otra falla de la base sigue siendo un error.
            if (BaseDeDatos.esClaveDuplicada(e)) {
                throw new ErroresDeNegocio.Conflicto(FACTURA_REPETIDA, "Esa venta ya tiene factura.");
            }
            throw e;
        }
    }
}
