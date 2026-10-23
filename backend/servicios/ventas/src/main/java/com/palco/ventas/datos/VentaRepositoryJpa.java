package com.palco.ventas.datos;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.datos.BaseDeDatos;
import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.TitularDeVenta;
import com.palco.ventas.dominio.Venta;

/**
 * Stateless: no guarda nada entre llamadas; el {@code EntityManager} se lo da
 * el contenedor en cada una. El estado de una compra en curso vive en otro
 * lado: en el bean stateful y en la base.
 *
 * Cada método corre dentro de una transacción, que es el comportamiento por
 * defecto de un Session Bean: si quien llama ya tiene una (el bean de la
 * compra, al pagar o al cancelar), el método se suma a esa; si no tiene (la
 * fachada, el barrido o el bean de la compra al abrirla), el contenedor abre
 * una corta, solo para ese método. La excepción es {@link #respaldarTitulares},
 * que abre siempre una propia (lo explica su comentario).
 */
@Stateless
public class VentaRepositoryJpa implements VentaRepository {

    @PersistenceContext(unitName = "ventas")
    EntityManager em;

    @Override
    public void guardar(Venta venta) {
        try {
            // Los renglones se guardan con la venta (cascade).
            em.persist(venta);
            // Como los ids los asigna el código, los INSERT salen recién acá. Se fuerzan ahora para
            // que una clave repetida falle dentro de este método y no al cerrar la transacción.
            em.flush();
        } catch (PersistenceException e) {
            // Solo una clave repetida es un conflicto; cualquier otra falla de la base sigue siendo un error.
            // La que puede repetirse es la clave de idempotencia: el pedido ya llega sin sectores repetidos.
            if (BaseDeDatos.esClaveDuplicada(e)) {
                throw new ErroresDeNegocio.Conflicto(TIPO_CLAVE_REPETIDA,
                        "Ya existe una compra hecha con esa clave de idempotencia.");
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
    public List<Venta> buscarPorComprador(String compradorId) {
        // El id desempata entre las que se crearon en el mismo segundo, para que el orden sea siempre el mismo.
        return em.createQuery("""
                        select v from Venta v
                        where v.compradorId = :compradorId
                        order by v.creada desc, v.id desc""", Venta.class)
                .setParameter("compradorId", compradorId)
                .getResultList()
                .stream()
                .map(this::cargada)
                .toList();
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
                        update Venta v set v.clavePago = :clave, v.pagoIntentado = :ahora
                        where v.id = :id
                          and v.estado = :enCheckout
                          and ((v.clavePago is null and v.venceReserva > :ahora) or v.clavePago = :clave)""")
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
                        update Venta v set v.estado = :cancelada, v.devolucionPendiente = true
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
                        update Venta v set v.estado = :vencida, v.devolucionPendiente = true
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
     * {@code REQUIRES_NEW}: el contenedor suspende la transacción del pago (que
     * todavía no tomó ninguna conexión), abre una nueva solo para esto y la
     * confirma al volver. Así la copia sobrevive a una falla posterior del
     * pago, que es justo el caso en el que hace falta, y la conexión se
     * devuelve antes de esperar a Pagos.
     *
     * No hay dos llamadas a la vez para la misma venta: {@code ComprasEnCurso}
     * deja una sola operación por compra. Si igual chocaran, la clave primaria
     * rechaza la segunda copia.
     */
    @Override
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void respaldarTitulares(String ventaId, List<TitularDeVenta> titulares) {
        long existentes = em.createQuery("select count(t) from TitularDeVenta t where t.ventaId = :ventaId", Long.class)
                .setParameter("ventaId", ventaId)
                .getSingleResult();
        if (existentes > 0) {
            return;
        }
        for (TitularDeVenta titular : titulares) {
            em.persist(titular);
        }
        // Los INSERT salen acá, dentro de este método: la copia queda escrita antes de volver.
        em.flush();
    }

    @Override
    public List<TitularDeVenta> buscarTitulares(String ventaId) {
        return em.createQuery("""
                        select t from TitularDeVenta t
                        where t.ventaId = :ventaId
                        order by t.orden""", TitularDeVenta.class)
                .setParameter("ventaId", ventaId)
                .getResultList();
    }

    @Override
    public List<Venta> buscarPagosSinResultado(LocalDateTime intentadosHasta) {
        return em.createQuery("""
                        select v from Venta v
                        where v.estado = :enCheckout
                          and v.clavePago is not null
                          and (v.pagoIntentado is null or v.pagoIntentado <= :hasta)
                        order by v.venceReserva""", Venta.class)
                .setParameter("enCheckout", EstadoVenta.EN_CHECKOUT)
                .setParameter("hasta", intentadosHasta)
                .getResultList()
                .stream()
                .map(this::cargada)
                .toList();
    }

    @Override
    public boolean confirmarConciliada(String ventaId, String clavePago, String pagoId, LocalDateTime ahora) {
        return cambioUnaFila(em.createQuery("""
                        update Venta v set v.estado = :confirmada, v.pagoId = :pagoId, v.confirmada = :ahora
                        where v.id = :id
                          and v.estado = :enCheckout
                          and v.clavePago = :clave""")
                .setParameter("id", ventaId)
                .setParameter("clave", clavePago)
                .setParameter("pagoId", pagoId)
                .setParameter("ahora", ahora)
                .setParameter("confirmada", EstadoVenta.CONFIRMADA)
                .setParameter("enCheckout", EstadoVenta.EN_CHECKOUT));
    }

    @Override
    public boolean vencerPagoSinCobro(String ventaId, String clavePago, LocalDateTime intentadosHasta) {
        return cambioUnaFila(em.createQuery("""
                        update Venta v set v.estado = :vencida, v.clavePago = null, v.devolucionPendiente = true
                        where v.id = :id
                          and v.estado = :enCheckout
                          and v.clavePago = :clave
                          and (v.pagoIntentado is null or v.pagoIntentado <= :hasta)""")
                .setParameter("id", ventaId)
                .setParameter("clave", clavePago)
                .setParameter("hasta", intentadosHasta)
                .setParameter("vencida", EstadoVenta.VENCIDA)
                .setParameter("enCheckout", EstadoVenta.EN_CHECKOUT));
    }

    /**
     * Carga ahora los renglones y las entradas, que JPA trae recién cuando se los
     * pide. Tiene que ser acá adentro, mientras dura la transacción: quien recibe
     * la venta puede no tener una, y ahí ya no se pueden pedir.
     */
    @Override
    public List<Venta> buscarDevolucionesPendientes() {
        return em.createQuery("""
                        select v from Venta v
                        where v.devolucionPendiente = true
                        order by v.creada""", Venta.class)
                .getResultList()
                .stream()
                .map(this::cargada)
                .toList();
    }

    @Override
    public boolean marcarCupoDevuelto(String ventaId) {
        return cambioUnaFila(em.createQuery("""
                        update Venta v set v.devolucionPendiente = false
                        where v.id = :id
                          and v.devolucionPendiente = true""")
                .setParameter("id", ventaId));
    }

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
