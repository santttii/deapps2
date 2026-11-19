package com.palco.eventos.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;

/**
 * Qué se acepta como imagen: el tipo declarado tiene que ser JPG, PNG o WEBP y,
 * además, el contenido tiene que empezar con la firma de ese tipo.
 */
class TiposDeImagenTest {

    @Test
    void cadaTipoAceptadoPasaConSuFirmaYDaSuExtension() {
        assertEquals("jpg", TiposDeImagen.validar(ImagenesDePrueba.JPG, "image/jpeg"));
        assertEquals("png", TiposDeImagen.validar(ImagenesDePrueba.PNG, "image/png"));
        assertEquals("webp", TiposDeImagen.validar(ImagenesDePrueba.WEBP, "image/webp"));
    }

    @Test
    void elTipoSeMiraSinMayusculasNiParametros() {
        assertEquals("png", TiposDeImagen.validar(ImagenesDePrueba.PNG, "IMAGE/PNG; charset=binary"));
    }

    @Test
    void unTextoRenombradoAPngSeRechazaConElMismoErrorQueUnTipoNoAceptado() {
        ErroresDeNegocio.DatosInvalidos disfrazado = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> TiposDeImagen.validar(ImagenesDePrueba.TEXTO, "image/png"));
        ErroresDeNegocio.DatosInvalidos tipoNoAceptado = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> TiposDeImagen.validar(ImagenesDePrueba.TEXTO, "image/svg+xml"));

        // Los dos salen como 400 /problemas/datos-invalidos con el mismo errores.imagen.
        assertEquals(tipoNoAceptado.tipo(), disfrazado.tipo());
        assertEquals(tipoNoAceptado.campos(), disfrazado.campos());
        assertEquals(Map.of("imagen", "La imagen tiene que ser JPG, PNG o WEBP."), disfrazado.campos());
    }

    @Test
    void unaImagenDeOtroFormatoQueElDeclaradoSeRechaza() {
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> TiposDeImagen.validar(ImagenesDePrueba.PNG, "image/jpeg"));
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> TiposDeImagen.validar(ImagenesDePrueba.JPG, "image/webp"));
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> TiposDeImagen.validar(ImagenesDePrueba.WEBP, "image/png"));
    }

    @Test
    void unRiffQueNoEsWebpSeRechaza() {
        // Un WAV también empieza con RIFF: lo que dice que es WEBP son los bytes 8 a 11.
        byte[] wav = { 'R', 'I', 'F', 'F', 0x24, 0, 0, 0, 'W', 'A', 'V', 'E' };

        assertThrows(ErroresDeNegocio.DatosInvalidos.class, () -> TiposDeImagen.validar(wav, "image/webp"));
    }

    @Test
    void unaImagenVaciaSeRechazaYUnaMasCortaQueSuFirmaTambien() {
        ErroresDeNegocio.DatosInvalidos vacia = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> TiposDeImagen.validar(new byte[0], "image/png"));
        assertEquals(Map.of("imagen", "No llegó ninguna imagen."), vacia.campos());
        assertThrows(ErroresDeNegocio.DatosInvalidos.class, () -> TiposDeImagen.validar(null, "image/png"));

        // Más cortas que su firma: se rechazan sin leer fuera del contenido.
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> TiposDeImagen.validar(new byte[] { (byte) 0xFF, (byte) 0xD8 }, "image/jpeg"));
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> TiposDeImagen.validar(new byte[] { 'R', 'I', 'F', 'F' }, "image/webp"));
        assertFalse(TiposDeImagen.tieneLaFirmaDe("png", new byte[0]));
        assertFalse(TiposDeImagen.tieneLaFirmaDe("jpg", null));
    }
}
