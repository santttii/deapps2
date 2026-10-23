package com.palco.validacion.api;

import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import jakarta.ws.rs.core.UriInfo;

import com.palco.common.seguridad.Sesion;
import com.palco.validacion.api.Dtos.EscaneoDto;
import com.palco.validacion.api.Dtos.NuevoEscaneoDto;
import com.palco.validacion.api.Dtos.ResultadoDeEscaneoDto;
import com.palco.validacion.negocio.IValidacion;

/**
 * API REST de la puerta, en {@code /api/v1/validacion/escaneos}. Escanear lo
 * hace solo el personal de puerta ({@code staff}); el registro lo ve también
 * el organizador.
 *
 * Cada escaneo responde 201, sea cual sea el resultado: lo que se crea es el
 * renglón del registro, que existe tanto si la entrada era válida como si no.
 * Que no pueda entrar es un dato de la respuesta ({@code resultado} y
 * {@code puedeEntrar}), no un error HTTP. Es el mismo criterio que Pagos con
 * un cobro rechazado. El único error propio es el 504: si Ventas no contesta
 * no hay resultado que dar.
 *
 * {@code @RequestScoped}: una instancia por pedido HTTP. No guarda nada; saca
 * del token quién escanea, valida el formato de lo que llega y delega en
 * {@link IValidacion}.
 */
@RequestScoped
@Path("escaneos")
@Produces(MediaType.APPLICATION_JSON)
public class EscaneosResource {

    @Inject
    IValidacion validacion;

    /**
     * {@code POST /api/v1/validacion/escaneos} con
     * {@code {"codigoQr": "…", "eventoId": "ev-1"}}. Responde 201 con el
     * resultado y la cabecera {@code Location} del escaneo.
     */
    @POST
    @RolesAllowed("staff")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response escanear(@NotNull(message = "Falta el cuerpo de la solicitud.") @Valid NuevoEscaneoDto nuevo,
            @Context SecurityContext seguridad, @Context UriInfo pedido) {
        ResultadoDeEscaneoDto resultado = validacion.escanear(nuevo.codigoQr(), nuevo.eventoId(),
                Sesion.usuarioId(seguridad));
        return Response.created(pedido.getAbsolutePathBuilder().path(resultado.id()).build())
                .entity(resultado)
                .build();
    }

    /**
     * {@code GET /api/v1/validacion/escaneos?eventoId=ev-1}: el registro de la
     * puerta, del más nuevo al más viejo. El organizador ve solo el de sus
     * eventos (403 {@code evento-ajeno} si no es suyo).
     */
    @GET
    @RolesAllowed({ "staff", "organizer" })
    public List<EscaneoDto> listar(
            @QueryParam("eventoId")
            @NotBlank(message = "Indicá el evento con ?eventoId=.")
            @Size(max = 40, message = "El id del evento tiene hasta 40 caracteres.")
            String eventoId,
            @Context SecurityContext seguridad) {
        return validacion.listar(eventoId, tokenDelOrganizador(seguridad));
    }

    /** {@code GET /api/v1/validacion/escaneos/ESC-1a2b3c4d}: un renglón del registro. */
    @GET
    @Path("{escaneoId}")
    @RolesAllowed({ "staff", "organizer" })
    public EscaneoDto obtener(@PathParam("escaneoId") String escaneoId, @Context SecurityContext seguridad) {
        return validacion.obtener(escaneoId, tokenDelOrganizador(seguridad));
    }

    /**
     * El token de quien consulta si es organizador, para que el negocio le
     * pregunte a Eventos cuáles son sus eventos; {@code null} si es el
     * personal de puerta, que por ahora ve el registro de cualquier evento.
     */
    static String tokenDelOrganizador(SecurityContext seguridad) {
        return seguridad.isUserInRole("staff") ? null : Sesion.tokenDelUsuario(seguridad);
    }
}
