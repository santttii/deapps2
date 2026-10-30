package com.palco.eventos.negocio.externos;

/**
 * Dónde se guardan las imágenes de los eventos, visto desde el alta y la
 * edición. Eventos depende de esta interfaz y no de quién las guarda, igual
 * que Ventas con {@code Cobros}.
 *
 * Tiene dos implementaciones y las elige {@link FabricaDeAlmacen}:
 * {@link AlmacenEnSupabase}, que las sube a Supabase Storage, y
 * {@link AlmacenNoConfigurado}, que rechaza la subida con un error que explica
 * qué falta configurar. No hay implementación "en disco" a propósito: el
 * hosting que se piensa usar (Render, Railway) tiene disco efímero, así que un
 * archivo subido se perdería en el próximo despliegue.
 */
public interface AlmacenDeImagenes {

    /**
     * Guarda la imagen y devuelve la URL pública con la que el frontend la
     * muestra. El nombre del archivo lo decide la implementación; quien llama
     * solo aporta el contenido y su tipo.
     *
     * Lanza {@code ErroresDeNegocio.DatosInvalidos} si el tipo no se acepta o
     * la imagen es demasiado grande, y {@code ErroresDeNegocio.SinRespuesta}
     * si el almacén no contesta: sin URL no se puede dar de alta el evento.
     *
     * @param contenido  los bytes de la imagen
     * @param tipo       el media type declarado ({@code image/jpeg}, {@code image/png}, {@code image/webp})
     * @param nombreOriginal el nombre con el que llegó, solo para elegir la extensión; puede ser null
     */
    String guardar(byte[] contenido, String tipo, String nombreOriginal);
}
