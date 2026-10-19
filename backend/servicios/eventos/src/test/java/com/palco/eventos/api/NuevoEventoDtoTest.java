package com.palco.eventos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.palco.eventos.api.Dtos.NuevoEventoDto;
import com.palco.eventos.api.Dtos.NuevoSectorDto;

/**
 * La forma del alta de un evento: lo que el recurso rechaza con 400 antes de
 * llegar al negocio, por las anotaciones de {@link Dtos}. Corre Bean
 * Validation como lo hace el servidor con {@code @Valid}, sin servidor.
 */
class NuevoEventoDtoTest {

    private static final LocalDateTime FECHA = LocalDateTime.of(2027, 3, 1, 21, 0);

    private static ValidatorFactory fabrica;
    private static Validator validador;

    @BeforeAll
    static void crearValidador() {
        fabrica = Validation.buildDefaultValidatorFactory();
        validador = fabrica.getValidator();
    }

    @AfterAll
    static void cerrar() {
        fabrica.close();
    }

    @Test
    void unEventoCompletoNoTieneErrores() {
        assertEquals(Map.of(), errores(evento("Show", "Música", "Estadio", FECHA, "Descripción",
                List.of(new NuevoSectorDto("Campo", 50_000, 100)))));
    }

    @Test
    void cadaCampoObligatorioTieneSuMensaje() {
        Map<String, String> errores = errores(evento(" ", "", null, null, "  ", List.of()));

        assertEquals(Map.of(
                "titulo", "Ingresá el título.",
                "categoria", "Elegí una categoría.",
                "venue", "Ingresá el lugar.",
                "fechaISO", "Ingresá la fecha.",
                "descripcion", "Ingresá una descripción.",
                "sectores", "Agregá al menos un sector."), errores);
    }

    @Test
    void cadaSectorSeValidaConSuPrecioYSuCupo() {
        Map<String, String> errores = errores(evento("Show", "Música", "Estadio", FECHA, "Descripción",
                List.of(new NuevoSectorDto("Campo", 50_000, 100), new NuevoSectorDto("", 0, 0))));

        assertEquals(Map.of(
                "sectores[1].nombre", "Ingresá el nombre del sector.",
                "sectores[1].precio", "El precio tiene que ser mayor a 0.",
                "sectores[1].cupo", "El cupo tiene que ser mayor a 0."), errores);
    }

    @Test
    void losTextosTienenElLargoDeLasColumnas() {
        Map<String, String> errores = errores(new NuevoEventoDto("t".repeat(201), "Música", "v".repeat(201), FECHA,
                "d".repeat(2001), "i".repeat(1001), List.of(new NuevoSectorDto("n".repeat(121), 1, 1))));

        assertEquals(List.of("descripcion", "imagenUrl", "sectores[0].nombre", "titulo", "venue"),
                errores.keySet().stream().sorted().toList());
    }

    @Test
    void enElLimiteDeLargoSeAcepta() {
        assertTrue(errores(new NuevoEventoDto("t".repeat(200), "Música", "v".repeat(200), FECHA,
                "d".repeat(2000), "i".repeat(1000), List.of(new NuevoSectorDto("n".repeat(120), 1, 1)))).isEmpty());
    }

    private static NuevoEventoDto evento(String titulo, String categoria, String venue, LocalDateTime fecha,
            String descripcion, List<NuevoSectorDto> sectores) {
        return new NuevoEventoDto(titulo, categoria, venue, fecha, descripcion, null, sectores);
    }

    /** Campo → mensaje, como los arma el manejador de errores para el Problem Details. */
    private static Map<String, String> errores(NuevoEventoDto dto) {
        return validador.validate(dto).stream()
                .collect(Collectors.toMap(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage));
    }
}
