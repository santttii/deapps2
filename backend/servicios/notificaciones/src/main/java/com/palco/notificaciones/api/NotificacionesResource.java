package com.palco.notificaciones.api;

import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.notificaciones.api.Dtos.RegistroDeAuditoriaDto;
import com.palco.notificaciones.negocio.IAuditoria;
import com.palco.notificaciones.negocio.PruebasDeMensajeria;

/**
 * API REST de ServicioDeNotificaciones, en {@code /api/notificaciones}. Es
 * interna y de desarrollo, y por eso no figura en la OpenAPI: sirve para
 * disparar la mensajería sin hacer una compra y para ver qué registró
 * auditoría.
 *
 * Todo pide rol {@code servicio}: con un rol humano, cualquier organizador
 * podría publicar pagos falsos. Ese rol solo lo consigue quien tiene el secreto
 * compartido ({@code POST /api/usuarios/servicios/token}).
 *
 * No lleva {@code @Consumes}: ningún endpoint recibe cuerpo.
 */
@RequestScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class NotificacionesResource {

    @Inject
    PruebasDeMensajeria pruebas;

    @Inject
    IAuditoria auditoria;

    /** Publica un {@code PAGO_CONFIRMADO} de ejemplo en la cola y en el tópico, y lo devuelve. */
    @POST
    @Path("pruebas/pago-confirmado")
    @RolesAllowed("servicio")
    public Response publicarPagoConfirmado() {
        PagoConfirmadoMensaje publicado = pruebas.publicarEjemplo();
        // 202 y no 201: el pedido quedó aceptado, pero los consumidores lo procesan después.
        return Response.accepted(publicado).build();
    }

    /** Publica y falla a propósito: responde siempre 409 y el mensaje no tiene que llegarle a nadie. */
    @POST
    @Path("pruebas/pago-confirmado-con-falla")
    @RolesAllowed("servicio")
    public void publicarPagoConfirmadoConFalla() {
        pruebas.publicarYDespuesFallar();
    }

    /** Manda al tópico un texto que no es un mensaje válido, para ver los reintentos y la DLQ. */
    @POST
    @Path("pruebas/mensaje-invalido")
    @RolesAllowed("servicio")
    public Response publicarMensajeInvalido() {
        pruebas.publicarMensajeInvalido();
        return Response.accepted().build();
    }

    /**
     * Los últimos 50 mensajes que registró auditoría, del más nuevo al más viejo.
     * Entre los que se registraron en el mismo segundo el orden no está garantizado.
     */
    @GET
    @Path("auditoria")
    @RolesAllowed("servicio")
    public List<RegistroDeAuditoriaDto> auditoria() {
        return auditoria.listar();
    }
}
