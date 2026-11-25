package com.palco.notificaciones.api;

import java.time.Instant;

/** Lo que sale de la API de notificaciones. Ningún endpoint recibe cuerpo. */
public final class Dtos {

    private Dtos() {
    }

    /**
     * Un mensaje {@code PAGO_CONFIRMADO} que auditoría recibió por el tópico.
     * No incluye el cuerpo del mensaje: trae el nombre de cada titular, y quien
     * consulta la auditoría no lo necesita. El DNI ya no se guarda (DEF-37).
     *
     * @param mensajeId  el {@code JMSMessageID}
     * @param recibidoEn instante en que se registró; sale como {@code 2026-10-16T21:30:05Z}
     */
    public record RegistroDeAuditoriaDto(
            String mensajeId,
            String tipoEvento,
            String ventaId,
            String compradorId,
            String eventoId,
            long montoTotal,
            Instant recibidoEn) {
    }
}
