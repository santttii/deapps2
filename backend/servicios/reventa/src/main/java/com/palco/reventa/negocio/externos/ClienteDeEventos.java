package com.palco.reventa.negocio.externos;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.JsonbException;

import com.palco.common.ErroresDeNegocio;
import com.palco.reventa.negocio.externos.ClienteRest.Respuesta;

/**
 * La fecha de un evento, que Reventa necesita para la regla de las 3 horas.
 * Se la pide a ServicioDeEventos por REST con el token de servicio.
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

    /** Del evento solo interesan el id y la fecha: hora local de Argentina, sin zona ("2026-11-20T21:00:00"). */
    public record Evento(String id, String fechaISO) {
    }

    @Inject
    ClienteRest rest;

    /** La fecha del evento en hora local de Argentina. 404 si Eventos no lo conoce. */
    public LocalDateTime fechaDelEvento(String eventoId) {
        Respuesta respuesta = rest.llamar("GET", RUTA, Map.of(), Map.of(), null);
        if (respuesta.estado() != 200) {
            LOG.warning("Eventos respondió " + respuesta.estado() + " al leer el catálogo.");
            throw new ErroresDeNegocio.SinRespuesta(EVENTOS_NO_RESPONDE);
        }
        Evento evento = leer(respuesta.cuerpo()).stream()
                .filter(e -> eventoId.equals(e.id()))
                .findFirst()
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe el evento de esa entrada."));
        try {
            return LocalDateTime.parse(evento.fechaISO());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ErroresDeNegocio.SinRespuesta("Eventos respondió una fecha que no se entiende.");
        }
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
