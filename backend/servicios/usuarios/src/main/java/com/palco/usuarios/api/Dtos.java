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
 */
public final class Dtos {

    private Dtos() {
    }

    /** Nunca incluye la contraseña ni el hash. */
    public record UsuarioDto(
            String id,
            String nombre,
            String apellido,
            String dni,
            String email,
            String rol,
            boolean identidadVerificada) {
    }

    public record RegistroDto(
            @NotBlank(message = "Ingresá un nombre válido.")
            @Pattern(regexp = "^\\s*[A-Za-zÀ-ÿ' -]{2,}\\s*$", message = "Ingresá un nombre válido.")
            String nombre,

            @NotBlank(message = "Ingresá un apellido válido.")
            @Pattern(regexp = "^\\s*[A-Za-zÀ-ÿ' -]{2,}\\s*$", message = "Ingresá un apellido válido.")
            String apellido,

            @NotBlank(message = "El DNI debe tener 7 u 8 dígitos.")
            String dni,

            @NotNull(message = "Tenés que ser mayor de 16 años.")
            LocalDate fechaNacimiento,

            @NotBlank(message = "Ingresá un email válido.")
            @Email(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", message = "Ingresá un email válido.")
            @Size(max = 200)
            String email,

            @Size(max = 30)
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
}
