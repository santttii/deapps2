package com.palco.ventas.dominio;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Una entrada: el lugar de una persona (el titular) en un sector del evento.
 *
 * Nace cuando se aprueba el pago, en {@code PENDIENTE_EMISION} y sin código.
 * La emisión, que es asincrónica, le pone el código QR y la deja
 * {@code VALIDA}. Ese cambio no tiene "set": lo hace {@code EntradaRepository}
 * con un UPDATE condicional, para que emitir dos veces no cambie nada.
 */
@Entity
@Table(name = "entrada")
public class Entrada {

    /** "T-" más 8 caracteres de un UUID; lo asigna el código, no la base. */
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venta_id")
    private Venta venta;

    /** El sector en ServicioDeEventos: acá es solo un texto. */
    @Column(name = "sector_id", nullable = false)
    private String sectorId;

    /** El nombre del sector ("Platea B"). */
    @Column(nullable = false)
    private String sector;

    /** Nombre y apellido en un solo campo, como en la API y en el mensaje PAGO_CONFIRMADO. */
    @Column(name = "titular_nombre", nullable = false)
    private String titularNombre;

    /** 7 u 8 dígitos. No se repite dentro de una venta. */
    @Column(name = "titular_dni", nullable = false)
    private String titularDni;

    /** El precio del sector, sin el cargo de servicio, en pesos enteros. Es la base del tope de reventa. */
    @Column(name = "precio_pagado", nullable = false)
    private long precioPagado;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoEntrada estado;

    /** Texto opaco y único; null hasta que la entrada se emite. */
    @Column(name = "codigo_qr", unique = true)
    private String codigoQr;

    /** Cuándo se emitió, en UTC; null hasta entonces. */
    private LocalDateTime emitida;

    /** Posición del titular en el pedido, empezando en 1: las entradas se devuelven en ese orden. */
    @Column(nullable = false)
    private int orden;

    protected Entrada() {
        // JPA
    }

    /**
     * Una entrada nueva, todavía sin emitir. La venta, el sector y el precio
     * salen del renglón: así una entrada no puede quedar con un precio distinto
     * del que se cobró por su sector.
     */
    public Entrada(String id, VentaItem item, String titularNombre, String titularDni, int orden) {
        this.id = id;
        this.venta = item.getVenta();
        this.sectorId = item.getSectorId();
        this.sector = item.getSector();
        this.precioPagado = item.getPrecioUnitario();
        this.titularNombre = titularNombre;
        this.titularDni = titularDni;
        this.orden = orden;
        this.estado = EstadoEntrada.PENDIENTE_EMISION;
    }

    public String getId() { return id; }
    public Venta getVenta() { return venta; }
    public String getSectorId() { return sectorId; }
    public String getSector() { return sector; }
    public String getTitularNombre() { return titularNombre; }
    public String getTitularDni() { return titularDni; }
    public long getPrecioPagado() { return precioPagado; }
    public EstadoEntrada getEstado() { return estado; }
    public String getCodigoQr() { return codigoQr; }
    public LocalDateTime getEmitida() { return emitida; }
    public int getOrden() { return orden; }
}
