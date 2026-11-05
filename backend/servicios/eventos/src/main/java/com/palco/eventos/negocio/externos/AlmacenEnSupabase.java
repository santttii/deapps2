package com.palco.eventos.negocio.externos;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.Textos;

/**
 * Sube las imágenes a Supabase Storage por su API REST, la misma que usa el
 * SDK de Supabase: {@code POST /storage/v1/object/{bucket}/{archivo}} con la
 * clave de servicio en {@code Authorization}. No hace falta ninguna
 * dependencia nueva: alcanza el cliente de JAX-RS que ya trae WildFly.
 *
 * Se configura por variables de entorno, que van en {@code backend/.env} (no
 * se versiona) y en el hosting:
 *
 * <ul>
 * <li>{@code PALCO_SUPABASE_URL}: la del proyecto, por ejemplo
 * {@code https://xxxxxxxx.supabase.co}. Sin esto no se puede subir nada, y
 * {@link FabricaDeAlmacen} elige {@link AlmacenNoConfigurado};</li>
 * <li>{@code PALCO_SUPABASE_BUCKET}: el bucket, {@code eventos} por defecto.
 * Tiene que estar creado y ser público para que la URL se pueda mostrar sin
 * firmar;</li>
 * <li>{@code PALCO_SUPABASE_CLAVE}: la {@code service_role key} del proyecto.
 * Es secreta: permite escribir en el storage, así que vive solo en el
 * servidor y nunca viaja al frontend.</li>
 * </ul>
 *
 * El nombre del archivo lo pone este componente, con un UUID: así el nombre
 * que subió alguien no decide la ruta (no se puede escribir fuera del bucket
 * con un {@code ../}) ni dos subidas se pisan.
 *
 * {@code @ApplicationScoped}: la configuración se lee una vez y un solo
 * cliente HTTP para todo el servicio. {@code @Typed} hace que solo se pueda
 * inyectar por su clase y no como {@link AlmacenDeImagenes}: ese lo entrega
 * {@link FabricaDeAlmacen}.
 */
@ApplicationScoped
@Typed(AlmacenEnSupabase.class)
public class AlmacenEnSupabase implements AlmacenDeImagenes {

    /** El valor de {@code PALCO_IMAGENES} que elige esta implementación. */
    public static final String NOMBRE = "supabase";

    static final String BUCKET_POR_DEFECTO = "eventos";
    static final String RUTA_DE_SUBIDA = "/storage/v1/object/%s/%s";
    static final String RUTA_PUBLICA = "/storage/v1/object/public/%s/%s";

    private static final Logger LOG = Logger.getLogger(AlmacenEnSupabase.class.getName());
    private static final String NO_RESPONDE = "No se pudo guardar la imagen. Probá de nuevo.";

    /** No son privados para que un test los fije sin variables de entorno. */
    String url = entorno("PALCO_SUPABASE_URL", null);
    String bucket = entorno("PALCO_SUPABASE_BUCKET", BUCKET_POR_DEFECTO);
    String clave = entorno("PALCO_SUPABASE_CLAVE", null);

    private Client cliente;

    /** Si hay proyecto y clave configurados. Lo mira {@link FabricaDeAlmacen} antes de elegir. */
    public static boolean estaConfigurado() {
        return entorno("PALCO_SUPABASE_URL", null) != null && entorno("PALCO_SUPABASE_CLAVE", null) != null;
    }

    @Override
    public String guardar(byte[] contenido, String tipo, String nombreOriginal) {
        TiposDeImagen.exigirTamanoValido(contenido);
        // La extensión sale del media type y no del nombre que llegó: el nombre lo escribió alguien.
        String archivo = UUID.randomUUID() + "." + TiposDeImagen.extensionDe(tipo);

        int estado = subir(archivo, contenido, tipo);
        // Supabase responde 200 con el path al crear el objeto.
        if (estado == 200 || estado == 201) {
            return url + String.format(RUTA_PUBLICA, bucket, archivo);
        }
        LOG.warning("Supabase Storage respondió " + estado + " al subir una imagen al bucket "
                + Textos.enUnaLinea(bucket) + "."
                + (estado == 400 || estado == 404
                        ? " Suele ser que el bucket no existe: hay que crearlo y marcarlo público."
                        : "")
                + (estado == 401 || estado == 403 ? " Revisá PALCO_SUPABASE_CLAVE (la service_role key)." : ""));
        throw new ErroresDeNegocio.SinRespuesta(NO_RESPONDE);
    }

    /** 2 segundos para conectar y 10 para subir: una imagen tarda más que una consulta. */
    @PostConstruct
    void abrirCliente() {
        cliente = ClientBuilder.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @PreDestroy
    void cerrarCliente() {
        cliente.close();
    }

    /** La llamada HTTP. No es privada para que un test la reemplace y no necesite Supabase. */
    int subir(String archivo, byte[] contenido, String tipo) {
        try (Response respuesta = cliente.target(url + String.format(RUTA_DE_SUBIDA, bucket, archivo))
                .request()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + clave)
                // Sin esto, repetir el mismo nombre daría 409; con un UUID no debería pasar nunca.
                .header("x-upsert", "false")
                .post(Entity.entity(contenido, tipo))) {
            return respuesta.getStatus();
        } catch (ProcessingException e) {
            LOG.warning("Supabase Storage no respondió al subir una imagen: " + e.getMessage());
            throw new ErroresDeNegocio.SinRespuesta(NO_RESPONDE);
        }
    }

    private static String entorno(String variable, String porDefecto) {
        String valor = System.getenv(variable);
        return valor == null || valor.isBlank() ? porDefecto : valor.strip();
    }
}
