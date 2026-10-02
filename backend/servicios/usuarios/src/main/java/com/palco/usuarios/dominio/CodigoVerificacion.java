package com.palco.usuarios.dominio;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Código de 6 dígitos vigente para verificar el email de un usuario. */
@Entity
@Table(name = "codigo_verificacion")
public class CodigoVerificacion {

    @Id
    @Column(name = "usuario_id")
    private String usuarioId;

    /** SHA-256 del código en hexadecimal. */
    @Column(name = "codigo_hash", nullable = false)
    private String codigoHash;

    @Column(nullable = false)
    private LocalDateTime expira;

    @Column(nullable = false)
    private LocalDateTime enviado;

    @Column(nullable = false)
    private int intentos;

    protected CodigoVerificacion() {
        // JPA
    }

    public CodigoVerificacion(String usuarioId) {
        this.usuarioId = usuarioId;
    }

    /** Reemplaza el código vigente por uno nuevo y reinicia los intentos. */
    public void renovar(String codigoHash, LocalDateTime ahora, LocalDateTime expira) {
        this.codigoHash = codigoHash;
        this.enviado = ahora;
        this.expira = expira;
        this.intentos = 0;
    }

    public void sumarIntento() {
        intentos++;
    }

    public String getUsuarioId() { return usuarioId; }
    public String getCodigoHash() { return codigoHash; }
    public LocalDateTime getExpira() { return expira; }
    public LocalDateTime getEnviado() { return enviado; }
    public int getIntentos() { return intentos; }
}
