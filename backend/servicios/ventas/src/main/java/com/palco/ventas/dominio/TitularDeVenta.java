package com.palco.ventas.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * La copia de un titular de una compra que se mandó a cobrar: nombre, DNI y
 * su posición en el pedido.
 *
 * Mientras la compra no se intenta pagar, los titulares viven solo en su
 * instancia stateful. Justo antes del cobro, la instancia deja esta copia en
 * la base, en una transacción propia que ya terminó cuando se llama a Pagos.
 * Desde ese momento puede haber un cobro hecho, y la compra tiene que poder
 * terminarse aunque la instancia se pierda: la conciliación de pagos crea las
 * entradas con estos datos.
 *
 * No se cambia después de creada: no tiene "set" ni relación con la venta en
 * Java (la venta no la carga). En la base sí tiene la clave foránea.
 */
@Entity
@Table(name = "venta_titular")
public class TitularDeVenta {

    /** El id de la venta más la posición: "V-3f9a1c2b-1". */
    @Id
    private String id;

    @Column(name = "venta_id", nullable = false)
    private String ventaId;

    @Column(nullable = false)
    private String nombre;

    @Column(nullable = false)
    private String dni;

    /** Posición del titular en el pedido, empezando en 1. */
    @Column(nullable = false)
    private int orden;

    protected TitularDeVenta() {
        // JPA
    }

    public TitularDeVenta(String ventaId, int orden, String nombre, String dni) {
        this.id = ventaId + "-" + orden;
        this.ventaId = ventaId;
        this.orden = orden;
        this.nombre = nombre;
        this.dni = dni;
    }

    public String getId() { return id; }
    public String getVentaId() { return ventaId; }
    public String getNombre() { return nombre; }
    public String getDni() { return dni; }
    public int getOrden() { return orden; }
}
