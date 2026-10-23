package com.palco.pagos.datos;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.datos.BaseDeDatos;
import com.palco.pagos.dominio.EstadoPago;
import com.palco.pagos.dominio.Pago;

/**
 * Stateless: no guarda nada entre llamadas; el {@code EntityManager} se lo da
 * el contenedor en cada una.
 *
 * Cada método corre en su propia transacción, que el contenedor abre y cierra
 * solo (es el comportamiento por defecto de un Session Bean). Son cortas a
 * propósito: quien llama, {@code ServicioDePagos}, no tiene transacción, así
 * que la conexión a la base se toma y se devuelve dentro de cada método.
 */
@Stateless
public class PagoRepositoryJpa implements PagoRepository {

    @PersistenceContext(unitName = "pagos")
    EntityManager em;

    @Override
    public Optional<Pago> buscarPorId(String pagoId) {
        return Optional.ofNullable(em.find(Pago.class, pagoId));
    }

    @Override
    public Optional<Pago> buscarPorClave(String claveIdempotencia) {
        return em.createQuery("select p from Pago p where p.claveIdempotencia = :clave", Pago.class)
                .setParameter("clave", claveIdempotencia)
                .getResultStream()
                .findFirst();
    }

    @Override
    public void guardar(Pago pago) {
        try {
            em.persist(pago);
            // Como el id lo asigna el código, el INSERT sale recién acá. Se fuerza ahora para
            // que una clave repetida falle dentro de este método y no al cerrar la transacción.
            em.flush();
        } catch (PersistenceException e) {
            // Solo una clave repetida es un conflicto; cualquier otra falla de la base sigue siendo un error.
            if (BaseDeDatos.esClaveDuplicada(e)) {
                throw new ErroresDeNegocio.Conflicto("pago-duplicado", "Ya existe un pago con esos datos.");
            }
            throw e;
        }
    }

    @Override
    public boolean marcarReembolsado(String pagoId, String motivo, LocalDateTime ahora) {
        int cambiadas = em.createQuery("""
                        update Pago p
                        set p.estado = :reembolsado, p.motivoReembolso = :motivo, p.reembolsado = :ahora,
                            p.actualizado = :ahora
                        where p.id = :id and p.estado = :aprobado""")
                .setParameter("id", pagoId)
                .setParameter("motivo", motivo)
                .setParameter("ahora", ahora)
                .setParameter("reembolsado", EstadoPago.REEMBOLSADO)
                .setParameter("aprobado", EstadoPago.APROBADO)
                .executeUpdate();
        // Lo cargado en este EntityManager quedó viejo: el próximo find lee la fila de nuevo.
        em.clear();
        return cambiadas == 1;
    }
}
