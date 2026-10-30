package com.palco.validacion.datos;

import java.util.List;
import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.datos.BaseDeDatos;
import com.palco.validacion.dominio.PuertaAsignada;

/**
 * Stateless, como {@link EscaneoRepositoryJpa}: cada método corre en su propia
 * transacción corta, porque quien llama no tiene una.
 */
@Stateless
public class PuertaAsignadaRepositoryJpa implements PuertaAsignadaRepository {

    @PersistenceContext(unitName = "validacion")
    EntityManager em;

    @Override
    public boolean estaAsignada(String eventoId, String staffEmail) {
        return buscar(eventoId, staffEmail).isPresent();
    }

    @Override
    public Optional<PuertaAsignada> buscar(String eventoId, String staffEmail) {
        return Optional.ofNullable(em.find(PuertaAsignada.class, new PuertaAsignada.Clave(eventoId, staffEmail)));
    }

    @Override
    public List<PuertaAsignada> delEvento(String eventoId) {
        return em.createQuery("""
                        select p from PuertaAsignada p where p.eventoId = :evento order by p.staffEmail""",
                        PuertaAsignada.class)
                .setParameter("evento", eventoId)
                .getResultList();
    }

    @Override
    public List<PuertaAsignada> deLaCuenta(String staffEmail) {
        return em.createQuery("""
                        select p from PuertaAsignada p where p.staffEmail = :email order by p.eventoId""",
                        PuertaAsignada.class)
                .setParameter("email", staffEmail)
                .getResultList();
    }

    @Override
    public void guardar(PuertaAsignada asignacion) {
        try {
            em.persist(asignacion);
            // La clave viene de afuera: el INSERT sale recién acá, y un par repetido choca dentro de este método.
            em.flush();
        } catch (PersistenceException e) {
            // EntityExistsException: el par ya estaba cargado en este EntityManager; el otro caso lo frena la base.
            if (e instanceof EntityExistsException || BaseDeDatos.esClaveDuplicada(e)) {
                // Dos asignaciones iguales a la vez: la otra ya la guardó. Deshace esta transacción y nada más.
                throw new ErroresDeNegocio.Conflicto("puerta-ya-asignada", "Esa cuenta ya estaba asignada.");
            }
            throw e;
        }
    }

    @Override
    public boolean quitar(String eventoId, String staffEmail) {
        return em.createQuery("delete from PuertaAsignada p where p.eventoId = :evento and p.staffEmail = :email")
                .setParameter("evento", eventoId)
                .setParameter("email", staffEmail)
                .executeUpdate() == 1;
    }
}
