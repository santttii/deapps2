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
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import com.palco.common.seguridad.Sesion;
import com.palco.eventos.api.Dtos.CambioCupoDto;
import com.palco.eventos.api.Dtos.CancelacionDto;
import com.palco.eventos.api.Dtos.EditarEventoDto;
import com.palco.eventos.api.Dtos.EventoDto;
import com.palco.eventos.api.Dtos.ImagenSubidaDto;
import com.palco.eventos.api.Dtos.NuevoEventoDto;
import com.palco.eventos.api.Dtos.OrganizadorDto;
import com.palco.eventos.api.Dtos.SectorDto;
import com.palco.eventos.negocio.IEventos;
import com.palco.eventos.negocio.externos.AlmacenDeImagenes;

/**
 * API REST de ServicioDeEventos, en {@code /api/eventos}. El catálogo es
 * público; crear eventos y ver los propios requiere rol {@code organizer}, y
 * actualizar cupo y saber el dueño de un evento es solo para otros servicios
 * (rol {@code servicio}).
 */
@RequestScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EventosResource {

    @Inject
    IEventos eventos;

    @Inject
    AlmacenDeImagenes imagenes;

    /** {@code GET /api/eventos?categoria=Música&q=kiroshi} */
    @GET
    @PermitAll
    public List<EventoDto> listar(@QueryParam("categoria") String categoria, @QueryParam("q") String busqueda) {
        return eventos.listarEventos(categoria, busqueda);
    }

    /**
     * {@code GET /api/eventos/mios}: los eventos del organizador del token,
     * ordenados por fecha. Lo usa su panel. Una ruta fija le gana a
     * {@code {slug}}, y por eso ningún evento recibe el slug {@code mios}.
     */
    @GET
    @RolesAllowed("organizer")
    @Path(IEventos.RUTA_MIOS)
    public List<EventoDto> mios(@Context SecurityContext seguridad) {
        return eventos.listarDelOrganizador(Sesion.usuarioId(seguridad));
    }

    /**
     * {@code GET /api/eventos/ev-1/organizador}: quién es el dueño del evento.
     * Solo para otros servicios (Validación); el catálogo público no lo muestra.
     */
    @GET
    @RolesAllowed("servicio")
    @Path("{eventoId}/organizador")
    public OrganizadorDto organizador(@PathParam("eventoId") String eventoId) {
        return new OrganizadorDto(eventoId, eventos.organizadorDe(eventoId));
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

    /**
     * {@code PUT /api/eventos/ev-1}: cambia el evento completo. Solo el
     * organizador dueño; para otro el evento no existe (404). La ruta lleva el
     * id y no el slug, porque el slug no cambia pero tampoco es lo que tiene
     * a mano el panel.
     */
    @PUT
    @RolesAllowed("organizer")
    @Path("{eventoId}")
    public EventoDto editar(@PathParam("eventoId") String eventoId,
            @NotNull(message = "Falta el cuerpo de la solicitud.") @Valid EditarEventoDto cambios,
            @Context SecurityContext seguridad) {
        return eventos.editarEvento(eventoId, cambios, Sesion.usuarioId(seguridad));
    }

    /**
     * {@code POST /api/eventos/imagenes}: sube la foto de un evento y devuelve
     * su URL pública, que después se manda como {@code imagenUrl} al crear o
     * editar el evento. Solo rol {@code organizer}.
     *
     * El cuerpo es la imagen cruda, con su tipo en {@code Content-Type}, y no
     * un {@code multipart/form-data}: el único campo que se manda es el
     * archivo, así que el sobre multipart no agregaría nada y desde el
     * navegador se manda el {@code File} tal cual. El nombre del archivo no
     * viaja porque no se usa: la extensión sale del {@code Content-Type} y el
     * nombre lo pone el almacén.
     *
     * Es una ruta fija y va antes de {@code {slug}}, igual que {@code mios};
     * ningún evento puede quedarse con el slug "imagenes".
     */
    @POST
    @Path("imagenes")
    @RolesAllowed("organizer")
    @Consumes({ "image/jpeg", "image/png", "image/webp" })
    public ImagenSubidaDto subirImagen(byte[] contenido,
            @HeaderParam(HttpHeaders.CONTENT_TYPE) String tipo) {
        return new ImagenSubidaDto(imagenes.guardar(contenido, tipo, null));
    }

    /**
     * {@code POST /api/eventos/ev-1/cancelacion} con {@code {"motivo": "..."}}:
     * cancela el evento. Solo el organizador dueño; para otro el evento no
     * existe (404).
     *
     * Es un POST a un sub-recurso y no un {@code DELETE} del evento porque no
     * se borra nada: el evento queda marcado, su detalle sigue respondiendo y
     * lo que se dispara es el reembolso de las compras. El cuerpo es opcional:
     * se puede cancelar sin dar motivo.
     */
    @POST
    @RolesAllowed("organizer")
    @Path("{eventoId}/cancelacion")
    public EventoDto cancelar(@PathParam("eventoId") String eventoId,
            @Valid CancelacionDto cancelacion,
            @Context SecurityContext seguridad) {
        return eventos.cancelarEvento(eventoId, cancelacion == null ? null : cancelacion.motivo(),
                Sesion.usuarioId(seguridad));
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
