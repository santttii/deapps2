package com.palco.ventas.api;

import java.net.URI;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import jakarta.ws.rs.core.UriInfo;

import com.palco.ventas.api.DtosDeReventa.EntradaEnReventaDto;
import com.palco.ventas.api.DtosDeReventa.PedidoDeVendedorDto;
import com.palco.ventas.api.DtosDeReventa.TransferenciaDto;
import com.palco.ventas.api.DtosDeReventa.TransferenciaHechaDto;
import com.palco.ventas.negocio.ReventaDeEntradas;

/**
 * Las operaciones de reventa sobre las entradas, en
 * {@code /api/v1/operaciones-de-reventa}. Son internas: todas piden el rol
 * {@code servicio} y las llama ServicioDeReventa. La persona que publica o
 * compra habla con Reventa ({@code /api/v1/reventas}); Reventa le pide a
 * Ventas, dueña de las entradas, que cambie su estado.
 *
 * La ruta no empieza con {@code reventas}: ese prefijo, bajo {@code /api/v1},
 * es el context-root del WAR de Reventa y el servidor le mandaría allá el
 * pedido. Tampoco cuelga de {@code entradas}, para no compartir la raíz con
 * {@link EntradasResource}.
 *
 * Capa delgada: valida el formato y delega en {@link ReventaDeEntradas}.
 * {@code @RequestScoped}: una instancia por pedido, que no guarda nada.
 */
@RequestScoped
@Path("operaciones-de-reventa")
@Produces(MediaType.APPLICATION_JSON)
public class ReventaDeEntradasResource {

    @Inject
    ReventaDeEntradas reventa;

    /** {@code GET /api/v1/operaciones-de-reventa/entradas/T-9c41d07e?vendedorId=U-1}: la entrada, si es de ese vendedor. */
    @GET
    @Path("entradas/{entradaId}")
    @RolesAllowed("servicio")
    public EntradaEnReventaDto consultar(@PathParam("entradaId") String entradaId,
            @QueryParam("vendedorId") @NotBlank(message = "Falta el parámetro vendedorId.") String vendedorId) {
        return reventa.consultar(entradaId, vendedorId);
    }

    /** {@code POST /api/v1/operaciones-de-reventa/publicaciones} con {@code {"entradaId", "vendedorId"}}: la deja PUBLICADA. */
    @POST
    @Path("publicaciones")
    @RolesAllowed("servicio")
    @Consumes(MediaType.APPLICATION_JSON)
    public EntradaEnReventaDto publicar(
            @NotNull(message = "Falta el cuerpo de la solicitud.") @Valid PedidoDeVendedorDto pedido) {
        return reventa.publicar(pedido.entradaId(), pedido.vendedorId());
    }

    /** {@code POST /api/v1/operaciones-de-reventa/retiros} con {@code {"entradaId", "vendedorId"}}: la vuelve a VALIDA. */
    @POST
    @Path("retiros")
    @RolesAllowed("servicio")
    @Consumes(MediaType.APPLICATION_JSON)
    public EntradaEnReventaDto retirar(
            @NotNull(message = "Falta el cuerpo de la solicitud.") @Valid PedidoDeVendedorDto pedido) {
        return reventa.retirar(pedido.entradaId(), pedido.vendedorId());
    }

    /**
     * {@code POST /api/v1/operaciones-de-reventa/transferencias}: la original
     * queda VENDIDA y nace la venta del comprador. Responde 201 con la venta
     * nueva y su entrada; repetir el pedido devuelve lo mismo, también con 201.
     */
    @POST
    @Path("transferencias")
    @RolesAllowed("servicio")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response transferir(@NotNull(message = "Falta el cuerpo de la solicitud.") @Valid TransferenciaDto pedido,
            @Context UriInfo info) {
        TransferenciaHechaDto hecha = reventa.transferir(pedido);
        URI venta = info.getBaseUriBuilder().path(Enlaces.VENTAS).path(hecha.ventaId()).build();
        return Response.created(venta).entity(hecha).build();
    }
}
