package com.palco.notificaciones.datos;

import java.util.List;

import jakarta.ejb.Local;

import com.palco.notificaciones.dominio.RegistroDeAuditoria;

/**
 * Dónde se guardan y se leen las filas de auditoría. El negocio conoce solo
 * esta interfaz: en el servidor la implementa
 * {@link RegistroDeAuditoriaRepositoryJpa} contra la base, y en los tests un
 * doble.
 *
 * Las filas no se modifican ni se borran después de guardadas: por eso no hay
 * métodos para eso.
 */
@Local
public interface RegistroDeAuditoriaRepository {

    /** Si ya hay una fila para ese mensaje (el {@code JMSMessageID} es la clave primaria). */
    boolean existe(String mensajeId);

    /**
     * Guarda la fila y manda el INSERT a la base en el momento, no al cerrar la
     * transacción: si la base lo rechaza, la falla sale de este método.
     */
    void guardar(RegistroDeAuditoria registro);

    /**
     * Las últimas {@code maximo} filas, de la más nueva a la más vieja según la
     * hora en que se registraron. Entre las de un mismo segundo desempata el
     * {@code mensajeId}, para que la respuesta sea siempre la misma.
     */
    List<RegistroDeAuditoria> ultimos(int maximo);
}
