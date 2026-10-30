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

    @Column(nullable = false)
    private boolean cancelado;

    @Column(name = "cancelado_en")
    private LocalDateTime canceladoEn;

    /** Lo que se le cuenta al comprador. Puede ser null: cancelar sin explicación se permite. */
    @Column(name = "cancelacion_motivo")
    private String cancelacionMotivo;

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

    /**
     * Quita un sector. Por el {@code orphanRemoval} del mapeo, sacarlo de la
     * lista borra su fila al cerrar la transacción.
     */
    public void quitarSector(Sector sector) {
        sectores.remove(sector);
    }

    /**
     * Cambia los datos del evento. El slug no se toca a propósito: es lo que
     * lleva la URL del frontend ({@code /evento/kiroshi-tour-continental}) y
     * cambiarlo rompería los enlaces que ya circulan.
     */
    public void actualizarDatos(String titulo, Categoria categoria, String venue, LocalDateTime fecha,
            String descripcion, String imagenUrl) {
        this.titulo = titulo;
        this.categoria = categoria;
        this.venue = venue;
        this.fecha = fecha;
        this.descripcion = descripcion;
        this.imagenUrl = imagenUrl;
    }

    /**
     * Marca el evento como cancelado. No borra nada: las ventas las anula
     * Ventas cuando recibe el mensaje, y el evento queda visible para que
     * quien tenga el enlace vea que se canceló.
     */
    public void cancelar(String motivo, LocalDateTime ahora) {
        this.cancelado = true;
        this.canceladoEn = ahora;
        this.cancelacionMotivo = motivo == null || motivo.isBlank() ? null : motivo.strip();
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
    public boolean isCancelado() { return cancelado; }
    public LocalDateTime getCanceladoEn() { return canceladoEn; }
    public String getCancelacionMotivo() { return cancelacionMotivo; }
    public List<Sector> getSectores() { return sectores; }
}
