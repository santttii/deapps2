package com.palco.validacion.datos;

import java.util.List;
import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.palco.validacion.dominio.Escaneo;
import com.palco.validacion.dominio.ResultadoEscaneo;

/**
 * Stateless: no guarda nada entre llamadas; el {@code EntityManager} se lo da
 * el contenedor en cada una.
 *
 * Cada método corre en su propia transacción, que el contenedor abre y cierra
 * solo (es el comportamiento por defecto de un Session Bean). Son cortas a
 * propósito: quien llama, {@code ServicioDeValidacion}, no tiene transacción
 * mientras espera a Ventas, así que la conexión a la base se toma y se
 * devuelve dentro de cada método.
 */
@Stateless
public class EscaneoRepositoryJpa implements EscaneoRepository {

    @PersistenceContext(unitName = "validacion")
    EntityManager em;

    @Override
    public void guardar(Escaneo escaneo) {
        em.persist(escaneo);
        // Como el id lo asigna el código, el INSERT sale recién acá. Se fuerza ahora para que una
        // falla de la base salga dentro de este método y no al cerrar la transacción.
        em.flush();
    }

    @Override
    public Optional<Escaneo> buscarPorId(String escaneoId) {
        return Optional.ofNullable(em.find(Escaneo.class, escaneoId));
    }

    @Override
    public List<Escaneo> listarPorEvento(String eventoId, int maximo) {
        // El id desempata los escaneos del mismo segundo, así el orden es siempre el mismo.
        return em.createQuery("""
                        select e from Escaneo e where e.eventoId = :evento
                        order by e.escaneado desc, e.id desc""", Escaneo.class)
                .setParameter("evento", eventoId)
                .setMaxResults(maximo)
                .getResultList();
    }

    @Override
    public Optional<Escaneo> ultimoDelCodigo(String codigoQr, ResultadoEscaneo resultado) {
        return em.createQuery("""
                        select e from Escaneo e where e.codigoQr = :codigo and e.resultado = :resultado
                        order by e.escaneado desc, e.id desc""", Escaneo.class)
                .setParameter("codigo", codigoQr)
                .setParameter("resultado", resultado)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }
}
