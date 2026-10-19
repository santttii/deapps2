package com.palco.validacion.negocio;

import java.util.List;

import jakarta.ejb.Local;

import com.palco.validacion.api.Dtos.EscaneoDto;
import com.palco.validacion.api.Dtos.ResultadoDeEscaneoDto;

/**
 * Interfaz de ServicioDeValidación: todo lo que el resto del sistema puede
 * pedirle.
 *
 * La Entrega Parcial N.º 1 la dibujaba con dos operaciones, validar la
 * entrada y marcarla como usada. Acá son una sola, {@link #escanear}: si
 * fueran dos pasos, entre "es válida" y "marcala" otra puerta podría usar la
 * misma entrada, y las dos dejarían pasar a alguien. Validar y usar tienen
 * que ser el mismo UPDATE.
 */
@Local
public interface IValidacion {

    /**
     * Escanea un código en la puerta de un evento: le pide a Ventas que use la
     * entrada, guarda el escaneo en el registro y devuelve el resultado. Que la
     * entrada ya esté usada, sea de otro evento o no exista no es un error: es
     * el resultado, y también queda registrado.
     *
     * Lanza {@code SinRespuesta} si Ventas no contesta. Ese intento también
     * queda en el registro, como {@code SIN_RESPUESTA}.
     *
     * @param escaneadoPor el usuario de puerta (claim "sub" de su token)
     */
    ResultadoDeEscaneoDto escanear(String codigoQr, String eventoId, String escaneadoPor);

    /** Los escaneos del evento, del más nuevo al más viejo, hasta {@code MAXIMO_POR_CONSULTA}. */
    List<EscaneoDto> listar(String eventoId);

    /** El escaneo con ese id, o {@code NoEncontrado}. */
    EscaneoDto obtener(String escaneoId);
}
