package com.palco.reventa.api;

import java.net.URI;
import java.util.List;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
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
import com.palco.reventa.api.Dtos.CompraDto;
import com.palco.reventa.api.Dtos.CompraHechaDto;
import com.palco.reventa.api.Dtos.NuevaPublicacionDto;
import com.palco.reventa.api.Dtos.PublicacionDto;
import com.palco.reventa.negocio.IReventa;

/**
 * API REST de la reventa, en {@code /api/v1/reventas}. Capa delgada: saca del
 * token quién hace el pedido, valida el formato y delega en {@link IReventa}.
 *
 * {@code @RequestScoped}: una instancia por pedido, que no guarda nada entre
 * un pedido y el siguiente.
 */
@RequestScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class ReventasResource {

    @Inject
    IReventa reventa;

    /** {@code GET /api/v1/reventas?eventoId=ev-1}: las publicaciones activas del evento. Público, como el catálogo. */
    @GET
    @PermitAll
    public List<PublicacionDto> listar(@QueryParam("eventoId") String eventoId) {
        return reventa.listarActivas(eventoId);
    }

    /** {@code GET /api/v1/reventas/mias}: las publicaciones del usuario, en todos los estados. */
    @GET
    @Path("mias")
    @RolesAllowed("user")
    public List<PublicacionDto> mias(@Context SecurityContext seguridad) {
        return reventa.listarDelVendedor(Sesion.usuarioId(seguridad));
    }

    /** {@code POST /api/v1/reventas} con {@code {"entradaId": "T-9c41d07e", "precio": 52000}}. */
    @POST
    @RolesAllowed("user")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response publicar(@NotNull(message = "Falta el cuerpo de la solicitud.") @Valid NuevaPublicacionDto nueva,
            @Context SecurityContext seguridad) {
        PublicacionDto publicacion = reventa.publicar(Sesion.usuarioId(seguridad), nueva);
        return Response.created(URI.create(publicacion.id())).entity(publicacion).build();
    }

    /** {@code DELETE /api/v1/reventas/R-3f9a1c2b}: la retira; la entrada vuelve a VALIDA. 204 sin cuerpo. */
    @DELETE
    @Path("{publicacionId}")
    @RolesAllowed("user")
    public Response retirar(@PathParam("publicacionId") String publicacionId, @Context SecurityContext seguridad) {
        reventa.retirar(Sesion.usuarioId(seguridad), publicacionId);
        return Response.noContent().build();
    }

    /**
     * {@code POST /api/v1/reventas/R-3f9a1c2b/compra} con la cabecera
     * {@code Idempotency-Key}. Responde 201 con la publicación vendida, la
     * entrada nueva del comprador y su venta; {@code Location} apunta a esa
     * entrada en Ventas. Repetirlo con la misma clave devuelve lo mismo.
     */
    @POST
    @Path("{publicacionId}/compra")
    @RolesAllowed("user")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response comprar(@PathParam("publicacionId") String publicacionId,
            @HeaderParam("Idempotency-Key") String claveIdempotencia,
            @NotNull(message = "Falta el cuerpo de la solicitud.") @Valid CompraDto compra,
            @Context SecurityContext seguridad) {
        CompraHechaDto hecha = reventa.comprar(Sesion.usuarioId(seguridad), publicacionId, compra, claveIdempotencia);
        return Response.created(URI.create("/api/v1/entradas/" + hecha.entrada().id())).entity(hecha).build();
    }
}
