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

    /**
     * La entrada con ese código QR y su venta cargada (de ahí sale el evento),
     * o vacío si ningún código coincide. Es lo que lee la validación en puerta.
     */
    Optional<Entrada> buscarPorCodigoQr(String codigoQr);

    /**
     * Pasa la entrada de {@code VALIDA} a {@code USADA} con un solo UPDATE
     * condicional. Devuelve {@code true} si esta llamada la usó y
     * {@code false} si no estaba {@code VALIDA}: ya la había usado otro
     * escaneo, está en la reventa o quedó anulada. Si dos puertas la escanean
     * a la vez, la base deja pasar a una sola.
     */
    boolean marcarUsada(String entradaId);
}
