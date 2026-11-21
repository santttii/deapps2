package com.palco.common.email;

import java.util.regex.Pattern;

import com.palco.common.Textos;

/**
 * Un email por mandar: a quién, con qué asunto y qué dice. El cuerpo es texto
 * plano, sin HTML: los avisos de Palco son cortos (un código de 6 dígitos, el
 * detalle de una compra) y así no hay que escapar nada ni mantener plantillas.
 *
 * El asunto no puede llevar saltos de línea: una cabecera SMTP termina en el
 * primer salto, y un asunto armado con texto que escribió alguien podría
 * agregar cabeceras propias (inyección de cabeceras). El constructor los
 * aplasta con {@link Textos#enUnaLinea}; el cuerpo no hace falta, que va
 * después de las cabeceras.
 */
public record Email(String para, String asunto, String cuerpo) {

    /**
     * Una dirección dentro de un texto, hasta la arroba: la primera letra (el
     * grupo 1) y el resto de lo que va antes de la arroba. No entran los
     * espacios ni los caracteres que separan direcciones o las encierran
     * ({@code <>()[],;:"}), así que en "Ana <ana@palco.test>" toma solo
     * "ana@".
     */
    private static final Pattern DIRECCION = Pattern.compile("([^\\s<>()\\[\\],;:\"@])[^\\s<>()\\[\\],;:\"@]*@");

    public Email {
        if (para == null || para.isBlank()) {
            throw new IllegalArgumentException("El email necesita un destinatario.");
        }
        para = para.strip();
        asunto = asunto == null ? "" : Textos.enUnaLinea(asunto).strip();
        cuerpo = cuerpo == null ? "" : cuerpo;
    }

    /** El destinatario para escribir en el log, enmascarado con {@link #enmascarar}. */
    String paraEnmascarado() {
        return enmascarar(para);
    }

    /**
     * El texto con cada dirección de email enmascarada: de lo que va antes de
     * la arroba queda la primera letra, y el dominio queda entero
     * ("ana.perez@palco.test" pasa a ser "a***@palco.test").
     *
     * Por qué: el email de una persona es un dato personal y el log no tiene
     * que guardarlo. Lo que queda alcanza para ver en el log que salió un
     * aviso y a qué dominio, sin que se pueda saber de quién era la cuenta. Es
     * la única regla de enmascarado del correo: la usan {@link CorreoAlLog} y
     * {@link CorreoSmtp}, para el destinatario y para el motivo de un envío
     * que falló, que puede repetir la dirección en la respuesta del servidor.
     */
    static String enmascarar(String texto) {
        return texto == null ? "" : DIRECCION.matcher(texto).replaceAll("$1***@");
    }
}
