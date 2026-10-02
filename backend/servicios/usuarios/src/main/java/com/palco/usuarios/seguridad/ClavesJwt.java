package com.palco.usuarios.seguridad;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;

/**
 * Par de claves RSA con el que ServicioDeUsuarios firma los tokens.
 *
 * Si está la variable {@code PALCO_JWT_CLAVE_PRIVADA} (PKCS#8 en base64) se usa
 * esa, así los tokens siguen valiendo después de reiniciar (producción). Si no,
 * se genera una al desplegar (desarrollo): al reiniciar hay que volver a
 * iniciar sesión.
 */
@Startup
@Singleton
public class ClavesJwt {

    private static final Logger LOG = Logger.getLogger(ClavesJwt.class.getName());

    private RSAPrivateKey privada;
    private RSAPublicKey publica;
    private String kid;

    @PostConstruct
    void cargar() {
        String configurada = System.getenv("PALCO_JWT_CLAVE_PRIVADA");
        try {
            if (configurada != null && !configurada.isBlank()) {
                KeyFactory rsa = KeyFactory.getInstance("RSA");
                RSAPrivateCrtKey clave = (RSAPrivateCrtKey) rsa.generatePrivate(
                        new PKCS8EncodedKeySpec(Base64.getDecoder().decode(configurada.strip())));
                privada = clave;
                publica = (RSAPublicKey) rsa.generatePublic(
                        new RSAPublicKeySpec(clave.getModulus(), clave.getPublicExponent()));
            } else {
                KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
                generador.initialize(2048);
                KeyPair par = generador.generateKeyPair();
                privada = (RSAPrivateKey) par.getPrivate();
                publica = (RSAPublicKey) par.getPublic();
                LOG.info("Sin PALCO_JWT_CLAVE_PRIVADA: se generó una clave nueva (los tokens anteriores dejan de valer).");
            }
        } catch (GeneralSecurityException | ClassCastException | IllegalArgumentException e) {
            throw new IllegalStateException("PALCO_JWT_CLAVE_PRIVADA no es una clave RSA PKCS#8 válida en base64", e);
        }
        kid = Tokens.kid(publica);
    }

    public RSAPrivateKey privada() { return privada; }
    public RSAPublicKey publica() { return publica; }
    public String kid() { return kid; }
}
