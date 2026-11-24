package com.palco.usuarios.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.hibernate.validator.HibernateValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.palco.usuarios.api.Dtos.RegistroDto;

/**
 * Nombre y apellido del registro (DEF-64). El registro es público: un nombre de
 * miles de espacios con un dígito al final hacía que la expresión probara cada
 * forma de repartir los espacios, y el 400 tardaba segundos (con 1500 espacios,
 * 7 s contra el compose; el tiempo crecía con el cubo del largo). Acá se prueba
 * con el mismo validador que usa el servidor, Hibernate Validator.
 *
 * Bean Validation evalúa todas las anotaciones de un campo aunque una ya haya
 * fallado: el {@code @Size} no le ahorra el texto largo a la expresión. Por eso
 * el ataque devuelve los dos mensajes, y lo que lo hace rápido es la expresión.
 */
class NombreYApellidoTest {

    /** 100.000 espacios y un dígito: unos 100 KB en un solo pedido sin sesión. */
    private static final String ATAQUE = " ".repeat(100_000) + "1";

    private static ValidatorFactory fabrica;
    private static Validator validador;

    @BeforeAll
    static void crearValidador() {
        fabrica = Validation.byProvider(HibernateValidator.class).configure()
                .defaultLocale(Locale.ENGLISH)
                .buildValidatorFactory();
        validador = fabrica.getValidator();
    }

    @AfterAll
    static void cerrar() {
        fabrica.close();
    }

    @Test
    void unNombreLlenoDeEspaciosSeRechazaEnMenosDeUnSegundo() {
        Map<String, Set<String>> errores = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> errores(registro(ATAQUE, "Gómez")));

        assertEquals(Map.of("nombre", Set.of("El nombre tiene hasta 80 caracteres.", "Ingresá un nombre válido.")),
                errores);
    }

    @Test
    void unApellidoLlenoDeEspaciosSeRechazaEnMenosDeUnSegundo() {
        Map<String, Set<String>> errores = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> errores(registro("Ana", ATAQUE)));

        assertEquals(Map.of("apellido", Set.of("El apellido tiene hasta 80 caracteres.", "Ingresá un apellido válido.")),
                errores);
    }

    @Test
    void ochentaCaracteresEsElLargoDeLaColumnaYOchentaYUnoNo() {
        assertEquals(Map.of(), errores(registro("A".repeat(80), "B".repeat(80))));
        assertEquals(Map.of("nombre", Set.of("El nombre tiene hasta 80 caracteres."),
                "apellido", Set.of("El apellido tiene hasta 80 caracteres.")),
                errores(registro("A".repeat(81), "B".repeat(81))));
    }

    /** Lo mismo que esNombreValido de frontend/src/lib/validate.ts: sin los espacios de los bordes, 2 o más. */
    @Test
    void aceptaLoMismoQueElFrontend() {
        assertEquals(Map.of(), errores(registro("  María José  ", "D'Angelo")));
        assertEquals(Map.of(), errores(registro("Ana\t", "De la Fuente")));
        assertEquals(Map.of("nombre", Set.of("Ingresá un nombre válido.")), errores(registro(" A", "Gómez")));
        assertEquals(Map.of("nombre", Set.of("Ingresá un nombre válido.")), errores(registro("Ana1", "Gómez")));
        assertEquals(Map.of("apellido", Set.of("Ingresá un apellido válido.")), errores(registro("Ana", "Gó\tmez")));
    }

    private static RegistroDto registro(String nombre, String apellido) {
        return new RegistroDto(nombre, apellido, "30123456", LocalDate.of(1995, 5, 17), "ana@palco.test", null,
                "palco1234");
    }

    /** Campo → mensajes de las anotaciones que fallaron. */
    private static Map<String, Set<String>> errores(RegistroDto dto) {
        return validador.validate(dto).stream()
                .collect(Collectors.groupingBy(v -> v.getPropertyPath().toString(),
                        Collectors.mapping(ConstraintViolation::getMessage, Collectors.toSet())));
    }
}
