package com.palco.common.rest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Escribe como {@link Problema} el 401 que el servidor emite antes de llegar a JAX-RS.
 *
 * Cuando el pedido trae un token que no se puede verificar (firma alterada, vencido o mal
 * formado), lo rechaza el mecanismo MP-JWT de WildFly en la capa de seguridad de Undertow:
 * la aplicación nunca lo ve, así que ni {@link ManejadoresDeErrores} ni {@link CorsFilter}
 * intervienen. Ese rechazo termina en un {@code sendError(401)} del contenedor, y el
 * {@code web-fragment.xml} de este jar registra este servlet como página de error del 401
 * en todos los servicios. Así esa respuesta sale igual que los demás errores: con
 * {@code application/problem+json}, con {@code WWW-Authenticate} y con las cabeceras CORS
 * (sin ellas el navegador la informa como error de red y el frontend no ve el 401).
 *
 * Los 401 que arma la aplicación no pasan por acá: los escribe JAX-RS como respuesta común,
 * no con {@code sendError}.
 *
 * Es stateless: no guarda nada entre pedidos.
 */
public class ErrorDeAutenticacionServlet extends HttpServlet {

    /** Tipo del problema, sin prefijo: el token vino pero no se pudo verificar. */
    static final String TIPO = "token-invalido";
    static final String TITULO = "No autenticado";
    static final String DETALLE = "La sesión no es válida o venció. Iniciá sesión de nuevo.";

    /** RFC 6750: el esquema es Bearer y {@code invalid_token} dice que el token mandado no sirve. */
    static final String DESAFIO = "Bearer error=\"invalid_token\"";

    private static final Jsonb JSONB = JsonbBuilder.create();

    @Override
    protected void service(HttpServletRequest pedido, HttpServletResponse respuesta) throws IOException {
        CorsFilter.cabecerasPara(pedido.getHeader("Origin")).forEach(respuesta::setHeader);
        respuesta.setContentType(ManejadoresDeErrores.PROBLEMA_JSON);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Solo atiende como página de error; pedida directo, la ruta no existe.
        if (pedido.getDispatcherType() != DispatcherType.ERROR) {
            respuesta.setStatus(HttpServletResponse.SC_NOT_FOUND);
            respuesta.getWriter().write(JSONB.toJson(new Problema(Problema.TIPO_SIN_DETALLE, "Not Found",
                    HttpServletResponse.SC_NOT_FOUND, null, pedido.getRequestURI(), null)));
            return;
        }
        respuesta.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        respuesta.setHeader("WWW-Authenticate", DESAFIO);
        respuesta.getWriter().write(cuerpo((String) pedido.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)));
    }

    /** El cuerpo JSON del problema para la ruta que se pidió. */
    static String cuerpo(String instancia) {
        return JSONB.toJson(Problema.de(HttpServletResponse.SC_UNAUTHORIZED, TIPO, TITULO, DETALLE, instancia));
    }
}
