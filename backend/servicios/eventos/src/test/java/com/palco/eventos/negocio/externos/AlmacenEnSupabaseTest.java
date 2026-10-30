package com.palco.eventos.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;

/**
 * La subida de imágenes a Supabase Storage: qué URL devuelve, qué rechaza y
 * qué hace con cada respuesta. Sin red.
 */
class AlmacenEnSupabaseTest {

    private static final byte[] UNA_IMAGEN = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3 };

    /** Reemplaza la llamada HTTP: así el test no necesita un proyecto de Supabase. */
    private static final class SupabaseSinRed extends AlmacenEnSupabase {
        final List<String> subidos = new ArrayList<>();
        int estado = 200;

        SupabaseSinRed() {
            url = "https://proyecto.supabase.co";
            bucket = "eventos";
            clave = "service-role-de-prueba";
        }

        @Override
        int subir(String archivo, byte[] contenido, String tipo) {
            subidos.add(archivo + " " + tipo + " " + contenido.length);
            return estado;
        }
    }

    @Test
    void unaImagenSubidaDevuelveSuUrlPublica() {
        SupabaseSinRed almacen = new SupabaseSinRed();

        String url = almacen.guardar(UNA_IMAGEN, "image/jpeg", "foto.jpg");

        assertTrue(url.startsWith("https://proyecto.supabase.co/storage/v1/object/public/eventos/"), url);
        assertTrue(url.endsWith(".jpg"), url);
        assertEquals(1, almacen.subidos.size());
    }

    @Test
    void elNombreDelArchivoLoPoneElAlmacenYNoQuienSube() {
        // Si la ruta la decidiera el nombre que llegó, un "../" escribiría fuera del bucket.
        SupabaseSinRed almacen = new SupabaseSinRed();

        String url = almacen.guardar(UNA_IMAGEN, "image/png", "../../otro-bucket/mio.png");

        assertTrue(url.endsWith(".png"), url);
        assertTrue(!url.contains(".."), url);
        // Dos subidas no se pisan: cada una lleva su UUID.
        String otra = almacen.guardar(UNA_IMAGEN, "image/png", "mio.png");
        assertTrue(!url.equals(otra));
    }

    @Test
    void laExtensionSaleDelTipoYNoDelNombre() {
        SupabaseSinRed almacen = new SupabaseSinRed();

        assertTrue(almacen.guardar(UNA_IMAGEN, "image/webp", "foto.jpg").endsWith(".webp"));
    }

    @Test
    void elTipoConParametrosSeAceptaIgual() {
        // Un navegador puede mandar "image/jpeg; charset=UTF-8".
        SupabaseSinRed almacen = new SupabaseSinRed();

        assertTrue(almacen.guardar(UNA_IMAGEN, "image/jpeg; charset=UTF-8", null).endsWith(".jpg"));
    }

    @Test
    void unTipoQueNoSeAceptaEsUnDatoInvalidoYNoSeSube() {
        SupabaseSinRed almacen = new SupabaseSinRed();

        for (String tipo : new String[] { "image/svg+xml", "application/pdf", "text/html", null, "" }) {
            assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                    () -> almacen.guardar(UNA_IMAGEN, tipo, "x"));
        }
        assertTrue(almacen.subidos.isEmpty());
    }

    @Test
    void unaImagenVaciaOMasGrandeQueElMaximoEsUnDatoInvalido() {
        SupabaseSinRed almacen = new SupabaseSinRed();

        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> almacen.guardar(new byte[0], "image/jpeg", "x"));
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> almacen.guardar(null, "image/jpeg", "x"));
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> almacen.guardar(new byte[TiposDeImagen.MAXIMO_BYTES + 1], "image/jpeg", "x"));
        assertTrue(almacen.subidos.isEmpty());
    }

    @Test
    void justoEnElMaximoSeAcepta() {
        SupabaseSinRed almacen = new SupabaseSinRed();

        assertTrue(almacen.guardar(new byte[TiposDeImagen.MAXIMO_BYTES], "image/jpeg", "x").endsWith(".jpg"));
    }

    @Test
    void siSupabaseRechazaLaSubidaNoHayUrlYEsSinRespuesta() {
        // Sin URL no se puede dar de alta el evento con esa foto: tiene que fallar, no inventar una.
        for (int estado : new int[] { 400, 401, 403, 404, 500 }) {
            SupabaseSinRed almacen = new SupabaseSinRed();
            almacen.estado = estado;

            assertThrows(ErroresDeNegocio.SinRespuesta.class,
                    () -> almacen.guardar(UNA_IMAGEN, "image/jpeg", "x"), "estado " + estado);
        }
    }

    @Test
    void unDoscientosUnoTambienEsUnaSubidaHecha() {
        SupabaseSinRed almacen = new SupabaseSinRed();
        almacen.estado = 201;

        assertTrue(almacen.guardar(UNA_IMAGEN, "image/jpeg", "x").contains("/public/eventos/"));
    }

    @Test
    void sinAlmacenConfiguradoLaSubidaSeRechazaConUnMensajeQueExplicaQueFalta() {
        AlmacenNoConfigurado ninguno = new AlmacenNoConfigurado();

        ErroresDeNegocio.SinRespuesta error = assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> ninguno.guardar(UNA_IMAGEN, "image/jpeg", "x"));

        assertTrue(error.getMessage().contains("configurar"), error.getMessage());
    }

    // --- Qué implementación se elige ---

    @Test
    void sinConfiguracionYSinSupabaseLaSubidaQuedaDeshabilitada() {
        AlmacenDeImagenes supabase = new SupabaseSinRed();
        AlmacenDeImagenes ninguno = new AlmacenNoConfigurado();

        assertSame(ninguno, FabricaDeAlmacen.elegir(null, false, supabase, ninguno));
        assertSame(supabase, FabricaDeAlmacen.elegir(null, true, supabase, ninguno));
    }

    @Test
    void elValorElegidoSeRespetaYUnoDesconocidoDecidePorLaConfiguracion() {
        AlmacenDeImagenes supabase = new SupabaseSinRed();
        AlmacenDeImagenes ninguno = new AlmacenNoConfigurado();

        assertSame(supabase, FabricaDeAlmacen.elegir("supabase", false, supabase, ninguno));
        assertSame(ninguno, FabricaDeAlmacen.elegir("ninguno", true, supabase, ninguno));
        assertSame(supabase, FabricaDeAlmacen.elegir("  SUPABASE ", false, supabase, ninguno));
        assertSame(supabase, FabricaDeAlmacen.elegir("s3", true, supabase, ninguno));
        assertSame(ninguno, FabricaDeAlmacen.elegir("s3", false, supabase, ninguno));
    }
}
