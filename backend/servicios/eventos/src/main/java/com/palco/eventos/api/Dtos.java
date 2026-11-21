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
 *
 * Cada anotación de validación lleva su propio {@code message}: es el texto
 * que el 400 devuelve por campo. Sin él, Bean Validation usa su mensaje por
 * defecto, que se traduce según el idioma del pedido o el del servidor: en la
 * imagen de Docker, a un cliente que no manda {@code Accept-Language} le llega
 * en inglés ("size must be between 0 and 200").
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
            /** Un evento cancelado sigue existiendo y su detalle responde: por eso viaja el motivo. */
            boolean cancelado,
            String cancelacionMotivo,
            List<SectorDto> sectores) {
    }

    /** El dueño de un evento: el id del organizador en Usuarios, o null si el evento no tiene. */
    public record OrganizadorDto(String eventoId, String organizadorId) {
    }

    public record NuevoSectorDto(
            @NotBlank(message = "Ingresá el nombre del sector.")
            @Size(max = 120, message = "El nombre del sector tiene hasta 120 caracteres.")
            String nombre,
            @Min(value = 1, message = "El precio tiene que ser mayor a 0.") long precio,
            @Min(value = 1, message = "El cupo tiene que ser mayor a 0.") int cupo) {
    }

    public record NuevoEventoDto(
            @NotBlank(message = "Ingresá el título.")
            @Size(max = 200, message = "El título tiene hasta 200 caracteres.")
            String titulo,
            @NotBlank(message = "Elegí una categoría.") String categoria,
            @NotBlank(message = "Ingresá el lugar.")
            @Size(max = 200, message = "El lugar tiene hasta 200 caracteres.")
            String venue,
            @NotNull(message = "Ingresá la fecha.") LocalDateTime fechaISO,
            @NotBlank(message = "Ingresá una descripción.")
            @Size(max = 2000, message = "La descripción tiene hasta 2000 caracteres.")
            String descripcion,
            @Size(max = 1000, message = "La URL de la imagen tiene hasta 1000 caracteres.") String imagenUrl,
            @NotEmpty(message = "Agregá al menos un sector.") List<@Valid NuevoSectorDto> sectores) {
    }

    /**
     * Un sector al editar el evento. Con {@code id} es uno que ya existe y se
     * cambia; sin {@code id}, uno nuevo que se agrega. Los sectores del evento
     * que no estén en la lista se quitan, porque {@code PUT} reemplaza el
     * evento completo.
     */
    public record SectorEditadoDto(
            @Size(max = 40, message = "El id del sector tiene hasta 40 caracteres.") String id,
            @NotBlank(message = "Ingresá el nombre del sector.")
            @Size(max = 120, message = "El nombre del sector tiene hasta 120 caracteres.")
            String nombre,
            @Min(value = 1, message = "El precio tiene que ser mayor a 0.") long precio,
            @Min(value = 1, message = "El cupo tiene que ser mayor a 0.") int cupo) {
    }

    /**
     * Los datos nuevos del evento. Es un reemplazo completo: lo que no venga se
     * borra. El slug no se puede cambiar (lo lleva la URL del frontend) y
     * tampoco el dueño.
     */
    public record EditarEventoDto(
            @NotBlank(message = "Ingresá el título.")
            @Size(max = 200, message = "El título tiene hasta 200 caracteres.")
            String titulo,
            @NotBlank(message = "Elegí una categoría.") String categoria,
            @NotBlank(message = "Ingresá el lugar.")
            @Size(max = 200, message = "El lugar tiene hasta 200 caracteres.")
            String venue,
            @NotNull(message = "Ingresá la fecha.") LocalDateTime fechaISO,
            @NotBlank(message = "Ingresá una descripción.")
            @Size(max = 2000, message = "La descripción tiene hasta 2000 caracteres.")
            String descripcion,
            @Size(max = 1000, message = "La URL de la imagen tiene hasta 1000 caracteres.") String imagenUrl,
            @NotEmpty(message = "El evento tiene que quedar con al menos un sector.")
            List<@Valid SectorEditadoDto> sectores) {
    }

    /** La URL pública de una imagen recién subida: es lo que se manda como {@code imagenUrl} del evento. */
    public record ImagenSubidaDto(String imagenUrl) {
    }

    /** El motivo de la cancelación, opcional: es lo que se le cuenta al comprador. */
    public record CancelacionDto(
            @Size(max = 500, message = "El motivo tiene hasta 500 caracteres.") String motivo) {
    }

    /**
     * Cambio de entradas vendidas de un sector: positivo para vender o
     * reservar, negativo para liberar.
     *
     * {@code clave} es opcional y solo vale para liberar: si viene, la
     * devolución es segura de repetir (la segunda vez con la misma clave no
     * resta nada). Quien devuelve arma una por venta y sector.
     */
    public record CambioCupoDto(
            int cantidad,
            @Size(max = 120, message = "La clave de devolución tiene como máximo 120 caracteres.") String clave) {
    }
}
