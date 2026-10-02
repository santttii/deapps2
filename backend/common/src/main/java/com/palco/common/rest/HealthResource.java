package com.palco.common.rest;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.RequestScoped;
import jakarta.servlet.ServletContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.palco.common.datos.BaseDeDatos;

/**
 * {@code GET /api/<servicio>/health}: lo trae cada servicio por depender de
 * {@code common}. Responde 200 si el servicio llega a la base y 503 si no.
 */
@RequestScoped
@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {

    @Resource(lookup = BaseDeDatos.JNDI)
    DataSource baseDeDatos;

    @Context
    ServletContext servlet;

    @GET
    public Response health() {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        String contexto = servlet.getContextPath();
        cuerpo.put("servicio", contexto.substring(contexto.lastIndexOf('/') + 1));

        boolean baseOk;
        try (Connection c = baseDeDatos.getConnection()) {
            baseOk = c.isValid(2);
        } catch (Exception e) {
            baseOk = false;
        }
        cuerpo.put("baseDeDatos", baseOk ? "ok" : "error");
        cuerpo.put("estado", baseOk ? "ok" : "degradado");

        return Response.status(baseOk ? Response.Status.OK : Response.Status.SERVICE_UNAVAILABLE)
                .entity(cuerpo)
                .build();
    }
}
