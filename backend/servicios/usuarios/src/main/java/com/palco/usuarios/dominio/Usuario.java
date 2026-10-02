package com.palco.usuarios.dominio;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "usuario")
public class Usuario {

    @Id
    private String id;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private String apellido;

    /** Solo dígitos, 7 u 8. */
    @Column(nullable = false, unique = true)
    private String dni;

    /** Siempre en minúsculas. */
    @Column(nullable = false, unique = true)
    private String email;

    private String telefono;

    @Column(name = "fecha_nacimiento")
    private LocalDate fechaNacimiento;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    @Convert(converter = ConversorRol.class)
    private Rol rol;

    @Column(name = "email_verificado", nullable = false)
    private boolean emailVerificado;

    @Column(name = "identidad_verificada", nullable = false)
    private boolean identidadVerificada;

    @Column(nullable = false)
    private LocalDateTime creado;

    protected Usuario() {
        // JPA
    }

    public Usuario(String id, String nombre, String apellido, String dni, String email, String telefono,
            LocalDate fechaNacimiento, String passwordHash, Rol rol) {
        this.id = id;
        this.nombre = nombre;
        this.apellido = apellido;
        this.dni = dni;
        this.email = email;
        this.telefono = telefono;
        this.fechaNacimiento = fechaNacimiento;
        this.passwordHash = passwordHash;
        this.rol = rol;
        this.creado = LocalDateTime.now();
    }

    public void marcarEmailVerificado() {
        this.emailVerificado = true;
    }

    public String getId() { return id; }
    public String getNombre() { return nombre; }
    public String getApellido() { return apellido; }
    public String getDni() { return dni; }
    public String getEmail() { return email; }
    public String getTelefono() { return telefono; }
    public LocalDate getFechaNacimiento() { return fechaNacimiento; }
    public String getPasswordHash() { return passwordHash; }
    public Rol getRol() { return rol; }
    public boolean isEmailVerificado() { return emailVerificado; }
    public boolean isIdentidadVerificada() { return identidadVerificada; }

    /** Guarda el rol en minúsculas ("user"), como lo espera el resto del sistema. */
    @Converter
    public static class ConversorRol implements AttributeConverter<Rol, String> {
        @Override
        public String convertToDatabaseColumn(Rol rol) {
            return rol == null ? null : rol.nombre();
        }

        @Override
        public Rol convertToEntityAttribute(String valor) {
            return valor == null ? null : Rol.valueOf(valor.toUpperCase());
        }
    }
}
