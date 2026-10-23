package com.palco.pagos.dominio;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Un cobro que la pasarela ya resolvió, aprobado o rechazado. Un aprobado
 * puede terminar reembolsado. No guarda el token de la tarjeta: sirve para un
 * solo cobro y es un dato sensible.
 */
@Entity
@Table(name = "pago")
public class Pago {

    /** "P-" más 8 caracteres de un UUID; lo asigna el código, no la base. */
    @Id
    private String id;

    /** La venta que se cobra. Vive en ServicioDeVentas: acá es solo un texto. */
    @Column(name = "venta_id", nullable = false)
    private String ventaId;

    /** Pesos enteros. */
    @Column(nullable = false)
    private long monto;

    @Column(name = "medio_de_pago", nullable = false)
    private String medioDePago;

    /** Qué pasarela lo cobró: lo que devuelve {@code PasarelaDePagos.nombre()}. */
    @Column(nullable = false)
    private String proveedor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoPago estado;

    /** Por qué se rechazó; null si se aprobó. */
    private String motivo;

    /** Única en la tabla: es lo que impide cobrar dos veces el mismo pedido. */
    @Column(name = "clave_idempotencia", nullable = false, unique = true)
    private String claveIdempotencia;

    /** El id del pago en la pasarela. Único en la tabla. */
    @Column(name = "id_externo", nullable = false, unique = true)
    private String idExterno;

    /** En UTC y sin fracción de segundo. */
    @Column(nullable = false)
    private LocalDateTime creado;

    @Column(nullable = false)
    private LocalDateTime actualizado;

    /** Por qué se devolvió; null si no se devolvió. */
    @Column(name = "motivo_reembolso")
    private String motivoReembolso;

    /** Cuándo se devolvió, en UTC; null si no se devolvió. */
    private LocalDateTime reembolsado;

    protected Pago() {
        // JPA
    }

    /** {@code ahora} llega como parámetro (sale de un Clock en UTC): la entidad no mira el reloj del servidor. */
    public Pago(String id, String ventaId, long monto, String medioDePago, String proveedor, EstadoPago estado,
            String motivo, String claveIdempotencia, String idExterno, LocalDateTime ahora) {
        this.id = id;
        this.ventaId = ventaId;
        this.monto = monto;
        this.medioDePago = medioDePago;
        this.proveedor = proveedor;
        this.estado = estado;
        this.motivo = motivo;
        this.claveIdempotencia = claveIdempotencia;
        this.idExterno = idExterno;
        this.creado = ahora;
        this.actualizado = ahora;
    }

    public String getId() { return id; }
    public String getVentaId() { return ventaId; }
    public long getMonto() { return monto; }
    public String getMedioDePago() { return medioDePago; }
    public String getProveedor() { return proveedor; }
    public EstadoPago getEstado() { return estado; }
    public String getMotivo() { return motivo; }
    public String getClaveIdempotencia() { return claveIdempotencia; }
    public String getIdExterno() { return idExterno; }
    public LocalDateTime getCreado() { return creado; }
    public LocalDateTime getActualizado() { return actualizado; }
    public String getMotivoReembolso() { return motivoReembolso; }
    public LocalDateTime getReembolsado() { return reembolsado; }

    /**
     * {@code APROBADO} → {@code REEMBOLSADO}, sobre la entidad. Lo usa el
     * repositorio en memoria de los tests; el de la base lo hace con un
     * UPDATE condicional ({@code PagoRepository.marcarReembolsado}).
     */
    public boolean reembolsar(String motivo, LocalDateTime ahora) {
        if (estado != EstadoPago.APROBADO) {
            return false;
        }
        this.estado = EstadoPago.REEMBOLSADO;
        this.motivoReembolso = motivo;
        this.reembolsado = ahora;
        this.actualizado = ahora;
        return true;
    }
}
