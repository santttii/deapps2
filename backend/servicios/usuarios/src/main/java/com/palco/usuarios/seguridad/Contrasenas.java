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
 * con 310.000) se sigue verificando con las suyas. Cuando la cuenta inicia
 * sesión con la contraseña correcta, {@code ServicioDeUsuarios} pregunta
 * {@link #tieneMenosIteraciones} y, si es así, guarda un hash nuevo con
 * {@link #ITERACIONES}: es el único momento en que se tiene la contraseña.
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

    /**
     * El hash de una cuenta que no tiene que poder entrar. Tiene el formato y las
     * iteraciones de siempre, así verificarlo tarda lo mismo que con cualquier
     * cuenta y el tiempo de respuesta no la distingue (por lo mismo que V5); pero
     * el resultado esperado son 32 bytes en cero, y no se conoce ninguna
     * contraseña que dé eso: buscarla es invertir PBKDF2.
     */
    public static final String INUTILIZABLE = PREFIJO + "$" + ITERACIONES + "$"
            + Base64.getEncoder().encodeToString(new byte[BYTES_SAL]) + "$"
            + Base64.getEncoder().encodeToString(new byte[BITS_HASH / 8]);

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
        String[] partes = partes(guardado);
        if (contrasena == null || partes.length == 0) {
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

    /**
     * Si el hash guardado se calculó con menos iteraciones que {@link #ITERACIONES}, como los de
     * antes del 16/10 (310.000). Un hash que no tiene el formato esperado no se toca: da false.
     */
    public static boolean tieneMenosIteraciones(String guardado) {
        String[] partes = partes(guardado);
        try {
            return partes.length > 0 && Integer.parseInt(partes[1]) < ITERACIONES;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Las cuatro partes del hash guardado, o ninguna si no tiene el formato esperado. */
    private static String[] partes(String guardado) {
        if (guardado == null) {
            return new String[0];
        }
        String[] partes = guardado.split("\\$");
        return partes.length == 4 && PREFIJO.equals(partes[0]) ? partes : new String[0];
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
