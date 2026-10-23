package com.palco.eventos.dominio;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Una devolución de cupo ya aplicada. Existe para que devolver sea seguro de
 * repetir: quien devuelve manda siempre la misma clave para la misma venta y
 * el mismo sector, y si la clave ya está, Eventos no resta otra vez.
 */
@Entity
@Table(name = "devolucion_de_cupo")
public class DevolucionDeCupo {

    @Id
    private String clave;

    @Column(name = "sector_id", nullable = false)
    private String sectorId;

    /** Lugares devueltos, en positivo. */
    @Column(nullable = false)
    private int cantidad;

    @Column(nullable = false)
    private LocalDateTime aplicada;

    protected DevolucionDeCupo() {
        // JPA
    }

    public DevolucionDeCupo(String clave, String sectorId, int cantidad, LocalDateTime aplicada) {
        this.clave = clave;
        this.sectorId = sectorId;
        this.cantidad = cantidad;
        this.aplicada = aplicada;
    }

    public String getClave() { return clave; }
    public String getSectorId() { return sectorId; }
    public int getCantidad() { return cantidad; }
    public LocalDateTime getAplicada() { return aplicada; }
}
