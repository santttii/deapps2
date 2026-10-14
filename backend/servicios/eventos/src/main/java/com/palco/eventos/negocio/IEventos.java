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

    /** Catálogo ordenado por fecha. Ambos filtros son opcionales (null = sin filtro). */
    List<EventoDto> listarEventos(String categoria, String busqueda);

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
}
