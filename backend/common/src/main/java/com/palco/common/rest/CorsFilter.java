package com.palco.common.rest;

import java.util.Arrays;
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
 */
@Provider
@PreMatching
public class CorsFilter implements ContainerResponseFilter {

    private static final Set<String> ORIGENES = Arrays
            .stream(System.getenv().getOrDefault("PALCO_CORS_ORIGENES", "http://localhost:5173").split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        String origen = request.getHeaderString("Origin");
        if (origen == null || !ORIGENES.contains(origen)) {
            return;
        }
        var h = response.getHeaders();
        h.putSingle("Access-Control-Allow-Origin", origen);
        h.putSingle("Access-Control-Allow-Credentials", "true");
        h.putSingle("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        h.putSingle("Access-Control-Allow-Headers", "Content-Type, Authorization");
        h.putSingle("Vary", "Origin");
    }
}
