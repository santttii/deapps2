package com.palco.ventas.api;

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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import com.palco.common.seguridad.Sesion;
import com.palco.ventas.api.Dtos.EntradaDto;
import com.palco.ventas.api.Dtos.UsoDeEntradaDto;
import com.palco.ventas.negocio.ComprasEnCurso;
import com.palco.ventas.negocio.UsoDeEntradas;

/**
 * API REST de las entradas, en {@code /api/v1/entradas}: consultar una
 * entrada y usarla en la puerta. Las entradas no se crean por acá: nacen
 * cuando se aprueba el pago de su venta.
 *
 * Es una capa delgada, igual que {@link VentasResource}: saca del token quién
 * hace el pedido, delega en el negocio y agrega el enlace {@code self}. La
 * entrada responde solo a quien la compró; una entrada ajena y una que no
 * existe responden lo mismo, 404.
 *
 * {@code @RequestScoped}: el servidor crea un objeto de esta clase por cada
 * pedido y lo descarta al responder. No guarda nada entre un pedido y el
 * siguiente: la entrada se lee de la base cada vez.
 */
@RequestScoped
@Path(Enlaces.ENTRADAS)
@Produces(MediaType.APPLICATION_JSON)
public class EntradasResource {

    @Inject
    ComprasEnCurso compras;

    @Inject
    UsoDeEntradas usos;

    /**
     * {@code GET /api/v1/entradas/T-9c41d07e}: la entrada como está ahora.
     * Recién pagada está {@code PENDIENTE_EMISION}, sin código; cuando la
     * emisión la procesa pasa a {@code VALIDA} y trae su {@code codigoQr}.
     */
    @GET
    @Path("{entradaId}")
    @RolesAllowed("user")
    public EntradaDto obtener(@PathParam("entradaId") String entradaId,
            @Context SecurityContext seguridad, @Context UriInfo pedido) {
        EntradaDto entrada = compras.obtenerEntrada(Sesion.usuarioId(seguridad), entradaId);
        return new Enlaces(pedido.getBaseUri()).completar(entrada);
    }

    /**
     * {@code POST /api/v1/entradas/usos} con
     * {@code {"codigoQr": "…", "eventoId": "ev-1"}}: usa la entrada en la
     * puerta y responde 200 con la entrada ya {@code USADA}. Es interna: la
     * llama ServicioDeValidación con el token de servicio, nunca el frontend.
     *
     * Responde 200 y no 201 porque no crea un recurso nuevo: cambia el estado
     * de una entrada que ya existía. Repetir el pedido no la usa dos veces:
     * la segunda vez responde 409 {@code entrada-ya-usada}.
     */
    @POST
    @Path(Enlaces.USOS)
    @RolesAllowed("servicio")
    @Consumes(MediaType.APPLICATION_JSON)
    public EntradaDto usar(@NotNull(message = "Falta el cuerpo de la solicitud.") @Valid UsoDeEntradaDto uso,
            @Context UriInfo pedido) {
        EntradaDto entrada = usos.marcarUsada(uso.codigoQr(), uso.eventoId());
        return new Enlaces(pedido.getBaseUri()).completar(entrada);
    }
}
