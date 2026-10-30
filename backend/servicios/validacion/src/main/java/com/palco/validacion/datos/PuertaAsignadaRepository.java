package com.palco.validacion.datos;

import java.util.List;
import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.validacion.dominio.PuertaAsignada;

/**
 * Qué cuenta de puerta trabaja en qué evento. En el servidor lo implementa
 * {@link PuertaAsignadaRepositoryJpa}; en los tests, un doble. Los emails
 * llegan ya en minúsculas.
 */
@Local
public interface PuertaAsignadaRepository {

    /** Si esa cuenta trabaja en la puerta de ese evento. Se pregunta en cada escaneo. */
    boolean estaAsignada(String eventoId, String staffEmail);

    Optional<PuertaAsignada> buscar(String eventoId, String staffEmail);

    /** Las cuentas de la puerta de ese evento, por email. */
    List<PuertaAsignada> delEvento(String eventoId);

    /** Las puertas de esa cuenta, por evento. */
    List<PuertaAsignada> deLaCuenta(String staffEmail);

    /** Guarda una asignación nueva. Quien llama ya comprobó que no existía. */
    void guardar(PuertaAsignada asignacion);

    /** Borra la asignación; devuelve si existía. */
    boolean quitar(String eventoId, String staffEmail);
}
