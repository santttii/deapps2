package com.palco.ventas.negocio.externos;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
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
import com.palco.common.seguridad.TokenDeServicio;

/**
 * El cobro de verdad: le pide a ServicioDePagos que cobre la venta. Es una
 * llamada REST a otro WAR ({@code POST /api/v1/pagos}) con el token de rol
 * {@code servicio}: Ventas no toca la tabla de pagos ni usa las clases de
 * Pagos.
 *
 * Es un Adapter: Pagos habla con códigos HTTP y JSON, y la compra solo
 * entiende la interfaz {@link Cobros}. Esta clase traduce una cosa a la otra:
 *
 * <ul>
 * <li>201 con {@code APROBADO}: cobro aprobado, con el id del pago que guardó Pagos;</li>
 * <li>201 con {@code RECHAZADO}: cobro rechazado, con el motivo de la pasarela;</li>
 * <li>400, o 409 {@code clave-reutilizada}: Pagos no aceptó el pedido y no cobró. Sale como un rechazo sin pago;</li>
 * <li>otro 409 ({@code pago-duplicado}): el pedido chocó con otro de la misma clave, que pudo
 * haber cobrado. Se repite una sola vez con la misma clave, para que Pagos devuelva el pago
 * que guardó; si vuelve a chocar, {@code SinRespuesta};</li>
 * <li>401: se renueva el token de servicio y se reintenta una sola vez;</li>
 * <li>otro 401, 403 o 404, o no se consiguió el token de servicio:
 * {@link CobroNoRealizado}. El pedido se frenó antes del cobro;</li>
 * <li>cualquier otra respuesta, o ninguna: {@code SinRespuesta}. No se sabe si se cobró.</li>
 * </ul>
 *
 * Las dos últimas le llegan al comprador como el mismo 504. Se distinguen
 * porque de eso depende que la venta quede marcada con un pago sin resultado.
 *
 * La clave que viaja en la cabecera {@code Idempotency-Key} no es la que
 * mandó el comprador, sino esa misma con el id de la venta adelante
 * ({@code V-3f9a1c2b:<clave>}, ver {@link #claveParaPagos}). En Ventas la
 * clave es única por comprador; en Pagos, en todo el servicio. Si viajara tal
 * cual, dos compradores que eligen la misma clave chocarían en Pagos y el
 * segundo recibiría un rechazo por una venta que no es suya. Con el id de la
 * venta adelante no pueden chocar, y repetir el mismo cobro sigue mandando la
 * misma clave: reintentar después de un {@code SinRespuesta} no cobra dos
 * veces, porque Pagos la reconoce y devuelve el pago que ya había guardado.
 *
 * Entre los dos servicios no hay transacción: el pago queda guardado en Pagos
 * apenas responde, aunque después la compra falle en Ventas. Para ese caso
 * está {@link #buscarCobro}, la consulta por clave que usa la conciliación.
 *
 * Es un bean {@code @ApplicationScoped}: una sola instancia para todo el
 * servicio, que comparte un único cliente HTTP y no guarda ningún dato de las
 * compras. {@code @Typed} hace que solo se pueda inyectar por su clase y no
 * como {@code Cobros}: el {@code Cobros} que recibe la compra es el que
 * entrega {@link FabricaDeCobros}.
 */
@ApplicationScoped
@Typed(ClienteDePagos.class)
public class ClienteDePagos implements Cobros {

    private static final Logger LOG = Logger.getLogger(ClienteDePagos.class.getName());

    /** Valor de {@code palco.ventas.cobros} que elige este cobro. */
    public static final String NOMBRE = "pagos";

    static final String RUTA_DE_PAGOS = "/api/v1/pagos";
    static final String CLAVE_DE_IDEMPOTENCIA = "Idempotency-Key";

    /** El motivo de un rechazo cuando Pagos responde 400: no aceptó los datos del pedido. */
    static final String MOTIVO_DATOS_INVALIDOS = "datos_invalidos";

    private static final String PAGOS_NO_RESPONDE = "El servicio de pagos no responde. Probá de nuevo.";

    private static final Jsonb JSONB = JsonbBuilder.create();

    /** Lo que se le manda a Pagos. La clave de idempotencia no va acá: viaja en la cabecera. */
    public record Pedido(String ventaId, long monto, String medioDePago, String token) {
    }

    /** El pago como lo devuelve Pagos. Para la compra importan {@code pagoId}, {@code estado} y {@code motivo}. */
    public record Pago(String pagoId, String ventaId, long monto, String estado, String motivo, String idExterno) {
    }

    /** El {@code type} del 409 con el que Pagos dice que esa clave ya cobró otra venta u otro monto. */
    static final String TIPO_CLAVE_REUTILIZADA = "/problemas/clave-reutilizada";

