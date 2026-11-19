package com.palco.eventos.negocio.externos;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import com.palco.eventos.datos.ImagenRepository;
import com.palco.eventos.dominio.Imagen;

/**
 * Guarda las fotos de los eventos en la propia base de Palco (esquema
 * {@code eventos}, tabla {@code imagen}) y las sirve desde
 * {@code GET /api/eventos/imagenes/{id}}. Es lo que usa {@link FabricaDeAlmacen}
 * cuando no hay Supabase configurado: así subir una foto funciona apenas se
 * levanta el sistema con {@code docker compose}, sin configurar nada.
 *
 * A diferencia de un disco, la base no es efímera: la foto dura lo mismo que el
 * evento y se respalda con él. El costo es que cada foto ocupa la base (hasta
 * {@link TiposDeImagen#MAXIMO_BYTES}), algo razonable para el volumen de un
 * organizador; para muchas fotos conviene Supabase Storage.
 *
 * Devuelve una URL relativa ({@code /api/eventos/imagenes/…}): el frontend le
 * antepone la dirección de la API, que no siempre es la misma que la de la web.
 *
 * {@code @Typed} hace que solo se pueda inyectar por su clase y no como
 * {@link AlmacenDeImagenes}: ese lo entrega {@link FabricaDeAlmacen}.
 */
@ApplicationScoped
@Typed(AlmacenEnBaseDeDatos.class)
public class AlmacenEnBaseDeDatos implements AlmacenDeImagenes {

    /** El valor de {@code PALCO_IMAGENES} que elige esta implementación. */
    public static final String NOMBRE = "base";

    /** Donde se sirven: {@code EventosResource} responde en esta ruta. */
    public static final String RUTA_PUBLICA = "/api/eventos/imagenes/";

    /** Un id válido: UUID y extensión aceptada. Cualquier otra cosa no se busca en la base. */
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.(jpg|png|webp)");

    @Inject
    ImagenRepository imagenes;

    /** No es privado para que el test fije la hora. */
    Clock reloj = Clock.systemUTC();

    @Override
    public String guardar(byte[] contenido, String tipo, String nombreOriginal) {
        // Tamaño, tipo y que el contenido sea de verdad de ese tipo. La extensión sale del media type
        // y no del nombre que llegó: el nombre lo escribió alguien.
        String extension = TiposDeImagen.validar(contenido, tipo);
        String id = UUID.randomUUID() + "." + extension;
        imagenes.guardar(new Imagen(id, TiposDeImagen.tipoDe(extension), contenido, LocalDateTime.now(reloj)));
        return RUTA_PUBLICA + id;
    }

    /** La imagen con ese id. Vacío si no existe o si el id no tiene la forma de uno de los nuestros. */
    public Optional<Imagen> leer(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return Optional.empty();
        }
        return imagenes.buscar(id);
    }
}
