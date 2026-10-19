package com.palco.eventos.datos;

import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.palco.eventos.dominio.Sector;

/**
 * Stateless: no guarda nada entre llamadas; el {@code EntityManager} se lo da
 * el contenedor en cada una.
 *
 * Cada método corre dentro de una transacción, que es el comportamiento por
 * defecto de un Session Bean: se suma a la de quien llama (el Session Bean de
 * eventos, al reservar o devolver cupo) y comparte con él el mismo contexto de
 * persistencia.
 */
@Stateless
public class SectorRepositoryJpa implements SectorRepository {

    @PersistenceContext(unitName = "eventos")
    EntityManager em;

    @Override
    public Optional<Sector> buscarPorId(String sectorId) {
        return Optional.ofNullable(em.find(Sector.class, sectorId));
    }

    @Override
    public boolean sumarVendidas(Sector sector, int cantidad) {
        // Un solo UPDATE con la condición adentro: si dos compras llegan a la vez por el último
        // lugar, la base deja pasar solo a una. No hay un "leo y después escribo" en el medio.
        int actualizados = em.createQuery("""
                        update Sector s set s.vendidas = s.vendidas + :cantidad
                        where s.id = :id
                          and s.vendidas + :cantidad >= 0
                          and s.vendidas + :cantidad <= s.cupo""")
                .setParameter("cantidad", cantidad)
                .setParameter("id", sector.getId())
                .executeUpdate();
        if (actualizados == 0) {
            return false;
        }
        // El UPDATE fue directo a la base: el sector cargado quedó con las vendidas de antes.
        em.refresh(sector);
        return true;
    }
}
