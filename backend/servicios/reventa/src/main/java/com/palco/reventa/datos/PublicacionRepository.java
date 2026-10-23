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
 * "Tomar" una publicación es anotar la compra en curso (comprador, clave,
 * titular y hora) sin cambiar el estado: sigue {@code ACTIVA}, pero nadie más
 * la puede tomar ni retirar mientras tenga la toma. Una toma no vence por
 * tiempo: la saca quien sabe cómo terminó el cobro (la compra misma, un
 * reintento del mismo comprador o {@code ConciliacionDeReventas}). Si
 * venciera, otro podría comprar una entrada que ya se cobró.
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
     * Anota la compra en curso con el titular de la entrada. Pasa si la
     * publicación está {@code ACTIVA}, no es de ese comprador y no está
     * tomada, o si la toma es de este mismo comprador con esta misma clave
     * (el reintento).
     */
    boolean tomar(String id, String compradorId, String clave, String titularNombre, String titularDni,
            LocalDateTime ahora);

    /** Quita la toma de esa clave cuando se sabe que la compra no siguió: la publicación queda libre. */
    boolean liberar(String id, String clave);

    /**
     * Las publicaciones {@code ACTIVA} con una toma anotada en
     * {@code intentadasHasta} o antes: compras que no terminaron y de las que
     * no se sabe si se cobró. Las usa {@code ConciliacionDeReventas}.
     */
    List<Publicacion> buscarTomasSinCerrar(LocalDateTime intentadasHasta);

    /**
     * Como {@link #liberar}, pero solo si la toma sigue siendo la que miró la
     * conciliación ({@code intentadasHasta} o antes): si mientras tanto el
     * comprador reintentó, la toma es más nueva y no se toca.
     */
    boolean liberarConciliada(String id, String clave, LocalDateTime intentadasHasta);

    /** {@code ACTIVA} → {@code VENDIDA}, con el comprador, la clave, la venta y el pago. */
    boolean marcarVendida(String id, String compradorId, String clave, String ventaId, String pagoId,
            LocalDateTime ahora);

    /**
     * {@code ACTIVA} → {@code ANULADA}, si la toma es de esa clave. Queda la
     * toma anotada (comprador, clave, titular): dice de quién es el cobro que
     * se devuelve.
     */
    boolean anular(String id, String clave, LocalDateTime ahora);

    /** {@code ACTIVA} → {@code RETIRADA}, si es de ese vendedor y no tiene una toma. */
    boolean retirar(String id, String vendedorId, LocalDateTime ahora);
}
