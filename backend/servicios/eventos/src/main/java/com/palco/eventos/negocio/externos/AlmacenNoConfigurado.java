package com.palco.eventos.negocio.externos;

import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

import com.palco.common.ErroresDeNegocio;

/**
 * Lo que se usa cuando no hay almacén configurado: rechaza la subida con un
 * error que explica qué falta. No guarda la imagen en ningún lado.
 *
 * Es a propósito que no haya un respaldo "en disco" o "en memoria": una imagen
 * que se pierde al redesplegar deja eventos con la foto rota, y eso es peor
 * que no dejar subirla. El alta del evento sigue funcionando: sin imagen usa
 * la foto de la categoría, que es lo que hacía antes de que existiera la subida.
 */
@ApplicationScoped
public class AlmacenNoConfigurado implements AlmacenDeImagenes {

    /** El valor de {@code PALCO_IMAGENES} que elige esta implementación. */
    public static final String NOMBRE = "ninguno";

    private static final Logger LOG = Logger.getLogger(AlmacenNoConfigurado.class.getName());

    @Override
    public String guardar(byte[] contenido, String tipo, String nombreOriginal) {
        LOG.warning("Se intentó subir una imagen pero no hay almacén configurado: faltan"
                + " PALCO_SUPABASE_URL y PALCO_SUPABASE_CLAVE.");
        throw new ErroresDeNegocio.SinRespuesta(
                "Todavía no se pueden subir imágenes: falta configurar el almacenamiento.");
    }
}
