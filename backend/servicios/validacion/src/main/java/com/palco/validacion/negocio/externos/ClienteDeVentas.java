package com.palco.validacion.negocio.externos;

import java.util.Map;
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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.Textos;
import com.palco.common.seguridad.TokenDeServicio;
import com.palco.validacion.dominio.ResultadoEscaneo;

/**
 * Lo que Validación le pide a ServicioDeVentas: usar una entrada en la puerta
 * ({@code POST /api/v1/entradas/usos}). Ventas es la dueña de las entradas;
 * Validación no toca sus tablas ni usa sus clases, y la llama por REST con el
 * token de rol {@code servicio}.
 *
 * Traduce cada respuesta de Ventas a un {@link ResultadoEscaneo}. Que la
 * entrada esté usada, sea de otro evento o no exista no es un error para la
 * puerta: es el resultado del escaneo. El único error es que Ventas no
 * conteste, porque entonces no se sabe si la entrada quedó usada.
 *
 * Es un bean {@code @ApplicationScoped}: una sola instancia para todo el
 * servicio, que comparte un único cliente HTTP y no guarda ningún dato de los
 * escaneos. No es la conversación de un usuario y no necesita transacciones,
 * así que no hace falta un Session Bean.
 */
@ApplicationScoped
public class ClienteDeVentas {

    private static final Logger LOG = Logger.getLogger(ClienteDeVentas.class.getName());

    static final String RUTA_DEL_USO = "/api/v1/entradas/usos";

    /** Los {@code type} de los 409 de Ventas que la puerta distingue. Los otros 409 son "no habilitada". */
    static final String TIPO_YA_USADA = "/problemas/entrada-ya-usada";
    static final String TIPO_OTRO_EVENTO = "/problemas/entrada-de-otro-evento";

    private static final Jsonb JSONB = JsonbBuilder.create();

    /** El titular como lo devuelve Ventas. */
    public record Titular(String nombre, String dni) {
    }

    /** Lo que se lee de la entrada que devuelve Ventas: solo lo que la puerta muestra. */
    public record Entrada(String id, String eventoId, String sectorId, String sector, Titular titular,
            String estado) {
    }

    /** El resultado del uso; {@code entrada} viene solo con {@code VALIDA}. */
    public record Uso(ResultadoEscaneo resultado, Entrada entrada) {
    }

    /** De un error de Ventas (Problem Details) solo se lee el {@code type}. */
    public record Problema(String type) {
    }

    /** Lo que se mira de una respuesta HTTP: el código de estado y el cuerpo como texto. */
    record Respuesta(int estado, String cuerpo) {
    }

    @Inject
    TokenDeServicio tokenDeServicio;

    private Client cliente;

    /**
     * Le pide a Ventas que use la entrada con ese código en ese evento. Se
     * decide por el código de estado y el {@code type}, nunca por el texto del
     * error. Lanza 504 {@code sin-respuesta} si Ventas no contesta o contesta
     * algo inesperado: en ese caso no se sabe si la entrada quedó usada.
     */
    public Uso usar(String codigoQr, String eventoId) {
        String cuerpo = JSONB.toJson(Map.of("codigoQr", codigoQr, "eventoId", eventoId));

        Respuesta respuesta = enviar(cuerpo, tokenDeServicio.obtener());
        if (respuesta.estado() == 401) {
            // El token guardado dejó de servir: pasa cuando se redespliega Usuarios, porque cambia la
            // clave con la que firma. Se pide uno nuevo y se reintenta una sola vez. Repetir es seguro:
            // un 401 quiere decir que Ventas rechazó el pedido sin tocar la entrada.
            tokenDeServicio.invalidar();
            respuesta = enviar(cuerpo, tokenDeServicio.obtener());
        }

        return switch (respuesta.estado()) {
            case 200 -> new Uso(ResultadoEscaneo.VALIDA, leerEntrada(respuesta.cuerpo()));
            case 404 -> new Uso(ResultadoEscaneo.INEXISTENTE, null);
            case 409 -> new Uso(resultadoDelConflicto(tipoDeProblema(respuesta.cuerpo())), null);
            default -> {
                LOG.warning("Ventas respondió " + respuesta.estado() + " al usar una entrada del evento "
                        + Textos.enUnaLinea(eventoId) + ". No se sabe si la marcó como usada.");
                throw new ErroresDeNegocio.SinRespuesta("El servicio de ventas no responde. Escaneá de nuevo.");
            }
        };
    }

    /**
     * Un 409 de Ventas dice por qué la entrada no sirve. Un {@code type} que no
     * se conoce cuenta como "no habilitada": ante la duda, no se deja entrar.
     */
    static ResultadoEscaneo resultadoDelConflicto(String tipo) {
        if (TIPO_YA_USADA.equals(tipo)) {
            return ResultadoEscaneo.YA_USADA;
        }
        if (TIPO_OTRO_EVENTO.equals(tipo)) {
            return ResultadoEscaneo.OTRO_EVENTO;
        }
        return ResultadoEscaneo.NO_HABILITADA;
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
     * que el test lo reemplace y no necesite un servidor. El código QR va en el
     * cuerpo, no en la ruta: así no queda en el log de accesos de Ventas.
     *
     * Dos puertas a la vez comparten esta instancia y su cliente HTTP. No hace
     * falta {@code synchronized}: el cliente que arma el servidor trae un pool
     * de conexiones y atiende varias llamadas a la vez.
     */
    Respuesta enviar(String cuerpo, String token) {
        try (Response respuesta = cliente.target(TokenDeServicio.URL_INTERNA)
                .path(RUTA_DEL_USO)
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .post(Entity.entity(cuerpo, MediaType.APPLICATION_JSON))) {
            // El cuerpo interesa en un 200 (la entrada) y en un 409 (el type dice por qué no sirve).
            int estado = respuesta.getStatus();
            return new Respuesta(estado, estado == 200 || estado == 409 ? respuesta.readEntity(String.class) : null);
        } catch (ProcessingException e) {
            // Sin conexión o se cumplió el tiempo de espera: no se sabe si Ventas llegó a usar la entrada.
            // El código no va al log: es lo que deja entrar a alguien.
            LOG.warning("Ventas no respondió al usar una entrada. No se sabe si la marcó como usada. Motivo: "
                    + Textos.enUnaLinea(e.getMessage()));
            throw new ErroresDeNegocio.SinRespuesta("El servicio de ventas no responde. Escaneá de nuevo.");
        }
    }

    /** El {@code type} de un error de Ventas, o null si el cuerpo no lo trae. */
    private static String tipoDeProblema(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Problema problema = JSONB.fromJson(json, Problema.class);
            return problema == null ? null : problema.type();
        } catch (JsonbException e) {
            return null;
        }
    }

    /**
     * Convierte el cuerpo de un 200. Si no trae la entrada con su titular, la
     * puerta no tiene contra qué comparar el documento: se trata como una
     * respuesta que no sirve, aunque Ventas ya la haya marcado como usada.
     */
    static Entrada leerEntrada(String json) {
        Entrada entrada;
        try {
            entrada = json == null ? null : JSONB.fromJson(json, Entrada.class);
        } catch (JsonbException e) {
            entrada = null;
        }
        if (entrada == null || entrada.id() == null || entrada.titular() == null
                || entrada.titular().nombre() == null || entrada.titular().dni() == null) {
            LOG.warning("Ventas respondió 200 al usar una entrada, pero sin los datos del titular.");
            throw new ErroresDeNegocio.SinRespuesta("Ventas respondió algo que no es una entrada. Escaneá de nuevo.");
        }
        return entrada;
    }
}
