package com.palco.eventos.negocio;

import java.util.List;

import jakarta.ejb.Local;

import com.palco.eventos.api.Dtos.EventoDto;
import com.palco.eventos.api.Dtos.NuevoEventoDto;
import com.palco.eventos.api.Dtos.SectorDto;

/**
 * Interfaz de ServicioDeEventos (Entrega 1): todo lo que el resto del sistema
 * puede pedirle. Los otros servicios la consumen por REST, nunca contra la
 * implementación.
 */
@Local
public interface IEventos {

    /** El tramo de {@code GET /api/eventos/mios}. Es una palabra reservada: ningún slug puede ser igual. */
    String RUTA_MIOS = "mios";

    /** Catálogo ordenado por fecha. Ambos filtros son opcionales (null = sin filtro). */
    List<EventoDto> listarEventos(String categoria, String busqueda);

    /**
     * Los eventos que creó ese organizador, ordenados por fecha. Si no creó
     * ninguno, la lista está vacía.
     */
    List<EventoDto> listarDelOrganizador(String organizadorId);

    /** Detalle por slug (el identificador que usa la URL del frontend). */
    EventoDto obtenerDetalle(String slug);

    /**
     * Alta de un evento con sus sectores. Genera id y slug únicos. Una fecha
     * que ya pasó es un dato inválido del campo {@code fechaISO} (400).
     */
    EventoDto crearEvento(NuevoEventoDto nuevo, String organizadorId);

    /**
     * Suma (venta/reserva) o resta (liberación) entradas vendidas de un sector,
     * de forma atómica: nunca queda por encima del cupo ni por debajo de 0.
     * Si el evento ya empezó, sumar es un conflicto {@code evento-finalizado}
     * (409); restar se acepta siempre.
     */
    SectorDto actualizarCupo(String eventoId, String sectorId, int cantidad);

    /**
     * Devuelve {@code cantidad} lugares (positiva) del sector, de forma segura
     * de repetir: la primera vez con una clave resta y la guarda, y las
     * siguientes con la misma clave no restan y devuelven el sector como está.
     * Si dos pedidos con la misma clave llegan a la vez, uno resta y el otro
     * recibe 409 {@code devolucion-ya-aplicada}. Una clave ya usada para otro
     * sector o con otra cantidad es 409 {@code clave-reutilizada}.
     */
    SectorDto devolverCupo(String eventoId, String sectorId, int cantidad, String clave);
}
