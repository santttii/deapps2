package com.palco.common.rest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import com.palco.common.ErroresDeNegocio;

/**
 * Convierte cualquier excepción en un {@link Problema} (Problem Details, RFC 9457).
 *
 * Hay un manejador por clase de excepción: JAX-RS elige solo el más específico,
 * y acá queda escrito en un único lugar qué código HTTP y qué título le toca a
 * cada una. El {@code type} lo trae la excepción y el {@code detail} es su mensaje.
 */
public final class ManejadoresDeErrores {

    /** Tipo de contenido de todas las respuestas de error. */
    public static final String PROBLEMA_JSON = "application/problem+json";

    // Títulos que comparten una excepción de negocio y el error HTTP equivalente.
    private static final String TITULO_DATOS_INVALIDOS = "Datos inválidos";
    private static final String TITULO_NO_AUTENTICADO = "No autenticado";
    private static final String TITULO_SIN_PERMISO = "Sin permiso";
    private static final String TITULO_NO_ENCONTRADO = "No encontrado";

    private static final String FALTA_INICIAR_SESION = "Tenés que iniciar sesión.";

    private ManejadoresDeErrores() {
    }

    /** Lo común a todos los manejadores: conocen la ruta del pedido y arman la respuesta. */
    abstract static class Manejador {

        /** El pedido en curso; de acá sale {@code instance}. */
        @Context
        UriInfo pedido;

        Response responder(int estado, String tipo, String titulo, String detalle) {
            return responder(estado, tipo, titulo, detalle, Map.of());
        }

        Response responder(int estado, String tipo, String titulo, String detalle, Map<String, String> errores) {
            return respuesta(Problema.de(estado, tipo, titulo, detalle, ruta(), errores)).build();
        }

        Response.ResponseBuilder respuesta(Problema problema) {
            return Response.status(problema.status()).type(PROBLEMA_JSON).entity(problema);
        }

        /**
         * El 401 se arma en un solo lugar porque lleva una cabecera más: el estándar HTTP
         * pide que diga con qué esquema hay que autenticarse, y acá siempre es el token.
         */
        Response noAutenticado(String tipo, String detalle) {
            return respuesta(Problema.de(401, tipo, TITULO_NO_AUTENTICADO, detalle, ruta()))
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .build();
        }

        String ruta() {
            return pedido == null ? null : pedido.getRequestUri().getPath();
        }
    }

    /** Errores de validación de Bean Validation → 400 con el detalle por campo. */
    @Provider
    public static class Validacion extends Manejador implements ExceptionMapper<ConstraintViolationException> {
        @Override
        public Response toResponse(ConstraintViolationException e) {
            Map<String, String> campos = new LinkedHashMap<>();
            for (ConstraintViolation<?> v : e.getConstraintViolations()) {
                String ruta = v.getPropertyPath().toString();
                String campo = ruta.substring(ruta.lastIndexOf('.') + 1);
                // Un parámetro entero nulo (sin cuerpo) llega como "arg0": no es un campo.
                if (campo.matches("arg\\d+")) {
                    return responder(400, "datos-invalidos", TITULO_DATOS_INVALIDOS, v.getMessage());
                }
                campos.put(campo, v.getMessage());
            }
            return responder(400, "datos-invalidos", TITULO_DATOS_INVALIDOS, "Hay datos inválidos.", campos);
        }
    }

    /** Dato inválido detectado por el negocio → 400 con el campo. */
    @Provider
    public static class DatosInvalidos extends Manejador implements ExceptionMapper<ErroresDeNegocio.DatosInvalidos> {
        @Override
        public Response toResponse(ErroresDeNegocio.DatosInvalidos e) {
            return responder(400, e.tipo(), TITULO_DATOS_INVALIDOS, e.getMessage(), e.campos());
        }
    }

    /** Sin sesión o credenciales incorrectas → 401. */
    @Provider
    public static class NoAutenticado extends Manejador implements ExceptionMapper<ErroresDeNegocio.NoAutenticado> {
        @Override
        public Response toResponse(ErroresDeNegocio.NoAutenticado e) {
            return noAutenticado(e.tipo(), e.getMessage());
        }
    }

    /** La pasarela rechazó el cobro → 402. */
    @Provider
    public static class PagoRechazado extends Manejador implements ExceptionMapper<ErroresDeNegocio.PagoRechazado> {
        @Override
        public Response toResponse(ErroresDeNegocio.PagoRechazado e) {
            return responder(402, e.tipo(), "Pago rechazado", e.getMessage());
        }
    }

    /** Usuario identificado que no puede hacer esto → 403. */
    @Provider
    public static class SinPermiso extends Manejador implements ExceptionMapper<ErroresDeNegocio.SinPermiso> {
        @Override
        public Response toResponse(ErroresDeNegocio.SinPermiso e) {
            return responder(403, e.tipo(), TITULO_SIN_PERMISO, e.getMessage());
        }
    }

    /** Recurso inexistente → 404. */
    @Provider
    public static class NoEncontrado extends Manejador implements ExceptionMapper<ErroresDeNegocio.NoEncontrado> {
        @Override
        public Response toResponse(ErroresDeNegocio.NoEncontrado e) {
            return responder(404, e.tipo(), TITULO_NO_ENCONTRADO, e.getMessage());
        }
    }

