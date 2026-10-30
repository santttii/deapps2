package com.palco.eventos.negocio.externos;

import java.util.Locale;
import java.util.Map;

import com.palco.common.ErroresDeNegocio;

/**
 * Qué imágenes se aceptan y con qué extensión se guardan. Está aparte de las
 * implementaciones del almacén para que la regla sea una sola: la misma valga
 * con Supabase o con cualquier otro lugar donde se guarden más adelante.
 *
 * La lista es corta a propósito: son los formatos que muestra cualquier
 * navegador. No se aceptan SVG, que puede traer scripts, ni formatos que el
 * navegador tendría que convertir.
 */
public final class TiposDeImagen {

    /** 2 MB. Una foto de evento no necesita más, y acota lo que se puede subir de una vez. */
    public static final int MAXIMO_BYTES = 2 * 1024 * 1024;

    /** Media type aceptado → extensión con la que se guarda. */
    private static final Map<String, String> EXTENSIONES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private TiposDeImagen() {
    }

    /**
     * La extensión que le corresponde a ese media type. Lanza
     * {@code DatosInvalidos} si no se acepta.
     */
    public static String extensionDe(String tipo) {
        String limpio = tipo == null ? "" : tipo.strip().toLowerCase(Locale.ROOT);
        // Un navegador puede mandar "image/jpeg; charset=..." : se mira solo el tipo.
        int puntoYComa = limpio.indexOf(';');
        if (puntoYComa >= 0) {
            limpio = limpio.substring(0, puntoYComa).strip();
        }
        String extension = EXTENSIONES.get(limpio);
        if (extension == null) {
            throw new ErroresDeNegocio.DatosInvalidos("imagen",
                    "La imagen tiene que ser JPG, PNG o WEBP.");
        }
        return extension;
    }

    /** Comprueba el tamaño. Lanza {@code DatosInvalidos} si está vacía o pasa el máximo. */
    public static void exigirTamanoValido(byte[] contenido) {
        if (contenido == null || contenido.length == 0) {
            throw new ErroresDeNegocio.DatosInvalidos("imagen", "No llegó ninguna imagen.");
        }
        if (contenido.length > MAXIMO_BYTES) {
            throw new ErroresDeNegocio.DatosInvalidos("imagen",
                    "La imagen no puede pasar de " + (MAXIMO_BYTES / (1024 * 1024)) + " MB.");
        }
    }
}
