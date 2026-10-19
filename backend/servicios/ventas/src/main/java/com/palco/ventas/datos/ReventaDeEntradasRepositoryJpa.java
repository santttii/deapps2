package com.palco.ventas.datos;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.palco.ventas.dominio.EstadoEntrada;

/**
 * Stateless: no guarda nada entre llamadas; el {@code EntityManager} se lo da
 * el contenedor en cada una. Cada método se suma a la transacción de quien
 * llama ({@code ReventaDeEntradas}) o, si no hay, el contenedor abre una corta.
 */
@Stateless
public class ReventaDeEntradasRepositoryJpa implements ReventaDeEntradasRepository {

    @PersistenceContext(unitName = "ventas")
    EntityManager em;

    @Override
    public boolean publicar(String entradaId, String vendedorId) {
        return cambiar(entradaId, vendedorId, EstadoEntrada.VALIDA, EstadoEntrada.PUBLICADA);
    }

    @Override
    public boolean retirar(String entradaId, String vendedorId) {
        return cambiar(entradaId, vendedorId, EstadoEntrada.PUBLICADA, EstadoEntrada.VALIDA);
    }

    @Override
    public boolean marcarVendida(String entradaId, String vendedorId) {
        return cambiar(entradaId, vendedorId, EstadoEntrada.PUBLICADA, EstadoEntrada.VENDIDA);
    }

    /**
     * Un UPDATE condicional. El dueño se controla con una subconsulta sobre la
     * venta (y no con {@code e.venta.compradorId}) porque un UPDATE masivo de
     * JPQL no admite un join en el WHERE. La condición sobre el estado es la que
     * decide entre dos pedidos simultáneos: el segundo espera el bloqueo de la
     * fila, ya la ve en el estado nuevo y no cambia nada.
     */
    private boolean cambiar(String entradaId, String vendedorId, EstadoEntrada desde, EstadoEntrada hacia) {
        em.flush();
        int cambiadas = em.createQuery("""
                        update Entrada e set e.estado = :hacia
                        where e.id = :id and e.estado = :desde
                          and e.venta.id in (select v.id from Venta v where v.compradorId = :vendedor)""")
                .setParameter("id", entradaId)
                .setParameter("vendedor", vendedorId)
                .setParameter("desde", desde)
                .setParameter("hacia", hacia)
                .executeUpdate();
        // El UPDATE fue directo a la base: lo cargado en esta transacción quedó viejo y se descarta.
        em.clear();
        return cambiadas == 1;
    }
}
