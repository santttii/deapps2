package com.palco.reventa.negocio.externos;

import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.JsonbException;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.Textos;
import com.palco.reventa.negocio.externos.ClienteRest.Respuesta;

/**
 * El cobro de una compra en la reventa: {@code POST /api/v1/pagos} con el
 * token de servicio, igual que lo hace Ventas con una compra del catálogo.
 * Como {@code ventaId} viaja el id de la publicación: la venta del comprador
 * todavía no existe (nace en Ventas recién con el pago aprobado).
 *
 * La clave de {@code Idempotency-Key} es la del comprador con el id de la
 * publicación adelante ({@code R-3f9a1c2b:<clave>}, ver
 * {@link #claveParaPagos}), por lo mismo que en Ventas: en Pagos la clave es
 * única en todo el servicio, así dos compradores que eligen la misma no
 * chocan, y repetir la misma compra manda la misma clave y Pagos devuelve el
 * pago que ya guardó, sin cobrar dos veces.
 *
 * Traduce así:
 * <ul>
 * <li>201: el pago, {@code APROBADO} o {@code RECHAZADO};</li>
 * <li>400, o 409 {@code clave-reutilizada}: Pagos no aceptó el pedido y no cobró: rechazo;</li>
 * <li>otro 409 ({@code pago-duplicado}): chocó con otro pedido de la misma clave, que pudo
 * haber cobrado. Se repite una vez para leer el pago guardado; si vuelve a chocar, {@code SinRespuesta};</li>
 * <li>403 o 404 (el servidor lo frenó antes de entrar a Pagos), o sin token: {@link PedidoNoEnviado};</li>
 * <li>cualquier otra cosa: {@code SinRespuesta}. No se sabe si se cobró.</li>
 * </ul>
 *
 * {@code @ApplicationScoped}: una instancia sin estado para todo el servicio.
 */
@ApplicationScoped
public class ClienteDePagos {

    private static final Logger LOG = Logger.getLogger(ClienteDePagos.class.getName());

    static final String RUTA = "/api/v1/pagos";
    static final String TIPO_CLAVE_REUTILIZADA = "/problemas/clave-reutilizada";
    /** El {@code type} del 404 con el que Pagos dice que con esa clave no hay ningún pago. */
    static final String TIPO_NO_ENCONTRADO = "/problemas/no-encontrado";
    /** El parámetro de la consulta por clave: {@code GET /api/v1/pagos?claveIdempotencia=...}. */
    static final String PARAMETRO_CLAVE = "claveIdempotencia";
    private static final String PAGOS_NO_RESPONDE = "El servicio de pagos no responde. Probá de nuevo.";

    /** El resultado del cobro. {@code pagoId} es null si Pagos no llegó a cobrar. */
    public record Cobro(String pagoId, boolean aprobado, String motivo) {
    }

    public record Pedido(String ventaId, long monto, String medioDePago, String token) {
    }

    public record Pago(String pagoId, String ventaId, long monto, String estado, String motivo) {
    }

    @Inject
    ClienteRest rest;

    public Cobro cobrar(String publicacionId, long monto, String medioDePago, String token, String clave) {
        String cuerpo = ClienteRest.JSONB.toJson(new Pedido(publicacionId, monto, medioDePago, token));
        Map<String, String> cabeceras = Map.of("Idempotency-Key", claveParaPagos(publicacionId, clave));

        Respuesta respuesta = rest.llamar("POST", RUTA, Map.of(), cabeceras, cuerpo);
        if (esPagoDuplicado(respuesta)) {
            respuesta = rest.llamar("POST", RUTA, Map.of(), cabeceras, cuerpo);
            if (esPagoDuplicado(respuesta)) {
                LOG.warning("Pagos volvió a responder 409 al cobrar la publicación " + publicacionId
                        + ": no se sabe si se cobró.");
                throw new ErroresDeNegocio.SinRespuesta(PAGOS_NO_RESPONDE);
            }
        }
        return switch (respuesta.estado()) {
            case 201 -> leer(respuesta.cuerpo());
            case 400, 409 -> {
                LOG.warning("Pagos respondió " + respuesta.estado() + " al cobrar la publicación " + publicacionId
                        + ": no cobró.");
                yield new Cobro(null, false, respuesta.estado() == 400 ? "datos_invalidos" : "clave_reutilizada");
            }
            case 401, 403, 404 -> {
                LOG.warning("Pagos respondió " + respuesta.estado() + " al cobrar la publicación " + publicacionId
                        + ": el pedido no entró a Pagos y no cobró.");
                throw new PedidoNoEnviado(PAGOS_NO_RESPONDE);
            }
            default -> {
                LOG.warning("Pagos respondió " + respuesta.estado() + " al cobrar la publicación " + publicacionId
                        + ": no se sabe si se cobró.");
                throw new ErroresDeNegocio.SinRespuesta(PAGOS_NO_RESPONDE);
            }
        };
    }

