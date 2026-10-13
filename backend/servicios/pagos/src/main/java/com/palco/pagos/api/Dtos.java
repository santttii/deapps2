package com.palco.pagos.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Lo que entra y sale de la API de pagos. Es una API interna: la consume
 * ServicioDeVentas, no el frontend. Los largos máximos son los de las columnas
 * de la tabla {@code pago}, así un dato que no entra se rechaza con un 400 y
 * no con un error de la base.
 */
public final class Dtos {

    private Dtos() {
    }

    /** El pedido de cobro. La clave de idempotencia no va acá: viaja en la cabecera {@code Idempotency-Key}. */
    public record NuevoPagoDto(
            @NotBlank(message = "Falta la venta que se cobra.")
            @Size(max = 40, message = "El id de la venta tiene hasta 40 caracteres.")
            String ventaId,

            /**
             * Pesos enteros. La validación corre después de que el conversor de JSON arma el
             * {@code long}: un decimal ya llegó truncado y un número que no entra en un
             * {@code long}, recortado. Se acepta porque el único cliente es Ventas, que manda un {@code long}.
             */
            @Min(value = 1, message = "El monto tiene que ser mayor a 0.")
            long monto,

            @NotBlank(message = "Falta el medio de pago.")
            @Size(max = 40, message = "El medio de pago tiene hasta 40 caracteres.")
            String medioDePago,

            /** El que entrega la pasarela al cargar la tarjeta. Se le pasa a la pasarela y no se guarda. */
            @NotBlank(message = "Falta el token del medio de pago.")
            @Size(max = 200, message = "El token tiene hasta 200 caracteres.")
            String token) {
    }

    /**
     * El pago ya resuelto. {@code estado} es {@code APROBADO} o {@code RECHAZADO};
     * {@code motivo} dice por qué se rechazó y es null si se aprobó;
     * {@code idExterno} es el id del pago en la pasarela.
     */
    public record PagoDto(String pagoId, String ventaId, long monto, String estado, String motivo, String idExterno) {
    }
}
