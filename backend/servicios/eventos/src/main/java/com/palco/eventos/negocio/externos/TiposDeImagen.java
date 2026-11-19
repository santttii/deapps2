package com.palco.eventos.negocio.externos;

import java.util.Arrays;
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
 *
 * El tipo declarado ({@code Content-Type}) lo escribe quien sube: un .txt
 * renombrado a .png llega como {@code image/png}. Por eso también se mira el
 * contenido: los primeros bytes de cada formato son fijos (su "firma") y
 * tienen que ser los del tipo declarado. Si no, se rechaza con el mismo error
 * que un tipo no aceptado, y nunca se guarda ni se sirve como imagen algo que
 * no lo es. Todo eso lo hace {@link #validar}, que es lo único que llaman los
 * almacenes antes de guardar.
 */
public final class TiposDeImagen {

    /** 2 MB. Una foto de evento no necesita más, y acota lo que se puede subir de una vez. */
    public static final int MAXIMO_BYTES = 2 * 1024 * 1024;

    /** Lo que se le contesta a quien sube algo que no es JPG, PNG ni WEBP, sea por el tipo o por el contenido. */
    private static final String SOLO_JPG_PNG_O_WEBP = "La imagen tiene que ser JPG, PNG o WEBP.";

    // Las firmas de cada formato. JPG: FF D8 FF. PNG: los 8 bytes de su especificación.
    // WEBP es un archivo RIFF: "RIFF", 4 bytes con el tamaño (cualquier valor) y "WEBP".
    private static final byte[] FIRMA_JPG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
    private static final byte[] FIRMA_PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
    private static final byte[] FIRMA_RIFF = { 'R', 'I', 'F', 'F' };
    private static final byte[] FIRMA_WEBP = { 'W', 'E', 'B', 'P' };

    /** Media type aceptado → extensión con la que se guarda. */
    private static final Map<String, String> EXTENSIONES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private TiposDeImagen() {
    }

    /**
     * Valida una imagen subida y devuelve la extensión con la que se guarda:
     * que no esté vacía ni pase del máximo, que el tipo declarado sea uno de
     * los aceptados y que el contenido empiece con la firma de ese tipo. Lanza
     * {@code DatosInvalidos} en el campo {@code imagen} si algo de eso falla.
     */
    public static String validar(byte[] contenido, String tipo) {
        exigirTamanoValido(contenido);
        String extension = extensionDe(tipo);
        if (!tieneLaFirmaDe(extension, contenido)) {
            throw new ErroresDeNegocio.DatosInvalidos("imagen", SOLO_JPG_PNG_O_WEBP);
        }
        return extension;
    }

    /**
     * La extensión que le corresponde a ese media type. Lanza
     * {@code DatosInvalidos} si no se acepta.
     */
    private static String extensionDe(String tipo) {
        String limpio = tipo == null ? "" : tipo.strip().toLowerCase(Locale.ROOT);
        // Un navegador puede mandar "image/jpeg; charset=..." : se mira solo el tipo.
        int puntoYComa = limpio.indexOf(';');
        if (puntoYComa >= 0) {
            limpio = limpio.substring(0, puntoYComa).strip();
        }
        String extension = EXTENSIONES.get(limpio);
        if (extension == null) {
            throw new ErroresDeNegocio.DatosInvalidos("imagen", SOLO_JPG_PNG_O_WEBP);
        }
        return extension;
    }

    /** Si el contenido empieza con la firma del formato de esa extensión. Uno vacío o más corto que la firma, no. */
    static boolean tieneLaFirmaDe(String extension, byte[] contenido) {
        return switch (extension) {
            case "jpg" -> empiezaCon(contenido, 0, FIRMA_JPG);
            case "png" -> empiezaCon(contenido, 0, FIRMA_PNG);
            case "webp" -> empiezaCon(contenido, 0, FIRMA_RIFF) && empiezaCon(contenido, 8, FIRMA_WEBP);
            default -> false;
        };
    }

    /** Si desde la posición {@code desde} el contenido tiene exactamente esos bytes. */
    private static boolean empiezaCon(byte[] contenido, int desde, byte[] firma) {
        return contenido != null && contenido.length >= desde + firma.length
                && Arrays.equals(contenido, desde, desde + firma.length, firma, 0, firma.length);
    }

    /**
     * El media type que le corresponde a una extensión de las aceptadas
     * ({@code jpg} → {@code image/jpeg}). Es el que se guarda y con el que se
     * devuelve la imagen, normalizado: sin parámetros ni mayúsculas.
     */
    public static String tipoDe(String extension) {
        return EXTENSIONES.entrySet().stream()
                .filter(e -> e.getValue().equals(extension))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new ErroresDeNegocio.DatosInvalidos("imagen", SOLO_JPG_PNG_O_WEBP));
    }

    /** Comprueba el tamaño. Lanza {@code DatosInvalidos} si está vacía o pasa el máximo. */
    private static void exigirTamanoValido(byte[] contenido) {
        if (contenido == null || contenido.length == 0) {
            throw new ErroresDeNegocio.DatosInvalidos("imagen", "No llegó ninguna imagen.");
        }
        if (contenido.length > MAXIMO_BYTES) {
            throw new ErroresDeNegocio.DatosInvalidos("imagen",
                    "La imagen no puede pasar de " + (MAXIMO_BYTES / (1024 * 1024)) + " MB.");
        }
    }
}