    /**
     * Cómo terminó el cobro de una compra, preguntándole a Pagos por su clave
     * (la misma que mandó {@link #cobrar}). La usa la conciliación.
     *
     * <ul>
     * <li>200: el pago, aprobado o rechazado. Tiene que ser de esta publicación.</li>
     * <li>404 de Pagos ({@code no-encontrado}): con esa clave no se cobró nada.</li>
     * <li>Cualquier otra cosa, incluido un 404 sin el error de Pagos (el WAR no
     * está desplegado): {@code SinRespuesta}. No se sabe.</li>
     * </ul>
     */
    public Optional<Cobro> buscarCobro(String publicacionId, String clave) {
        Respuesta respuesta = rest.llamar("GET", RUTA,
                Map.of(PARAMETRO_CLAVE, claveParaPagos(publicacionId, clave)), Map.of(), null);
        if (respuesta.estado() == 200) {
            Pago pago = pagoDe(respuesta.cuerpo());
            if (pago == null || !publicacionId.equals(pago.ventaId())) {
                LOG.warning("Pagos respondió a la consulta del cobro de la publicación " + publicacionId
                        + " con un pago que no es de ella: no se sabe cómo terminó.");
                throw new ErroresDeNegocio.SinRespuesta(PAGOS_NO_RESPONDE);
            }
            return Optional.of(leer(respuesta.cuerpo()));
        }
        if (respuesta.estado() == 404 && TIPO_NO_ENCONTRADO.equals(respuesta.tipo())) {
            return Optional.empty();
        }
        LOG.warning("Pagos respondió " + respuesta.estado() + " a la consulta del cobro de la publicación "
                + publicacionId + ": no se sabe cómo terminó.");
        throw new ErroresDeNegocio.SinRespuesta(PAGOS_NO_RESPONDE);
    }

    private static Pago pagoDe(String json) {
        try {
            return ClienteRest.JSONB.fromJson(json, Pago.class);
        } catch (JsonbException e) {
            return null;
        }
    }

    /** El id de la publicación, dos puntos y la clave del comprador: determinística, entra en las 80 posiciones de Pagos. */
    static String claveParaPagos(String publicacionId, String clave) {
        return publicacionId + ":" + clave;
    }

    private static boolean esPagoDuplicado(Respuesta respuesta) {
        return respuesta.estado() == 409 && !TIPO_CLAVE_REUTILIZADA.equals(respuesta.tipo());
    }

    /** Un 201 que no trae un pago con id, aprobado o rechazado, no dice cómo terminó el cobro. */
    static Cobro leer(String json) {
        Pago pago;
        try {
            pago = ClienteRest.JSONB.fromJson(json, Pago.class);
        } catch (JsonbException e) {
            pago = null;
        }
        if (pago == null || pago.pagoId() == null || pago.pagoId().isBlank()) {
            throw new ErroresDeNegocio.SinRespuesta("Pagos respondió algo que no es un pago.");
        }
        if ("APROBADO".equals(pago.estado())) {
            return new Cobro(pago.pagoId(), true, null);
        }
        if ("RECHAZADO".equals(pago.estado())) {
            return new Cobro(pago.pagoId(), false, pago.motivo());
        }
        throw new ErroresDeNegocio.SinRespuesta("El pago " + Textos.enUnaLinea(pago.pagoId())
                + " todavía no tiene resultado.");
    }
}
