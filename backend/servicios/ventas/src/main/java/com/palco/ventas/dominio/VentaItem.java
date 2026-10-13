package com.palco.ventas.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Un renglón de la compra: cuántas entradas de un sector y a qué precio.
 *
 * Guarda una copia del nombre y del precio del sector al momento de reservar.
 * El sector vive en ServicioDeEventos y puede cambiar después; lo que se
 * compró, no.
 */
@Entity
@Table(name = "venta_item")
public class VentaItem {

    /** El id de la venta más el número de renglón: "V-3f9a1c2b-1". No sale por la API. */
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venta_id")
    private Venta venta;

    /** El sector en ServicioDeEventos: acá es solo un texto. Un sector aparece una sola vez por venta. */
    @Column(name = "sector_id", nullable = false)
    private String sectorId;

    /** El nombre del sector ("Platea B"). */
    @Column(nullable = false)
    private String sector;

    /** Pesos enteros. */
    @Column(name = "precio_unitario", nullable = false)
    private long precioUnitario;

    @Column(nullable = false)
    private int cantidad;

    /** Posición del renglón en el pedido, empezando en 1. */
    @Column(nullable = false)
    private int orden;

    protected VentaItem() {
        // JPA
    }

    /** Sin "public": los renglones se crean solo con {@link Venta#agregarItem}, que además recalcula los montos. */
    VentaItem(String id, Venta venta, String sectorId, String sector, long precioUnitario, int cantidad, int orden) {
        this.id = id;
        this.venta = venta;
        this.sectorId = sectorId;
        this.sector = sector;
        this.precioUnitario = precioUnitario;
        this.cantidad = cantidad;
        this.orden = orden;
    }

    /** Precio por cantidad, en pesos enteros. */
    public long subtotal() {
        return precioUnitario * cantidad;
    }

    public String getId() { return id; }
    public Venta getVenta() { return venta; }
    public String getSectorId() { return sectorId; }
    public String getSector() { return sector; }
    public long getPrecioUnitario() { return precioUnitario; }
    public int getCantidad() { return cantidad; }
    public int getOrden() { return orden; }
}
