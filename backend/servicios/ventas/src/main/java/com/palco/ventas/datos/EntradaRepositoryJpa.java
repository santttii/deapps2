package com.palco.ventas.datos;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.datos.BaseDeDatos;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.EstadoEntrada;

/**
 * Stateless: no guarda nada entre llamadas; el {@code EntityManager} se lo da
 * el contenedor en cada una.
 *
 * Cada método corre dentro de una transacción, que es el comportamiento por
 * defecto de un Session Bean: se suma a la de quien llama (el bean de la
 * compra o el consumidor de mensajes) o, si no hay, el contenedor abre una
 * corta, solo para ese método.
 */
@Stateless
public class EntradaRepositoryJpa implements EntradaRepository {

    @PersistenceContext(unitName = "ventas")
    EntityManager em;

    @Override
    public void guardar(List<Entrada> entradas) {
        try {
            for (Entrada entrada : entradas) {
                em.persist(entrada);
            }
            // Como el id lo asigna el código, los INSERT salen recién acá. Se fuerzan ahora para
            // que una clave repetida falle dentro de este método y no al cerrar la transacción.
            em.flush();
        } catch (PersistenceException e) {
            // Solo una clave repetida es un conflicto; cualquier otra falla de la base sigue siendo un error.
            if (BaseDeDatos.esClaveDuplicada(e)) {
                throw new ErroresDeNegocio.Conflicto("entrada-repetida",
                        "La compra ya tiene una entrada para ese DNI.");
            }
            throw e;
        }
    }

    @Override
    public Optional<Entrada> buscarPorId(String entradaId) {
        return em.createQuery("select e from Entrada e join fetch e.venta where e.id = :id", Entrada.class)
                .setParameter("id", entradaId)
                .getResultStream()
                .findFirst();
    }

    @Override
    public int emitirPendientes(String ventaId, LocalDateTime ahora) {
        // Primero se manda a la base lo que estuviera pendiente, para que la consulta lo encuentre.
        em.flush();
        List<String> pendientes = em.createQuery("""
                        select e.id from Entrada e
                        where e.venta.id = :ventaId and e.estado = :pendiente
                        order by e.orden""", String.class)
                .setParameter("ventaId", ventaId)
                .setParameter("pendiente", EstadoEntrada.PENDIENTE_EMISION)
                .getResultList();

        int emitidas = 0;
        for (String entradaId : pendientes) {
            // Un UPDATE por entrada, porque cada una lleva un código distinto. La condición sobre el
            // estado es lo que lo hace idempotente: si el mensaje llega dos veces, la segunda vez no
            // queda ninguna pendiente; y si dos emisiones se cruzan, la base deja pasar a una sola.
            emitidas += em.createQuery("""
                            update Entrada e set e.estado = :valida, e.codigoQr = :codigo, e.emitida = :ahora
                            where e.id = :id and e.estado = :pendiente""")
                    .setParameter("id", entradaId)
                    .setParameter("codigo", nuevoCodigoQr())
                    .setParameter("ahora", ahora)
                    .setParameter("valida", EstadoEntrada.VALIDA)
                    .setParameter("pendiente", EstadoEntrada.PENDIENTE_EMISION)
                    .executeUpdate();
        }
        // Los UPDATE fueron directo a la base: lo que ya estaba cargado en esta transacción quedó
        // con los datos de antes. Se lo descarta, así la próxima búsqueda lee de la base.
        em.clear();
        return emitidas;
    }

    /** Un UUID al azar: texto opaco, único y que no se puede adivinar a partir del id de la entrada. */
    private static String nuevoCodigoQr() {
        return UUID.randomUUID().toString();
    }
}
