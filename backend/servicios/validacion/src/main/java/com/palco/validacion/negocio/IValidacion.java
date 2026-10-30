package com.palco.validacion.negocio;

import java.util.List;

import jakarta.ejb.Local;

import com.palco.validacion.api.Dtos.EscaneoDto;
import com.palco.validacion.api.Dtos.PuertaAsignadaDto;
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
     * Solo escanea una cuenta asignada a la puerta de ese evento; si no, lanza
     * {@code SinPermiso} ({@code puerta-no-asignada}) sin llamar a Ventas ni
     * registrar nada.
     *
     * @param staff el usuario de puerta: su id queda en el registro
     */
    ResultadoDeEscaneoDto escanear(String codigoQr, String eventoId, Solicitante staff);

    /**
     * Los escaneos del evento, del más nuevo al más viejo, hasta
     * {@code MAXIMO_POR_CONSULTA}. Los ve el staff asignado a esa puerta (si no,
     * {@code puerta-no-asignada}) y el organizador dueño del evento (si no,
     * {@code NoEncontrado}, igual que un evento que no existe).
     */
    List<EscaneoDto> listar(String eventoId, Solicitante quien);

    /** El escaneo con ese id, con el mismo control que {@link #listar}; si no lo puede ver, {@code NoEncontrado}. */
    EscaneoDto obtener(String escaneoId, Solicitante quien);

    /** Las puertas de esa cuenta de staff: los eventos donde puede escanear. */
    List<PuertaAsignadaDto> misPuertas(Solicitante staff);

    /** Las cuentas asignadas a la puerta del evento. Solo para el organizador dueño; si no, {@code NoEncontrado}. */
    List<PuertaAsignadaDto> equipoDePuerta(String eventoId, Solicitante organizador);

    /**
     * Asigna una cuenta a la puerta del evento. Solo el organizador dueño; si
     * no, {@code NoEncontrado}. Asignar una cuenta que ya estaba devuelve la
     * asignación que había. No se comprueba que el email sea de una cuenta de
     * puerta: uno que no existe no deja escanear a nadie.
     */
    PuertaAsignadaDto asignar(String eventoId, String staffEmail, Solicitante organizador);

    /** Saca una cuenta de la puerta del evento, si estaba. Solo el organizador dueño; si no, {@code NoEncontrado}. */
    void quitar(String eventoId, String staffEmail, Solicitante organizador);
}
