package com.palco.eventos.negocio.externos;

import java.util.Locale;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Factory: decide dónde se guardan las imágenes. Es el único lugar que conoce
 * las dos implementaciones; el recurso REST pide un {@link AlmacenDeImagenes}
 * con {@code @Inject} y recibe el que salga de acá. Mismo mecanismo que
 * {@code FabricaDeCobros} en Ventas y {@code FabricaDeCorreos} en common.
 *
 * Lo elige la variable {@code PALCO_IMAGENES}: {@code supabase} o
 * {@code ninguno}. Sin ella se mira si hay proyecto y clave configurados
 * ({@code PALCO_SUPABASE_URL} y {@code PALCO_SUPABASE_CLAVE}): con los dos se
 * sube a Supabase, y sin ellos se rechaza la subida, porque no habría a dónde
 * subirla.
 *
 * {@code @ApplicationScoped}: se elige una sola vez y vale hasta que se
 * redespliega el servicio.
 */
@ApplicationScoped
public class FabricaDeAlmacen {

    private static final Logger LOG = Logger.getLogger(FabricaDeAlmacen.class.getName());

    static final String VARIABLE = "PALCO_IMAGENES";

    @Produces
    @ApplicationScoped
    AlmacenDeImagenes almacen(AlmacenEnSupabase supabase, AlmacenNoConfigurado ninguno) {
        return elegir(System.getenv(VARIABLE), AlmacenEnSupabase.estaConfigurado(), supabase, ninguno);
    }

    /** Aparte del método de arriba para poder probarlo sin servidor ni variables de entorno. */
    static AlmacenDeImagenes elegir(String elegido, boolean supabaseConfigurado,
            AlmacenDeImagenes supabase, AlmacenDeImagenes ninguno) {
        String nombre = elegido == null ? "" : elegido.strip().toLowerCase(Locale.ROOT);

        if (nombre.equals(AlmacenEnSupabase.NOMBRE)) {
            if (!supabaseConfigurado) {
                LOG.warning("Imágenes: " + VARIABLE + " vale \"" + AlmacenEnSupabase.NOMBRE + "\" pero faltan"
                        + " PALCO_SUPABASE_URL o PALCO_SUPABASE_CLAVE. Se respeta la elección y cada subida"
                        + " va a fallar con un WARNING.");
            }
            return supabase;
        }
        if (nombre.equals(AlmacenNoConfigurado.NOMBRE)) {
            LOG.info("Imágenes: la subida está deshabilitada (" + VARIABLE + "="
                    + AlmacenNoConfigurado.NOMBRE + "). El alta usa la foto de la categoría.");
            return ninguno;
        }
        if (!nombre.isEmpty()) {
            LOG.warning("Imágenes: " + VARIABLE + " vale \"" + elegido + "\" y ese valor no existe. Los posibles"
                    + " son \"" + AlmacenEnSupabase.NOMBRE + "\" y \"" + AlmacenNoConfigurado.NOMBRE
                    + "\". Se decide por la configuración de Supabase.");
        }
        if (supabaseConfigurado) {
            LOG.info("Imágenes: se suben a Supabase Storage.");
            return supabase;
        }
        LOG.warning("Imágenes: sin PALCO_SUPABASE_URL y PALCO_SUPABASE_CLAVE no se pueden subir imágenes."
                + " El alta sigue funcionando y usa la foto de la categoría.");
        return ninguno;
    }
}
