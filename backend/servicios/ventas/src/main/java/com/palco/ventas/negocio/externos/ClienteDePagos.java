package com.palco.ventas.negocio.externos;

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
 * <li>400 o 409: Pagos no aceptó el pedido y no cobró. Sale como un rechazo sin pago;</li>
 * <li>401: se renueva el token de servicio y se reintenta una sola vez;</li>
 * <li>otro 401, 403 o 404, o no se consiguió el token de servicio:
 * {@link CobroNoRealizado}. El pedido se frenó antes del cobro;</li>
 * <li>cualquier otra respuesta, o ninguna: {@code SinRespuesta}. No se sabe si se cobró.</li>
 * </ul>
 *
 * Las dos últimas le llegan al comprador como el mismo 504. Se distinguen
 * porque de eso depende que la venta quede marcada con un pago sin resultado.
 *
 * La clave de idempotencia del pago viaja tal cual en la cabecera
 * {@code Idempotency-Key}. Por eso reintentar después de un
 * {@code SinRespuesta} no cobra dos veces: Pagos reconoce la clave y devuelve
 * el pago que ya había guardado.
 *
 * Entre los dos servicios no hay transacción: el pago queda guardado en Pagos
 * apenas responde, aunque después la compra falle en Ventas.
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

    /** El motivo de un rechazo cuando Pagos responde 409: esa clave ya se usó para otra venta. */
    static final String MOTIVO_CLAVE_REUTILIZADA = "clave_reutilizada";

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

        Respuesta respuesta = enviar(cuerpo, claveIdempotencia, tokenPara(ventaId));
        if (respuesta.estado() == 401) {
            // El token guardado dejó de servir: pasa cuando se redespliega Usuarios, porque cambia la
            // clave con la que firma. Se pide uno nuevo y se reintenta una sola vez. Un 401 es anterior
            // al cobro, así que repetir el pedido no cobra dos veces.
            tokenDeServicio.invalidar();
            respuesta = enviar(cuerpo, claveIdempotencia, tokenPara(ventaId));
        }

        // Se decide por el código de estado, nunca por el texto del error.
        return switch (respuesta.estado()) {
            case 201 -> leerResultado(respuesta.cuerpo());
            case 409 -> sinCobro(ventaId, 409, MOTIVO_CLAVE_REUTILIZADA);
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
            // El cuerpo solo interesa en un 201: de un error se mira el código y nada más.
            int estado = respuesta.getStatus();
            return new Respuesta(estado, estado == 201 ? respuesta.readEntity(String.class) : null);
        } catch (ProcessingException e) {
            // Sin conexión o se cumplió el tiempo de espera: no se sabe si Pagos llegó a cobrar.
            LOG.warning("Pagos no respondió al cobrar: " + e.getMessage());
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
