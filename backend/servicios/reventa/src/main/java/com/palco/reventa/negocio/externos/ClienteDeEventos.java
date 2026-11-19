package com.palco.reventa.negocio.externos;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.JsonbException;

import com.palco.common.ErroresDeNegocio;
import com.palco.reventa.negocio.externos.ClienteRest.Respuesta;

/**
 * Lo que Reventa necesita de un evento: la fecha, para la regla de las 3
 * horas, y el precio de lista de cada sector, que es la base del tope y del
 * mínimo. Se lo pide a ServicioDeEventos por REST con el token de servicio.
 *
 * Eventos busca el detalle por slug y no por id, y la entrada solo conoce el
 * id del evento. Por eso se lee el catálogo ({@code GET /api/eventos}) y se
 * busca el evento ahí. El catálogo es chico; si crece, lo que corresponde es
 * una consulta por id en Eventos.
 *
 * {@code @ApplicationScoped}: una instancia sin estado para todo el servicio.
 */
@ApplicationScoped
public class ClienteDeEventos {

    private static final Logger LOG = Logger.getLogger(ClienteDeEventos.class.getName());

    static final String RUTA = "/api/eventos";
    private static final String EVENTOS_NO_RESPONDE = "El servicio de eventos no responde. Probá de nuevo.";

    /**
     * Del evento interesan el id, la fecha (hora local de Argentina, sin zona:
     * "2026-11-20T21:00:00") y sus sectores.
     */
    public record Evento(String id, String fechaISO, List<Sector> sectores) {
    }

    /** Del sector, el id y el precio de lista en pesos enteros. */
    public record Sector(String id, long precio) {
    }

    @Inject
    ClienteRest rest;

    /** La fecha del evento en hora local de Argentina. 404 si Eventos no lo conoce. */
    public LocalDateTime fechaDelEvento(String eventoId) {
        Evento evento = buscar(eventoId);
        try {
            return LocalDateTime.parse(evento.fechaISO());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ErroresDeNegocio.SinRespuesta("Eventos respondió una fecha que no se entiende.");
        }
    }

    /**
     * El precio de lista del sector: lo que costó la entrada en la primera
     * venta. Es la base del tope porque no cambia con las reventas; lo pagado,
     * sí. Eventos no deja cambiarlo cuando el sector ya tiene entradas
     * vendidas. 404 si Eventos no conoce el evento o el sector.
     */
    public long precioDelSector(String eventoId, String sectorId) {
        Evento evento = buscar(eventoId);
        Sector sector = Objects.requireNonNullElse(evento.sectores(), List.<Sector>of()).stream()
                .filter(s -> sectorId.equals(s.id()))
                .findFirst()
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe el sector de esa entrada."));
        // Un sector sin precio llega como 0: con eso el tope sería 0 y no se podría publicar nada.
        if (sector.precio() <= 0) {
            throw new ErroresDeNegocio.SinRespuesta("Eventos respondió un precio que no se entiende.");
        }
        return sector.precio();
    }

    /** El evento, leído del catálogo. {@code SinRespuesta} si Eventos no lo entrega; 404 si no lo conoce. */
    private Evento buscar(String eventoId) {
        Respuesta respuesta = rest.llamar("GET", RUTA, Map.of(), Map.of(), null);
        if (respuesta.estado() != 200) {
            LOG.warning("Eventos respondió " + respuesta.estado() + " al leer el catálogo.");
            throw new ErroresDeNegocio.SinRespuesta(EVENTOS_NO_RESPONDE);
        }
        return leer(respuesta.cuerpo()).stream()
                .filter(e -> eventoId.equals(e.id()))
                .findFirst()
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe el evento de esa entrada."));
    }

    private static List<Evento> leer(String json) {
        try {
            Evento[] eventos = ClienteRest.JSONB.fromJson(json, Evento[].class);
            if (eventos != null) {
                return List.of(eventos);
            }
        } catch (JsonbException e) {
            // Se trata abajo.
        }
        throw new ErroresDeNegocio.SinRespuesta("Eventos respondió algo que no es el catálogo.");
    }
}
