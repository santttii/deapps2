package com.palco.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import jakarta.ejb.ApplicationException;

import org.junit.jupiter.api.Test;

class ErroresDeNegocioTest {

    @Test
    void cadaExcepcionTraeSuTipoPorDefecto() {
        assertEquals("datos-invalidos", new ErroresDeNegocio.DatosInvalidos("dni", "x").tipo());
        assertEquals("no-autenticado", new ErroresDeNegocio.NoAutenticado("x").tipo());
        assertEquals("pago-rechazado", new ErroresDeNegocio.PagoRechazado("x").tipo());
        assertEquals("sin-permiso", new ErroresDeNegocio.SinPermiso("x").tipo());
        assertEquals("no-encontrado", new ErroresDeNegocio.NoEncontrado("x").tipo());
        assertEquals("conflicto", new ErroresDeNegocio.Conflicto("x").tipo());
        assertEquals("regla-incumplida", new ErroresDeNegocio.ReglaIncumplida("x").tipo());
        assertEquals("sin-respuesta", new ErroresDeNegocio.SinRespuesta("x").tipo());
    }

    @Test
    void elTipoSePuedeElegirYElMensajeNoCambia() {
        ErroresDeNegocio.Conflicto conflicto = new ErroresDeNegocio.Conflicto("cupo-agotado", "No quedan entradas.");
        assertEquals("cupo-agotado", conflicto.tipo());
        assertEquals("No quedan entradas.", conflicto.getMessage());

        assertEquals("identidad-no-verificada",
                new ErroresDeNegocio.SinPermiso("identidad-no-verificada", "x").tipo());
        assertEquals("dni-repetido", new ErroresDeNegocio.ReglaIncumplida("dni-repetido", "x").tipo());
    }

    @Test
    void datosInvalidosGuardaElCampoConSuMensaje() {
        ErroresDeNegocio.DatosInvalidos error = new ErroresDeNegocio.DatosInvalidos("dni", "El DNI debe tener 7 u 8 dígitos.");
        assertEquals(Map.of("dni", "El DNI debe tener 7 u 8 dígitos."), error.campos());
        assertEquals("El DNI debe tener 7 u 8 dígitos.", error.getMessage());
    }

    /** Sin la anotación el contenedor las trataría como fallas del sistema: las envuelve y destruye el bean. */
    @Test
    void todasDeshacenLaTransaccion() {
        List<Class<?>> clases = List.of(
                ErroresDeNegocio.DatosInvalidos.class,
                ErroresDeNegocio.NoAutenticado.class,
                ErroresDeNegocio.PagoRechazado.class,
                ErroresDeNegocio.SinPermiso.class,
                ErroresDeNegocio.NoEncontrado.class,
                ErroresDeNegocio.Conflicto.class,
                ErroresDeNegocio.ReglaIncumplida.class,
                ErroresDeNegocio.SinRespuesta.class);
        for (Class<?> clase : clases) {
            ApplicationException anotacion = clase.getDeclaredAnnotation(ApplicationException.class);
            assertNotNull(anotacion, clase.getSimpleName() + " no tiene @ApplicationException");
            assertTrue(anotacion.rollback(), clase.getSimpleName() + " no deshace la transacción");
        }
    }
}
