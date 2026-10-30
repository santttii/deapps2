package com.palco.validacion.negocio.externos;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.Textos;
import com.palco.common.seguridad.TokenDeServicio;

/**
 * Lo que Validación le pregunta a ServicioDeEventos: quién es el dueño de un
 * evento ({@code GET /api/eventos/{eventoId}/organizador}, rol
 * {@code servicio}). Con eso un organizador ve el registro y arma la puerta
 * solo de sus eventos (limitación L17).
 *
 * El escaneo no la usa: si el staff está asignado se sabe en la base de
 * Validación, así la puerta sigue funcionando aunque Eventos no responda.
 *
 * {@code @ApplicationScoped}, como {@link ClienteDeVentas}: un solo cliente
 * HTTP para todo el servicio y ningún dato de los pedidos.
 */
@ApplicationScoped
public class ClienteDeEventos {

    private static final Logger LOG = Logger.getLogger(ClienteDeEventos.class.getName());

    static final String RUTA = "/api/eventos/%s/organizador";
    static final String TIPO_NO_ENCONTRADO = "/problemas/no-encontrado";
    private static final String EVENTOS_NO_RESPONDE = "El servicio de eventos no responde. Probá de nuevo.";

    private static final Jsonb JSONB = JsonbBuilder.create();

    /** Lo que devuelve Eventos. {@code organizadorId} es null si el evento no tiene dueño. */
    public record Organizador(String eventoId, String organizadorId) {
    }

    /** De un error de Eventos solo se lee el {@code type}. */
    public record Problema(String type) {
    }

    record Respuesta(int estado, String cuerpo) {
    }

    @Inject
    TokenDeServicio tokenDeServicio;

    private Client cliente;

    /**
     * El id del organizador dueño del evento, o null si no tiene. Lanza
     * {@code NoEncontrado} si Eventos dice que el evento no existe, y
     * {@code SinRespuesta} ante cualquier otra respuesta: no se sabe de quién es.
     */
    public String organizadorDe(String eventoId) {
        Respuesta respuesta = enviar(eventoId, tokenDeServicio.obtener());
        if (respuesta.estado() == 401) {
            // Usuarios se redesplegó y cambió la clave: se pide otro token y se repite. Es una lectura.
            tokenDeServicio.invalidar();
            respuesta = enviar(eventoId, tokenDeServicio.obtener());
        }
        if (respuesta.estado() == 200) {
            Organizador leido = leer(respuesta.cuerpo(), Organizador.class);
            if (leido != null && eventoId.equals(leido.eventoId())) {
                return leido.organizadorId();
            }
        } else if (respuesta.estado() == 404) {
            Problema problema = leer(respuesta.cuerpo(), Problema.class);
            // Un 404 sin el error de Eventos es el servidor diciendo que el WAR no está: no se sabe nada.
            if (problema != null && TIPO_NO_ENCONTRADO.equals(problema.type())) {
                throw new ErroresDeNegocio.NoEncontrado("No existe ese evento.");
            }
        }
        LOG.warning("Eventos respondió " + respuesta.estado() + " al preguntar el dueño del evento "
                + Textos.enUnaLinea(eventoId) + ".");
        throw new ErroresDeNegocio.SinRespuesta(EVENTOS_NO_RESPONDE);
    }

    @PostConstruct
    void abrirCliente() {
        cliente = ClientBuilder.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    @PreDestroy
    void cerrarCliente() {
        cliente.close();
    }

    /** La llamada HTTP. No es privada para que un test la reemplace y no necesite un servidor. */
    Respuesta enviar(String eventoId, String token) {
        try (Response respuesta = cliente.target(TokenDeServicio.URL_INTERNA)
                .path(String.format(RUTA, eventoId))
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .get()) {
            return new Respuesta(respuesta.getStatus(), respuesta.hasEntity() ? respuesta.readEntity(String.class) : null);
        } catch (ProcessingException e) {
            LOG.warning("Eventos no respondió al preguntar el dueño de un evento. Motivo: "
                    + Textos.enUnaLinea(e.getMessage()));
            throw new ErroresDeNegocio.SinRespuesta(EVENTOS_NO_RESPONDE);
        }
    }

    private static <T> T leer(String json, Class<T> tipo) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSONB.fromJson(json, tipo);
        } catch (JsonbException e) {
            return null;
        }
    }
}
