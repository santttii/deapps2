package com.palco.asistente.negocio;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Las protecciones del asistente, antes y después del modelo.
 *
 * Entrada: largo máximo, enmascarar datos personales (DNI, tarjeta, email) antes de que salgan
 * hacia el proveedor del modelo (OWASP LLM02), y frenar intentos de inyección de prompt
 * (LLM01) y pedidos de datos de terceros.
 *
 * Salida: si la respuesta del modelo trae un DNI, un email o un número de tarjeta, no se
 * muestra (LLM02).
 */
public final class Guardrails {

    /** Largo máximo de una pregunta. */
    public static final int LARGO_MAXIMO = 500;

    static final String DNI_OCULTO = "[dni oculto]";
    static final String TARJETA_OCULTA = "[tarjeta oculta]";
    static final String EMAIL_OCULTO = "[email oculto]";

    // 13 a 19 dígitos, con espacios o guiones entre grupos: un número de tarjeta.
    private static final Pattern TARJETA = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    // 7 u 8 dígitos sueltos, con o sin puntos: un DNI argentino.
    private static final Pattern DNI = Pattern.compile("\\b\\d{1,2}\\.?\\d{3}\\.?\\d{3}\\b");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");

    // Frases típicas de inyección de prompt, sobre el texto normalizado (sin tildes ni mayúsculas).
    private static final List<Pattern> INYECCION = List.of(
            Pattern.compile("ignor\\w* (tus|las|todas)\\b.*(instruccion|regla)"),
            Pattern.compile("olvid\\w* (tus|las)\\b.*(instruccion|regla)"),
            Pattern.compile("prompt del sistema|system prompt|instrucciones del sistema"),
            Pattern.compile("modo (desarrollador|dios|admin)|\\bsoy (el )?(admin|administrador|desarrollador)\\b"),
            Pattern.compile("actua como|hacete pasar|fingi ser"));

    // Pedir datos personales: "pasame el DNI de...", "decime el email del titular".
    private static final Pattern DATOS_DE_TERCEROS = Pattern.compile(
            "(pasa|pasame|dame|decime|mostrame|mostra|quiero|necesito)\\b.*\\b(dni|documento|email|mail|correo|telefono|tarjeta)\\b");

    private Guardrails() {
    }

    /** El resultado de revisar una pregunta. */
    public record Revision(boolean bloqueada, String motivo, String texto) {
        static Revision bloqueada(String motivo) {
            return new Revision(true, motivo, null);
        }

        static Revision aceptada(String texto) {
            return new Revision(false, null, texto);
        }
    }

    /** Revisa y limpia la pregunta antes de mandarla al modelo. */
    public static Revision revisarEntrada(String pregunta) {
        String texto = pregunta == null ? "" : pregunta.strip();
        if (texto.isEmpty()) {
            return Revision.bloqueada("vacia");
        }
        if (texto.length() > LARGO_MAXIMO) {
            return Revision.bloqueada("demasiado-larga");
        }
        String normalizada = Texto.normalizar(texto);
        for (Pattern p : INYECCION) {
            if (p.matcher(normalizada).find()) {
                return Revision.bloqueada("inyeccion");
            }
        }
        if (DATOS_DE_TERCEROS.matcher(normalizada).find()) {
            return Revision.bloqueada("datos-personales");
        }
        return Revision.aceptada(enmascarar(texto));
    }

    /** Reemplaza tarjetas, DNI y emails por marcas: esos datos nunca llegan al modelo. */
    public static String enmascarar(String texto) {
        String limpio = TARJETA.matcher(texto).replaceAll(TARJETA_OCULTA);
        limpio = EMAIL.matcher(limpio).replaceAll(EMAIL_OCULTO);
        return DNI.matcher(limpio).replaceAll(DNI_OCULTO);
    }

    /** true si la respuesta del modelo trae datos personales y no se puede mostrar. */
    public static boolean salidaConDatosPersonales(String respuesta) {
        String texto = respuesta == null ? "" : respuesta;
        return TARJETA.matcher(texto).find() || DNI.matcher(texto).find() || EMAIL.matcher(texto).find();
    }
}
