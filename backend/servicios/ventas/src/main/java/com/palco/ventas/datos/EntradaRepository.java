package com.palco.ventas.datos;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.ventas.dominio.Entrada;

/**
 * Dónde se guardan, se buscan y se emiten las entradas. El negocio y el
 * consumidor de mensajes conocen solo esta interfaz: en el servidor la
 * implementa {@link EntradaRepositoryJpa} contra la base, y en los tests un
 * doble.
 */
@Local
public interface EntradaRepository {

    /**
     * Guarda las entradas de una venta recién confirmada; los INSERT salen en
     * el momento. Lanza {@code ErroresDeNegocio.Conflicto} si la venta ya tiene
     * una entrada para alguno de esos DNI: es la base la que lo impide, con una
     * restricción única.
     */
    void guardar(List<Entrada> entradas);

    /**
     * La entrada con su venta cargada, para leer el comprador y el evento fuera
     * de una transacción. De esa venta no vienen cargados los renglones ni las
     * otras entradas.
     */
    Optional<Entrada> buscarPorId(String entradaId);

    /**
     * Emite las entradas de la venta que siguen {@code PENDIENTE_EMISION}: a
     * cada una le pone un código QR propio, la deja {@code VALIDA} y anota
     * {@code ahora} (en UTC) como fecha de emisión. Devuelve cuántas emitió.
     *
     * Es idempotente: si se llama dos veces para la misma venta, la segunda no
     * encuentra ninguna pendiente y devuelve 0. Tampoco falla si la venta no
     * tiene entradas o no existe.
     */
    int emitirPendientes(String ventaId, LocalDateTime ahora);
}
