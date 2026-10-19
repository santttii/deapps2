package com.palco.facturacion.dominio;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * La factura de una compra confirmada, ya autorizada por ARCA: número de
 * comprobante y CAE. Hay una sola por venta (restricción única en la base).
 * No cambia después de creada: no hay notas de crédito ni anulaciones.
 */
@Entity
@Table(name = "factura")
public class Factura {

    /** "F-" más 8 caracteres de un UUID; lo asigna el código, no la base. */
    @Id
    private String id;

    /** La venta facturada. Vive en ServicioDeVentas: acá es solo un texto. Única en la tabla. */
    @Column(name = "venta_id", nullable = false, unique = true)
    private String ventaId;

    /** El usuario que compró (el "sub" de su token). Es quien puede consultar la factura. */
    @Column(name = "comprador_id", nullable = false)
    private String compradorId;

    @Column(name = "evento_id", nullable = false)
    private String eventoId;

    /** Código de ARCA: 6 es factura B. */
    @Column(name = "tipo_comprobante", nullable = false)
    private int tipoComprobante;

    @Column(name = "punto_de_venta", nullable = false)
    private int puntoDeVenta;

    /** Lo asigna ARCA. */
    @Column(nullable = false)
    private long numero;

    /** Código de autorización electrónico que da ARCA: 14 dígitos. */
    @Column(nullable = false)
    private String cae;

    @Column(name = "vencimiento_cae", nullable = false)
    private LocalDate vencimientoCae;

    /** Pesos enteros: el total cobrado de la venta. */
    @Column(nullable = false)
    private long importe;

    /** Fecha del comprobante, en hora argentina. */
    @Column(nullable = false)
    private LocalDate fecha;

    /** En UTC y sin fracción de segundo. */
    @Column(nullable = false)
    private LocalDateTime emitida;

    protected Factura() {
        // JPA
    }

    public Factura(String id, String ventaId, String compradorId, String eventoId, int tipoComprobante,
            int puntoDeVenta, long numero, String cae, LocalDate vencimientoCae, long importe, LocalDate fecha,
            LocalDateTime emitida) {
        this.id = id;
        this.ventaId = ventaId;
        this.compradorId = compradorId;
        this.eventoId = eventoId;
        this.tipoComprobante = tipoComprobante;
        this.puntoDeVenta = puntoDeVenta;
        this.numero = numero;
        this.cae = cae;
        this.vencimientoCae = vencimientoCae;
        this.importe = importe;
        this.fecha = fecha;
        this.emitida = emitida;
    }

    /** ¿Es de ese comprador? Una factura ajena se responde como si no existiera. */
    public boolean esDe(String usuarioId) {
        return compradorId.equals(usuarioId);
    }

    public String getId() { return id; }
    public String getVentaId() { return ventaId; }
    public String getCompradorId() { return compradorId; }
    public String getEventoId() { return eventoId; }
    public int getTipoComprobante() { return tipoComprobante; }
    public int getPuntoDeVenta() { return puntoDeVenta; }
    public long getNumero() { return numero; }
    public String getCae() { return cae; }
    public LocalDate getVencimientoCae() { return vencimientoCae; }
    public long getImporte() { return importe; }
    public LocalDate getFecha() { return fecha; }
    public LocalDateTime getEmitida() { return emitida; }
}
