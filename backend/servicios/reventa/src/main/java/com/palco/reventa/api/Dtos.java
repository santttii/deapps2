package com.palco.reventa.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Lo que entra y sale de la API de la reventa. Los montos son pesos enteros;
 * las fechas, instantes en UTC con la Z al final.
 */
public final class Dtos {

    private Dtos() {
    }

    /**
     * Una publicación. {@code precio} es lo que paga el comprador por la
     * entrada, sin el cargo de servicio; {@code precioMaximo} es el tope de
     * esa entrada (lo pagado más el 10%). {@code estado} es {@code ACTIVA},
     * {@code VENDIDA}, {@code RETIRADA} o {@code ANULADA}.
     */
    public record PublicacionDto(
            String id,
            String entradaId,
            String eventoId,
            String sectorId,
            String sector,
            long precio,
            long precioMaximo,
            String estado,
            String publicada) {
    }

    /** El pedido de publicar. El rango del precio lo controla el negocio (422), no esta validación. */
    public record NuevaPublicacionDto(
            @NotBlank(message = "Indicá la entrada que querés revender.")
            @Size(max = 40, message = "El id de la entrada tiene hasta 40 caracteres.")
            String entradaId,

            @NotNull(message = "Indicá el precio.")
            Long precio) {
    }

    /** El titular nuevo de la entrada. El DNI se valida en el negocio, con la regla de ReglasDeNegocio. */
    public record TitularDto(
            @NotBlank(message = "Ingresá el nombre y apellido del titular.")
            @Size(max = 200, message = "El nombre del titular tiene hasta 200 caracteres.")
            String nombre,

            @NotBlank(message = "El DNI debe tener 7 u 8 dígitos.")
            String dni) {
    }

    /** El pedido de compra. {@code token} es el de la pasarela: se le pasa a Pagos y no se guarda. */
    public record CompraDto(
            @NotNull(message = "Falta el titular.")
            @Valid
            TitularDto titular,

            @NotBlank(message = "Falta el medio de pago.")
            @Size(max = 40, message = "El medio de pago tiene hasta 40 caracteres.")
            String medioDePago,

            @NotBlank(message = "Falta el token del medio de pago.")
            @Size(max = 200, message = "El token tiene hasta 200 caracteres.")
            String token) {
    }

    /** La entrada que recibió el comprador: nueva, VALIDA, con su código QR y a su nombre. */
    public record EntradaTransferidaDto(
            String id,
            String codigoQr,
            TitularDto titular,
            String eventoId,
            String sectorId,
            long precioPagado) {
    }

    /** El resultado de una compra: la publicación ya VENDIDA, la entrada nueva y la venta del comprador en Ventas. */
    public record CompraHechaDto(PublicacionDto publicacion, EntradaTransferidaDto entrada, String ventaId) {
    }
}
