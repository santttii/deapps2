package com.palco.eventos.negocio;

import java.util.List;

import jakarta.ejb.Local;

import com.palco.eventos.api.Dtos.EditarEventoDto;
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

    /** El tramo de {@code POST /api/eventos/imagenes}. También es una palabra reservada. */
    String RUTA_IMAGENES = "imagenes";

    /** Catálogo ordenado por fecha. Ambos filtros son opcionales (null = sin filtro). */
    List<EventoDto> listarEventos(String categoria, String busqueda);

    /**
     * Los eventos de ese organizador, ordenados por fecha: los que creó con su
     * cuenta. Es lo que muestra su panel (limitación L18).
     */
    List<EventoDto> listarDelOrganizador(String organizadorId);

    /**
     * El id del organizador dueño del evento, o {@code NoEncontrado}. Lo usa
     * Validación para que un organizador vea y arme solo la puerta de sus
     * eventos (limitación L17). Un evento sin dueño devuelve null.
     */
    String organizadorDe(String eventoId);

    /** Detalle por slug (el identificador que usa la URL del frontend). */
    EventoDto obtenerDetalle(String slug);

    /**
     * Alta de un evento con sus sectores. Genera id y slug únicos. Una fecha
     * que ya pasó es un dato inválido del campo {@code fechaISO} (400).
     */
    EventoDto crearEvento(NuevoEventoDto nuevo, String organizadorId);

    /**
     * Cambia los datos de un evento y sus sectores. Reemplaza el evento
     * completo: los sectores que no vengan en la lista se quitan.
     *
     * Solo el organizador dueño: para otro el evento no existe (404), igual
     * que uno que no existe de verdad. Un evento que ya empezó no se edita
     * (409 {@code evento-finalizado}) y tampoco uno cancelado (409
     * {@code evento-cancelado}).
     *
     * Lo que no se puede, con 409 {@code sector-con-ventas}: bajar el cupo de
     * un sector por debajo de lo que ya se vendió, cambiarle el precio si
     * tiene ventas o quitarlo si tiene entradas vendidas. El slug no cambia
     * aunque cambie el título: lo lleva la URL del frontend.
     */
    EventoDto editarEvento(String eventoId, EditarEventoDto cambios, String organizadorId);

    /**
     * Cancela un evento: lo marca como cancelado y publica
     * {@code EVENTO_CANCELADO} en el tópico de cancelaciones. Devuelve el
     * evento ya cancelado.
     *
     * El evento no se borra: su detalle sigue respondiendo para que quien
     * tenga el enlace vea que se canceló, pero sale del catálogo público y no
     * se le puede vender nada más.
     *
     * Las entradas ya vendidas no se tocan acá. Ventas consume el mensaje,
     * anula las entradas, le pide a Pagos el reembolso y publica un
     * {@code COMPRA_ANULADA} por compra, que es lo que escucha Notificaciones
     * para avisarle a cada comprador.
     *
     * Solo el organizador dueño: para otro el evento no existe (404). Un
     * evento que ya empezó no se cancela (409 {@code evento-finalizado}:
     * reembolsar algo que ya se usó no corresponde) y uno ya cancelado
     * tampoco (409 {@code evento-cancelado}), así nadie reembolsa dos veces.
     *
     * @param motivo lo que se le cuenta al comprador; puede ser null
     */
    EventoDto cancelarEvento(String eventoId, String motivo, String organizadorId);

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
