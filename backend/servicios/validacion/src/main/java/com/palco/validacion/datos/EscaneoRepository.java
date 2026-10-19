package com.palco.validacion.datos;

import java.util.List;
import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.validacion.dominio.Escaneo;
import com.palco.validacion.dominio.ResultadoEscaneo;

/**
 * Dónde se guarda y se consulta el registro de la puerta.
 * {@code ServicioDeValidacion} conoce solo esta interfaz: en el servidor la
 * implementa {@link EscaneoRepositoryJpa} contra la base, y en los tests un
 * doble.
 */
@Local
public interface EscaneoRepository {

    /** Guarda un escaneo nuevo; el INSERT sale en el momento. */
    void guardar(Escaneo escaneo);

    Optional<Escaneo> buscarPorId(String escaneoId);

    /** Los escaneos del evento, del más nuevo al más viejo, hasta {@code maximo}. */
    List<Escaneo> listarPorEvento(String eventoId, int maximo);

    /** El escaneo más reciente de ese código con ese resultado, si hubo alguno. */
    Optional<Escaneo> ultimoDelCodigo(String codigoQr, ResultadoEscaneo resultado);
}
