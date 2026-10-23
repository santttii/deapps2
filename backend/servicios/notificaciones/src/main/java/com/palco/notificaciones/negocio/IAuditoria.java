package com.palco.notificaciones.negocio;

import java.util.List;

import jakarta.ejb.Local;

import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.notificaciones.api.Dtos.RegistroDeAuditoriaDto;

/**
 * Auditoría de las compras confirmadas: guarda una fila por cada mensaje que
 * llega por el tópico. Es una interfaz aparte de {@link INotificaciones} porque
 * es otra responsabilidad: avisar puede fallar o perderse, auditar no.
 */
@Local
public interface IAuditoria {

    /**
     * Registra un mensaje recibido. Es idempotente: si el broker reentrega el
     * mismo mensaje (mismo {@code mensajeId}), no vuelve a registrarlo.
     *
     * @param mensajeId  el {@code JMSMessageID}
     * @param tipoEvento {@code PAGO_CONFIRMADO} o {@code REVENTA_CONFIRMADA}
     * @param mensaje    el mensaje ya leído y validado
     * @param cuerpo     el JSON tal como llegó
     */
    void registrar(String mensajeId, String tipoEvento, PagoConfirmadoMensaje mensaje, String cuerpo);

    /**
     * Los últimos 50 registros, del más nuevo al más viejo según la hora en que
     * se registraron. Esa hora se guarda truncada a segundos: entre los
     * registros de un mismo segundo el orden no está garantizado.
     */
    List<RegistroDeAuditoriaDto> listar();
}
