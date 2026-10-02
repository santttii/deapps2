package com.palco.app.errores;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

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
                campos.put(ruta.substring(ruta.lastIndexOf('.') + 1), v.getMessage());
            }
            return json(400, new ErrorRespuesta(400, "Hay datos inválidos.", campos));
        }
    }

    /** Errores HTTP lanzados a propósito (404, 403, 409…) → se respeta el código. */
    @Provider
    public static class Http implements ExceptionMapper<WebApplicationException> {
        @Override
        public Response toResponse(WebApplicationException e) {
            int estado = e.getResponse().getStatus();
            String mensaje = estado == 404
                    ? "Recurso no encontrado."
                    : e.getMessage() != null ? e.getMessage() : e.getResponse().getStatusInfo().getReasonPhrase();
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
