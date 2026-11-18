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
import com.palco.common.seguridad.TokenDeServicio;

/**
 * Lo que Validación le pregunta a ServicioDeUsuarios: el rol de una cuenta por
 * su email ({@code GET /api/usuarios/cuentas?email=}, rol {@code servicio}).
 * Con eso el organizador solo puede asignar a la puerta cuentas de personal de
 * puerta (CA-14.3): asignar una de comprador no le daría acceso a nada y
 * dejaría una puerta que nadie puede atender.
 *
 * Solo lo usa la asignación. El escaneo no depende de Usuarios.
 *
 * {@code @ApplicationScoped}, como {@link ClienteDeEventos}: un solo cliente
 * HTTP para todo el servicio y ningún dato de los pedidos.
 */
@ApplicationScoped
public class ClienteDeUsuarios {

    private static final Logger LOG = Logger.getLogger(ClienteDeUsuarios.class.getName());

    static final String RUTA = "/api/usuarios/cuentas";
    static final String TIPO_NO_ENCONTRADO = "/problemas/no-encontrado";
    private static final String USUARIOS_NO_RESPONDE = "El servicio de usuarios no responde. Probá de nuevo.";

    private static final Jsonb JSONB = JsonbBuilder.create();

    /** Lo que devuelve Usuarios: el email y el rol, nada más de la cuenta. */
    public record Cuenta(String email, String rol) {
    }

    /** De un error de Usuarios solo se lee el {@code type}. */
    public record Problema(String type) {
    }

    record Respuesta(int estado, String cuerpo) {
    }

    @Inject
    TokenDeServicio tokenDeServicio;

    private Client cliente;

    /**
     * El rol de la cuenta verificada con ese email, o null si no hay ninguna.
     * Lanza {@code SinRespuesta} ante cualquier otra respuesta: no se sabe.
     */
    public String rolDe(String email) {
        Respuesta respuesta = enviar(email, tokenDeServicio.obtener());
        if (respuesta.estado() == 401) {
            // Usuarios se redesplegó y cambió la clave: se pide otro token y se repite. Es una lectura.
            tokenDeServicio.invalidar();
            respuesta = enviar(email, tokenDeServicio.obtener());
        }
        if (respuesta.estado() == 200) {
            Cuenta cuenta = leer(respuesta.cuerpo(), Cuenta.class);
            if (cuenta != null && cuenta.rol() != null) {
                return cuenta.rol();
            }
        } else if (respuesta.estado() == 404) {
            Problema problema = leer(respuesta.cuerpo(), Problema.class);
            // Un 404 sin el error de Usuarios es el servidor diciendo que el WAR no está: no se sabe nada.
            if (problema != null && TIPO_NO_ENCONTRADO.equals(problema.type())) {
                return null;
            }
        }
        LOG.warning("Usuarios respondió " + respuesta.estado() + " al preguntar el rol de una cuenta.");
        throw new ErroresDeNegocio.SinRespuesta(USUARIOS_NO_RESPONDE);
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
    Respuesta enviar(String email, String token) {
        try (Response respuesta = cliente.target(TokenDeServicio.URL_INTERNA)
                .path(RUTA)
                .queryParam("email", email)
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .get()) {
            return new Respuesta(respuesta.getStatus(), respuesta.hasEntity() ? respuesta.readEntity(String.class) : null);
        } catch (ProcessingException e) {
            // El email no va al log: es un dato personal.
            LOG.warning("Usuarios no respondió al preguntar el rol de una cuenta.");
            throw new ErroresDeNegocio.SinRespuesta(USUARIOS_NO_RESPONDE);
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
