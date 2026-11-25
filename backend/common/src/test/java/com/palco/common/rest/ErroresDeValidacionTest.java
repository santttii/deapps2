package com.palco.common.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;

import org.junit.jupiter.api.Test;

/**
 * El 400 de un cuerpo inválido sale igual cada vez que llega el mismo pedido (DEF-62). Bean
 * Validation entrega las violaciones en un conjunto sin orden fijo; acá se arman en un
 * {@link LinkedHashSet}, que las recorre en el orden en que se agregan, para pasarle al
 * manejador las mismas violaciones en órdenes distintos. Se prueba la parte que arma el
 * problema; la respuesta HTTP la arma JAX-RS con ese mismo problema, como en
 * {@link ErrorInesperadoTest}.
 */
class ErroresDeValidacionTest {

    private static final String RUTA = "/api/usuarios/registro";

    private static final ConstraintViolation<?> EMAIL =
            violacion("registrar.registro.email", "Ingresá un email válido.");
    private static final ConstraintViolation<?> NOMBRE =
            violacion("registrar.registro.nombre", "Ingresá un nombre válido.");
    private static final ConstraintViolation<?> PASSWORD =
            violacion("registrar.registro.password", "Mínimo 8 caracteres, con letras y números.");

    @Test
    void losCamposSalenEnOrdenAlfabeticoAunqueLasViolacionesLleguenEnOtroOrden() {
        String unOrden = comoLoEscribeElServidor(problema(PASSWORD, EMAIL, NOMBRE));
        String otroOrden = comoLoEscribeElServidor(problema(NOMBRE, PASSWORD, EMAIL));

        assertEquals(unOrden, otroOrden);
        assertEquals("{\"type\":\"/problemas/datos-invalidos\",\"title\":\"Datos inválidos\",\"status\":400,"
                + "\"detail\":\"Hay datos inválidos.\",\"instance\":\"/api/usuarios/registro\","
                + "\"errores\":{\"email\":\"Ingresá un email válido.\",\"nombre\":\"Ingresá un nombre válido.\","
                + "\"password\":\"Mínimo 8 caracteres, con letras y números.\"}}", unOrden);
    }

    @Test
    void unCampoQueRompeDosAnotacionesLlevaSiempreElMismoMensaje() {
        // Un nombre de 81 letras con un dígito rompe el @Size y el @Pattern de RegistroDto.
        ConstraintViolation<?> largo = violacion("registrar.registro.nombre", "El nombre tiene hasta 80 caracteres.");

        assertEquals(Map.of("nombre", "El nombre tiene hasta 80 caracteres."), problema(largo, NOMBRE).errores());
        assertEquals(Map.of("nombre", "El nombre tiene hasta 80 caracteres."), problema(NOMBRE, largo).errores());
    }

    @Test
    void sinCuerpoElMensajeVaEnElDetalleYNoHayErroresPorCampo() {
        Problema sinCuerpo = problema(violacion("registrar.arg0", "Falta el cuerpo de la solicitud."));

        assertEquals(400, sinCuerpo.status());
        assertEquals("/problemas/datos-invalidos", sinCuerpo.type());
        assertEquals("Falta el cuerpo de la solicitud.", sinCuerpo.detail());
        assertNull(sinCuerpo.errores());
    }

    private static Problema problema(ConstraintViolation<?>... violaciones) {
        return ManejadoresDeErrores.Validacion.problema(new LinkedHashSet<>(List.of(violaciones)), RUTA);
    }

    private static ConstraintViolation<?> violacion(String ruta, String mensaje) {
        return new Violacion(new RutaDeTexto(ruta), mensaje);
    }

    private static String comoLoEscribeElServidor(Problema problema) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        new JsonProvider().writeTo(problema, Problema.class, Problema.class, new Annotation[0], null, null, salida);
        return salida.toString(StandardCharsets.UTF_8);
    }

    /** La ruta de una violación tal como la escribe Bean Validation: el manejador solo lee su texto. */
    private record RutaDeTexto(String texto) implements Path {
        @Override
        public Iterator<Node> iterator() {
            return Collections.emptyIterator();
        }

        @Override
        public String toString() {
            return texto;
        }
    }

    /** Una violación con lo único que lee el manejador: la ruta y el mensaje. */
    private record Violacion(Path ruta, String mensaje) implements ConstraintViolation<Object> {
        @Override
        public Path getPropertyPath() {
            return ruta;
        }

        @Override
        public String getMessage() {
            return mensaje;
        }

        @Override
        public String getMessageTemplate() {
            return mensaje;
        }

        @Override
        public Object getRootBean() {
            return null;
        }

        @Override
        public Class<Object> getRootBeanClass() {
            return Object.class;
        }

        @Override
        public Object getLeafBean() {
            return null;
        }

        @Override
        public Object[] getExecutableParameters() {
            return new Object[0];
        }

        @Override
        public Object getExecutableReturnValue() {
            return null;
        }

        @Override
        public Object getInvalidValue() {
            return null;
        }

        @Override
        public ConstraintDescriptor<?> getConstraintDescriptor() {
            return null;
        }

        @Override
        public <U> U unwrap(Class<U> tipo) {
            return tipo.cast(this);
        }
    }
}
