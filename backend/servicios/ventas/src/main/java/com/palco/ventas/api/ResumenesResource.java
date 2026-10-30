package com.palco.ventas.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

import com.palco.common.seguridad.Sesion;
import com.palco.ventas.api.Dtos.ResumenDeVentasDto;
import com.palco.ventas.negocio.ResumenDeVentas;

/**
 * Las ventas por día y la liquidación de un evento, en
 * {@code /api/v1/resumenes}. Es lo que muestra el panel del organizador.
 *
 * Está en un recurso aparte de {@link VentasResource} porque es para otro
 * rol: ahí todas las operaciones son del comprador de una venta, y acá el que
 * pregunta es el organizador dueño del evento. Y tiene su propia ruta, en
 * lugar de ser {@code ventas/resumen}, para que no haya que razonar sobre si
 * gana la ruta fija o {@code ventas/{ventaId}}.
 *
 * {@code @RequestScoped}: no guarda nada; saca del token quién pregunta y
 * delega en {@link ResumenDeVentas}.
 */
@RequestScoped
@Path("resumenes")
@Produces(MediaType.APPLICATION_JSON)
public class ResumenesResource {

    @Inject
    ResumenDeVentas resumenes;

    /**
     * {@code GET /api/v1/resumenes?eventoId=ev-1}: las ventas por día y la
     * liquidación del evento. Solo el organizador dueño; para otro el evento
     * no existe (404).
     */
    @GET
    @RolesAllowed("organizer")
    public ResumenDeVentasDto delEvento(
            @QueryParam("eventoId")
            @NotBlank(message = "Indicá el evento con ?eventoId=.")
            @Size(max = 40, message = "El id del evento tiene hasta 40 caracteres.")
            String eventoId,
            @Context SecurityContext seguridad) {
        return resumenes.delEvento(eventoId, Sesion.usuarioId(seguridad));
    }
}
