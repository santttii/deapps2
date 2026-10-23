package com.palco.eventos.datos;

import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.eventos.dominio.DevolucionDeCupo;

/**
 * Las devoluciones de cupo ya aplicadas, por su clave. El negocio conoce solo
 * esta interfaz: en el servidor la implementa {@link DevolucionDeCupoRepositoryJpa}
 * y en los tests un doble.
 */
@Local
public interface DevolucionDeCupoRepository {

    /** La devolución con esa clave, si ya se aplicó. */
    Optional<DevolucionDeCupo> buscar(String clave);

    /**
     * Registra una devolución recién aplicada. Corre en la misma transacción
     * que la resta del cupo: o quedan las dos o ninguna. Si otra devolución con
     * la misma clave se guardó justo antes (dos pedidos iguales a la vez), lanza
     * 409 {@code devolucion-ya-aplicada} y la transacción se deshace, así que
     * ese pedido no resta nada.
     */
    void registrar(DevolucionDeCupo devolucion);
}
