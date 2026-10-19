package com.palco.usuarios.datos;

import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.palco.usuarios.dominio.Usuario;

/**
 * Stateless: no guarda nada entre llamadas; el {@code EntityManager} se lo da
 * el contenedor en cada una.
 *
 * Cada método corre dentro de una transacción, que es el comportamiento por
 * defecto de un Session Bean: se suma a la de quien llama (el Session Bean de
 * usuarios) y comparte con él el mismo contexto de persistencia. Por eso un
 * error que no deshace la transacción ({@code CodigoIncorrecto},
 * {@code CredencialesIncorrectas}) sigue sin deshacerla: la decisión es del
 * negocio, no de este bean.
 */
@Stateless
public class UsuarioRepositoryJpa implements UsuarioRepository {

    @PersistenceContext(unitName = "usuarios")
    EntityManager em;

    @Override
    public Optional<Usuario> buscarPorId(String usuarioId) {
        return Optional.ofNullable(em.find(Usuario.class, usuarioId));
    }

    @Override
    public Optional<Usuario> buscarPorEmail(String email) {
        return em.createQuery("select u from Usuario u where u.email = :email", Usuario.class)
                .setParameter("email", email)
                .getResultStream()
                .findFirst();
    }

    @Override
    public Optional<Usuario> buscarPorDni(String dni) {
        return em.createQuery("select u from Usuario u where u.dni = :dni", Usuario.class)
                .setParameter("dni", dni)
                .getResultStream()
                .findFirst();
    }

    @Override
    public void guardar(Usuario usuario) {
        em.persist(usuario);
    }

    @Override
    public void borrar(Usuario usuario) {
        em.remove(usuario);
        // Sin esto el DELETE saldría al cerrar la transacción, después del INSERT de la cuenta
        // nueva, y la restricción única de email o DNI lo rechazaría.
        em.flush();
    }
}
