package com.palco.notificaciones.negocio.externos;

import java.util.Optional;
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

import com.palco.common.Textos;
import com.palco.common.seguridad.TokenDeServicio;

/**
 * Lo que Notificaciones le pregunta a ServicioDeUsuarios: a qué email avisarle
 * al comprador. Es {@code GET /api/usuarios/{usuarioId}} con el token de
 * servicio, porque acá no hay ningún pedido HTTP de una persona: el aviso lo
 * dispara un mensaje del tópico.
 *
 * El email no viaja en el mensaje {@code PAGO_CONFIRMADO} a propósito: el
 * mensaje lleva ids y lo escuchan tres consumidores, así que cuantos menos
 * datos personales tenga, mejor. Quien necesita el email lo pide.
 *
 * A diferencia de los clientes de Ventas o Validación, este nunca lanza: si
 * Usuarios no contesta devuelve {@link Optional#empty()} y el aviso se anota
 * en el log. Un aviso no puede hacer que el mensaje se reintente y la compra
 * se avise dos veces.
 *
 * {@code @ApplicationScoped}: un solo cliente HTTP para todo el servicio y
 * ningún dato de cada aviso.
 */
@ApplicationScoped
public class ClienteDeUsuarios {

    private static final Logger LOG = Logger.getLogger(ClienteDeUsuarios.class.getName());

    static final String RUTA = "/api/usuarios/%s";

    private static final Jsonb JSONB = JsonbBuilder.create();

    /** Del usuario solo interesan el email y el nombre, para encabezar el aviso. */
    public record Usuario(String id, String nombre, String email) {
    }

    /** Lo que se mira de una respuesta HTTP: el código de estado y el cuerpo como texto. */
    record Respuesta(int estado, String cuerpo) {
    }

    @Inject
    TokenDeServicio tokenDeServicio;

    private Client cliente;

    /**
     * El usuario con ese id, o vacío si Usuarios no lo conoce o no contesta.
     * Ante un 401 pide un token nuevo y reintenta una sola vez: pasa cuando se
     * redespliega Usuarios y cambia la clave con la que firma. Es una lectura,
     * así que repetirla es seguro.
     */
    public Optional<Usuario> buscar(String usuarioId) {
        Respuesta respuesta = enviar(usuarioId, tokenDeServicio.obtener());
        if (respuesta.estado() == 401) {
            tokenDeServicio.invalidar();
            respuesta = enviar(usuarioId, tokenDeServicio.obtener());
        }
        if (respuesta.estado() == 200) {
            Usuario usuario = leer(respuesta.cuerpo());
            if (usuario != null && usuario.email() != null && !usuario.email().isBlank()) {
                return Optional.of(usuario);
            }
        }
        LOG.warning("Usuarios respondió " + respuesta.estado() + " al pedir el email de "
                + Textos.enUnaLinea(usuarioId) + ": el aviso queda sin mandar.");
        return Optional.empty();
    }

    /** 2 segundos para conectar y 5 para leer, como los demás clientes. */
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
    Respuesta enviar(String usuarioId, String token) {
        try (Response respuesta = cliente.target(TokenDeServicio.URL_INTERNA + String.format(RUTA, usuarioId))
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .get()) {
            int estado = respuesta.getStatus();
            return new Respuesta(estado, estado == 200 ? respuesta.readEntity(String.class) : null);
        } catch (ProcessingException e) {
            // Sin conexión o se cumplió el tiempo de espera. No se lanza: el aviso no reintenta el mensaje.
            LOG.warning("Usuarios no respondió al pedir el email del comprador: " + e.getMessage());
            return new Respuesta(0, null);
        }
    }

    private static Usuario leer(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSONB.fromJson(json, Usuario.class);
        } catch (JsonbException e) {
            return null;
        }
    }
}
