package com.palco.eventos.negocio.externos;

import java.nio.charset.StandardCharsets;

/**
 * Contenidos para los tests de las imágenes: el comienzo real de cada formato
 * aceptado (su firma y el primer bloque) y un texto disfrazado de imagen.
 */
final class ImagenesDePrueba {

    /** Un JPG: la firma FF D8 FF y el comienzo de un bloque JFIF. */
    static final byte[] JPG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F' };

    /** Un PNG: sus 8 bytes de firma y el comienzo del bloque IHDR. */
    static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R' };

    /** Un WEBP: "RIFF", el tamaño (en little endian), "WEBP" y el comienzo del bloque VP8. */
    static final byte[] WEBP = { 'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' ' };

    /** Un .txt al que le cambiaron la extensión: es lo que manda la colección de Postman. */
    static final byte[] TEXTO = "no es una imagen de verdad".getBytes(StandardCharsets.UTF_8);

    private ImagenesDePrueba() {
    }

    /** Un JPG de exactamente ese tamaño: la firma y después ceros. */
    static byte[] jpgDe(int tamano) {
        byte[] contenido = new byte[tamano];
        System.arraycopy(JPG, 0, contenido, 0, JPG.length);
        return contenido;
    }
}
