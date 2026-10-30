package com.palco.usuarios.dominio;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Código de 6 dígitos vigente para que una cuenta vuelva a poner su
 * contraseña. Uno por cuenta: la clave es el id del usuario, igual que en
 * {@link CodigoVerificacion}.
 *
 * Es una tabla aparte de {@code codigo_verificacion} a propósito, y no una
 * columna "tipo" en la misma: así un código que se pidió para reiniciar la
 * contraseña nunca sirve para verificar un email, ni al revés. Las dos cosas
 * llegan por mail al mismo buzón, pero habilitan permisos distintos.
 */
@Entity
@Table(name = "reinicio_password")
public class CodigoDeReinicio {

    @Id
    @Column(name = "usuario_id")
    private String usuarioId;

    /** SHA-256 del código en hexadecimal. El código en claro solo viaja en el email. */
    @Column(name = "codigo_hash", nullable = false)
    private String codigoHash;

    @Column(nullable = false)
    private LocalDateTime expira;

    @Column(nullable = false)
    private LocalDateTime enviado;

    @Column(nullable = false)
    private int intentos;

    protected CodigoDeReinicio() {
        // JPA
    }

    public CodigoDeReinicio(String usuarioId) {
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
