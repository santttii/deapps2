package com.palco.asistente.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

import com.palco.asistente.negocio.IAsistente;
import com.palco.asistente.negocio.Respuesta;
import com.palco.common.seguridad.Sesion;

/**
 * POST /api/v1/asistente/preguntas: una pregunta, una respuesta. Para cualquier usuario con
 * sesión. Siempre responde 200 con el origen de la respuesta (MODELO, FALLBACK o BLOQUEADA):
 * que el modelo no conteste no es un error para quien pregunta.
 *
 * Quien pregunta se identifica con su id interno (el claim "sub" del token), no con su email:
 * el asistente lo escribe en el log de cada consulta, y el email es un dato personal.
 */
@RequestScoped
@Path("/preguntas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AsistenteResource {

    @Inject
    IAsistente asistente;

    @Context
    SecurityContext seguridad;

    public record PreguntaDto(@NotNull(message = "Escribí una pregunta.") String pregunta) { }

    public record RespuestaDto(String respuesta, String origen, String motivo, boolean derivarASoporte,
            long milisegundos) {
        static RespuestaDto de(Respuesta r) {
            return new RespuestaDto(r.texto(), r.origen().name(), r.motivo(), r.derivarASoporte(), r.milisegundos());
        }
    }

    @POST
    @RolesAllowed({ "user", "organizer", "staff" })
    public RespuestaDto preguntar(@Valid @NotNull(message = "Falta el cuerpo con la pregunta.") PreguntaDto cuerpo) {
        return RespuestaDto.de(asistente.responder(Sesion.usuarioId(seguridad), cuerpo.pregunta()));
    }
}
