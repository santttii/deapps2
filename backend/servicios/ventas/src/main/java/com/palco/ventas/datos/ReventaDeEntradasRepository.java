package com.palco.ventas.datos;

import jakarta.ejb.Local;

/**
 * Los cambios de estado que la reventa le hace a una entrada. Están aparte de
 * {@link EntradaRepository} porque son de otra operación (la reventa, que pide
 * ServicioDeReventa) y así no se mezclan con la emisión y el uso en puerta.
 *
 * Cada cambio es un solo UPDATE con la condición adentro (el estado de partida
 * y que la entrada sea de ese vendedor) y devuelve si cambió una fila: si dos
 * pedidos llegan a la vez sobre la misma entrada, la base deja pasar a uno
 * solo. El vendedor es el comprador de la venta a la que pertenece la entrada.
 */
@Local
public interface ReventaDeEntradasRepository {

    /** {@code VALIDA} → {@code PUBLICADA}, solo si la entrada es de ese vendedor. */
    boolean publicar(String entradaId, String vendedorId);

    /** {@code PUBLICADA} → {@code VALIDA}, solo si la entrada es de ese vendedor. */
    boolean retirar(String entradaId, String vendedorId);

    /** {@code PUBLICADA} → {@code VENDIDA}, solo si la entrada es de ese vendedor. */
    boolean marcarVendida(String entradaId, String vendedorId);
}
