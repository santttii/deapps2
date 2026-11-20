package com.palco.common.seguridad;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.palco.common.ErroresDeNegocio;

/**
 * Consigue el token con el que un servicio llama a otro (rol {@code servicio}).
 * Es el esquema de credenciales de cliente: el servicio le presenta a Usuarios
 * un secreto compartido y Usuarios le firma un token corto (5 minutos).
 *
 * Uso: {@code Authorization: Bearer <obtener()>}. Si el servicio llamado
 * responde 401, quien llama hace {@link #invalidar()}, {@link #obtener()} y
 * reintenta una sola vez: pasa cada vez que se redespliega Usuarios, porque
 * cambia la clave con la que firma.
 *
 * El secreto sale de {@code PALCO_SECRETO_SERVICIOS} y el código no trae un
 * valor de reserva: uno escrito acá lo conocería cualquiera que lea el repo, y
 * con él podría pedirse un token de rol {@code servicio}. Si la variable falta,
 * el servicio no puede llamar a los otros y el log lo avisa (ver leerSecreto).
 * dev.sh y docker/arrancar.sh la generan si no viene definida.
 *
 * Es un bean {@code @ApplicationScoped} y no un Session Bean: hay una sola
 * instancia por servicio y lo único que guarda, el token vigente, es el mismo
 * para todos los pedidos. No es la conversación de un usuario (eso sería
 * stateful) y tampoco sirve un stateless, que no puede recordar el token entre
 * llamadas. Como lo usan varios hilos a la vez, los métodos son
 * {@code synchronized}. No depende del pedido HTTP en curso, así que también
 * se puede usar desde un temporizador.
 */
@ApplicationScoped
public class TokenDeServicio {

    private static final Logger LOG = Logger.getLogger(TokenDeServicio.class.getName());

    /** Dirección de los otros servicios: PALCO_URL_INTERNA o http://localhost:8080. */
    public static final String URL_INTERNA = entorno("PALCO_URL_INTERNA", "http://localhost:8080");

    static final String RUTA_DEL_TOKEN = "/api/usuarios/servicios/token";

    /** Un token al que le queda menos que esto se renueva, para que no venza en medio de una llamada. */
    static final Duration MARGEN = Duration.ofSeconds(30);

    private static final String SECRETO = leerSecreto(System.getenv("PALCO_SECRETO_SERVICIOS"));
    private static final Jsonb JSONB = JsonbBuilder.create();

    /** Lo que responde {@code POST /api/usuarios/servicios/token}. */
    public record Respuesta(String token, long expiraEnSegundos) {
    }

    /** De dónde sale el "ahora"; los tests lo reemplazan por un reloj fijo. */
    Clock reloj = Clock.systemUTC();

    private Client cliente;
    private String token;
    private Instant vence;

    /**
     * PALCO_SECRETO_SERVICIOS o, si falta, uno al azar que nadie conoce. Lo leen
     * este cliente y ServicioDeUsuarios.
     */
    public static String secretoCompartido() {
        return SECRETO;
    }

    /** Token vigente. Si no hay o le quedan menos de 30 segundos, pide uno a Usuarios. */
    public synchronized String obtener() {
        Instant ahora = reloj.instant();
        if (token == null || ahora.isAfter(vence.minus(MARGEN))) {
            Respuesta nuevo = pedirAUsuarios();
            token = nuevo.token();
            vence = ahora.plusSeconds(nuevo.expiraEnSegundos());
        }
        return token;
    }

    /** Descarta el token guardado; el próximo obtener() pide uno nuevo. */
    public synchronized void invalidar() {
        token = null;
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
     * Le pide un token a Usuarios presentando el secreto. Se decide por el código
     * de estado: cualquier cosa que no sea un 200 con un token es "sin respuesta".
     * No es privado para que el test lo reemplace y no necesite un servidor.
     */
    Respuesta pedirAUsuarios() {
        String cuerpo = JSONB.toJson(Map.of("secreto", secretoCompartido()));
        try (Response respuesta = cliente.target(URL_INTERNA + RUTA_DEL_TOKEN)
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.entity(cuerpo, MediaType.APPLICATION_JSON))) {
            if (respuesta.getStatus() != 200) {
                LOG.warning("Usuarios no emitió el token de servicio: respondió " + respuesta.getStatus()
                        + ". Si es 401, PALCO_SECRETO_SERVICIOS falta o no coincide entre los servicios.");
                throw new ErroresDeNegocio.SinRespuesta("No se pudo obtener el token de servicio.");
            }
            return leerRespuesta(respuesta.readEntity(String.class));
        } catch (ProcessingException e) {
            // Sin conexión o se cumplió el tiempo de espera.
            LOG.warning("Usuarios no respondió al pedir el token de servicio: " + e.getMessage());
            throw new ErroresDeNegocio.SinRespuesta("El servicio de usuarios no responde.");
        }
    }

    /** Convierte el cuerpo de la respuesta; si no trae un token utilizable es "sin respuesta". */
    static Respuesta leerRespuesta(String json) {
        Respuesta respuesta;
        try {
            respuesta = JSONB.fromJson(json, Respuesta.class);
        } catch (JsonbException e) {
            respuesta = null;
        }
        if (respuesta == null || respuesta.token() == null || respuesta.token().isBlank()
                || respuesta.expiraEnSegundos() <= 0) {
            throw new ErroresDeNegocio.SinRespuesta("Usuarios respondió algo que no es un token de servicio.");
        }
        return respuesta;
    }

    /**
     * El valor de PALCO_SECRETO_SERVICIOS sin los espacios de los costados. Si
     * falta, uno al azar de 32 bytes, distinto en cada servicio (cada WAR trae su
     * copia de esta clase) y en cada arranque, que nadie conoce: este servicio no
     * consigue tokens de servicio y, si es Usuarios, no se los entrega a nadie. Es
     * el mismo criterio que la clave JWT, que si falta también se genera al azar,
     * pero acá el sistema no anda sin la variable, así que el log lo avisa como error.
     */
    static String leerSecreto(String configurado) {
        if (configurado == null || configurado.isBlank()) {
            LOG.severe("Falta PALCO_SECRETO_SERVICIOS: este servicio no puede pedir tokens de servicio y, si es "
                    + "Usuarios, no los entrega. Hay que definirlo con el mismo valor en todos los servicios.");
            byte[] azar = new byte[32];
            new SecureRandom().nextBytes(azar);
            return HexFormat.of().formatHex(azar);
        }
        return configurado.strip();
    }

    private static String entorno(String variable, String porDefecto) {
        String valor = System.getenv(variable);
        return valor == null || valor.isBlank() ? porDefecto : valor.strip();
    }
}
