package com.palco.common.rest;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Permite que el frontend (otro origen) llame a la API.
 * Los orígenes permitidos salen de la variable de entorno
 * {@code PALCO_CORS_ORIGENES}, separados por coma.
 *
 * Las cabeceras se arman en {@link #cabecerasPara}, que también usa
 * {@link ErrorDeAutenticacionServlet} para los 401 que emite el servidor antes de
 * llegar a JAX-RS: así hay una sola lista de orígenes y de cabeceras.
 */
@Provider
@PreMatching
public class CorsFilter implements ContainerResponseFilter {

    private static final Set<String> ORIGENES = origenes(System.getenv("PALCO_CORS_ORIGENES"));

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        cabecerasPara(request.getHeaderString("Origin"))
                .forEach((nombre, valor) -> response.getHeaders().putSingle(nombre, valor));
    }

    /** Las cabeceras CORS para un pedido con ese {@code Origin}; vacío si el origen no está permitido o no vino. */
    public static Map<String, String> cabecerasPara(String origen) {
        return cabecerasPara(origen, ORIGENES);
    }

    static Map<String, String> cabecerasPara(String origen, Set<String> permitidos) {
        if (origen == null || !permitidos.contains(origen)) {
            return Map.of();
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Access-Control-Allow-Origin", origen);
        h.put("Access-Control-Allow-Credentials", "true");
        h.put("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        // Cabeceras que el navegador puede mandar: el token, la clave de idempotencia de
        // las compras y la etiqueta de versión para no volver a descargar una respuesta.
        h.put("Access-Control-Allow-Headers", "Content-Type, Authorization, Idempotency-Key, If-None-Match");
        // Cabeceras de la respuesta que el navegador deja leer al frontend: sin esto no ve
        // dónde quedó el recurso creado (Location) ni su versión (ETag).
        h.put("Access-Control-Expose-Headers", "Location, ETag");
        h.put("Vary", "Origin");
        return h;
    }

    /** Lee la lista separada por comas; sin la variable, solo el frontend de desarrollo. */
    static Set<String> origenes(String variable) {
        return Arrays.stream((variable == null ? "http://localhost:5173" : variable).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
