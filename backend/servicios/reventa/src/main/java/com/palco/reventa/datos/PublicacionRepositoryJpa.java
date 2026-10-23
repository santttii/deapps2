package com.palco.reventa.datos;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import com.palco.reventa.dominio.EstadoPublicacion;
import com.palco.reventa.dominio.Publicacion;

/**
 * Stateless: no guarda nada entre llamadas; el {@code EntityManager} se lo da
 * el contenedor en cada una. Cada método se suma a la transacción de quien
 * llama o, si no hay (la compra corre sin una), el contenedor abre una corta
 * solo para ese método: cada paso de la compra queda confirmado al volver.
 */
@Stateless
public class PublicacionRepositoryJpa implements PublicacionRepository {

    @PersistenceContext(unitName = "reventa")
    EntityManager em;

    @Override
    public void guardar(Publicacion publicacion) {
        em.persist(publicacion);
        // El id lo asigna el código: el INSERT sale recién acá, así una falla de la base aparece dentro de este método.
        em.flush();
    }

    @Override
    public Optional<Publicacion> buscarPorId(String id) {
        return Optional.ofNullable(em.find(Publicacion.class, id));
    }

    @Override
    public List<Publicacion> buscarActivasPorEvento(String eventoId) {
        return em.createQuery("""
                        select p from Publicacion p
                        where p.eventoId = :eventoId and p.estado = :activa
                        order by p.precio, p.publicada, p.id""", Publicacion.class)
                .setParameter("eventoId", eventoId)
                .setParameter("activa", EstadoPublicacion.ACTIVA)
                .getResultList();
    }

    @Override
    public List<Publicacion> buscarPorVendedor(String vendedorId) {
        return em.createQuery("""
                        select p from Publicacion p
                        where p.vendedorId = :vendedorId
                        order by p.publicada desc, p.id desc""", Publicacion.class)
                .setParameter("vendedorId", vendedorId)
                .getResultList();
    }

    @Override
    public boolean tomar(String id, String compradorId, String clave, String titularNombre, String titularDni,
            LocalDateTime ahora) {
        return cambioUnaFila(em.createQuery("""
                        update Publicacion p
                        set p.compradorId = :comprador, p.claveCompra = :clave, p.compraIntentada = :ahora,
                            p.titularNombre = :nombre, p.titularDni = :dni
                        where p.id = :id
                          and p.estado = :activa
                          and p.vendedorId <> :comprador
                          and (p.claveCompra is null
                               or (p.claveCompra = :clave and p.compradorId = :comprador))""")
                .setParameter("id", id)
                .setParameter("comprador", compradorId)
                .setParameter("clave", clave)
                .setParameter("nombre", titularNombre)
                .setParameter("dni", titularDni)
                .setParameter("ahora", ahora)
                .setParameter("activa", EstadoPublicacion.ACTIVA));
    }

    @Override
    public boolean liberar(String id, String clave) {
        return cambioUnaFila(em.createQuery("""
                        update Publicacion p
                        set p.compradorId = null, p.claveCompra = null, p.compraIntentada = null,
                            p.titularNombre = null, p.titularDni = null
                        where p.id = :id and p.estado = :activa and p.claveCompra = :clave""")
                .setParameter("id", id)
                .setParameter("clave", clave)
                .setParameter("activa", EstadoPublicacion.ACTIVA));
    }

    @Override
    public List<Publicacion> buscarTomasSinCerrar(LocalDateTime intentadasHasta) {
        return em.createQuery("""
                        select p from Publicacion p
                        where p.estado = :activa
                          and p.claveCompra is not null
                          and p.compraIntentada <= :hasta
                        order by p.compraIntentada""", Publicacion.class)
                .setParameter("activa", EstadoPublicacion.ACTIVA)
                .setParameter("hasta", intentadasHasta)
                .getResultList();
    }

    @Override
    public boolean liberarConciliada(String id, String clave, LocalDateTime intentadasHasta) {
        return cambioUnaFila(em.createQuery("""
                        update Publicacion p
                        set p.compradorId = null, p.claveCompra = null, p.compraIntentada = null,
                            p.titularNombre = null, p.titularDni = null
                        where p.id = :id
                          and p.estado = :activa
                          and p.claveCompra = :clave
                          and p.compraIntentada <= :hasta""")
                .setParameter("id", id)
                .setParameter("clave", clave)
                .setParameter("hasta", intentadasHasta)
                .setParameter("activa", EstadoPublicacion.ACTIVA));
    }

    @Override
    public boolean marcarVendida(String id, String compradorId, String clave, String ventaId, String pagoId,
            LocalDateTime ahora) {
        // No exige que la toma siga siendo de esta clave: si se llega acá, Ventas ya transfirió la entrada,
        // y Ventas deja transferirla una sola vez. Lo que dice Ventas manda.
        return cambioUnaFila(em.createQuery("""
                        update Publicacion p
                        set p.estado = :vendida, p.compradorId = :comprador, p.claveCompra = :clave,
                            p.ventaId = :ventaId, p.pagoId = :pagoId, p.cerrada = :ahora
                        where p.id = :id and p.estado = :activa""")
                .setParameter("id", id)
                .setParameter("comprador", compradorId)
                .setParameter("clave", clave)
                .setParameter("ventaId", ventaId)
                .setParameter("pagoId", pagoId)
                .setParameter("ahora", ahora)
                .setParameter("vendida", EstadoPublicacion.VENDIDA)
                .setParameter("activa", EstadoPublicacion.ACTIVA));
    }

    @Override
    public boolean anular(String id, String clave, LocalDateTime ahora) {
        return cambioUnaFila(em.createQuery("""
                        update Publicacion p
                        set p.estado = :anulada, p.cerrada = :ahora
                        where p.id = :id and p.estado = :activa and p.claveCompra = :clave""")
                .setParameter("id", id)
                .setParameter("clave", clave)
                .setParameter("ahora", ahora)
                .setParameter("anulada", EstadoPublicacion.ANULADA)
                .setParameter("activa", EstadoPublicacion.ACTIVA));
    }

    @Override
    public boolean retirar(String id, String vendedorId, LocalDateTime ahora) {
        return cambioUnaFila(em.createQuery("""
                        update Publicacion p
                        set p.estado = :retirada, p.cerrada = :ahora
                        where p.id = :id
                          and p.vendedorId = :vendedor
                          and p.estado = :activa
                          and p.claveCompra is null""")
                .setParameter("id", id)
                .setParameter("vendedor", vendedorId)
                .setParameter("ahora", ahora)
                .setParameter("retirada", EstadoPublicacion.RETIRADA)
                .setParameter("activa", EstadoPublicacion.ACTIVA));
    }

    /**
     * Corre un UPDATE condicional y dice si cambió una fila. La base decide en
     * una sola sentencia: no hay un "leo y después escribo" en el que se pueda
     * meter otro pedido. Lo cargado en la transacción quedó viejo y se descarta.
     */
    private boolean cambioUnaFila(Query update) {
        em.flush();
        boolean cambio = update.executeUpdate() == 1;
        em.clear();
        return cambio;
    }
}
