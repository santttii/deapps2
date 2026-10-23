package com.palco.validacion.negocio.externos;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
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
 * Lo que Validación le pide a ServicioDeEventos: los eventos de un organizador
 * ({@code GET /api/eventos/mios}), para que el organizador vea el registro de
 * la puerta solo de los suyos (limitación L17).
 *
 * A diferencia de {@link ClienteDeVentas}, no usa el token de servicio sino el
 * del organizador que hace el pedido: la pregunta es "¿cuáles son los eventos
 * de este usuario?", y Eventos la contesta con el {@code sub} del token. Así
 * Validación no tiene que mandar un id de usuario que Eventos debería creer, y
 * Eventos no necesita una consulta por dueño para otros servicios.
 *
 * {@code @ApplicationScoped}: una instancia sin estado y un cliente HTTP para
 * todo el servicio, igual que {@link ClienteDeVentas}.
 */
@ApplicationScoped
public class ClienteDeEventos {

    private static final Logger LOG = Logger.getLogger(ClienteDeEventos.class.getName());

    static final String RUTA_DE_LOS_MIOS = "/api/eventos/mios";
    private static final String EVENTOS_NO_RESPONDE = "El servicio de eventos no responde. Probá de nuevo.";

    private static final Jsonb JSONB = JsonbBuilder.create();

    /** Del evento solo interesa el id. */
    public record Evento(String id) {
    }

    /** Lo que se mira de una respuesta HTTP: el código de estado y el cuerpo como texto. */
    record Respuesta(int estado, String cuerpo) {
    }

    private Client cliente;

    /**
     * Los ids de los eventos del organizador dueño del token. Lanza 504
     * {@code sin-respuesta} si Eventos no contesta o contesta algo que no es
     * la lista: sin la lista no se sabe si el evento es suyo, y ante la duda
     * no se muestra el registro.
     */
    public Set<String> eventosDelOrganizador(String tokenDelOrganizador) {
        Respuesta respuesta = enviar(tokenDelOrganizador);
        if (respuesta.estado() != 200) {
            LOG.warning("Eventos respondió " + respuesta.estado() + " al pedir los eventos de un organizador.");
            throw new ErroresDeNegocio.SinRespuesta(EVENTOS_NO_RESPONDE);
        }
        return leer(respuesta.cuerpo());
    }

    /** 2 segundos para conectar y 5 para leer, como el cliente de Ventas. */
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

    /** Hace la llamada HTTP. No es privado para que el test lo reemplace y no necesite un servidor. */
    Respuesta enviar(String token) {
        try (Response respuesta = cliente.target(TokenDeServicio.URL_INTERNA)
                .path(RUTA_DE_LOS_MIOS)
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .get()) {
            int estado = respuesta.getStatus();
            return new Respuesta(estado, estado == 200 ? respuesta.readEntity(String.class) : null);
        } catch (ProcessingException e) {
            LOG.warning("Eventos no respondió al pedir los eventos de un organizador. Motivo: "
                    + Textos.enUnaLinea(e.getMessage()));
            throw new ErroresDeNegocio.SinRespuesta(EVENTOS_NO_RESPONDE);
        }
    }

    private static Set<String> leer(String json) {
        try {
            Evento[] eventos = json == null ? null : JSONB.fromJson(json, Evento[].class);
            if (eventos != null) {
                return Arrays.stream(eventos)
                        .filter(Objects::nonNull)
                        .map(Evento::id)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toUnmodifiableSet());
            }
        } catch (JsonbException e) {
            // Se trata abajo.
        }
        LOG.warning("Eventos respondió 200 a los eventos de un organizador, pero sin una lista de eventos.");
        throw new ErroresDeNegocio.SinRespuesta("Eventos respondió algo que no es una lista de eventos.");
    }
}
