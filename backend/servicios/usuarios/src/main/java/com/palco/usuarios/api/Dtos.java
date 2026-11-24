package com.palco.usuarios.api;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Lo que entra y sale de la API de usuarios. {@link UsuarioDto} coincide con el
 * tipo {@code Usuario} del frontend; las validaciones replican
 * {@code frontend/src/lib/validate.ts}.
 *
 * Cada anotación de validación lleva su propio {@code message}: es el texto
 * que el 400 devuelve por campo. Sin él, Bean Validation usa su mensaje por
 * defecto, que se traduce según el idioma del pedido o el del servidor: en la
 * imagen de Docker, a un cliente que no manda {@code Accept-Language} le llega
 * en inglés ("size must be between 0 and 200").
 */
public final class Dtos {

    /** Nombre o apellido: ver {@link RegistroDto}. */
    static final String NOMBRE = "^\\s*+[A-Za-zÀ-ÿ' -]{2,}+\\s*+$";

    /** El largo de las columnas {@code nombre} y {@code apellido} de {@code usuarios.usuario}. */
    static final int LARGO_NOMBRE = 80;

    private Dtos() {
    }

    /** Nunca incluye la contraseña ni el hash. */
    /** Lo que ve otro servicio de una cuenta por su email: solo el rol. */
    public record CuentaDto(String email, String rol) {
    }

    public record UsuarioDto(
            String id,
            String nombre,
            String apellido,
            String dni,
            String email,
            String rol,
            boolean identidadVerificada) {
    }

    /**
     * El alta de una cuenta. Nombre y apellido usan {@link #NOMBRE}, con
     * cuantificadores posesivos ({@code *+}, {@code {2,}+}): el espacio está en
     * la clase del medio y en los {@code \s} de los bordes, y con cuantificadores
     * comunes un texto largo que no termina bien hace probar cada forma de
     * repartir los espacios entre las tres partes (el tiempo crecía con el cubo
     * del largo, DEF-64). Posesivos no vuelven atrás y el tiempo es lineal. Acepta
     * lo mismo que {@code esNombreValido} del frontend: el texto sin los espacios
     * de los bordes tiene 2 o más caracteres de la clase. El {@code @Size} es el
     * largo de la columna ({@code varchar(80)}, V1).
     */
    public record RegistroDto(
            @NotBlank(message = "Ingresá un nombre válido.")
            @Size(max = LARGO_NOMBRE, message = "El nombre tiene hasta " + LARGO_NOMBRE + " caracteres.")
            @Pattern(regexp = NOMBRE, message = "Ingresá un nombre válido.")
            String nombre,

            @NotBlank(message = "Ingresá un apellido válido.")
            @Size(max = LARGO_NOMBRE, message = "El apellido tiene hasta " + LARGO_NOMBRE + " caracteres.")
            @Pattern(regexp = NOMBRE, message = "Ingresá un apellido válido.")
            String apellido,

            @NotBlank(message = "El DNI debe tener 7 u 8 dígitos.")
            String dni,

            @NotNull(message = "Tenés que ser mayor de 16 años.")
            LocalDate fechaNacimiento,

            @NotBlank(message = "Ingresá un email válido.")
            @Email(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", message = "Ingresá un email válido.")
            @Size(max = 200, message = "El email tiene hasta 200 caracteres.")
            String email,

            @Size(max = 30, message = "El teléfono tiene hasta 30 caracteres.")
            String telefono,

            @NotNull(message = "Mínimo 8 caracteres, con letras y números.")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "Mínimo 8 caracteres, con letras y números.")
            String password) {
    }

    public record VerificacionDto(
            @NotBlank(message = "Ingresá tu email.") String email,
            @NotBlank(message = "Ingresá el código de 6 dígitos.")
            @Pattern(regexp = "^\\d{6}$", message = "Ingresá el código de 6 dígitos.") String codigo) {
    }

    public record ReenvioDto(@NotBlank(message = "Ingresá tu email.") String email) {
    }

    /**
     * Pedido de reinicio de contraseña: solo el email. La respuesta es la misma
     * exista o no la cuenta, para no revelar qué emails están registrados.
     */
    public record OlvideDto(@NotBlank(message = "Ingresá tu email.") String email) {
    }

    /** El código que llegó por email y la contraseña nueva. */
    public record ReinicioDto(
            @NotBlank(message = "Ingresá tu email.") String email,

            @NotBlank(message = "Ingresá el código de 6 dígitos.")
            @Pattern(regexp = "^\\d{6}$", message = "Ingresá el código de 6 dígitos.") String codigo,

            @NotNull(message = "Mínimo 8 caracteres, con letras y números.")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "Mínimo 8 caracteres, con letras y números.")
            String password) {
    }

    public record LoginDto(
            @NotBlank(message = "Revisá el email y la contraseña.") String email,
            @NotBlank(message = "Revisá el email y la contraseña.") String password) {
    }

    /** Respuesta de login y verificación: el token va en {@code Authorization: Bearer <token>}. */
    public record SesionDto(String token, long expiraEnSegundos, UsuarioDto usuario) {
    }

    /** Respuesta del registro: todavía no hay sesión, falta verificar el email. */
    public record RegistroPendienteDto(String email, String mensaje) {
    }

    /** Lo que presenta un servicio para pedir su token: el secreto que comparten todos los servicios. */
    public record SecretoDto(@NotBlank(message = "Falta el secreto.") String secreto) {
    }

    /** Token de rol {@code servicio}, para las llamadas entre servicios. No lleva usuario. */
    public record TokenDeServicioDto(String token, long expiraEnSegundos) {
    }
}
