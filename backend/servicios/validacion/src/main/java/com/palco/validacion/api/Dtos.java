package com.palco.validacion.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Lo que entra y sale de la API de validación. Los largos máximos son los de
 * las columnas de la tabla {@code escaneo}, así un dato que no entra se
 * rechaza con un 400 y no con un error de la base.
 *
 * Los instantes van en UTC, con la Z al final ("2026-11-20T21:03:12Z"),
 * igual que en Ventas.
 */
public final class Dtos {

    private Dtos() {
    }

    /** Lo que manda la pantalla de puerta: el código leído (o tipeado) y el evento que controla esa puerta. */
    public record NuevoEscaneoDto(
            @NotBlank(message = "Falta el código QR.")
            @Size(max = 200, message = "El código QR tiene hasta 200 caracteres.")
            String codigoQr,

            @NotBlank(message = "Falta el evento que controla la puerta.")
            @Size(max = 40, message = "El id del evento tiene hasta 40 caracteres.")
            String eventoId) {
    }

    /** El titular de la entrada, para contrastarlo con el documento. */
    public record TitularDto(String nombre, String dni) {
    }

    /** La entrada que se acaba de usar: solo viene cuando el resultado es {@code VALIDA}. */
    public record EntradaEscaneadaDto(String id, String sectorId, String sector, TitularDto titular) {
    }

    /**
     * El escaneo anterior del mismo código, cuando el resultado es
     * {@code YA_USADA}: el que la usó ({@code VALIDA}) o, si no hay, el último
     * que quedó sin respuesta de Ventas ({@code SIN_RESPUESTA}). Es null si
     * este servicio no registró ninguno de los dos.
     */
    public record UsoAnteriorDto(String escaneoId, String resultado, String escaneadoPor, String escaneado) {
    }

    /**
     * La respuesta al escanear. {@code resultado} es uno de {@code VALIDA},
     * {@code YA_USADA}, {@code INEXISTENTE}, {@code OTRO_EVENTO} o
     * {@code NO_HABILITADA}; {@code puedeEntrar} es true solo con
     * {@code VALIDA}. {@code mensaje} es el texto para mostrarle al personal
     * de puerta. {@code entrada} y {@code usoAnterior} pueden ser null.
     */
    public record ResultadoDeEscaneoDto(
            String id,
            String resultado,
            boolean puedeEntrar,
            String mensaje,
            String eventoId,
            EntradaEscaneadaDto entrada,
            UsoAnteriorDto usoAnterior,
            String escaneadoPor,
            String escaneado) {
    }

    /**
     * Un renglón del registro de la puerta. El código no viaja completo, solo
     * sus últimos caracteres ({@code codigoParcial}): un código de otro evento
     * escaneado por error sigue sirviendo en el suyo, y el registro lo ven
     * más personas que las de esa puerta. Con el final alcanza para
     * reconocerlo; el código completo queda en la base.
     */
    public record EscaneoDto(
            String id,
            String eventoId,
            String codigoParcial,
            String resultado,
            String entradaId,
            String escaneadoPor,
            String escaneado) {
    }
}
