package com.palco.ventas.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.palco.ventas.api.Dtos.EntradaDto;
import com.palco.ventas.api.Dtos.TitularDto;

/**
 * Lo que entra y sale de las operaciones de reventa de Ventas
 * ({@link ReventaDeEntradasResource}). Es una API interna: la usa
 * ServicioDeReventa con el token de servicio, nunca el frontend. Por eso el
 * vendedor y el comprador viajan en el cuerpo: el token es del servicio, no
 * de la persona.
 */
public final class DtosDeReventa {

    private DtosDeReventa() {
    }

    /** Una entrada vista desde la reventa: de qué evento y sector es, lo que se pagó por ella y su estado. */
    public record EntradaEnReventaDto(
            String entradaId,
            String ventaId,
            String eventoId,
            String sectorId,
            String sector,
            long precioPagado,
            String estado) {
    }

    /** Publicar o retirar: qué entrada y quién dice ser su dueño. */
    public record PedidoDeVendedorDto(
            @NotBlank(message = "Falta la entrada.")
            @Size(max = 40, message = "El id de la entrada tiene hasta 40 caracteres.")
            String entradaId,

            @NotBlank(message = "Falta el vendedor.")
            @Size(max = 40, message = "El id del vendedor tiene hasta 40 caracteres.")
            String vendedorId) {
    }

    /**
     * La transferencia de una entrada vendida en la reventa. {@code precio} es
     * el de la publicación, sin el cargo de servicio: pasa a ser el
     * {@code precioPagado} de la entrada nueva. {@code pagoId} es el cobro que
     * ServicioDeReventa ya hizo contra Pagos.
     */
    public record TransferenciaDto(
            @NotBlank(message = "Falta la publicación.")
            @Size(max = 40, message = "El id de la publicación tiene hasta 40 caracteres.")
            String publicacionId,

            @NotBlank(message = "Falta la entrada.")
            @Size(max = 40, message = "El id de la entrada tiene hasta 40 caracteres.")
            String entradaId,

            @NotBlank(message = "Falta el vendedor.")
            @Size(max = 40, message = "El id del vendedor tiene hasta 40 caracteres.")
            String vendedorId,

            @NotBlank(message = "Falta el comprador.")
            @Size(max = 40, message = "El id del comprador tiene hasta 40 caracteres.")
            String compradorId,

            @NotNull(message = "Falta el titular.")
            @Valid
            TitularDto titular,

            @Min(value = 1, message = "El precio tiene que ser mayor a 0.")
            long precio,

            @NotBlank(message = "Falta el pago.")
            @Size(max = 40, message = "El id del pago tiene hasta 40 caracteres.")
            String pagoId) {
    }

    /** La venta nueva del comprador y su entrada, ya {@code VALIDA} y con código QR. */
    public record TransferenciaHechaDto(String ventaId, EntradaDto entrada) {
    }
}
