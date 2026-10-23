package com.palco.eventos.api;

import java.net.URI;
import java.util.List;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import com.palco.common.seguridad.Sesion;
import com.palco.eventos.api.Dtos.CambioCupoDto;
import com.palco.eventos.api.Dtos.EventoDto;
import com.palco.eventos.api.Dtos.NuevoEventoDto;
import com.palco.eventos.api.Dtos.SectorDto;
import com.palco.eventos.negocio.IEventos;

/**
 * API REST de ServicioDeEventos, en {@code /api/eventos}. El catálogo es
 * público; crear eventos y ver los propios requiere rol {@code organizer} y
 * actualizar cupo es solo para otros servicios (rol {@code servicio}, Fase 4).
 */
@RequestScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EventosResource {

    @Inject
    IEventos eventos;

    /** {@code GET /api/eventos?categoria=Música&q=kiroshi} */
    @GET
    @PermitAll
    public List<EventoDto> listar(@QueryParam("categoria") String categoria, @QueryParam("q") String busqueda) {
        return eventos.listarEventos(categoria, busqueda);
    }

    /**
     * {@code GET /api/eventos/mios}: los eventos del organizador del token,
     * ordenados por fecha. Lo usan el panel y Validación, que con el token del
     * organizador comprueba que el evento cuyo registro de la puerta pide sea
     * suyo. Una ruta fija le gana a {@code {slug}}, y por eso ningún evento
     * recibe el slug {@code mios}.
     */
    @GET
    @RolesAllowed("organizer")
    @Path(IEventos.RUTA_MIOS)
    public List<EventoDto> mios(@Context SecurityContext seguridad) {
        return eventos.listarDelOrganizador(Sesion.usuarioId(seguridad));
    }

    /** {@code GET /api/eventos/atletico-norte-racing-sur} */
    @GET
    @PermitAll
    @Path("{slug}")
    public EventoDto detalle(@PathParam("slug") String slug) {
        return eventos.obtenerDetalle(slug);
    }

    /** {@code POST /api/eventos}: el evento queda a nombre del organizador del token. */
    @POST
    @RolesAllowed("organizer")
    public Response crear(@NotNull(message = "Falta el cuerpo de la solicitud.") @Valid NuevoEventoDto nuevo,
            @Context SecurityContext seguridad) {
        EventoDto creado = eventos.crearEvento(nuevo, Sesion.usuarioId(seguridad));
        return Response.created(URI.create(creado.slug())).entity(creado).build();
    }

    /** {@code POST /api/eventos/ev-1/sectores/sec-1-1/cupo} con {@code {"cantidad": 2}} */
    @POST
    @RolesAllowed("servicio")
    @Path("{eventoId}/sectores/{sectorId}/cupo")
    public SectorDto actualizarCupo(@PathParam("eventoId") String eventoId,
            @PathParam("sectorId") String sectorId,
            @NotNull(message = "Falta el cuerpo de la solicitud.") @Valid CambioCupoDto cambio) {
        // Con clave es una devolución segura de repetir (la manda Ventas al cancelar o vencer una compra).
        if (cambio.clave() != null) {
            return eventos.devolverCupo(eventoId, sectorId, -cambio.cantidad(), cambio.clave());
        }
        return eventos.actualizarCupo(eventoId, sectorId, cambio.cantidad());
    }
}
