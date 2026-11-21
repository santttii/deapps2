package com.palco.facturacion.dominio;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Un comprobante que ARCA rechazó ({@code Resultado} "R"): lo que se le pidió
 * y por qué no lo autorizó. Hay uno como mucho por venta (restricción única en
 * la base) y no cambia después de creado.
 *
 * Es aparte de {@link Factura} y no un estado de ella: una factura siempre
 * tiene número y CAE, y un rechazo no tiene ninguno de los dos.
 *
 * Sirve para dos cosas: que la consulta de la factura de esa venta diga que
 * ARCA la rechazó, en lugar del mismo 404 que mientras la factura está en
 * camino, y que quien lo revise tenga el motivo y lo que se pidió.
 */
@Entity
@Table(name = "comprobante_rechazado")
public class ComprobanteRechazado {

    /** El largo de la columna {@code motivo}: una observación más larga se guarda recortada. */
    public static final int LARGO_MAXIMO_MOTIVO = 500;

    /** "R-" más 8 caracteres de un UUID; lo asigna el código, no la base. */
    @Id
    private String id;

    /** La venta que no se pudo facturar. Vive en ServicioDeVentas: acá es solo un texto. Única en la tabla. */
    @Column(name = "venta_id", nullable = false, unique = true)
    private String ventaId;

    /** El usuario que compró: es el único comprador que se entera del rechazo al consultar la factura. */
    @Column(name = "comprador_id", nullable = false)
    private String compradorId;

    @Column(name = "evento_id", nullable = false)
    private String eventoId;

    /** Pesos enteros: el total que se pidió facturar. */
    @Column(nullable = false)
    private long importe;

    /** Fecha del comprobante que se pidió, en hora argentina. */
    @Column(nullable = false)
    private LocalDate fecha;

    /** Lo que respondió ARCA, recortado a {@link #LARGO_MAXIMO_MOTIVO} caracteres. */
    @Column(nullable = false, length = LARGO_MAXIMO_MOTIVO)
    private String motivo;

    /** En UTC y sin fracción de segundo. */
    @Column(nullable = false)
    private LocalDateTime rechazado;

    protected ComprobanteRechazado() {
        // JPA
    }

    public ComprobanteRechazado(String id, String ventaId, String compradorId, String eventoId, long importe,
            LocalDate fecha, String motivo, LocalDateTime rechazado) {
        this.id = id;
        this.ventaId = ventaId;
        this.compradorId = compradorId;
        this.eventoId = eventoId;
        this.importe = importe;
        this.fecha = fecha;
        // La observación la escribe un sistema externo y no tiene largo fijo: recortarla acá
        // evita que un motivo largo haga fallar justo el registro del rechazo.
        String completo = motivo == null ? "" : motivo;
        this.motivo = completo.length() <= LARGO_MAXIMO_MOTIVO ? completo : completo.substring(0, LARGO_MAXIMO_MOTIVO);
        this.rechazado = rechazado;
    }

    public String getId() { return id; }
    public String getVentaId() { return ventaId; }
    public String getCompradorId() { return compradorId; }
    public String getEventoId() { return eventoId; }
    public long getImporte() { return importe; }
    public LocalDate getFecha() { return fecha; }
    public String getMotivo() { return motivo; }
    public LocalDateTime getRechazado() { return rechazado; }
}
