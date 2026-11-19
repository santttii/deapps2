package com.palco.eventos.datos;

import java.util.List;
import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import com.palco.eventos.dominio.Categoria;
import com.palco.eventos.dominio.Evento;
import com.palco.eventos.dominio.Sector;

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
        // Los cancelados no salen en el catálogo: no se les puede comprar nada.
        return em.createQuery("""
                        select distinct e from Evento e left join fetch e.sectores
                        where e.cancelado = false and (:categoria is null or e.categoria = :categoria)
                        order by e.fecha""", Evento.class)
                .setParameter("categoria", categoria)
                .getResultList();
    }

    @Override
    public List<Evento> listarDelOrganizador(String organizadorId) {
        return em.createQuery("""
                        select distinct e from Evento e left join fetch e.sectores
                        where e.organizadorId = :organizador
                        order by e.fecha""", Evento.class)
                .setParameter("organizador", organizadorId)
                .getResultList();
    }

    @Override
    public Optional<Evento> buscarPorId(String id) {
        return Optional.ofNullable(em.find(Evento.class, id));
    }

    @Override
    public Optional<Evento> buscarPorIdParaCambiar(String id) {
        // SELECT ... FOR UPDATE de la fila del evento y, aparte, de las de sus sectores. Van en dos
        // consultas sin join porque PostgreSQL no bloquea el lado opcional de un left join.
        Evento evento = em.find(Evento.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (evento == null) {
            return Optional.empty();
        }
        // Los sectores también: las vendidas son parte del evento que se muestra (y de su ETag), y
        // sin el bloqueo una venta podría cambiarlas entre la comparación y el guardado. Quedan en
        // el contexto de persistencia; la lista del evento se arma con estas mismas instancias.
        em.createQuery("select s from Sector s where s.evento = :evento", Sector.class)
                .setParameter("evento", evento)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();
        return Optional.of(evento);
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
