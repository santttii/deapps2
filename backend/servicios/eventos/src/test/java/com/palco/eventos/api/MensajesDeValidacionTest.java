package com.palco.eventos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
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

import com.palco.eventos.api.Dtos.CancelacionDto;
import com.palco.eventos.api.Dtos.EditarEventoDto;
import com.palco.eventos.api.Dtos.NuevoEventoDto;
import com.palco.eventos.api.Dtos.NuevoSectorDto;
import com.palco.eventos.api.Dtos.SectorEditadoDto;

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

    private static final LocalDateTime FECHA = LocalDateTime.of(2027, 3, 1, 21, 0);

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

    /** Así salían los largos de Eventos antes de tener mensaje: es lo que este test evita. */
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
        Stream.concat(Arrays.stream(Dtos.class.getDeclaredClasses()), Stream.of(EventosResource.class))
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
    void unEventoConTextosDemasiadoLargosExplicaCadaLimiteEnEspanol() {
        NuevoEventoDto largo = new NuevoEventoDto("t".repeat(201), "Música", "v".repeat(201), FECHA,
                "d".repeat(2001), "i".repeat(1001), List.of(new NuevoSectorDto("n".repeat(121), 1, 1)));

        assertEquals(Map.of(
                "titulo", "El título tiene hasta 200 caracteres.",
                "venue", "El lugar tiene hasta 200 caracteres.",
                "descripcion", "La descripción tiene hasta 2000 caracteres.",
                "imagenUrl", "La URL de la imagen tiene hasta 1000 caracteres.",
                "sectores[0].nombre", "El nombre del sector tiene hasta 120 caracteres."), errores(largo));
    }

    @Test
    void alEditarUnSectorConElIdDemasiadoLargoTambienSeExplicaEnEspanol() {
        EditarEventoDto cambios = new EditarEventoDto("Show", "Música", "Estadio", FECHA, "Descripción", null,
                List.of(new SectorEditadoDto("s".repeat(41), "Campo", 50_000, 100)));

        assertEquals(Map.of("sectores[0].id", "El id del sector tiene hasta 40 caracteres."), errores(cambios));
    }

    @Test
    void unMotivoDeCancelacionDemasiadoLargoSeExplicaEnEspanol() {
        assertEquals(Map.of("motivo", "El motivo tiene hasta 500 caracteres."),
                errores(new CancelacionDto("m".repeat(501))));
    }

    private static void revisar(String donde, Set<ConstraintDescriptor<?>> restricciones, List<String> sinMensaje) {
        for (ConstraintDescriptor<?> restriccion : restricciones) {
            // El mensaje por defecto es una clave entre llaves del paquete de Bean Validation.
            if (restriccion.getMessageTemplate().startsWith("{jakarta.validation.constraints.")) {
                sinMensaje.add(donde + " @" + restriccion.getAnnotation().annotationType().getSimpleName());
            }
        }
    }

    /**
     * Ruta del campo → mensaje. El manejador de errores pone el mismo mensaje, con la
     * última parte de la ruta como clave.
     */
    private static Map<String, String> errores(Object dto) {
        return validador.validate(dto).stream()
                .collect(Collectors.toMap(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage));
    }
}
