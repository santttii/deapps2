package com.palco.common.mensajeria;

import java.io.StringReader;
import java.time.Instant;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonReader;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;

/**
 * El mensaje {@code EVENTO_CANCELADO}: lo publica Eventos cuando el
 * organizador dueño cancela un evento, y viaja como texto JSON por el tópico
 * {@code topico.eventos-cancelados}.
 *
 * Lleva el id del evento y nada de las ventas: quién compró qué lo sabe
 * Ventas, que es la dueña de las ventas. Ventas lo consume, anula las entradas
 * de ese evento, pide el reembolso a Pagos y publica un
 * {@link CompraAnuladaMensaje} por cada compra, que es lo que escucha
 * Notificaciones para avisarle a cada comprador.
 *
 * Es un {@code record} porque es un dato que no cambia después de creado.
 * Como en {@link PagoConfirmadoMensaje}, los métodos propios no empiezan con
 * "get" ni "is": JSON-B los tomaría por campos.
 *
 * @param eventoId  id del evento cancelado
 * @param titulo    título del evento, para el aviso (Ventas y Notificaciones no lo tienen)
 * @param motivo    lo que se le cuenta al comprador; puede ser null
 * @param timestamp instante de la cancelación
 */
public record EventoCanceladoMensaje(String eventoId, String titulo, String motivo, Instant timestamp) {

    /** Largo de la columna de los ids en todas las tablas. */
    private static final int LARGO_MAXIMO_ID = 40;

    private static final Jsonb JSONB = JsonbBuilder.create();

    /** El mensaje como texto JSON. */
    public String aJson() {
        return JSONB.toJson(this);
    }

    /**
     * Convierte el texto recibido y lo valida. Como en
     * {@link PagoConfirmadoMensaje#desdeJson}, primero se lee con JSON-P: con
     * un JSON cortado dentro de un campo que el mensaje no conoce, JSON-B no
     * lanza error ni termina y el consumidor quedaría girando para siempre.
     */
    public static EventoCanceladoMensaje desdeJson(String json) {
        if (json == null || json.isBlank()) {
            throw new MensajeInvalido("El mensaje está vacío.");
        }
        try (JsonReader lector = Json.createReader(new StringReader(json))) {
            lector.readValue();
        } catch (JsonException e) {
            throw new MensajeInvalido("El mensaje no es un JSON de EVENTO_CANCELADO.", e);
        }
        EventoCanceladoMensaje mensaje;
        try {
            mensaje = JSONB.fromJson(json, EventoCanceladoMensaje.class);
        } catch (JsonbException e) {
            throw new MensajeInvalido("El mensaje no es un JSON de EVENTO_CANCELADO.", e);
        }
        if (mensaje == null) {
            throw new MensajeInvalido("El mensaje es null.");
        }
        mensaje.validar();
        return mensaje;
    }

    /** Comprueba que el mensaje esté completo; si no, lanza {@link MensajeInvalido} diciendo qué falta. */
    public void validar() {
        if (eventoId == null || eventoId.isBlank() || eventoId.length() > LARGO_MAXIMO_ID) {
            throw new MensajeInvalido("eventoId tiene que tener entre 1 y " + LARGO_MAXIMO_ID + " caracteres.");
        }
        if (titulo == null || titulo.isBlank()) {
            throw new MensajeInvalido("Falta el título del evento.");
        }
        if (timestamp == null) {
            throw new MensajeInvalido("Falta timestamp.");
        }
    }
}
