package com.palco.pagos.datos;

import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.pagos.dominio.Pago;

/**
 * Dónde se guardan y se buscan los pagos. {@code ServicioDePagos} conoce solo
 * esta interfaz: en el servidor la implementa {@link PagoRepositoryJpa} contra
 * la base, y en los tests un repositorio en memoria.
 */
@Local
public interface PagoRepository {

    Optional<Pago> buscarPorId(String pagoId);

    Optional<Pago> buscarPorClave(String claveIdempotencia);

    /**
     * Guarda un pago nuevo. Lanza {@code ErroresDeNegocio.Conflicto} si ya hay
     * un pago con esa clave de idempotencia, ese id externo o ese id: es la
     * base la que lo impide, con sus restricciones únicas.
     */
    void guardar(Pago pago);
}
