package com.palco.reventa.dominio;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Un cobro de reventa que hay que devolver: Pagos lo aprobó y Ventas no
 * transfirió la entrada (limitación L2). Se anota antes de anular la
 * publicación, así el reembolso no depende de que Pagos responda en ese
 * momento.
 *
 * Desde el código solo se crea. El estado lo cambia
 * {@code ReembolsoRepository} con un UPDATE condicional.
 */
@Entity
@Table(name = "reembolso")
public class Reembolso {

    /** Un reembolso por pago: es lo que impide anotarlo dos veces. */
    @Id
    @Column(name = "pago_id")
    private String pagoId;

    @Column(name = "publicacion_id", nullable = false)
    private String publicacionId;

    @Column(name = "comprador_id", nullable = false)
    private String compradorId;

    /** Lo que se cobró, en pesos enteros. */
    @Column(nullable = false)
    private long monto;

    @Column(nullable = false)
    private String motivo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoReembolso estado;

    @Column(nullable = false)
    private LocalDateTime anotado;

    private LocalDateTime resuelto;

    protected Reembolso() {
        // JPA
    }

    /** Nace {@code PENDIENTE}. {@code ahora} va en UTC. */
    public Reembolso(String pagoId, String publicacionId, String compradorId, long monto, String motivo,
            LocalDateTime ahora) {
        this.pagoId = pagoId;
        this.publicacionId = publicacionId;
        this.compradorId = compradorId;
        this.monto = monto;
        this.motivo = motivo;
        this.estado = EstadoReembolso.PENDIENTE;
        this.anotado = ahora;
    }

    public String getPagoId() { return pagoId; }
    public String getPublicacionId() { return publicacionId; }
    public String getCompradorId() { return compradorId; }
    public long getMonto() { return monto; }
    public String getMotivo() { return motivo; }
    public EstadoReembolso getEstado() { return estado; }
    public LocalDateTime getAnotado() { return anotado; }
    public LocalDateTime getResuelto() { return resuelto; }
}
