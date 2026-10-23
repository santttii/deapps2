package com.palco.eventos.datos;

import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.datos.BaseDeDatos;
import com.palco.eventos.dominio.DevolucionDeCupo;
import com.palco.eventos.negocio.TiposDeProblema;

/**
 * Stateless, como los otros repositorios: se suma a la transacción del Session
 * Bean de eventos, que es la misma en la que se resta el cupo.
 */
@Stateless
public class DevolucionDeCupoRepositoryJpa implements DevolucionDeCupoRepository {

    @PersistenceContext(unitName = "eventos")
    EntityManager em;

    @Override
    public Optional<DevolucionDeCupo> buscar(String clave) {
        return Optional.ofNullable(em.find(DevolucionDeCupo.class, clave));
    }

    @Override
    public void registrar(DevolucionDeCupo devolucion) {
        try {
            em.persist(devolucion);
            // La clave la arma quien llama: el INSERT se fuerza ahora para que una clave repetida
            // falle acá, con un error claro, y no al cerrar la transacción.
            em.flush();
        } catch (PersistenceException e) {
            if (BaseDeDatos.esClaveDuplicada(e)) {
                throw new ErroresDeNegocio.Conflicto(TiposDeProblema.DEVOLUCION_YA_APLICADA,
                        "Esa devolución ya se aplicó.");
            }
            throw e;
        }
    }
}
