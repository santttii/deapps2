package com.palco.common.mensajeria;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonReader;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;

import com.palco.common.ReglasDeNegocio;

/**
 * El mensaje {@code PAGO_CONFIRMADO}: lo publica Ventas cuando se aprueba un
 * pago y viaja como texto JSON, igual en la cola y en el tópico. El contrato
 * está en {@code docs/api/mensajeria-pago-confirmado.md}.
 *
 * Es un {@code record} porque es un dato que no cambia después de creado.
 * {@code timestamp} es un {@link Instant}: JSON-B lo escribe en UTC, como
 * {@code 2026-10-16T21:30:00Z}.
 *
 * Los métodos propios no empiezan con "get" ni "is": JSON-B los tomaría por
 * campos y los escribiría en el mensaje.
 *
 * @param ventaId     id de la venta
 * @param compradorId id del usuario que compró (el claim "sub" de su token)
 * @param eventoId    id del evento
 * @param entradas    una por entrada de la venta
 * @param montoTotal  total cobrado, en pesos enteros
 * @param timestamp   instante de la confirmación
 */
public record PagoConfirmadoMensaje(String ventaId, String compradorId, String eventoId,
        List<Entrada> entradas, long montoTotal, Instant timestamp) {

    /** @param sector nombre del sector, no su id */
    public record Entrada(String entradaId, String sector, Titular titular) {
    }

    /** @param nombre nombre y apellido */
    public record Titular(String nombre, String dni) {
    }

    /** Largo de la columna de los ids en todas las tablas. */
    private static final int LARGO_MAXIMO_ID = 40;

    private static final Jsonb JSONB = JsonbBuilder.create();

    /** El mensaje como texto JSON, con los campos en orden alfabético. */
    public String aJson() {
        return JSONB.toJson(this);
    }

    /**
     * Convierte el texto recibido y lo valida: devuelve un mensaje completo o
     * lanza {@link MensajeInvalido}. La validación va acá porque JSON-B no se
     * queja si falta un campo (lo deja en null o en 0) y para el texto
     * {@code null} devuelve null.
     */
    public static PagoConfirmadoMensaje desdeJson(String json) {
        if (json == null || json.isBlank()) {
            throw new MensajeInvalido("El mensaje está vacío.");
        }
        // Antes de convertirlo se lee el texto entero con el lector estándar de JSON (JSON-P), que
        // avisa si está incompleto. Hace falta porque JSON-B, con un JSON cortado dentro de un campo
        // que el mensaje no conoce (por ejemplo {"extra":{ ), no lanza error ni termina: el
        // consumidor quedaría girando para siempre, sin reintentos ni DLQ.
        try (JsonReader lector = Json.createReader(new StringReader(json))) {
            lector.readValue();
        } catch (JsonException e) {
            throw new MensajeInvalido("El mensaje no es un JSON de PAGO_CONFIRMADO.", e);
        }
        PagoConfirmadoMensaje mensaje;
        try {
            mensaje = JSONB.fromJson(json, PagoConfirmadoMensaje.class);
        } catch (JsonbException e) {
            throw new MensajeInvalido("El mensaje no es un JSON de PAGO_CONFIRMADO.", e);
        }
        if (mensaje == null) {
            throw new MensajeInvalido("El mensaje es null.");
        }
        mensaje.validar();
        return mensaje;
    }

    /** Comprueba que el mensaje esté completo; si no, lanza {@link MensajeInvalido} diciendo qué falta. */
    public void validar() {
        exigirId("ventaId", ventaId);
        exigirId("compradorId", compradorId);
        exigirId("eventoId", eventoId);
        if (entradas == null || entradas.isEmpty() || entradas.size() > ReglasDeNegocio.MAX_ENTRADAS_POR_ORDEN) {
            throw new MensajeInvalido("Tiene que traer entre 1 y " + ReglasDeNegocio.MAX_ENTRADAS_POR_ORDEN + " entradas.");
        }
        for (Entrada entrada : entradas) {
            if (entrada == null) {
                throw new MensajeInvalido("Hay una entrada nula.");
            }
            exigirId("entradaId", entrada.entradaId());
            Titular titular = entrada.titular();
            if (titular == null || titular.nombre() == null || titular.nombre().isBlank()) {
                throw new MensajeInvalido("Falta el nombre del titular de la entrada " + entrada.entradaId() + ".");
            }
            // La regla del DNI está en un solo lugar: es la misma que aplica el registro de usuarios.
            if (!ReglasDeNegocio.esDniValido(titular.dni())) {
                throw new MensajeInvalido("El DNI del titular de la entrada " + entrada.entradaId()
                        + " no tiene 7 u 8 dígitos.");
            }
        }
        if (montoTotal <= 0) {
            throw new MensajeInvalido("montoTotal tiene que ser mayor que 0.");
        }
        if (timestamp == null) {
            throw new MensajeInvalido("Falta timestamp.");
        }
    }

    private static void exigirId(String campo, String valor) {
        if (valor == null || valor.isBlank() || valor.length() > LARGO_MAXIMO_ID) {
            throw new MensajeInvalido(campo + " tiene que tener entre 1 y " + LARGO_MAXIMO_ID + " caracteres.");
        }
    }
}
