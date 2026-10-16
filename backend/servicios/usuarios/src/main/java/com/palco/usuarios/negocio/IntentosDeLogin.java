package com.palco.usuarios.negocio;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.palco.usuarios.dominio.IntentoLogin;

/**
 * Dónde quedan los inicios de sesión fallidos: la tabla {@code intento_login}.
 * Cuántos se permiten y durante cuánto tiempo lo decide {@link ServicioDeUsuarios};
 * acá solo se guardan y se consultan.
 *
 * Stateless: no recuerda nada entre llamadas. Los intentos viven en la base y no
 * en un mapa en memoria, así el límite es el mismo con varias instancias del
 * servicio o después de un redespliegue.
 */
@Stateless
public class IntentosDeLogin {

    @PersistenceContext(unitName = "usuarios")
    EntityManager em;

    /** Los momentos de los fallidos posteriores a {@code desde}, del más nuevo al más viejo; como mucho {@code maximo}. */
    public List<LocalDateTime> recientes(String emailHash, LocalDateTime desde, int maximo) {
        return em.createQuery("select i.momento from IntentoLogin i where i.emailHash = :email "
                        + "and i.momento > :desde order by i.momento desc", LocalDateTime.class)
                .setParameter("email", emailHash)
                .setParameter("desde", desde)
                .setMaxResults(maximo)
                .getResultList();
    }

    /** Anota un fallido y borra los de ese email anteriores a {@code desde}, que ya no cuentan. */
    public void registrar(String emailHash, LocalDateTime momento, LocalDateTime desde) {
        em.createQuery("delete from IntentoLogin i where i.emailHash = :email and i.momento <= :desde")
                .setParameter("email", emailHash)
                .setParameter("desde", desde)
                .executeUpdate();
        em.persist(new IntentoLogin(emailHash, momento));
    }

    /** Después de un login correcto los fallidos anteriores dejan de contar. */
    public void olvidar(String emailHash) {
        em.createQuery("delete from IntentoLogin i where i.emailHash = :email")
                .setParameter("email", emailHash)
                .executeUpdate();
    }
}
