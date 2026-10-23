package com.palco.eventos.datos;

import java.util.List;
import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.palco.eventos.dominio.Categoria;
import com.palco.eventos.dominio.Evento;

/**
 * Stateless: no guarda nada entre llamadas; el {@code EntityManager} se lo da
 * el contenedor en cada una.
 *
 * Cada método corre dentro de una transacción, que es el comportamiento por
 * defecto de un Session Bean: se suma a la de quien llama (el Session Bean de
 * eventos) y comparte con él el mismo contexto de persistencia, así que el
 * evento que devuelve sigue administrado mientras dura esa transacción.
 */
@Stateless
public class EventoRepositoryJpa implements EventoRepository {

    @PersistenceContext(unitName = "eventos")
    EntityManager em;

    @Override
    public List<Evento> listar(Categoria categoria) {
        // "distinct" porque el join fetch repite el evento una vez por sector.
        return em.createQuery("""
                        select distinct e from Evento e left join fetch e.sectores
                        where :categoria is null or e.categoria = :categoria
                        order by e.fecha""", Evento.class)
                .setParameter("categoria", categoria)
                .getResultList();
    }

    @Override
    public List<Evento> listarDelOrganizador(String organizadorId) {
        return em.createQuery("""
                        select distinct e from Evento e left join fetch e.sectores
                        where e.organizadorId = :organizadorId
                        order by e.fecha""", Evento.class)
                .setParameter("organizadorId", organizadorId)
                .getResultList();
    }

    @Override
    public Optional<Evento> buscarPorSlug(String slug) {
        return em.createQuery("select e from Evento e left join fetch e.sectores where e.slug = :slug", Evento.class)
                .setParameter("slug", slug)
                .getResultStream()
                .findFirst();
    }

    @Override
    public boolean existeSlug(String slug) {
        return !em.createQuery("select 1 from Evento e where e.slug = :slug")
                .setParameter("slug", slug)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    @Override
    public void guardar(Evento evento) {
        // Los sectores se guardan con el evento (cascade). El INSERT sale al cerrar la transacción.
        em.persist(evento);
    }
}
