package com.palco.eventos.api;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Lo que entra y sale de la API de eventos. Los campos coinciden con el tipo
 * {@code Evento} del frontend ({@code frontend/src/types/index.ts}).
 */
public final class Dtos {

    private Dtos() {
    }

    public record SectorDto(String id, String nombre, long precio, int cupo, int vendidas) {
    }

    public record EventoDto(
            String id,
            String slug,
            String titulo,
            String categoria,
            String venue,
            /** Fecha y hora local, formato "2026-10-08T20:30:00" (como el frontend). */
            String fechaISO,
            String descripcion,
            boolean destacado,
            String imagenUrl,
            List<SectorDto> sectores) {
    }

    public record NuevoSectorDto(
            @NotBlank(message = "Ingresá el nombre del sector.") @Size(max = 120) String nombre,
            @Min(value = 1, message = "El precio tiene que ser mayor a 0.") long precio,
            @Min(value = 1, message = "El cupo tiene que ser mayor a 0.") int cupo) {
    }

    public record NuevoEventoDto(
            @NotBlank(message = "Ingresá el título.") @Size(max = 200) String titulo,
            @NotBlank(message = "Elegí una categoría.") String categoria,
            @NotBlank(message = "Ingresá el lugar.") @Size(max = 200) String venue,
            @NotNull(message = "Ingresá la fecha.") LocalDateTime fechaISO,
            @NotBlank(message = "Ingresá una descripción.") @Size(max = 2000) String descripcion,
            @Size(max = 1000) String imagenUrl,
            @NotEmpty(message = "Agregá al menos un sector.") List<@Valid NuevoSectorDto> sectores) {
    }

    /**
     * Cambio de entradas vendidas de un sector: positivo para vender o
     * reservar, negativo para liberar.
     */
    public record CambioCupoDto(int cantidad) {
    }
}
