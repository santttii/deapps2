package com.palco.notificaciones.datos;

import java.util.List;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.palco.notificaciones.dominio.RegistroDeAuditoria;

/**
 * Stateless: no guarda nada entre llamadas; el {@code EntityManager} se lo da
 * el contenedor en cada una.
 *
 * Cada método corre dentro de una transacción, que es el comportamiento por
 * defecto de un Session Bean: se suma a la del MDB de auditoría, la misma en
 * la que se recibió el mensaje. Si el INSERT falla, esa transacción se deshace
 * entera y el mensaje vuelve al broker.
 */
@Stateless
public class RegistroDeAuditoriaRepositoryJpa implements RegistroDeAuditoriaRepository {

    @PersistenceContext(unitName = "notificaciones")
    EntityManager em;

    @Override
    public boolean existe(String mensajeId) {
        return em.find(RegistroDeAuditoria.class, mensajeId) != null;
    }

    @Override
    public void guardar(RegistroDeAuditoria registro) {
        em.persist(registro);
        // Como el id lo asigna el código, el INSERT sale recién acá. Se fuerza ahora para que,
        // si la base lo rechaza, la falla salga de este método: el MDB no llega a escribir en
        // el log que procesó un mensaje cuya fila no existe.
        em.flush();
    }

    @Override
    public List<RegistroDeAuditoria> ultimos(int maximo) {
        // recibidoEn está truncada a segundos: el mensajeId solo desempata para que la
        // respuesta sea siempre la misma, no dice cuál de los dos mensajes llegó primero.
        return em.createQuery("""
                        select r from RegistroDeAuditoria r
                        order by r.recibidoEn desc, r.mensajeId desc""", RegistroDeAuditoria.class)
                .setMaxResults(maximo)
                .getResultList();
    }
}
