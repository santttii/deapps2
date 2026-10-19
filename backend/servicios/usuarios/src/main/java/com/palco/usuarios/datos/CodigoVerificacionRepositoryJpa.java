package com.palco.usuarios.datos;

import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.palco.usuarios.dominio.CodigoVerificacion;

/**
 * Stateless: no guarda nada entre llamadas; el {@code EntityManager} se lo da
 * el contenedor en cada una.
 *
 * Cada método corre dentro de una transacción, que es el comportamiento por
 * defecto de un Session Bean: se suma a la de quien llama (el Session Bean de
 * usuarios) y comparte con él el mismo contexto de persistencia.
 */
@Stateless
public class CodigoVerificacionRepositoryJpa implements CodigoVerificacionRepository {

    @PersistenceContext(unitName = "usuarios")
    EntityManager em;

    @Override
    public Optional<CodigoVerificacion> buscarPorUsuario(String usuarioId) {
        return Optional.ofNullable(em.find(CodigoVerificacion.class, usuarioId));
    }

    @Override
    public void guardar(CodigoVerificacion codigo) {
        em.persist(codigo);
    }

    @Override
    public void guardarCambios(CodigoVerificacion codigo) {
        // El código llega administrado (lo trajo buscarPorUsuario en esta misma transacción):
        // alcanza con mandar el UPDATE ahora en lugar de esperar al cierre.
        em.flush();
    }

    @Override
    public void borrar(CodigoVerificacion codigo) {
        em.remove(codigo);
    }
}
