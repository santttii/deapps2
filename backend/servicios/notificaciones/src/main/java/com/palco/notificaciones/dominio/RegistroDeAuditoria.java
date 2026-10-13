package com.palco.notificaciones.dominio;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Una fila por cada mensaje {@code PAGO_CONFIRMADO} que auditoría recibió por
 * el tópico. No se modifica después de creada: por eso no tiene setters.
 */
@Entity
@Table(name = "registro_auditoria")
public class RegistroDeAuditoria {

    /**
     * El {@code JMSMessageID}. El broker conserva el mismo id cuando reentrega
     * un mensaje, así que como clave primaria impide registrarlo dos veces.
     */
    @Id
    @Column(name = "mensaje_id")
    private String mensajeId;

    @Column(name = "tipo_evento", nullable = false)
    private String tipoEvento;

    @Column(name = "venta_id", nullable = false)
    private String ventaId;

    @Column(name = "comprador_id", nullable = false)
    private String compradorId;

    @Column(name = "evento_id", nullable = false)
    private String eventoId;

    @Column(name = "monto_total", nullable = false)
    private long montoTotal;

    /** El JSON tal como llegó. */
    @Column(nullable = false, length = 4000)
    private String cuerpo;

    /** Hora UTC en que se registró, truncada a segundos. */
    @Column(name = "recibido_en", nullable = false)
    private LocalDateTime recibidoEn;

    protected RegistroDeAuditoria() {
        // JPA
    }

    public RegistroDeAuditoria(String mensajeId, String tipoEvento, String ventaId, String compradorId,
            String eventoId, long montoTotal, String cuerpo, LocalDateTime recibidoEn) {
        this.mensajeId = mensajeId;
        this.tipoEvento = tipoEvento;
        this.ventaId = ventaId;
        this.compradorId = compradorId;
        this.eventoId = eventoId;
        this.montoTotal = montoTotal;
        this.cuerpo = cuerpo;
        this.recibidoEn = recibidoEn;
    }

    public String getMensajeId() { return mensajeId; }
    public String getTipoEvento() { return tipoEvento; }
    public String getVentaId() { return ventaId; }
    public String getCompradorId() { return compradorId; }
    public String getEventoId() { return eventoId; }
    public long getMontoTotal() { return montoTotal; }
    public String getCuerpo() { return cuerpo; }
    public LocalDateTime getRecibidoEn() { return recibidoEn; }
}
