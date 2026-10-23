package com.palco.reventa.datos;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.reventa.dominio.EstadoReembolso;
import com.palco.reventa.dominio.Reembolso;

/**
 * Dónde se anotan los cobros de reventa que hay que devolver. En el servidor
 * lo implementa {@link ReembolsoRepositoryJpa}; en los tests, un doble.
 */
@Local
public interface ReembolsoRepository {

    /**
     * Anota un reembolso nuevo, {@code PENDIENTE}. Devuelve false si ese pago
     * ya tenía uno anotado: vale el primero. Si otro pedido lo anota en el
     * mismo instante, la clave primaria lo frena y lanza un {@code Conflicto}
     * ({@code reembolso-ya-anotado}): también ahí vale el otro.
     */
    boolean anotar(Reembolso reembolso);

    Optional<Reembolso> buscar(String pagoId);

    /** Los {@code PENDIENTE}, del más viejo al más nuevo. */
    List<Reembolso> buscarPendientes();

    /**
     * {@code PENDIENTE} → {@code HECHO} o {@code RECHAZADO}. Un solo UPDATE
     * con la condición adentro: si otro pedido lo resolvió antes, devuelve
     * false.
     */
    boolean resolver(String pagoId, EstadoReembolso estado, LocalDateTime ahora);
}
