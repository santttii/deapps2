package com.palco.usuarios.seguridad;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hash de contraseñas con PBKDF2-HMAC-SHA256 (incluido en Java, sin
 * dependencias). Formato guardado:
 * {@code pbkdf2-sha256$<iteraciones>$<sal base64>$<hash base64>}.
 *
 * Las iteraciones van en el hash guardado: subirlas solo cambia los hashes
 * nuevos, y uno viejo (por ejemplo, los de las cuentas de prueba de V2 y V3,
 * con 310.000) se sigue verificando con las suyas.
 */
public final class Contrasenas {

    private static final String PREFIJO = "pbkdf2-sha256";
    /**
     * Las que recomienda hoy OWASP para PBKDF2-HMAC-SHA256 (Password Storage Cheat
     * Sheet). Antes eran 310.000, la recomendación anterior.
     */
    public static final int ITERACIONES = 600_000;
    private static final int BYTES_SAL = 16;
    private static final int BITS_HASH = 256;
    private static final SecureRandom AZAR = new SecureRandom();

    private Contrasenas() {
    }

    public static String hashear(String contrasena) {
        byte[] sal = new byte[BYTES_SAL];
        AZAR.nextBytes(sal);
        byte[] hash = pbkdf2(contrasena, sal, ITERACIONES);
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIJO + "$" + ITERACIONES + "$" + b64.encodeToString(sal) + "$" + b64.encodeToString(hash);
    }

    /** Compara en tiempo constante. Devuelve false si el hash guardado no tiene el formato esperado. */
    public static boolean verificar(String contrasena, String guardado) {
        if (contrasena == null || guardado == null) {
            return false;
        }
        String[] partes = guardado.split("\\$");
        if (partes.length != 4 || !PREFIJO.equals(partes[0])) {
            return false;
        }
        try {
            int iteraciones = Integer.parseInt(partes[1]);
            byte[] sal = Base64.getDecoder().decode(partes[2]);
            byte[] esperado = Base64.getDecoder().decode(partes[3]);
            return MessageDigest.isEqual(esperado, pbkdf2(contrasena, sal, iteraciones));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String contrasena, byte[] sal, int iteraciones) {
        PBEKeySpec spec = new PBEKeySpec(contrasena.toCharArray(), sal, iteraciones, BITS_HASH);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 no disponible en esta JVM", e);
        } finally {
            spec.clearPassword();
        }
    }
}
