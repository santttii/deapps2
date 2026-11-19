package com.palco.common.rest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;

/**
 * ETag de las respuestas de los GET marcados con {@link ConEtag}.
 *
 * El ETag es un hash (SHA-256, recortado) del cuerpo en JSON: si el recurso no cambió, el hash
 * es el mismo. Si el cliente manda ese valor en {@code If-None-Match}, la respuesta pasa a ser
 * {@code 304 Not Modified} sin cuerpo: el cliente usa la copia que ya tenía y no viaja de nuevo.
 *
 * Es un ETag fuerte: dos respuestas con el mismo ETag son idénticas byte a byte en JSON.
 *
 * El otro lado es {@code If-Match}, en las ediciones: el cliente manda el ETag que leyó y el
 * servidor edita solo si el recurso sigue igual. Lo compara el negocio dentro de su transacción,
 * no este filtro, que corre después de la operación. Para que el valor comparado sea el mismo que
 * entregó el GET hay una sola función que lo calcula, {@link #etagDeLaRepresentacion}, y la usan
 * este filtro, el negocio y la respuesta de la edición.
 */
@Provider
@ConEtag
public class FiltroDeEtag implements ContainerResponseFilter {

    private static final Jsonb JSON = JsonbBuilder.create();

    @Override
    public void filter(ContainerRequestContext pedido, ContainerResponseContext respuesta) {
        if (!HttpMethod.GET.equals(pedido.getMethod()) || respuesta.getStatus() != 200 || !respuesta.hasEntity()) {
            return;
        }
        String etag = etagDeLaRepresentacion(respuesta.getEntity());
        respuesta.getHeaders().putSingle(HttpHeaders.ETAG, etag);
        if (coincide(pedido.getHeaderString(HttpHeaders.IF_NONE_MATCH), etag)) {
            respuesta.setStatus(304);
            respuesta.setEntity(null);
        }
    }

    /**
     * El ETag de lo que va en el cuerpo de una respuesta (un DTO): el hash de cómo queda en JSON.
     * Es el que pone este filtro en los GET; quien compare un {@code If-Match} lo calcula con esta
     * misma función para que el valor sea idéntico.
     */
    public static String etagDeLaRepresentacion(Object representacion) {
        return etagDe(JSON.toJson(representacion));
    }

    /** El ETag de un cuerpo: {@code "<hash>"}, con las comillas que pide HTTP. */
    static String etagDe(String cuerpo) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(cuerpo.getBytes(StandardCharsets.UTF_8));
            return '"' + Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, 16)) + '"';
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /**
     * true si el {@code If-Match} del cliente nombra este ETag (o es {@code *}), con la comparación
     * fuerte que pide RFC 9110 (13.1.1) para {@code If-Match}: un valor débil ({@code W/"..."})
     * nunca coincide, porque solo promete "equivalente" y para editar hace falta "idéntico". Los
     * ETag de Palco son fuertes, así que el que entregó el GET coincide tal cual.
     *
     * {@code *} quiere decir "si existe": quien llama ya comprobó que el recurso existe.
     */
    public static boolean coincideFuerte(String ifMatch, String etag) {
        if (ifMatch == null) {
            return false;
        }
        for (String valor : ifMatch.split(",")) {
            String v = valor.strip();
            if (v.equals("*") || (!etag.startsWith("W/") && v.equals(etag))) {
                return true;
            }
        }
        return false;
    }

    /**
     * true si el {@code If-None-Match} del cliente nombra este ETag (o es {@code *}). Acá la
     * comparación es débil (RFC 9110, 13.1.2): para no volver a descargar alcanza con "equivalente".
     */
    static boolean coincide(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String valor : ifNoneMatch.split(",")) {
            String v = valor.strip();
            if (v.startsWith("W/")) {
                v = v.substring(2);
            }
            if (v.equals("*") || v.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
