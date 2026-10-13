package com.palco.ventas.api;

import java.util.List;
import java.util.Map;

import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Lo que entra y sale de la API de ventas. Los nombres de los campos son los
 * de la sección 6 de {@code docs/planes/00-contratos-compartidos.md}.
 *
 * Las anotaciones validan la forma del pedido (que no falte nada y que cada
 * dato entre en su columna) y responden 400. Las reglas de negocio (el máximo
 * de entradas, un titular por entrada, el DNI sin repetir) no van acá: las
 * aplica {@code ServicioDeVentas} y responden 422. Por eso {@code cantidad}
 * no lleva un máximo.
 */
public final class Dtos {

    private Dtos() {
    }

    /** El pedido de compra: qué evento, cuántas entradas de cada sector y a nombre de quién va cada una. */
    public record NuevaVentaDto(
            @NotBlank(message = "Falta el evento.")
            @Size(max = 40, message = "El id del evento tiene hasta 40 caracteres.")
            String eventoId,

            @NotEmpty(message = "Elegí al menos una entrada.")
            List<@NotNull(message = "Hay un renglón vacío.") @Valid ItemPedidoDto> items,

            /** Uno por entrada. Se asignan a los sectores en el orden de {@code items}. */
            @NotEmpty(message = "Faltan los titulares de las entradas.")
            List<@NotNull(message = "Hay un titular vacío.") @Valid TitularDto> titulares) {
    }

    /** Un renglón del pedido: cuántas entradas de un sector. El precio no viaja: lo dice Eventos al reservar. */
    public record ItemPedidoDto(
            @NotBlank(message = "Falta el sector.")
            @Size(max = 40, message = "El id del sector tiene hasta 40 caracteres.")
            String sectorId,

            @Min(value = 1, message = "La cantidad tiene que ser al menos 1.")
            int cantidad) {
    }

    /**
     * La persona a cuyo nombre sale una entrada. {@code nombre} lleva nombre y
     * apellido juntos. El formato del DNI no se valida acá con una expresión
     * propia: la regla está una sola vez, en {@code ReglasDeNegocio.esDniValido},
     * y la aplica {@code ServicioDeVentas}.
     */
    public record TitularDto(
            @NotBlank(message = "Ingresá el nombre y apellido del titular.")
            @Size(max = 200, message = "El nombre del titular tiene hasta 200 caracteres.")
            String nombre,

            @NotBlank(message = "El DNI debe tener 7 u 8 dígitos.")
            String dni) {
    }

    /**
     * El pedido de pago. {@code token} es el que entrega la pasarela al cargar
     * la tarjeta: se le pasa al cobro y no se guarda en ninguna tabla.
     */
    public record NuevoPagoDto(
            @NotBlank(message = "Falta el medio de pago.")
            @Size(max = 40, message = "El medio de pago tiene hasta 40 caracteres.")
            String medioDePago,

            @NotBlank(message = "Falta el token del medio de pago.")
            @Size(max = 200, message = "El token tiene hasta 200 caracteres.")
            String token) {
    }

    /** Un renglón de la venta, con el nombre y el precio del sector que devolvió Eventos al reservar. */
    public record ItemDto(String sectorId, String sector, int cantidad, long precioUnitario) {
    }

    /**
     * Un enlace a algo que el cliente puede hacer con el recurso: la ruta, sin
     * host, y el método HTTP. Los dos nombres están en inglés porque así
     * figuran en la OpenAPI del grupo.
     */
    public record EnlaceDto(String href, String method) {
    }

    /**
     * Una entrada dentro de la venta. La lista está vacía hasta que se aprueba
     * el pago. {@code enlaces} sale en el JSON como {@code _links}, igual que
     * en {@link VentaDto}.
     */
    public record EntradaDeVentaDto(
            String id,
            String sector,
            TitularDto titular,
            String estado,
            @JsonbProperty("_links") Map<String, EnlaceDto> enlaces) {

        /** La misma entrada, con los enlaces que arma la capa REST. */
        public EntradaDeVentaDto conEnlaces(Map<String, EnlaceDto> nuevos) {
            return new EntradaDeVentaDto(id, sector, titular, estado, nuevos);
        }
    }

    /**
     * La entrada completa, como responde {@code GET /entradas/{id}}.
     * {@code precioPagado} es el precio del sector, sin el cargo de servicio.
     * {@code codigoQr} y {@code emitida} son null mientras la entrada está en
     * {@code PENDIENTE_EMISION}; {@code emitida} es un instante en UTC, con la
     * Z al final. {@code enlaces} sale en el JSON como {@code _links}.
     */
    public record EntradaDto(
            String id,
            String ventaId,
            String eventoId,
            String sectorId,
            String sector,
            TitularDto titular,
            long precioPagado,
            String estado,
            String codigoQr,
            String emitida,
            @JsonbProperty("_links") Map<String, EnlaceDto> enlaces) {

        /** La misma entrada, con los enlaces que arma la capa REST. */
        public EntradaDto conEnlaces(Map<String, EnlaceDto> nuevos) {
            return new EntradaDto(id, ventaId, eventoId, sectorId, sector, titular, precioPagado, estado, codigoQr,
                    emitida, nuevos);
        }
    }

    /**
     * La venta como la ve el comprador. Los montos son pesos enteros.
     * {@code venceReserva} es un instante en UTC, con la Z al final
     * ("2026-10-19T21:10:00Z"). {@code pagoId} es null hasta que se paga.
     *
     * {@code enlaces} dice qué se puede hacer con la venta en el estado en que
     * está, y sale en el JSON con el nombre {@code _links}, que es el del
     * contrato. El negocio lo deja vacío: las rutas son cosa de la capa REST,
     * que lo completa al responder (ver {@code Enlaces}).
     */
    public record VentaDto(
            String id,
            String estado,
            String facturaEstado,
            String eventoId,
            List<ItemDto> items,
            long subtotal,
            long cargoServicio,
            long montoTotal,
            String venceReserva,
            String pagoId,
            List<EntradaDeVentaDto> entradas,
            @JsonbProperty("_links") Map<String, EnlaceDto> enlaces) {

        /** La misma venta, con sus entradas y sus enlaces ya completados por la capa REST. */
        public VentaDto conEnlaces(List<EntradaDeVentaDto> entradasConEnlaces, Map<String, EnlaceDto> nuevos) {
            return new VentaDto(id, estado, facturaEstado, eventoId, items, subtotal, cargoServicio, montoTotal,
                    venceReserva, pagoId, entradasConEnlaces, nuevos);
        }
    }
}
