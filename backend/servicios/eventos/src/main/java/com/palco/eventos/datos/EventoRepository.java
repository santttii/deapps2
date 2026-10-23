package com.palco.eventos.datos;

import java.util.List;
import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.eventos.dominio.Categoria;
import com.palco.eventos.dominio.Evento;

/**
 * Dónde se guardan y se buscan los eventos. El negocio conoce solo esta
 * interfaz: en el servidor la implementa {@link EventoRepositoryJpa} contra la
 * base, y en los tests un doble.
 *
 * Toda búsqueda devuelve el evento con sus sectores ya cargados, en el orden
 * de la columna {@code orden}: quien lo recibe arma el DTO sin volver a la base.
 */
@Local
public interface EventoRepository {

    /**
     * Los eventos de esa categoría, o todos si es {@code null}, ordenados por
     * fecha. La búsqueda por texto no está acá: la hace el negocio en Java,
     * para que no dependa de cómo cada base compara acentos.
     */
    List<Evento> listar(Categoria categoria);

    /** Los eventos que creó ese organizador, ordenados por fecha. */
    List<Evento> listarDelOrganizador(String organizadorId);

    /** El evento con ese slug, si existe. */
    Optional<Evento> buscarPorSlug(String slug);

    /** Si ya hay un evento con ese slug. Se usa para elegir uno libre antes de guardar. */
    boolean existeSlug(String slug);

    /** Guarda un evento nuevo junto con sus sectores (cascade). */
    void guardar(Evento evento);
}