    /** El {@code type} del 404 con el que Pagos dice que con esa clave no hay ningún pago. */
    static final String TIPO_NO_ENCONTRADO = "/problemas/no-encontrado";

    /** El parámetro de la consulta por clave: {@code GET /api/v1/pagos?claveIdempotencia=...}. */
    static final String PARAMETRO_CLAVE = "claveIdempotencia";

    /** De un error de Pagos (Problem Details) solo se lee el {@code type}. */
    public record Problema(String type) {
    }

    /** Lo que se mira de una respuesta HTTP: el código de estado y el cuerpo como texto. */
    record Respuesta(int estado, String cuerpo) {
    }

    @Inject
    TokenDeServicio tokenDeServicio;

    private Client cliente;

    @Override
    public ResultadoDeCobro cobrar(String ventaId, long monto, String medioDePago, String token,
            String claveIdempotencia) {
        String cuerpo = JSONB.toJson(new Pedido(ventaId, monto, medioDePago, token));
        String clave = claveParaPagos(ventaId, claveIdempotencia);

        Respuesta respuesta = pedir(cuerpo, clave, ventaId);
        if (esPagoDuplicado(respuesta)) {
            // Dos pedidos con esta clave chocaron al guardarse en Pagos (el típico: el reintento después
            // de un 504 mientras el original seguía en curso) y Pagos no llegó a releer el que ganó.
            // Ese otro pedido pudo haber cobrado, así que no es un rechazo. Con la misma clave Pagos
            // no cobra de nuevo: devuelve el pago guardado.
            LOG.info("Pagos respondió 409 pago-duplicado al cobrar la venta " + ventaId
                    + ": se repite el pedido con la misma clave para leer el pago guardado.");
            respuesta = pedir(cuerpo, clave, ventaId);
            if (esPagoDuplicado(respuesta)) {
                LOG.warning("Pagos volvió a responder 409 pago-duplicado al cobrar la venta " + ventaId
                        + ": no se sabe si se cobró.");
                throw new ErroresDeNegocio.SinRespuesta(PAGOS_NO_RESPONDE);
            }
        }

        // Se decide por el código de estado y, en un 409, por el type del error; nunca por el texto.
        return switch (respuesta.estado()) {
            case 201 -> leerResultado(respuesta.cuerpo());
            case 409 -> sinCobro(ventaId, 409, ResultadoDeCobro.MOTIVO_CLAVE_REUTILIZADA);
            case 400 -> sinCobro(ventaId, 400, MOTIVO_DATOS_INVALIDOS);
            case 401, 403, 404 -> {
                // Las tres respuestas son anteriores al cobro: el servidor las da sin entrar al código de Pagos.
                LOG.warning("Pagos respondió " + respuesta.estado() + " al cobrar la venta " + ventaId
                        + ": no cobró. Con 401 o 403 no aceptó el token de servicio; con 404, el servicio de"
                        + " Pagos no está desplegado.");
                throw new CobroNoRealizado(PAGOS_NO_RESPONDE);
            }
            default -> {
                LOG.warning("Pagos respondió " + respuesta.estado() + " al cobrar la venta " + ventaId
                        + ": no se sabe si se cobró.");
                throw new ErroresDeNegocio.SinRespuesta(PAGOS_NO_RESPONDE);
            }
        };
    }

    /**
     * Le pregunta a Pagos por el cobro de esa clave
     * ({@code GET /api/v1/pagos?claveIdempotencia=<ventaId>:<clave>}), la misma
     * clave derivada que viajó al cobrar. Traduce así:
     *
     * <ul>
     * <li>200 con un pago de esta venta, aprobado o rechazado: ese resultado;</li>
     * <li>404 con {@code "type": "/problemas/no-encontrado"}: vacío. Es el 404 que
     * arma Pagos cuando la clave no cobró;</li>
     * <li>401: se renueva el token de servicio y se reintenta una sola vez;</li>
     * <li>cualquier otra cosa, o ninguna respuesta: {@code SinRespuesta}. Entre
     * ellas, un 404 sin ese cuerpo (el servidor lo da sin entrar a Pagos cuando
     * el servicio no está desplegado), un pago de otra venta o uno que todavía
     * no tiene resultado.</li>
     * </ul>
     *
     * Solo lee: repetir la consulta no cobra ni cambia nada en Pagos.
     */
    @Override
    public Optional<ResultadoDeCobro> buscarCobro(String ventaId, String claveIdempotencia) {
        String clave = claveParaPagos(ventaId, claveIdempotencia);
        Respuesta respuesta = consultar(clave, tokenParaConsultar(ventaId));
        if (respuesta.estado() == 401) {
            tokenDeServicio.invalidar();
            respuesta = consultar(clave, tokenParaConsultar(ventaId));
        }
        if (respuesta.estado() == 200) {
            return Optional.of(leerPagoDeLaVenta(respuesta.cuerpo(), ventaId));
        }
        if (respuesta.estado() == 404 && TIPO_NO_ENCONTRADO.equals(tipoDeProblema(respuesta.cuerpo()))) {
            return Optional.empty();
        }
        LOG.warning("Pagos respondió " + respuesta.estado() + " a la consulta del cobro de la venta " + ventaId
                + ": no se sabe cómo terminó.");
        throw new ErroresDeNegocio.SinRespuesta(PAGOS_NO_RESPONDE);
    }

