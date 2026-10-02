package com.palco.eventos.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Tipo de entrada de un evento (Platea A, Campo general…), con su precio y cupo. */
@Entity
@Table(name = "sector")
public class Sector {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evento_id")
    private Evento evento;

    @Column(nullable = false)
    private String nombre;

    /** Pesos enteros. */
    @Column(nullable = false)
    private long precio;

    @Column(nullable = false)
    private int cupo;

    @Column(nullable = false)
    private int vendidas;

    @Column(nullable = false)
    private int orden;

    protected Sector() {
        // JPA
    }

    public Sector(String id, Evento evento, String nombre, long precio, int cupo, int orden) {
        this.id = id;
        this.evento = evento;
        this.nombre = nombre;
        this.precio = precio;
        this.cupo = cupo;
        this.orden = orden;
    }

    public int disponibles() {
        return cupo - vendidas;
    }

    public String getId() { return id; }
    public Evento getEvento() { return evento; }
    public String getNombre() { return nombre; }
    public long getPrecio() { return precio; }
    public int getCupo() { return cupo; }
    public int getVendidas() { return vendidas; }
    public int getOrden() { return orden; }
}
