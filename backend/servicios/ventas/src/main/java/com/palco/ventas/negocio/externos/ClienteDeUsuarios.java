package com.palco.ventas.negocio.externos;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

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
import com.palco.common.seguridad.TokenDeServicio;

/**
 * Lo que Ventas le pregunta a ServicioDeUsuarios: quién es el comprador y si
 * ya verificó su identidad. Es {@code GET /api/usuarios/yo}, la misma
 * operación que usa el frontend, y se llama con el token del propio comprador
 * (no con el de servicio): Usuarios responde sobre el dueño del token.
 *
 * Es un bean {@code @ApplicationScoped}: una sola instancia para todo el
 * servicio, que comparte un único cliente HTTP y no guarda nada de ningún
 * comprador. No es la conversación de un usuario (eso es el stateful) y no
 * necesita transacciones, así que no hace falta un Session Bean.
 */
@ApplicationScoped
public class ClienteDeUsuarios {

    private static final Logger LOG = Logger.getLogger(ClienteDeUsuarios.class.getName());

    static final String RUTA_DE_YO = "/api/usuarios/yo";

    private static final Jsonb JSONB = JsonbBuilder.create();

    /** El usuario como lo devuelve Usuarios. Para comprar importa {@code identidadVerificada}. */
    public record Usuario(String id, String nombre, String apellido, String dni, String email, String rol,
            boolean identidadVerificada) {
    }

    /** Lo que se mira de una respuesta HTTP: el código de estado y el cuerpo como texto. */
    record Respuesta(int estado, String cuerpo) {
    }

    private Client cliente;

    /**
     * Los datos del comprador dueño del token. Lanza 401 si Usuarios ya no
     * acepta ese token (pasa cuando se redespliega Usuarios, porque cambia la
     * clave con la que firma: hay que iniciar sesión de nuevo) y 504
     * {@code sin-respuesta} si Usuarios no contesta o contesta otra cosa.
     *
     * @param tokenDelComprador el token tal cual llegó en el pedido, sin la palabra "Bearer"
     */
    public Usuario yo(String tokenDelComprador) {
        Respuesta respuesta = enviar(tokenDelComprador);
        // Se decide por el código de estado, nunca por el texto del error.
        return switch (respuesta.estado()) {
            case 200 -> leerUsuario(respuesta.cuerpo());
            case 401 -> throw new ErroresDeNegocio.NoAutenticado("Tu sesión venció. Iniciá sesión de nuevo.");
            default -> {
                LOG.warning("Usuarios respondió " + respuesta.estado() + " al consultar al comprador.");
                throw new ErroresDeNegocio.SinRespuesta("El servicio de usuarios no responde. Probá de nuevo.");
            }
        };
    }

    /** Un solo cliente HTTP para toda la vida del bean: 2 segundos para conectar y 5 para leer. */
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

    /**
     * Hace la llamada HTTP y devuelve el código y el cuerpo. No es privado para
     * que el test lo reemplace y no necesite un servidor.
     *
     * Dos compras a la vez comparten esta instancia y su cliente HTTP. No hace
     * falta {@code synchronized}: el cliente que arma el servidor trae un pool
     * de conexiones y atiende varias llamadas a la vez.
     */
    Respuesta enviar(String tokenDelComprador) {
        try (Response respuesta = cliente.target(TokenDeServicio.URL_INTERNA + RUTA_DE_YO)
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDelComprador)
                .get()) {
            // El cuerpo solo interesa en un 200: de un error se mira el código y nada más.
            int estado = respuesta.getStatus();
            return new Respuesta(estado, estado == 200 ? respuesta.readEntity(String.class) : null);
        } catch (ProcessingException e) {
            // Sin conexión o se cumplió el tiempo de espera.
            LOG.warning("Usuarios no respondió al consultar al comprador: " + e.getMessage());
            throw new ErroresDeNegocio.SinRespuesta("El servicio de usuarios no responde. Probá de nuevo.");
        }
    }

    /** Convierte el cuerpo de un 200. Si no trae un usuario con id, no sirve para decidir nada. */
    static Usuario leerUsuario(String json) {
        Usuario usuario;
        try {
            usuario = JSONB.fromJson(json, Usuario.class);
        } catch (JsonbException e) {
            usuario = null;
        }
        if (usuario == null || usuario.id() == null || usuario.id().isBlank()) {
            throw new ErroresDeNegocio.SinRespuesta("Usuarios respondió algo que no es un usuario.");
        }
        return usuario;
    }
}
