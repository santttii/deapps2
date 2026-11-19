package com.palco.asistente.negocio.externos;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.palco.asistente.negocio.Configuracion;
import com.palco.common.ReglasDeNegocio;

/**
 * Un modelo de lenguaje real, por la API de chat de OpenAI ({@code POST /chat/completions}). Es
 * el formato que también aceptan Gemini, Groq u Ollama (local, sin clave), así que el mismo
 * adaptador sirve para cualquiera de ellos cambiando la dirección y el modelo. Como
 * {@link ModeloSimulado}, traduce esa API a {@link ModeloDeLenguaje} (patrón Adapter).
 *
 * Se configura por variables de entorno, que van en {@code backend/.env} (no se versiona) y en
 * el hosting:
 * <ul>
 * <li>{@code PALCO_ASISTENTE_MODELO=openai}: lo elige {@link FabricaDeModelo};</li>
 * <li>{@code PALCO_ASISTENTE_OPENAI_URL}: la base de la API, por defecto
 * {@code https://api.openai.com/v1} (Ollama: {@code http://localhost:11434/v1});</li>
 * <li>{@code PALCO_ASISTENTE_OPENAI_MODELO}: el modelo, por defecto {@code gpt-4o-mini};</li>
 * <li>{@code PALCO_ASISTENTE_OPENAI_CLAVE}: la clave de la API. Es secreta: vive solo en el
 * servidor y no va al log. Ollama no la pide.</li>
 * </ul>
 *
 * El modelo no conoce las reglas de Palco: además de las instrucciones del gateway, se le mandan
 * los datos con los que puede responder, sacados de {@link ReglasDeNegocio}, para que no invente
 * topes ni plazos. La pregunta llega con los datos personales ya enmascarados por los guardrails.
 * El tiempo máximo lo controla el gateway (5 s); el cliente HTTP tiene su propio límite para no
 * dejar una conexión abierta si el proveedor no contesta.
 *
 * {@code @ApplicationScoped}: un solo cliente HTTP para todo el servicio y ningún dato de los pedidos.
 */
@ApplicationScoped
@Typed(ModeloCompatibleOpenAI.class)
public class ModeloCompatibleOpenAI implements ModeloDeLenguaje {

    static final String PROPIEDAD_URL = "palco.asistente.openai.url";
    static final String PROPIEDAD_MODELO = "palco.asistente.openai.modelo";
    static final String PROPIEDAD_CLAVE = "palco.asistente.openai.clave";
    static final String URL_POR_DEFECTO = "https://api.openai.com/v1";
    static final String MODELO_POR_DEFECTO = "gpt-4o-mini";

    private static final Logger LOG = Logger.getLogger(ModeloCompatibleOpenAI.class.getName());
    private static final Jsonb JSONB = JsonbBuilder.create();

    /** Lo que se le manda al proveedor. */
    public record Mensaje(String role, String content) {
    }

    public record Pedido(String model, List<Mensaje> messages, double temperature,
            @JsonbProperty("max_tokens") int maxTokens) {
    }

    /** De la respuesta solo se lee el texto de la primera opción. */
    public record Contenido(String content) {
    }

    public record Opcion(Contenido message) {
    }

    public record Completado(List<Opcion> choices) {
    }

    record Respuesta(int estado, String cuerpo) {
    }

    private Client cliente;

    @Override
    public String nombre() {
        return "openai-compatible/" + modelo();
    }

    @Override
    public String completar(String instrucciones, String pregunta) {
        Pedido pedido = new Pedido(modelo(),
                List.of(new Mensaje("system", instrucciones + "\n\n" + datosDePalco()), new Mensaje("user", pregunta)),
                0.2, 300);
        Respuesta respuesta = enviar(url(), clave(), JSONB.toJson(pedido));
        if (respuesta.estado() != 200) {
            // El cuerpo de un error del proveedor puede traer datos de la cuenta: no va al log.
            LOG.warning("Asistente: el proveedor de IA respondió " + respuesta.estado() + ".");
            throw new IllegalStateException("El proveedor de IA respondió " + respuesta.estado() + ".");
        }
        String texto = texto(respuesta.cuerpo());
        if (texto == null) {
            throw new IllegalStateException("El proveedor de IA respondió sin texto.");
        }
        return texto;
    }

