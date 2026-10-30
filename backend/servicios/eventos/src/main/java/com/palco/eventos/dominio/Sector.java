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

    public void renombrar(String nombre) {
        this.nombre = nombre;
    }

    /**
     * Cambia el precio de lista. Quien llama tiene que haber comprobado que no
     * haya entradas vendidas: el tope de la reventa se calcula sobre el precio
     * que pagó cada comprador, y mover el de lista después de vender dejaría
     * publicaciones con topes que no se corresponden con lo que se cobró.
     */
    public void cambiarPrecio(long precio) {
        this.precio = precio;
    }

    /**
     * Cambia el cupo. Quien llama tiene que haber comprobado que el cupo nuevo
     * no sea menor que las vendidas: la base lo garantiza igual
     * ({@code sector_vendidas_validas}), pero como error de restricción y no
     * con un mensaje que se entienda.
     */
    public void cambiarCupo(int cupo) {
        this.cupo = cupo;
    }

    public void cambiarOrden(int orden) {
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
