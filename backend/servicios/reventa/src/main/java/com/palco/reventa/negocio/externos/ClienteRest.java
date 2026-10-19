package com.palco.reventa.negocio.externos;

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
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.Textos;
import com.palco.common.seguridad.TokenDeServicio;

/**
 * La llamada HTTP a otro servicio, con el token de rol {@code servicio}. La
 * comparten los tres clientes de Reventa (Ventas, Pagos y Eventos): cada uno
 * arma su pedido y traduce la respuesta, y esta clase hace lo que es igual
 * para todos.
 *
 * <ul>
 * <li>Tiempos de espera: 2 segundos para conectar y 10 para leer (Pagos
 * espera a su pasarela).</li>
 * <li>Si el servicio llamado responde 401, el token guardado dejó de servir
 * (pasa cuando se redespliega Usuarios): se pide uno nuevo y se reintenta una
 * sola vez. Un 401 es anterior a la operación, así que repetir no la hace dos
 * veces.</li>
 * <li>Si no se consigue el token de servicio, el pedido ni sale: lanza
 * {@link PedidoNoEnviado}. Si sale y no vuelve respuesta, lanza
 * {@code SinRespuesta}: no se sabe si el otro servicio lo aplicó.</li>
 * </ul>
 *
 * {@code @ApplicationScoped}: una sola instancia con un solo cliente HTTP,
 * que trae su pool de conexiones y atiende varias llamadas a la vez. No
 * guarda datos de ningún pedido.
 */
@ApplicationScoped
public class ClienteRest {

    private static final Logger LOG = Logger.getLogger(ClienteRest.class.getName());

    static final Jsonb JSONB = JsonbBuilder.create();

    /** Lo que se mira de una respuesta: el código de estado y el cuerpo como texto. */
    public record Respuesta(int estado, String cuerpo) {

        /** El {@code type} del error (Problem Details), o null si el cuerpo no lo trae. */
        public String tipo() {
            if (cuerpo == null || cuerpo.isBlank()) {
                return null;
            }
            try {
                Problema problema = JSONB.fromJson(cuerpo, Problema.class);
                return problema == null ? null : problema.type();
            } catch (JsonbException e) {
                return null;
            }
        }
    }

    /** De un error de otro servicio solo se lee el {@code type}. */
    public record Problema(String type) {
    }

    @Inject
    TokenDeServicio tokenDeServicio;

    private Client cliente;

    /**
     * Hace el pedido y devuelve el código y el cuerpo.
     *
     * @param metodo "GET", "POST" o "DELETE"
     * @param ruta la ruta desde la raíz del servidor, ya con los ids codificados
     * @param consulta los parámetros de la consulta (puede ser vacío)
     * @param cabeceras cabeceras extra, como {@code Idempotency-Key} (puede ser vacío)
     * @param cuerpo el JSON que se manda, o null
     */
    public Respuesta llamar(String metodo, String ruta, Map<String, String> consulta, Map<String, String> cabeceras,
            String cuerpo) {
        Respuesta respuesta = enviar(metodo, ruta, consulta, cabeceras, cuerpo, token(ruta));
        if (respuesta.estado() == 401) {
            tokenDeServicio.invalidar();
            respuesta = enviar(metodo, ruta, consulta, cabeceras, cuerpo, token(ruta));
        }
        return respuesta;
    }

    /** El token de rol {@code servicio}; si Usuarios no lo entrega, el pedido no sale. */
    private String token(String ruta) {
        try {
            return tokenDeServicio.obtener();
        } catch (ErroresDeNegocio.SinRespuesta e) {
            LOG.warning("No se consiguió el token de servicio para llamar a " + Textos.enUnaLinea(ruta)
                    + ": el pedido no salió. Motivo: " + e.getMessage());
            throw new PedidoNoEnviado("Un servicio interno no responde. Probá de nuevo.");
        }
    }

    @PostConstruct
    void abrirCliente() {
        cliente = ClientBuilder.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @PreDestroy
    void cerrarCliente() {
        cliente.close();
    }

    /** La llamada HTTP misma. No es privada para que un test la reemplace y no necesite un servidor. */
    Respuesta enviar(String metodo, String ruta, Map<String, String> consulta, Map<String, String> cabeceras,
            String cuerpo, String token) {
        WebTarget destino = cliente.target(TokenDeServicio.URL_INTERNA).path(ruta);
        for (Map.Entry<String, String> parametro : consulta.entrySet()) {
            destino = destino.queryParam(parametro.getKey(), parametro.getValue());
        }
        Invocation.Builder pedido = destino.request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        for (Map.Entry<String, String> cabecera : cabeceras.entrySet()) {
            pedido = pedido.header(cabecera.getKey(), cabecera.getValue());
        }
        try (Response respuesta = cuerpo == null
                ? pedido.method(metodo)
                : pedido.method(metodo, Entity.entity(cuerpo, MediaType.APPLICATION_JSON))) {
            return new Respuesta(respuesta.getStatus(), respuesta.hasEntity() ? respuesta.readEntity(String.class) : null);
        } catch (ProcessingException e) {
            // Sin conexión o se cumplió el tiempo de espera: no se sabe si el otro servicio lo aplicó.
            LOG.warning("Sin respuesta de " + metodo + " " + Textos.enUnaLinea(ruta) + ": "
                    + Textos.enUnaLinea(e.getMessage()));
            throw new ErroresDeNegocio.SinRespuesta("Un servicio interno no responde. Probá de nuevo.");
        }
    }
}