    /**
     * Lo que el modelo puede usar para responder. Sale de {@link ReglasDeNegocio}: si cambia una
     * regla, cambia lo que sabe el asistente.
     */
    static String datosDePalco() {
        return "Datos de Palco que podés usar (no inventes otros):\n"
                + "- Se compran hasta " + ReglasDeNegocio.MAX_ENTRADAS_POR_ORDEN + " entradas por compra y hasta "
                + ReglasDeNegocio.MAX_ENTRADAS_POR_EVENTO + " por persona y evento.\n"
                + "- Al comprar, los lugares quedan reservados " + ReglasDeNegocio.MINUTOS_RESERVA
                + " minutos para pagar.\n"
                + "- El cargo de servicio es el " + ReglasDeNegocio.TASA_SERVICIO_PORCENTAJE + " % del subtotal.\n"
                + "- Cada entrada es nominativa: en la puerta se muestra el QR y el DNI del titular.\n"
                + "- La reventa oficial permite publicar una entrada entre el " + ReglasDeNegocio.MINIMO_REVENTA_PORCENTAJE
                + " % del precio original de la entrada (el de lista de su sector) y ese precio más un "
                + ReglasDeNegocio.TOPE_REVENTA_PORCENTAJE + " %, hasta "
                + ReglasDeNegocio.HORAS_LIMITE_REVENTA + " horas antes del evento. Se publica y se retira desde"
                + " Mis entradas; cuando se vende, el QR anterior deja de servir.\n"
                + "- Si se cancela un evento, la compra se anula, se devuelve el dinero y se avisa por email.\n"
                + "- La factura se emite sola después de pagar y se ve con su número y CAE en el detalle de la compra.\n"
                + "- Las compras y las entradas, con su estado y su QR, están en Mis entradas.";
    }

    @PostConstruct
    void abrirCliente() {
        cliente = ClientBuilder.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                // Uno más que el tiempo máximo del gateway (AsistenteIA.TIEMPO_MAXIMO): el que corta a
                // los 5 segundos es el gateway, con su motivo "timeout"; este límite solo libera la conexión.
                .readTimeout(6, TimeUnit.SECONDS)
                .build();
    }

    @PreDestroy
    void cerrarCliente() {
        cliente.close();
    }

    /** La llamada HTTP. No es privada para que un test la reemplace y no necesite un proveedor. */
    Respuesta enviar(String url, String clave, String cuerpo) {
        Invocation.Builder pedido = cliente.target(url).path("chat/completions").request(MediaType.APPLICATION_JSON);
        if (clave != null && !clave.isBlank()) {
            pedido = pedido.header(HttpHeaders.AUTHORIZATION, "Bearer " + clave);
        }
        try (Response respuesta = pedido.post(Entity.json(cuerpo))) {
            return new Respuesta(respuesta.getStatus(), respuesta.hasEntity() ? respuesta.readEntity(String.class) : null);
        } catch (ProcessingException e) {
            throw new IllegalStateException("El proveedor de IA no respondió.", e);
        }
    }

    static String texto(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Completado completado = JSONB.fromJson(json, Completado.class);
            if (completado == null || completado.choices() == null || completado.choices().isEmpty()) {
                return null;
            }
            Opcion primera = completado.choices().get(0);
            return primera == null || primera.message() == null ? null : primera.message().content();
        } catch (JsonbException e) {
            return null;
        }
    }

    // Se leen en cada pedido, como la demora del modelo simulado: se cambian sin redesplegar.
    String url() {
        return Configuracion.leer(PROPIEDAD_URL, String.class, URL_POR_DEFECTO);
    }

    String modelo() {
        return Configuracion.leer(PROPIEDAD_MODELO, String.class, MODELO_POR_DEFECTO);
    }

    String clave() {
        return Configuracion.leer(PROPIEDAD_CLAVE, String.class, "");
    }
}
