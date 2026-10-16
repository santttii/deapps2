package com.palco.usuarios.dominio;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Un inicio de sesión fallido. Se guarda el SHA-256 del email y no el email: la
 * tabla también anota emails que no tienen cuenta (los que se escribieron mal o
 * los que prueba un atacante), y así no se juntan datos de nadie.
 */
@Entity
@Table(name = "intento_login")
public class IntentoLogin {

    @Id
    private String id;

    /** SHA-256 en hexadecimal del email normalizado (sin espacios y en minúsculas). */
    @Column(name = "email_hash", nullable = false)
    private String emailHash;

    @Column(nullable = false)
    private LocalDateTime momento;

    protected IntentoLogin() {
        // JPA
    }

    public IntentoLogin(String emailHash, LocalDateTime momento) {
        this.id = UUID.randomUUID().toString();
        this.emailHash = emailHash;
        this.momento = momento;
    }

    public String getId() { return id; }
    public String getEmailHash() { return emailHash; }
    public LocalDateTime getMomento() { return momento; }
}
