package com.palco.common.rest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import com.palco.common.ErroresDeNegocio;

/** Convierte cualquier excepción en una {@link ErrorRespuesta} JSON. */
public final class ManejadoresDeErrores {

    private ManejadoresDeErrores() {
    }

    private static Response json(int estado, ErrorRespuesta cuerpo) {
        return Response.status(estado).type(MediaType.APPLICATION_JSON).entity(cuerpo).build();
    }

    /** Errores de validación de Bean Validation → 400 con el detalle por campo. */
    @Provider
    public static class Validacion implements ExceptionMapper<ConstraintViolationException> {
        @Override
        public Response toResponse(ConstraintViolationException e) {
            Map<String, String> campos = new LinkedHashMap<>();
            for (ConstraintViolation<?> v : e.getConstraintViolations()) {
                String ruta = v.getPropertyPath().toString();
                String campo = ruta.substring(ruta.lastIndexOf('.') + 1);
                // Un parámetro entero nulo (sin cuerpo) llega como "arg0": no es un campo.
                if (campo.matches("arg\\d+")) {
                    return json(400, new ErrorRespuesta(400, v.getMessage()));
                }
                campos.put(campo, v.getMessage());
            }
            return json(400, new ErrorRespuesta(400, "Hay datos inválidos.", campos));
        }
    }

    /** Sin sesión o credenciales incorrectas → 401. */
    @Provider
    public static class NoAutenticado implements ExceptionMapper<ErroresDeNegocio.NoAutenticado> {
        @Override
        public Response toResponse(ErroresDeNegocio.NoAutenticado e) {
            return json(401, new ErrorRespuesta(401, e.getMessage()));
        }
    }

    /** Recurso inexistente → 404. */
    @Provider
    public static class NoEncontrado implements ExceptionMapper<ErroresDeNegocio.NoEncontrado> {
        @Override
        public Response toResponse(ErroresDeNegocio.NoEncontrado e) {
            return json(404, new ErrorRespuesta(404, e.getMessage()));
        }
    }

    /** Choque con el estado actual → 409. */
    @Provider
    public static class Conflicto implements ExceptionMapper<ErroresDeNegocio.Conflicto> {
        @Override
        public Response toResponse(ErroresDeNegocio.Conflicto e) {
            return json(409, new ErrorRespuesta(409, e.getMessage()));
        }
    }

    /** Dato inválido detectado por el negocio → 400 con el campo. */
    @Provider
    public static class DatosInvalidos implements ExceptionMapper<ErroresDeNegocio.DatosInvalidos> {
        @Override
        public Response toResponse(ErroresDeNegocio.DatosInvalidos e) {
            return json(400, new ErrorRespuesta(400, e.getMessage(), e.campos()));
        }
    }

    /** Errores HTTP lanzados a propósito (404, 403, 409…) → se respeta el código. */
    @Provider
    public static class Http implements ExceptionMapper<WebApplicationException> {

        @Context
        SecurityContext seguridad;

        @Override
        public Response toResponse(WebApplicationException e) {
            int estado = e.getResponse().getStatus();
            // @RolesAllowed responde 403 también cuando no hay token: eso es "falta iniciar sesión" (401).
            if (estado == 403 && (seguridad == null || seguridad.getUserPrincipal() == null)) {
                return Response.status(401)
                        .type(MediaType.APPLICATION_JSON)
                        .header("WWW-Authenticate", "Bearer")
                        .entity(new ErrorRespuesta(401, "Tenés que iniciar sesión."))
                        .build();
            }
            String mensaje = switch (estado) {
                case 401 -> "Tenés que iniciar sesión.";
                case 403 -> "No tenés permiso para hacer esto.";
                case 404 -> "Recurso no encontrado.";
                default -> e.getMessage() != null ? e.getMessage() : e.getResponse().getStatusInfo().getReasonPhrase();
            };
            return json(estado, new ErrorRespuesta(estado, mensaje));
        }
    }

    /** Cualquier otra cosa → 500 sin filtrar detalles internos al cliente. */
    @Provider
    public static class Inesperado implements ExceptionMapper<Throwable> {
        private static final Logger LOG = Logger.getLogger(Inesperado.class.getName());

        @Override
        public Response toResponse(Throwable e) {
            LOG.log(Level.SEVERE, "Error no manejado", e);
            return json(500, new ErrorRespuesta(500, "Ocurrió un error inesperado."));
        }
    }
}
