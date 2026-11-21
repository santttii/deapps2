package com.palco.usuarios.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.MethodType;

import org.hibernate.validator.HibernateValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.palco.usuarios.api.Dtos.RegistroDto;

/**
 * El 400 de un cuerpo inválido lleva, por campo, el mensaje de la anotación que
 * falló: {@code ManejadoresDeErrores.Validacion} lo copia tal cual en
 * {@code errores} del Problem Details, con "Hay datos inválidos." como detalle.
 *
 * Una anotación sin {@code message} usa el texto por defecto de Bean Validation,
 * que depende del idioma del pedido o del servidor. Por eso el validador de
 * estos tests arranca en inglés, como el servidor en Docker: si un mensaje sale
 * en español, es porque está escrito en {@link Dtos}.
 */
class MensajesDeValidacionTest {

    /**
     * Un email bien formado de 200 caracteres, el largo de la columna. Va con el
     * dominio en partes de 63 letras porque es lo más largo que acepta {@code @Email}
     * en cada parte: así el único límite que se prueba es el de {@code @Size}.
     */
    private static final String EMAIL_DE_200 =
            "ana@" + "p".repeat(63) + "." + "a".repeat(63) + "." + "l".repeat(63) + ".test";

    /** Para ver qué pasa con una anotación sin mensaje propio. */
    private record SinMensaje(@Size(max = 1) String texto) {
    }

    private static ValidatorFactory fabrica;
    private static Validator validador;

    @BeforeAll
    static void crearValidadorEnIngles() {
        fabrica = Validation.byProvider(HibernateValidator.class).configure()
                .defaultLocale(Locale.ENGLISH)
                .buildValidatorFactory();
        validador = fabrica.getValidator();
    }

    @AfterAll
    static void cerrar() {
        fabrica.close();
    }

    /** Así salían el email y el teléfono largos antes de tener mensaje: es lo que este test evita. */
    @Test
    void sinMensajePropioElTextoSaleEnIngles() {
        assertEquals(Map.of("texto", "size must be between 0 and 1"), errores(new SinMensaje("dos")));
    }

    /**
     * Si alguien suma una validación sin {@code message}, este test la nombra. Revisa
     * los campos de los DTO y los parámetros del recurso (el cuerpo que falta).
     */
    @Test
    void ningunaValidacionDeLaApiUsaElMensajePorDefecto() {
        List<String> sinMensaje = new ArrayList<>();
        Stream.concat(Arrays.stream(Dtos.class.getDeclaredClasses()), Stream.of(UsuariosResource.class))
                .forEach(clase -> {
                    BeanDescriptor descripcion = validador.getConstraintsForClass(clase);
                    descripcion.getConstrainedProperties().forEach(campo -> revisar(
                            clase.getSimpleName() + "." + campo.getPropertyName(),
                            campo.getConstraintDescriptors(), sinMensaje));
                    descripcion.getConstrainedMethods(MethodType.NON_GETTER, MethodType.GETTER)
                            .forEach(metodo -> metodo.getParameterDescriptors().forEach(parametro -> revisar(
                                    clase.getSimpleName() + "." + metodo.getName() + "(" + parametro.getName() + ")",
                                    parametro.getConstraintDescriptors(), sinMensaje)));
                });

        assertEquals(List.of(), sinMensaje);
    }

    @Test
    void unEmailDeMasDe200CaracteresSeExplicaEnEspanol() {
        assertEquals(Map.of(), errores(registro(EMAIL_DE_200, null)));
        assertEquals(Map.of("email", "El email tiene hasta 200 caracteres."),
                errores(registro("x" + EMAIL_DE_200, null)));
    }

    @Test
    void unTelefonoDeMasDe30CaracteresSeExplicaEnEspanol() {
        assertEquals(Map.of(), errores(registro("ana@palco.test", "1".repeat(30))));
        assertEquals(Map.of("telefono", "El teléfono tiene hasta 30 caracteres."),
                errores(registro("ana@palco.test", "1".repeat(31))));
    }

    @Test
    void unRegistroVacioTieneUnMensajeEnEspanolPorCampo() {
        assertEquals(Map.of(
                "nombre", "Ingresá un nombre válido.",
                "apellido", "Ingresá un apellido válido.",
                "dni", "El DNI debe tener 7 u 8 dígitos.",
                "fechaNacimiento", "Tenés que ser mayor de 16 años.",
                "email", "Ingresá un email válido.",
                "password", "Mínimo 8 caracteres, con letras y números."),
                errores(new RegistroDto(null, null, null, null, null, null, null)));
    }

    private static RegistroDto registro(String email, String telefono) {
        return new RegistroDto("Ana", "Gómez", "30123456", LocalDate.of(1995, 5, 17), email, telefono, "palco1234");
    }

    private static void revisar(String donde, Set<ConstraintDescriptor<?>> restricciones, List<String> sinMensaje) {
        for (ConstraintDescriptor<?> restriccion : restricciones) {
            // El mensaje por defecto es una clave entre llaves del paquete de Bean Validation.
            if (restriccion.getMessageTemplate().startsWith("{jakarta.validation.constraints.")) {
                sinMensaje.add(donde + " @" + restriccion.getAnnotation().annotationType().getSimpleName());
            }
        }
    }

    /** Campo → mensaje, como los pone el manejador de errores en el Problem Details. */
    private static Map<String, String> errores(Object dto) {
        return validador.validate(dto).stream()
                .collect(Collectors.toMap(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage));
    }
}