    /** El token de servicio para la consulta. Si Usuarios no lo entrega, no se sabe nada del cobro. */
    private String tokenParaConsultar(String ventaId) {
        try {
            return tokenDeServicio.obtener();
        } catch (ErroresDeNegocio.SinRespuesta e) {
            LOG.warning("No se consiguió el token de servicio para consultar el cobro de la venta " + ventaId
                    + ". Motivo: " + e.getMessage());
            throw new ErroresDeNegocio.SinRespuesta(PAGOS_NO_RESPONDE);
        }
    }

    /**
     * El pago de un 200 de la consulta. Además de lo que exige
     * {@link #leerResultado}, tiene que ser de esta venta: la clave lleva el id
     * de la venta adelante, así que otra venta sería un error de Pagos, y con
     * eso no se confirma ni se vence nada.
     */
    static ResultadoDeCobro leerPagoDeLaVenta(String json, String ventaId) {
        Pago pago;
        try {
            pago = JSONB.fromJson(json, Pago.class);
        } catch (JsonbException e) {
            pago = null;
        }
        if (pago == null || !ventaId.equals(pago.ventaId())) {
            throw new ErroresDeNegocio.SinRespuesta("Pagos respondió un pago que no es de la venta " + ventaId + ".");
        }
        return leerResultado(json);
    }

    /**
     * La clave que se le manda a Pagos: el id de la venta, dos puntos y la
     * clave del comprador. Es determinística, así que el mismo cobro repetido
     * lleva siempre la misma. Entra en las 80 posiciones que acepta Pagos: el
     * id de la venta tiene 10 y la clave del comprador es un UUID de 36.
     */
    static String claveParaPagos(String ventaId, String claveIdempotencia) {
        return ventaId + ":" + claveIdempotencia;
    }

    /**
     * Manda el pedido a Pagos. Si el token guardado dejó de servir (401: pasa
     * cuando se redespliega Usuarios, porque cambia la clave con la que firma),
     * pide uno nuevo y reintenta una sola vez. Un 401 es anterior al cobro, así
     * que repetir el pedido no cobra dos veces.
     */
    private Respuesta pedir(String cuerpo, String claveIdempotencia, String ventaId) {
        Respuesta respuesta = enviar(cuerpo, claveIdempotencia, tokenPara(ventaId));
        if (respuesta.estado() == 401) {
            tokenDeServicio.invalidar();
            respuesta = enviar(cuerpo, claveIdempotencia, tokenPara(ventaId));
        }
        return respuesta;
    }

    /**
     * Un 409 que no es {@code clave-reutilizada}. Hoy es el {@code pago-duplicado}
     * de Pagos; un 409 sin un {@code type} legible se trata igual, porque leerlo
     * como "no se cobró" podría dejar sin venta un cobro hecho.
     */
    static boolean esPagoDuplicado(Respuesta respuesta) {
        return respuesta.estado() == 409 && !TIPO_CLAVE_REUTILIZADA.equals(tipoDeProblema(respuesta.cuerpo()));
    }

    /** El {@code type} de un error de Pagos, o null si el cuerpo no lo trae. */
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
     * El token de rol {@code servicio} con el que se llama a Pagos. Si Usuarios
     * no lo entrega, el pedido ni siquiera sale hacia Pagos: no se cobró.
     */
    private String tokenPara(String ventaId) {
        try {
            return tokenDeServicio.obtener();
        } catch (ErroresDeNegocio.SinRespuesta e) {
            LOG.warning("No se consiguió el token de servicio para cobrar la venta " + ventaId
                    + ": no se llamó a Pagos y no se cobró. Motivo: " + e.getMessage());
            throw new CobroNoRealizado(PAGOS_NO_RESPONDE);
        }
    }

