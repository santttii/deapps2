package com.palco.eventos.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.eventos.datos.ImagenRepository;
import com.palco.eventos.dominio.Imagen;

/** El almacén en la base, con el repositorio reemplazado por un mapa. */
class AlmacenEnBaseDeDatosTest {

    private static final byte[] UNA_IMAGEN = ImagenesDePrueba.JPG;

    private final Map<String, Imagen> guardadas = new HashMap<>();
    private AlmacenEnBaseDeDatos almacen;

    @BeforeEach
    void preparar() {
        almacen = new AlmacenEnBaseDeDatos();
        almacen.imagenes = new ImagenRepository() {
            @Override
            public void guardar(Imagen imagen) {
                guardadas.put(imagen.getId(), imagen);
            }

            @Override
            public Optional<Imagen> buscar(String id) {
                return Optional.ofNullable(guardadas.get(id));
            }
        };
        almacen.reloj = Clock.fixed(Instant.parse("2026-11-18T15:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void guardaLaImagenYDevuelveSuRutaPublica() {
        String url = almacen.guardar(UNA_IMAGEN, "image/jpeg", "foto.png");

        assertTrue(url.startsWith("/api/eventos/imagenes/"), url);
        // La extensión sale del tipo, no del nombre del archivo.
        assertTrue(url.endsWith(".jpg"), url);
        String id = url.substring(url.lastIndexOf('/') + 1);
        Imagen guardada = guardadas.get(id);
        assertEquals("image/jpeg", guardada.getTipo());
        assertEquals(UNA_IMAGEN.length, guardada.getTamano());
        assertEquals(LocalDateTime.of(2026, 11, 18, 15, 0), guardada.getSubida());
    }

    @Test
    void elTipoSeGuardaNormalizado() {
        String url = almacen.guardar(ImagenesDePrueba.PNG, "IMAGE/PNG; charset=binary", null);

        String id = url.substring(url.lastIndexOf('/') + 1);
        assertEquals("image/png", guardadas.get(id).getTipo());
    }

    @Test
    void loGuardadoSeLeePorSuId() {
        String url = almacen.guardar(ImagenesDePrueba.WEBP, "image/webp", null);
        String id = url.substring(url.lastIndexOf('/') + 1);

        assertArrayEquals(ImagenesDePrueba.WEBP, almacen.leer(id).orElseThrow().getContenido());
    }

    @Test
    void unIdQueNoTieneLaFormaDeLosNuestrosNiSeBusca() {
        guardadas.put("../evento.jpg", new Imagen("../evento.jpg", "image/jpeg", UNA_IMAGEN, LocalDateTime.now()));

        assertTrue(almacen.leer("../evento.jpg").isEmpty());
        assertTrue(almacen.leer("no-existe").isEmpty());
        assertTrue(almacen.leer(null).isEmpty());
        assertTrue(almacen.leer("3f9a1c2b-0000-4000-8000-000000000000.jpg").isEmpty());
    }

    @Test
    void rechazaTiposNoAceptadosContenidosDisfrazadosYTamanosFueraDeRango() {
        assertThrows(ErroresDeNegocio.DatosInvalidos.class, () -> almacen.guardar(UNA_IMAGEN, "image/svg+xml", null));
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> almacen.guardar(ImagenesDePrueba.TEXTO, "image/png", null));
        assertThrows(ErroresDeNegocio.DatosInvalidos.class, () -> almacen.guardar(new byte[0], "image/jpeg", null));
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> almacen.guardar(new byte[TiposDeImagen.MAXIMO_BYTES + 1], "image/jpeg", null));
        assertTrue(guardadas.isEmpty());
    }
}
