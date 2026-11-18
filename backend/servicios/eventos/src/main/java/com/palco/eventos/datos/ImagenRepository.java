package com.palco.eventos.datos;

import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.eventos.dominio.Imagen;

/**
 * Las fotos de los eventos guardadas en la base. El almacén
 * {@code AlmacenEnBaseDeDatos} conoce solo esta interfaz: en el servidor la
 * implementa {@link ImagenRepositoryJpa} y en los tests un doble.
 */
@Local
public interface ImagenRepository {

    /** Guarda una imagen nueva. El id lo genera quien llama y no se repite. */
    void guardar(Imagen imagen);

    /** La imagen con ese id, si existe. */
    Optional<Imagen> buscar(String id);
}
