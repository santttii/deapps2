package com.palco.eventos.datos;

import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.eventos.dominio.Sector;

/**
 * Dónde se buscan los sectores y se mueve su cupo. El negocio conoce solo esta
 * interfaz: en el servidor la implementa {@link SectorRepositoryJpa} contra la
 * base, y en los tests un doble.
 *
 * Los sectores se crean con su evento ({@link EventoRepository#guardar}): acá
 * no hay alta.
 */
@Local
public interface SectorRepository {

    /** El sector con ese id, de cualquier evento. Si es del evento pedido lo controla el negocio. */
    Optional<Sector> buscarPorId(String sectorId);

    /**
     * Suma {@code cantidad} a las entradas vendidas del sector (negativa para
     * liberar), en un solo UPDATE con la condición adentro: solo cambia si el
     * resultado queda entre 0 y el cupo. Devuelve si cambió.
     *
     * Si cambió, deja {@code sector} con los valores que quedaron en la base,
     * así quien llama devuelve las vendidas de verdad y no las que tenía cargadas.
     */
    boolean sumarVendidas(Sector sector, int cantidad);
}
