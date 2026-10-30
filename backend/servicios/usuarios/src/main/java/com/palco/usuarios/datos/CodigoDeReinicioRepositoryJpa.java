package com.palco.usuarios.datos;

import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.palco.usuarios.dominio.CodigoDeReinicio;

/**
 * Stateless: no guarda nada entre llamadas; el {@code EntityManager} se lo da
 * el contenedor en cada una. Cada método se suma a la transacción de quien
 * llama, igual que {@link CodigoVerificacionRepositoryJpa}.
 */
@Stateless
public class CodigoDeReinicioRepositoryJpa implements CodigoDeReinicioRepository {

    @PersistenceContext(unitName = "usuarios")
    EntityManager em;

    @Override
    public Optional<CodigoDeReinicio> buscarPorUsuario(String usuarioId) {
        return Optional.ofNullable(em.find(CodigoDeReinicio.class, usuarioId));
    }

    @Override
    public void guardar(CodigoDeReinicio codigo) {
        em.persist(codigo);
    }

    @Override
    public void guardarCambios(CodigoDeReinicio codigo) {
        // El código llega administrado (lo trajo buscarPorUsuario en esta misma transacción):
        // alcanza con mandar el UPDATE ahora en lugar de esperar al cierre.
        em.flush();
    }

    @Override
    public void borrar(CodigoDeReinicio codigo) {
        em.remove(codigo);
    }
}
