package com.palco.reventa.dominio;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.palco.common.ReglasDeNegocio;

/**
 * Una entrada puesta en la reventa oficial, con su precio.
 *
 * La entrada es de ServicioDeVentas: acá se guarda su id y una copia del
 * evento, el sector y lo que pagó el vendedor, que es la base del tope. El
 * tope ({@code precioMaximo}) se calcula una vez, al publicar, con
 * {@code ReglasDeNegocio}.
 *
 * Desde el código solo se crea. El estado y los datos de la compra no tienen
 * "set": los cambia {@code PublicacionRepository} con UPDATE condicionales, y
 * después la publicación se vuelve a buscar.
 */
@Entity
@Table(name = "publicacion")
public class Publicacion {

    /** "R-" más 8 caracteres de un UUID. */
    @Id
    private String id;

    @Column(name = "entrada_id", nullable = false)
    private String entradaId;

    @Column(name = "vendedor_id", nullable = false)
    private String vendedorId;

    @Column(name = "evento_id", nullable = false)
    private String eventoId;

    @Column(name = "sector_id", nullable = false)
    private String sectorId;

    /** El nombre del sector ("Platea B"), para listar sin pedirle nada a Eventos. */
    @Column(nullable = false)
    private String sector;

    @Column(name = "precio_pagado", nullable = false)
    private long precioPagado;

    /** Lo que paga el comprador por la entrada, sin el cargo de servicio. */
    @Column(nullable = false)
    private long precio;

    @Column(name = "precio_maximo", nullable = false)
    private long precioMaximo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoPublicacion estado;

    /** En UTC y sin fracción de segundo. */
    @Column(nullable = false)
    private LocalDateTime publicada;

    /** Quién la está comprando o la compró; null mientras nadie lo intentó. */
    @Column(name = "comprador_id")
    private String compradorId;

    /** La Idempotency-Key de esa compra. */
    @Column(name = "clave_compra")
    private String claveCompra;

    /** Cuándo empezó esa compra, en UTC. Una compra trabada se puede retomar pasado un tiempo. */
    @Column(name = "compra_intentada")
    private LocalDateTime compraIntentada;

    /** El titular de la entrada que se está comprando; lo anota la toma, antes de cobrar. */
    @Column(name = "titular_nombre")
    private String titularNombre;

    @Column(name = "titular_dni")
    private String titularDni;

    @Column(name = "venta_id")
    private String ventaId;

    @Column(name = "pago_id")
    private String pagoId;

    /** Cuándo se vendió o se retiró. */
    private LocalDateTime cerrada;

    protected Publicacion() {
        // JPA
    }

    /** Una publicación nueva, {@code ACTIVA}, con su tope calculado por {@code ReglasDeNegocio}. */
    public Publicacion(String id, String entradaId, String vendedorId, String eventoId, String sectorId,
            String sector, long precioPagado, long precio, LocalDateTime ahora) {
        this.id = id;
        this.entradaId = entradaId;
        this.vendedorId = vendedorId;
        this.eventoId = eventoId;
        this.sectorId = sectorId;
        this.sector = sector;
        this.precioPagado = precioPagado;
        this.precio = precio;
        this.precioMaximo = ReglasDeNegocio.topeReventa(precioPagado);
        this.estado = EstadoPublicacion.ACTIVA;
        this.publicada = ahora;
    }

    public String getId() { return id; }
    public String getEntradaId() { return entradaId; }
    public String getVendedorId() { return vendedorId; }
    public String getEventoId() { return eventoId; }
    public String getSectorId() { return sectorId; }
    public String getSector() { return sector; }
    public long getPrecioPagado() { return precioPagado; }
    public long getPrecio() { return precio; }
    public long getPrecioMaximo() { return precioMaximo; }
    public EstadoPublicacion getEstado() { return estado; }
    public LocalDateTime getPublicada() { return publicada; }
    public String getCompradorId() { return compradorId; }
    public String getClaveCompra() { return claveCompra; }
    public LocalDateTime getCompraIntentada() { return compraIntentada; }
    public String getTitularNombre() { return titularNombre; }
    public String getTitularDni() { return titularDni; }
    public String getVentaId() { return ventaId; }
    public String getPagoId() { return pagoId; }
    public LocalDateTime getCerrada() { return cerrada; }
}
