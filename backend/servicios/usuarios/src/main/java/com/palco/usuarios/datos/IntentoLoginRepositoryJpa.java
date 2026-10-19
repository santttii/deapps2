package com.palco.usuarios.datos;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.palco.usuarios.dominio.IntentoLogin;

/**
 * Stateless: no recuerda nada entre llamadas; los intentos están en la base.
 *
 * Cada método corre dentro de una transacción, que es el comportamiento por
 * defecto de un Session Bean: se suma a la del login. Cuando el login falla
 * con {@code CredencialesIncorrectas}, que no deshace la transacción, el
 * fallido que se anotó acá queda guardado.
 */
@Stateless
public class IntentoLoginRepositoryJpa implements IntentoLoginRepository {

    @PersistenceContext(unitName = "usuarios")
    EntityManager em;

    @Override
    public List<LocalDateTime> recientes(String emailHash, LocalDateTime desde, int maximo) {
        return em.createQuery("select i.momento from IntentoLogin i where i.emailHash = :email "
                        + "and i.momento > :desde order by i.momento desc", LocalDateTime.class)
                .setParameter("email", emailHash)
                .setParameter("desde", desde)
                .setMaxResults(maximo)
                .getResultList();
    }

    @Override
    public void registrar(String emailHash, LocalDateTime momento, LocalDateTime desde) {
        em.createQuery("delete from IntentoLogin i where i.emailHash = :email and i.momento <= :desde")
                .setParameter("email", emailHash)
                .setParameter("desde", desde)
                .executeUpdate();
        em.persist(new IntentoLogin(emailHash, momento));
    }

    @Override
    public void olvidar(String emailHash) {
        em.createQuery("delete from IntentoLogin i where i.emailHash = :email")
                .setParameter("email", emailHash)
                .executeUpdate();
    }
}
