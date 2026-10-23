package com.palco.reventa.datos;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.datos.BaseDeDatos;
import com.palco.reventa.dominio.EstadoReembolso;
import com.palco.reventa.dominio.Reembolso;

/**
 * Stateless, como {@link PublicacionRepositoryJpa}: cada método se suma a la
 * transacción de quien llama o, si no hay, corre en una corta propia.
 */
@Stateless
public class ReembolsoRepositoryJpa implements ReembolsoRepository {

    /** El {@code type} del Conflicto de dos anotaciones simultáneas del mismo pago. */
    public static final String REEMBOLSO_YA_ANOTADO = "reembolso-ya-anotado";

    @PersistenceContext(unitName = "reventa")
    EntityManager em;

    @Override
    public boolean anotar(Reembolso reembolso) {
        if (em.find(Reembolso.class, reembolso.getPagoId()) != null) {
            return false;
        }
        try {
            em.persist(reembolso);
            // El id viene de afuera: el INSERT sale recién acá, y un pago repetido choca dentro de este método.
            em.flush();
            return true;
        } catch (PersistenceException e) {
            if (BaseDeDatos.esClaveDuplicada(e)) {
                // Otro pedido lo anotó entre el find y el INSERT. La transacción ya quedó para deshacerse:
                // se lanza un error de negocio (que la deshace) en vez de seguir como si nada.
                throw new ErroresDeNegocio.Conflicto(REEMBOLSO_YA_ANOTADO, "Ese reembolso ya estaba anotado.");
            }
            throw e;
        }
    }

    @Override
    public Optional<Reembolso> buscar(String pagoId) {
        return Optional.ofNullable(em.find(Reembolso.class, pagoId));
    }

    @Override
    public List<Reembolso> buscarPendientes() {
        return em.createQuery("""
                        select r from Reembolso r
                        where r.estado = :pendiente
                        order by r.anotado, r.pagoId""", Reembolso.class)
                .setParameter("pendiente", EstadoReembolso.PENDIENTE)
                .getResultList();
    }

    @Override
    public boolean resolver(String pagoId, EstadoReembolso estado, LocalDateTime ahora) {
        int cambiadas = em.createQuery("""
                        update Reembolso r
                        set r.estado = :estado, r.resuelto = :ahora
                        where r.pagoId = :pagoId and r.estado = :pendiente""")
                .setParameter("pagoId", pagoId)
                .setParameter("estado", estado)
                .setParameter("ahora", ahora)
                .setParameter("pendiente", EstadoReembolso.PENDIENTE)
                .executeUpdate();
        em.clear();
        return cambiadas == 1;
    }
}
