package com.palco.usuarios.seguridad;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

/**
 * Arma y firma tokens JWT (RS256) y publica la clave pública en formato JWKS.
 * Es el formato que entiende MicroProfile JWT, que es lo que usan los demás
 * servicios para verificar los tokens.
 */
public final class Tokens {

    private static final Jsonb JSONB = JsonbBuilder.create();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private Tokens() {
    }

    /** Devuelve {@code header.payload.firma}. */
    public static String firmar(Map<String, Object> claims, RSAPrivateKey clavePrivada, String kid) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", kid);

        String contenido = parte(header) + "." + parte(claims);
        try {
            Signature firma = Signature.getInstance("SHA256withRSA");
            firma.initSign(clavePrivada);
            firma.update(contenido.getBytes(StandardCharsets.US_ASCII));
            return contenido + "." + B64URL.encodeToString(firma.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo firmar el token", e);
        }
    }

    /** JWKS con una sola clave: lo que devuelve {@code GET /api/usuarios/jwks}. */
    public static Map<String, Object> jwks(RSAPublicKey clavePublica, String kid) {
        Map<String, Object> clave = new LinkedHashMap<>();
        clave.put("kty", "RSA");
        clave.put("use", "sig");
        clave.put("alg", "RS256");
        clave.put("kid", kid);
        clave.put("n", enteroSinSigno(clavePublica.getModulus()));
        clave.put("e", enteroSinSigno(clavePublica.getPublicExponent()));
        return Map.of("keys", List.of(clave));
    }

    /** Identificador corto y estable de una clave pública (hash de su módulo). */
    public static String kid(RSAPublicKey clavePublica) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(clavePublica.getModulus().toByteArray());
            return B64URL.encodeToString(Arrays.copyOf(hash, 9));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String parte(Map<String, Object> mapa) {
        return B64URL.encodeToString(JSONB.toJson(mapa).getBytes(StandardCharsets.UTF_8));
    }

    /** Base64url del entero sin el byte de signo que agrega BigInteger. */
    private static String enteroSinSigno(BigInteger valor) {
        byte[] bytes = valor.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return B64URL.encodeToString(bytes);
    }
}
