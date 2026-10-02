package com.palco.app;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.Queue;
import jakarta.jms.QueueBrowser;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.palco.app.mensajeria.DestinosJms;

/**
 * Indica si el backend está vivo y si llega al broker JMS.
 * Responde 200 si todo anda y 503 si alguna dependencia falla.
 */
@RequestScoped
@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {

    @Inject
    JMSContext jms;

    @Resource(lookup = DestinosJms.COLA_EMISION_ENTRADAS)
    Queue colaEmisionEntradas;

    @GET
    public Response health() {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("servicio", "palco-backend");

        Map<String, Object> jmsEstado = estadoJms();
        cuerpo.put("jms", jmsEstado);

        boolean ok = "ok".equals(jmsEstado.get("estado"));
        cuerpo.put("estado", ok ? "ok" : "degradado");
        return Response.status(ok ? Response.Status.OK : Response.Status.SERVICE_UNAVAILABLE)
                .entity(cuerpo)
                .build();
    }

    private Map<String, Object> estadoJms() {
        Map<String, Object> estado = new LinkedHashMap<>();
        try (QueueBrowser browser = jms.createBrowser(colaEmisionEntradas)) {
            estado.put("estado", "ok");
            estado.put("emisionEntradasPendientes", Collections.list(browser.getEnumeration()).size());
        } catch (JMSException | RuntimeException e) {
            estado.put("estado", "error");
            estado.put("detalle", e.getMessage());
        }
        return estado;
    }
}
