package com.palco.eventos.dominio;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(name = "evento")
public class Evento {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String titulo;

    @Column(nullable = false)
    private Categoria categoria;

    @Column(nullable = false)
    private String venue;

    @Column(nullable = false)
    private LocalDateTime fecha;

    @Column(nullable = false)
    private String descripcion;

    @Column(nullable = false)
    private boolean destacado;

    @Column(name = "imagen_url")
    private String imagenUrl;

    @Column(name = "organizador_id")
    private String organizadorId;

    @OneToMany(mappedBy = "evento", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orden")
    private List<Sector> sectores = new ArrayList<>();

    protected Evento() {
        // JPA
    }

    public Evento(String id, String slug, String titulo, Categoria categoria, String venue, LocalDateTime fecha,
            String descripcion, String imagenUrl, String organizadorId) {
        this.id = id;
        this.slug = slug;
        this.titulo = titulo;
        this.categoria = categoria;
        this.venue = venue;
        this.fecha = fecha;
        this.descripcion = descripcion;
        this.imagenUrl = imagenUrl;
        this.organizadorId = organizadorId;
    }

    public void agregarSector(Sector sector) {
        sectores.add(sector);
    }

    public String getId() { return id; }
    public String getSlug() { return slug; }
    public String getTitulo() { return titulo; }
    public Categoria getCategoria() { return categoria; }
    public String getVenue() { return venue; }
    public LocalDateTime getFecha() { return fecha; }
    public String getDescripcion() { return descripcion; }
    public boolean isDestacado() { return destacado; }
    public String getImagenUrl() { return imagenUrl; }
    public String getOrganizadorId() { return organizadorId; }
    public List<Sector> getSectores() { return sectores; }
}
