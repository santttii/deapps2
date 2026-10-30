package com.palco.validacion.api;

import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import com.palco.validacion.api.Dtos.PuertaAsignadaDto;
import com.palco.validacion.negocio.IValidacion;

/**
 * Quién trabaja en la puerta de cada evento, en {@code /api/v1/validacion/puertas}
 * (limitación L17). El organizador dueño del evento asigna y quita cuentas de
 * staff por su email; un evento ajeno responde 404, igual que uno que no
 * existe. El staff ve en qué eventos puede escanear.
 *
 * {@code @RequestScoped}: una instancia por pedido HTTP. No guarda nada; saca
 * del token quién pide y delega en {@link IValidacion}.
 */
@RequestScoped
@Path("puertas")
@Produces(MediaType.APPLICATION_JSON)
public class PuertasResource {

    @Inject
    IValidacion validacion;

    /** {@code GET /api/v1/validacion/puertas/mias}: los eventos donde el staff del token puede escanear. */
    @GET
    @Path("mias")
    @RolesAllowed("staff")
    public List<PuertaAsignadaDto> mias(@Context SecurityContext seguridad) {
        return validacion.misPuertas(EscaneosResource.quien(seguridad));
    }

    /** {@code GET /api/v1/validacion/puertas?eventoId=ev-1}: las cuentas de la puerta de un evento propio. */
    @GET
    @RolesAllowed("organizer")
    public List<PuertaAsignadaDto> equipo(
            @QueryParam("eventoId")
            @NotBlank(message = "Indicá el evento con ?eventoId=.")
            @Size(max = 40, message = "El id del evento tiene hasta 40 caracteres.")
            String eventoId,
            @Context SecurityContext seguridad) {
        return validacion.equipoDePuerta(eventoId, EscaneosResource.quien(seguridad));
    }

    /**
     * {@code PUT /api/v1/validacion/puertas/ev-1/puerta@palco.test}: asigna esa
     * cuenta a la puerta del evento y responde 200 con la asignación. Repetirlo
     * responde la misma.
     */
    @PUT
    @Path("{eventoId}/{email}")
    @RolesAllowed("organizer")
    public PuertaAsignadaDto asignar(
            @PathParam("eventoId") @Size(max = 40, message = "El id del evento tiene hasta 40 caracteres.")
            String eventoId,
            @PathParam("email") @NotBlank @Email(message = "Ingresá un email válido.")
            @Size(max = 200, message = "El email tiene hasta 200 caracteres.") String email,
            @Context SecurityContext seguridad) {
        return validacion.asignar(eventoId, email, EscaneosResource.quien(seguridad));
    }

    /** {@code DELETE /api/v1/validacion/puertas/ev-1/puerta@palco.test}: la saca; 204 aunque no estuviera. */
    @DELETE
    @Path("{eventoId}/{email}")
    @RolesAllowed("organizer")
    public Response quitar(
            @PathParam("eventoId") @Size(max = 40, message = "El id del evento tiene hasta 40 caracteres.")
            String eventoId,
            @PathParam("email") @Size(max = 200, message = "El email tiene hasta 200 caracteres.") String email,
            @Context SecurityContext seguridad) {
        validacion.quitar(eventoId, email, EscaneosResource.quien(seguridad));
        return Response.noContent().build();
    }
}