    /**
     * Pagos contestó que no aceptó el pedido, y eso es seguro: no cobró y esa
     * clave no va a cobrar esta venta. Para la compra es lo mismo que un
     * rechazo de la pasarela: sigue abierta y se paga de nuevo con una clave
     * nueva. No se lanza {@code SinRespuesta} porque dejaría la venta marcada
     * con un pago en curso que nunca va a terminar, reteniendo su cupo.
     */
    private static ResultadoDeCobro sinCobro(String ventaId, int estado, String motivo) {
        LOG.warning("Pagos respondió " + estado + " al cobrar la venta " + ventaId + ": no cobró (" + motivo + ").");
        return new ResultadoDeCobro(null, ResultadoDeCobro.RECHAZADO, motivo);
    }

    /** Un solo cliente HTTP para toda la vida del bean: 2 segundos para conectar y 10 para leer, porque Pagos espera a su pasarela. */
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

    /**
     * Hace la llamada HTTP y devuelve el código y el cuerpo. No es privado para
     * que el test lo reemplace y no necesite un servidor.
     *
     * Dos compras a la vez comparten esta instancia y su cliente HTTP. No hace
     * falta {@code synchronized}: el cliente que arma el servidor trae un pool
     * de conexiones y atiende varias llamadas a la vez.
     *
     * @param tokenDeServicio el token de rol {@code servicio}, no el de la tarjeta (ese va dentro del cuerpo)
     */
    Respuesta enviar(String cuerpo, String claveIdempotencia, String tokenDeServicio) {
        try (Response respuesta = cliente.target(TokenDeServicio.URL_INTERNA + RUTA_DE_PAGOS)
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDeServicio)
                .header(CLAVE_DE_IDEMPOTENCIA, claveIdempotencia)
                .post(Entity.entity(cuerpo, MediaType.APPLICATION_JSON))) {
            // El cuerpo interesa en un 201 (el pago) y en un 409 (el type, para saber si cobró).
            // De cualquier otro error se mira el código y nada más.
            int estado = respuesta.getStatus();
            return new Respuesta(estado, estado == 201 || estado == 409 ? respuesta.readEntity(String.class) : null);
        } catch (ProcessingException e) {
            // Sin conexión o se cumplió el tiempo de espera: no se sabe si Pagos llegó a cobrar.
            LOG.warning("Pagos no respondió al cobrar: " + e.getMessage());
            throw new ErroresDeNegocio.SinRespuesta(PAGOS_NO_RESPONDE);
        }
    }

    /**
     * Hace la consulta HTTP por clave y devuelve el código y el cuerpo. Como
     * {@link #enviar}, no es privado para que el test lo reemplace.
     */
    Respuesta consultar(String claveIdempotencia, String tokenDeServicio) {
        try (Response respuesta = cliente.target(TokenDeServicio.URL_INTERNA + RUTA_DE_PAGOS)
                .queryParam(PARAMETRO_CLAVE, claveIdempotencia)
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDeServicio)
                .get()) {
            // El cuerpo interesa en un 200 (el pago) y en un 404 (el type, para saber si es el de Pagos).
            int estado = respuesta.getStatus();
            return new Respuesta(estado, estado == 200 || estado == 404 ? respuesta.readEntity(String.class) : null);
        } catch (ProcessingException e) {
            LOG.warning("Pagos no respondió a la consulta de un cobro: " + e.getMessage());
            throw new ErroresDeNegocio.SinRespuesta(PAGOS_NO_RESPONDE);
        }
    }

    /**
     * Convierte el cuerpo de un 201 en el resultado del cobro. Si no trae un
     * pago con id, aprobado o rechazado, no se sabe cómo terminó el cobro.
     */
    static ResultadoDeCobro leerResultado(String json) {
        Pago pago;
        try {
            pago = JSONB.fromJson(json, Pago.class);
        } catch (JsonbException e) {
            pago = null;
        }
        if (pago == null || pago.pagoId() == null || pago.pagoId().isBlank()) {
            throw new ErroresDeNegocio.SinRespuesta("Pagos respondió algo que no es un pago.");
        }
        if (ResultadoDeCobro.APROBADO.equals(pago.estado())) {
            return new ResultadoDeCobro(pago.pagoId(), ResultadoDeCobro.APROBADO, null);
        }
        if (ResultadoDeCobro.RECHAZADO.equals(pago.estado())) {
            return new ResultadoDeCobro(pago.pagoId(), ResultadoDeCobro.RECHAZADO, pago.motivo());
        }
        // Un estado que hoy Pagos no devuelve (por ejemplo PENDIENTE, que llega con el webhook).
        throw new ErroresDeNegocio.SinRespuesta("El pago " + pago.pagoId() + " todavía no tiene resultado.");
    }
}
