package com.palco.eventos.datos;

import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.palco.eventos.dominio.Imagen;

/**
 * Stateless, como los otros repositorios: cada llamada corre en su propia
 * transacción (o en la de quien llama, si ya hay una).
 */
@Stateless
public class ImagenRepositoryJpa implements ImagenRepository {

    @PersistenceContext(unitName = "eventos")
    EntityManager em;

    @Override
    public void guardar(Imagen imagen) {
        em.persist(imagen);
    }

    @Override
    public Optional<Imagen> buscar(String id) {
        return Optional.ofNullable(em.find(Imagen.class, id));
    }
}