    /** Choque con el estado actual → 409. */
    @Provider
    public static class Conflicto extends Manejador implements ExceptionMapper<ErroresDeNegocio.Conflicto> {
        @Override
        public Response toResponse(ErroresDeNegocio.Conflicto e) {
            return responder(409, e.tipo(), "Conflicto", e.getMessage());
        }
    }

    /** Pedido bien formado que rompe una regla de negocio → 422. */
    @Provider
    public static class ReglaIncumplida extends Manejador implements ExceptionMapper<ErroresDeNegocio.ReglaIncumplida> {
        @Override
        public Response toResponse(ErroresDeNegocio.ReglaIncumplida e) {
            return responder(422, e.tipo(), "Regla de negocio incumplida", e.getMessage());
        }
    }

    /**
     * Demasiados intentos seguidos → 429. {@code Retry-After} dice en cuántos segundos se
     * puede volver a intentar (RFC 9110, 10.2.3); el cliente no tiene que adivinarlo.
     */
    @Provider
    public static class DemasiadosIntentos extends Manejador
            implements ExceptionMapper<ErroresDeNegocio.DemasiadosIntentos> {
        @Override
        public Response toResponse(ErroresDeNegocio.DemasiadosIntentos e) {
            return respuesta(Problema.de(429, e.tipo(), "Demasiados intentos", e.getMessage(), ruta()))
                    .header(HttpHeaders.RETRY_AFTER, e.segundosDeEspera())
                    .build();
        }
    }

    /** Otro servicio o la pasarela no respondió → 504. */
    @Provider
    public static class SinRespuesta extends Manejador implements ExceptionMapper<ErroresDeNegocio.SinRespuesta> {
        @Override
        public Response toResponse(ErroresDeNegocio.SinRespuesta e) {
            return responder(504, e.tipo(), "Sin respuesta", e.getMessage());
        }
    }

    /** Errores HTTP que lanza JAX-RS o el código a propósito (ruta inexistente, JSON mal formado, 405…) → se respeta el código. */
    @Provider
    public static class Http extends Manejador implements ExceptionMapper<WebApplicationException> {

        @Context
        SecurityContext seguridad;

        @Override
        public Response toResponse(WebApplicationException e) {
            int estado = e.getResponse().getStatus();
            // @RolesAllowed responde 403 también cuando no hay token: eso es "falta iniciar sesión" (401).
            if (estado == 403 && (seguridad == null || seguridad.getUserPrincipal() == null)) {
                estado = 401;
            }
            return switch (estado) {
                case 400 -> responder(400, "datos-invalidos", TITULO_DATOS_INVALIDOS, mensaje(e));
                case 401 -> noAutenticado("no-autenticado", FALTA_INICIAR_SESION);
                case 403 -> responder(403, "sin-permiso", TITULO_SIN_PERMISO, "No tenés permiso para hacer esto.");
                case 404 -> responder(404, "no-encontrado", TITULO_NO_ENCONTRADO, "Recurso no encontrado.");
                default -> soloElCodigo(estado, e.getResponse());
            };
        }

        /**
         * Para el resto (405, 406, 415…) el problema es el código HTTP mismo: el estándar
         * indica type "about:blank" y, como título, la frase del código. Va sin detail,
         * porque el mensaje de esa excepción es un texto interno del servidor y no uno
         * escrito para el usuario.
         */
        private Response soloElCodigo(int estado, Response original) {
            Response.ResponseBuilder respuesta = respuesta(new Problema(Problema.TIPO_SIN_DETALLE,
                    original.getStatusInfo().getReasonPhrase(), estado, null, ruta(), null));
            // Un 405 tiene que decir qué métodos sí se aceptan: el servidor ya lo calculó
            // en la cabecera Allow de su respuesta, y acá se copia a la nuestra.
            String permitidos = original.getHeaderString(HttpHeaders.ALLOW);
            if (permitidos != null) {
                respuesta.header(HttpHeaders.ALLOW, permitidos);
            }
            return respuesta.build();
        }

        /** El texto de un 400 lo escribe nuestro código (por ejemplo JsonProvider, ante un JSON mal formado). */
        private static String mensaje(WebApplicationException e) {
            return e.getMessage() != null ? e.getMessage() : e.getResponse().getStatusInfo().getReasonPhrase();
        }
    }

    /**
     * Cualquier otra cosa → 500 sin filtrar detalles internos al cliente.
     *
     * Cada error lleva un id nuevo (un UUID al azar, que no dice nada del error) que
     * se escribe en el log junto con la traza y viaja en la respuesta como
     * {@code idError}. Quien reporta el problema pasa ese id y se encuentra su traza
     * en el log sin mandarle al cliente ningún detalle interno.
     */
    @Provider
    public static class Inesperado extends Manejador implements ExceptionMapper<Throwable> {
        private static final Logger LOG = Logger.getLogger(Inesperado.class.getName());

        @Override
        public Response toResponse(Throwable e) {
            return respuesta(registrar(e, ruta())).build();
        }

        /** Escribe el error en el log con un id nuevo y devuelve el problema que lo lleva. */
        static Problema registrar(Throwable e, String ruta) {
            String id = UUID.randomUUID().toString();
            LOG.log(Level.SEVERE, "Error no manejado (idError " + id + ")", e);
            return Problema.de(500, "error-inesperado", "Error inesperado", "Ocurrió un error inesperado.", ruta)
                    .conIdError(id);
        }
    }
}
