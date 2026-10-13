package com.palco.ventas.datos;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.datos.BaseDeDatos;
import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.Venta;

/**
 * Stateless: no guarda nada entre llamadas; el {@code EntityManager} se lo da
 * el contenedor en cada una. El estado de una compra en curso vive en otro
 * lado: en el bean stateful y en la base.
 *
 * Cada método corre dentro de una transacción, que es el comportamiento por
 * defecto de un Session Bean: si quien llama ya tiene una (el bean de la
 * compra), el método se suma a esa; si no tiene (la fachada o el barrido), el
 * contenedor abre una corta, solo para ese método.
 */
@Stateless
public class VentaRepositoryJpa implements VentaRepository {

    @PersistenceContext(unitName = "ventas")
    EntityManager em;

    @Override
    public void guardar(Venta venta) {
        try {
            em.persist(venta);
            // Como el id lo asigna el código, el INSERT sale recién acá. Se fuerza ahora para
            // que una clave repetida falle dentro de este método y no al cerrar la transacción.
            em.flush();
        } catch (PersistenceException e) {
            // Solo una clave repetida es un conflicto; cualquier otra falla de la base sigue siendo un error.
            if (BaseDeDatos.esClaveDuplicada(e)) {
                throw new ErroresDeNegocio.Conflicto(TIPO_CLAVE_REPETIDA,
                        "Ya existe una compra hecha con esa clave de idempotencia.");
            }
            throw e;
        }
    }

    @Override
    public void actualizar(Venta venta) {
        try {
            // merge sirve para los dos casos: si la venta se guardó en esta misma transacción, ya está
            // a cargo del EntityManager y no copia nada; si viene de otra, copia sus datos sobre la que
            // lee de la base. Los renglones nuevos se guardan con ella (cascade).
            em.merge(venta);
            em.flush();
        } catch (PersistenceException e) {
            // La única clave que puede repetirse acá es la de un renglón: (venta, sector).
            if (BaseDeDatos.esClaveDuplicada(e)) {
                throw new ErroresDeNegocio.Conflicto("sector-repetido", "La compra nombra dos veces el mismo sector.");
            }
            throw e;
        }
    }

    @Override
    public Optional<Venta> buscarPorId(String ventaId) {
        return Optional.ofNullable(em.find(Venta.class, ventaId)).map(this::cargada);
    }

    @Override
    public Optional<Venta> buscarPorClave(String compradorId, String claveIdempotencia) {
        return em.createQuery("""
                        select v from Venta v
                        where v.compradorId = :compradorId and v.claveIdempotencia = :clave""", Venta.class)
                .setParameter("compradorId", compradorId)
                .setParameter("clave", claveIdempotencia)
                .getResultStream()
                .findFirst()
                .map(this::cargada);
    }

    @Override
    public List<Venta> buscarReservasVencidas(LocalDateTime ahora) {
        return em.createQuery("""
                        select v from Venta v
                        where v.estado = :enCheckout
                          and v.venceReserva <= :ahora
                          and v.clavePago is null
                        order by v.venceReserva""", Venta.class)
                .setParameter("enCheckout", EstadoVenta.EN_CHECKOUT)
                .setParameter("ahora", ahora)
                .getResultList()
                .stream()
                .map(this::cargada)
                .toList();
    }

    @Override
    public boolean guardarClaveDePago(String ventaId, String clavePago, LocalDateTime ahora) {
        return cambioUnaFila(em.createQuery("""
                        update Venta v set v.clavePago = :clave
                        where v.id = :id
                          and v.estado = :enCheckout
                          and v.venceReserva > :ahora
                          and (v.clavePago is null or v.clavePago = :clave)""")
                .setParameter("id", ventaId)
                .setParameter("clave", clavePago)
                .setParameter("enCheckout", EstadoVenta.EN_CHECKOUT)
                .setParameter("ahora", ahora));
    }

    @Override
    public boolean borrarClaveDePago(String ventaId, String clavePago) {
        return cambioUnaFila(em.createQuery("""
                        update Venta v set v.clavePago = null
                        where v.id = :id
                          and v.estado = :enCheckout
                          and v.clavePago = :clave""")
                .setParameter("id", ventaId)
                .setParameter("clave", clavePago)
                .setParameter("enCheckout", EstadoVenta.EN_CHECKOUT));
    }

    @Override
    public boolean confirmar(String ventaId, String pagoId, LocalDateTime ahora) {
        return cambioUnaFila(em.createQuery("""
                        update Venta v set v.estado = :confirmada, v.pagoId = :pagoId, v.confirmada = :ahora
                        where v.id = :id
                          and v.estado = :enCheckout""")
                .setParameter("id", ventaId)
                .setParameter("pagoId", pagoId)
                .setParameter("ahora", ahora)
                .setParameter("confirmada", EstadoVenta.CONFIRMADA)
                .setParameter("enCheckout", EstadoVenta.EN_CHECKOUT));
    }

    @Override
    public boolean cancelar(String ventaId) {
        return cambioUnaFila(em.createQuery("""
                        update Venta v set v.estado = :cancelada
                        where v.id = :id
                          and v.estado = :enCheckout
                          and v.clavePago is null""")
                .setParameter("id", ventaId)
                .setParameter("cancelada", EstadoVenta.CANCELADA)
                .setParameter("enCheckout", EstadoVenta.EN_CHECKOUT));
    }

    @Override
    public boolean vencer(String ventaId, LocalDateTime ahora) {
        return cambioUnaFila(em.createQuery("""
                        update Venta v set v.estado = :vencida
                        where v.id = :id
                          and v.estado = :enCheckout
                          and v.venceReserva <= :ahora
                          and v.clavePago is null""")
                .setParameter("id", ventaId)
                .setParameter("ahora", ahora)
                .setParameter("vencida", EstadoVenta.VENCIDA)
                .setParameter("enCheckout", EstadoVenta.EN_CHECKOUT));
    }

    @Override
    public boolean marcarEmitida(String ventaId) {
        return cambioUnaFila(em.createQuery("""
                        update Venta v set v.estado = :emitida
                        where v.id = :id
                          and v.estado = :confirmada""")
                .setParameter("id", ventaId)
                .setParameter("emitida", EstadoVenta.EMITIDA)
                .setParameter("confirmada", EstadoVenta.CONFIRMADA));
    }

    /**
     * Carga ahora los renglones y las entradas, que JPA trae recién cuando se los
     * pide. Tiene que ser acá adentro, mientras dura la transacción: quien recibe
     * la venta puede no tener una, y ahí ya no se pueden pedir.
     */
    private Venta cargada(Venta venta) {
        venta.getItems().size();
        venta.getEntradas().size();
        return venta;
    }

    /**
     * Corre un UPDATE condicional y dice si cambió una fila. Es la base la que
     * decide, en una sola sentencia: no hay un "leo y después escribo" en el que
     * se pueda meter otro pedido.
     */
    private boolean cambioUnaFila(Query update) {
        // Primero se manda a la base lo que estuviera pendiente, para que el UPDATE lo encuentre.
        em.flush();
        boolean cambio = update.executeUpdate() == 1;
        // El UPDATE fue directo a la base: las ventas que ya estaban cargadas en esta transacción
        // quedaron con los datos de antes. Se las descarta, así la próxima búsqueda lee de la base.
        em.clear();
        return cambio;
    }
}
