package com.palco.ventas.dominio;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import com.palco.common.ReglasDeNegocio;

/**
 * Una compra: quién compra, qué evento, cuántas entradas de cada sector y en
 * qué estado está. El token de la tarjeta no se guarda.
 *
 * Desde el código solo se le agregan renglones ({@link #agregarItem}). El
 * estado, la clave del pago y el id del pago no tienen "set": los cambia
 * {@code VentaRepository} con un UPDATE condicional, y después de eso la
 * venta se vuelve a buscar.
 */
@Entity
@Table(name = "venta")
public class Venta {

    /** "V-" más 8 caracteres de un UUID; lo asigna el código, no la base. */
    @Id
    private String id;

    /** El usuario que compra (el claim "sub" de su token). Vive en ServicioDeUsuarios: acá es solo un texto. */
    @Column(name = "comprador_id", nullable = false)
    private String compradorId;

    /** El evento. Vive en ServicioDeEventos: acá es solo un texto. */
    @Column(name = "evento_id", nullable = false)
    private String eventoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoVenta estado;

    @Enumerated(EnumType.STRING)
    @Column(name = "factura_estado", nullable = false)
    private EstadoFactura facturaEstado;

    /** Pesos enteros: la suma de precio por cantidad de los renglones. */
    @Column(nullable = false)
    private long subtotal;

    @Column(name = "cargo_servicio", nullable = false)
    private long cargoServicio;

    /** Lo que se cobra: subtotal más cargo de servicio. */
    @Column(name = "monto_total", nullable = false)
    private long montoTotal;

    /** Hasta cuándo vale la reserva del cupo, en UTC. Es el único reloj de la reserva. */
    @Column(name = "vence_reserva", nullable = false)
    private LocalDateTime venceReserva;

    /** La Idempotency-Key del pedido que creó la venta. Única por comprador: es lo que impide crear dos veces la misma compra. */
    @Column(name = "clave_idempotencia", nullable = false)
    private String claveIdempotencia;

    /** La Idempotency-Key del intento de pago; null si nadie intentó pagar. */
    @Column(name = "clave_pago")
    private String clavePago;

    /** El pago aprobado; null hasta que la venta se confirma. */
    @Column(name = "pago_id")
    private String pagoId;

    /** En UTC y sin fracción de segundo, igual que {@code confirmada}. */
    @Column(nullable = false)
    private LocalDateTime creada;

    private LocalDateTime confirmada;

    /** Los renglones se guardan junto con la venta (cascade): no tienen repositorio propio. */
    @OneToMany(mappedBy = "venta", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orden")
    private List<VentaItem> items = new ArrayList<>();

    /** Solo lectura: las entradas las guarda {@code EntradaRepository} cuando se aprueba el pago. */
    @OneToMany(mappedBy = "venta")
    @OrderBy("orden")
    private List<Entrada> entradas = new ArrayList<>();

    protected Venta() {
        // JPA
    }

    /**
     * Una venta nueva: {@code EN_CHECKOUT}, sin factura, sin renglones y con los montos en cero.
     * {@code ahora} y {@code venceReserva} llegan como parámetro (salen de un Clock en UTC):
     * la entidad no mira el reloj del servidor.
     */
    public Venta(String id, String compradorId, String eventoId, String claveIdempotencia, LocalDateTime ahora,
            LocalDateTime venceReserva) {
        this.id = id;
        this.compradorId = compradorId;
        this.eventoId = eventoId;
        this.claveIdempotencia = claveIdempotencia;
        this.estado = EstadoVenta.EN_CHECKOUT;
        this.facturaEstado = EstadoFactura.NO_APLICA;
        this.creada = ahora;
        this.venceReserva = venceReserva;
    }

    /**
     * Suma un renglón (un sector y cuántas entradas) y recalcula los montos, así
     * el total nunca queda distinto de lo que dicen los renglones. El nombre y el
     * precio son los que devolvió Eventos al reservar el cupo.
     */
    public void agregarItem(String sectorId, String sector, long precioUnitario, int cantidad) {
        int orden = items.size() + 1;
        VentaItem item = new VentaItem(id + "-" + orden, this, sectorId, sector, precioUnitario, cantidad, orden);
        items.add(item);

        subtotal += item.subtotal();
        // La tasa está en un solo lugar del sistema: ReglasDeNegocio.
        cargoServicio = ReglasDeNegocio.cargoServicio(subtotal);
        montoTotal = subtotal + cargoServicio;
    }

    /**
     * Dice si la reserva del cupo ya no vale en el instante {@code ahora}. La
     * reserva vale mientras {@code ahora} sea anterior a {@code venceReserva}:
     * es la misma condición que usan los UPDATE de {@code VentaRepository}.
     */
    public boolean reservaVencida(LocalDateTime ahora) {
        return !ahora.isBefore(venceReserva);
    }

    /** Una venta está paga desde que se confirma: ya no se puede cancelar ni volver a pagar. */
    public boolean estaPaga() {
        return estado == EstadoVenta.CONFIRMADA || estado == EstadoVenta.EMITIDA;
    }

    public String getId() { return id; }
    public String getCompradorId() { return compradorId; }
    public String getEventoId() { return eventoId; }
    public EstadoVenta getEstado() { return estado; }
    public EstadoFactura getFacturaEstado() { return facturaEstado; }
    public long getSubtotal() { return subtotal; }
    public long getCargoServicio() { return cargoServicio; }
    public long getMontoTotal() { return montoTotal; }
    public LocalDateTime getVenceReserva() { return venceReserva; }
    public String getClaveIdempotencia() { return claveIdempotencia; }
    public String getClavePago() { return clavePago; }
    public String getPagoId() { return pagoId; }
    public LocalDateTime getCreada() { return creada; }
    public LocalDateTime getConfirmada() { return confirmada; }
    public List<VentaItem> getItems() { return items; }
    public List<Entrada> getEntradas() { return entradas; }
}
