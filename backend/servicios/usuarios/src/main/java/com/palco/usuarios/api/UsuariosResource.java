package com.palco.usuarios.api;

import java.util.Map;

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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import com.palco.common.seguridad.Sesion;
import com.palco.usuarios.api.Dtos.LoginDto;
import com.palco.usuarios.api.Dtos.ReenvioDto;
import com.palco.usuarios.api.Dtos.RegistroDto;
import com.palco.usuarios.api.Dtos.RegistroPendienteDto;
import com.palco.usuarios.api.Dtos.SesionDto;
import com.palco.usuarios.api.Dtos.UsuarioDto;
import com.palco.usuarios.api.Dtos.VerificacionDto;
import com.palco.usuarios.negocio.IUsuarios;
import com.palco.usuarios.seguridad.ClavesJwt;
import com.palco.usuarios.seguridad.Tokens;

/**
 * API REST de ServicioDeUsuarios, en {@code /api/usuarios}.
 *
 * Flujo de alta: {@code POST /registro} → llega un código por email →
 * {@code POST /verificacion} devuelve la sesión. Después, {@code POST /login}.
 * La sesión es un token JWT que el frontend manda en cada pedido como
 * {@code Authorization: Bearer <token>}; cerrar sesión es descartarlo.
 */
@RequestScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UsuariosResource {

    private static final String FALTA_CUERPO = "Falta el cuerpo de la solicitud.";

    @Inject
    IUsuarios usuarios;

    @Inject
    ClavesJwt claves;

    @POST
    @Path("registro")
    @PermitAll
    public Response registrar(@NotNull(message = FALTA_CUERPO) @Valid RegistroDto registro) {
        RegistroPendienteDto pendiente = usuarios.registrar(registro);
        return Response.status(Response.Status.CREATED).entity(pendiente).build();
    }

    @POST
    @Path("verificacion")
    @PermitAll
    public SesionDto verificar(@NotNull(message = FALTA_CUERPO) @Valid VerificacionDto verificacion) {
        return usuarios.verificarEmail(verificacion);
    }

    @POST
    @Path("verificacion/reenvio")
    @PermitAll
    public Response reenviar(@NotNull(message = FALTA_CUERPO) @Valid ReenvioDto reenvio) {
        usuarios.reenviarCodigo(reenvio.email());
        return Response.noContent().build();
    }

    @POST
    @Path("login")
    @PermitAll
    public SesionDto login(@NotNull(message = FALTA_CUERPO) @Valid LoginDto login) {
        return usuarios.iniciarSesion(login);
    }

    /** Usuario dueño del token. */
    @GET
    @Path("yo")
    @RolesAllowed({ "user", "organizer", "staff" })
    public UsuarioDto yo(@Context SecurityContext seguridad) {
        return usuarios.obtener(Sesion.usuarioId(seguridad));
    }

    /** Clave pública con la que los demás servicios verifican los tokens. */
    @GET
    @Path("jwks")
    @PermitAll
    public Map<String, Object> jwks() {
        return Tokens.jwks(claves.publica(), claves.kid());
    }
}
