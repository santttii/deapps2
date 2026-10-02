package com.palco.usuarios.seguridad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TokensTest {

    private static RSAPrivateKey privada;
    private static RSAPublicKey publica;

    @BeforeAll
    static void generarClaves() throws Exception {
        KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
        generador.initialize(2048);
        KeyPair par = generador.generateKeyPair();
        privada = (RSAPrivateKey) par.getPrivate();
        publica = (RSAPublicKey) par.getPublic();
    }

    @Test
    void laFirmaSeVerificaConLaClavePublicaReconstruidaDesdeElJwks() throws Exception {
        String kid = Tokens.kid(publica);
        String token = Tokens.firmar(Map.of("sub", "user-1", "groups", List.of("user")), privada, kid);
        String[] partes = token.split("\\.");
        assertEquals(3, partes.length);

        // Lo mismo que hace cada servicio: arma la clave pública con n y e del JWKS.
        @SuppressWarnings("unchecked")
        Map<String, Object> jwk = ((List<Map<String, Object>>) Tokens.jwks(publica, kid).get("keys")).get(0);
        Base64.Decoder b64 = Base64.getUrlDecoder();
        RSAPublicKey reconstruida = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, b64.decode((String) jwk.get("n"))),
                new BigInteger(1, b64.decode((String) jwk.get("e")))));

        Signature verificador = Signature.getInstance("SHA256withRSA");
        verificador.initVerify(reconstruida);
        verificador.update((partes[0] + "." + partes[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verificador.verify(b64.decode(partes[2])));

        String header = new String(b64.decode(partes[0]), StandardCharsets.UTF_8);
        assertTrue(header.contains("\"alg\":\"RS256\"") && header.contains("\"kid\":\"" + kid + "\""));
    }

    @Test
    void unTokenModificadoNoVerifica() throws Exception {
        String token = Tokens.firmar(Map.of("sub", "user-1"), privada, "k");
        String[] partes = token.split("\\.");
        String payloadTrucho = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"user-admin\"}".getBytes(StandardCharsets.UTF_8));

        Signature verificador = Signature.getInstance("SHA256withRSA");
        verificador.initVerify(publica);
        verificador.update((partes[0] + "." + payloadTrucho).getBytes(StandardCharsets.US_ASCII));
        assertFalse(verificador.verify(Base64.getUrlDecoder().decode(partes[2])));
    }
}
