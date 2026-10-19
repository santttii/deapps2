package com.palco.reventa.datos;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.reventa.dominio.Publicacion;

/**
 * Dónde se guardan y se buscan las publicaciones. El negocio conoce solo esta
 * interfaz: en el servidor la implementa {@link PublicacionRepositoryJpa} y en
 * los tests un doble.
 *
 * Cada cambio es un solo UPDATE con la condición adentro y devuelve si cambió
 * una fila: solo sigue quien la cambió. Así, de dos compras a la vez de la
 * misma publicación, una sola llega a cobrar. El "ahora" llega como
 * parámetro, en UTC.
 *
 * "Tomar" una publicación es anotar la compra en curso (comprador, clave y
 * hora) sin cambiar el estado: sigue {@code ACTIVA}, pero nadie más la puede
 * tomar ni retirar mientras la toma esté vigente. Una toma anotada en
 * {@code tomasVencidasHasta} o antes está vencida: es la que dejó trabada una
 * falla, y otro la puede tomar.
 */
@Local
public interface PublicacionRepository {

    /** Guarda una publicación nueva; el INSERT sale en el momento. */
    void guardar(Publicacion publicacion);

    Optional<Publicacion> buscarPorId(String id);

    /** Las {@code ACTIVA} de ese evento, de la más barata a la más cara. */
    List<Publicacion> buscarActivasPorEvento(String eventoId);

    /** Todas las de ese vendedor, en cualquier estado, de la más nueva a la más vieja. */
    List<Publicacion> buscarPorVendedor(String vendedorId);

    /**
     * Anota la compra en curso. Pasa si la publicación está {@code ACTIVA}, no
     * es de ese comprador y no está tomada: sin toma, con la toma vencida, o
     * con la toma de este mismo comprador y esta misma clave (el reintento).
     */
    boolean tomar(String id, String compradorId, String clave, LocalDateTime ahora, LocalDateTime tomasVencidasHasta);

    /** Quita la toma de esa clave cuando se sabe que la compra no siguió: la publicación queda libre. */
    boolean liberar(String id, String clave);

    /** {@code ACTIVA} → {@code VENDIDA}, con el comprador, la clave, la venta y el pago. */
    boolean marcarVendida(String id, String compradorId, String clave, String ventaId, String pagoId,
            LocalDateTime ahora);

    /** {@code ACTIVA} → {@code RETIRADA}, si es de ese vendedor y no hay una toma vigente. */
    boolean retirar(String id, String vendedorId, LocalDateTime ahora, LocalDateTime tomasVencidasHasta);
}
