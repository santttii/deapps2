package com.palco.eventos.dominio;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * La foto de un evento guardada en la base. El id es un UUID con la extensión
 * ({@code 3f9a….jpg}) y es lo que va en la URL pública; el tipo es el media type
 * con el que se la devuelve. No guarda el nombre del archivo que se subió: lo
 * escribió alguien y no hace falta.
 */
@Entity
@Table(name = "imagen")
public class Imagen {

    @Id
    private String id;

    @Column(nullable = false)
    private String tipo;

    @Column(nullable = false)
    private byte[] contenido;

    @Column(nullable = false)
    private int tamano;

    @Column(nullable = false)
    private LocalDateTime subida;

    protected Imagen() {
        // JPA
    }

    public Imagen(String id, String tipo, byte[] contenido, LocalDateTime subida) {
        this.id = id;
        this.tipo = tipo;
        this.contenido = contenido;
        this.tamano = contenido.length;
        this.subida = subida;
    }

    public String getId() { return id; }
    public String getTipo() { return tipo; }
    public byte[] getContenido() { return contenido; }
    public int getTamano() { return tamano; }
    public LocalDateTime getSubida() { return subida; }
}
