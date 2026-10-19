package com.palco.notificaciones.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.logging.Logger;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

import com.palco.common.Textos;
import com.palco.common.mensajeria.DestinosJms;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.notificaciones.api.Dtos.RegistroDeAuditoriaDto;
import com.palco.notificaciones.datos.RegistroDeAuditoriaRepository;
import com.palco.notificaciones.dominio.RegistroDeAuditoria;

/**
 * Stateless: registrar un mensaje no depende del anterior, y lo que tiene que
 * durar queda en la base, así que el contenedor reutiliza un pool de
 * instancias. El reloj no es estado de una conversación: es el mismo para
 * todas las instancias y solo se reemplaza en los tests.
 *
 * Decide qué se guarda y cuándo no (la reentrega del broker); el acceso a la
 * base está en {@link RegistroDeAuditoriaRepository}, que se suma a la
 * transacción del MDB.
 */
@Stateless
public class ServicioDeAuditoria implements IAuditoria {

    private static final Logger LOG = Logger.getLogger(ServicioDeAuditoria.class.getName());

    static final int MAXIMO_A_LISTAR = 50;

    @Inject
    RegistroDeAuditoriaRepository registros;

    /** De acá sale el "ahora". El servidor corre en hora argentina y la columna guarda hora universal (UTC). */
    Clock reloj = Clock.systemUTC();

    @Override
    public void registrar(String mensajeId, PagoConfirmadoMensaje mensaje, String cuerpo) {
        // El id del mensaje es la clave primaria y no cambia cuando el broker reentrega:
        // si la fila ya está, este mensaje ya se registró y no hay nada que hacer.
        if (registros.existe(mensajeId)) {
            LOG.info("El mensaje " + Textos.enUnaLinea(mensajeId)
                    + " ya estaba registrado (reentrega del broker): no se repite.");
            return;
        }
        // guardar manda el INSERT en el momento: si la base lo rechaza, la falla sale de acá y
        // quien llama no llega a escribir en el log que procesó un mensaje cuya fila no existe.
        registros.guardar(new RegistroDeAuditoria(
                mensajeId,
                // El MDB filtra por tipoEvento: lo único que llega hasta acá es PAGO_CONFIRMADO.
                DestinosJms.PAGO_CONFIRMADO,
                mensaje.ventaId(),
                mensaje.compradorId(),
                mensaje.eventoId(),
                mensaje.montoTotal(),
                cuerpo,
                ahoraEnUtc()));
    }

    /** La hora del reloj pasada a UTC a mano, para no depender de la zona con que se lo haya creado. */
    private LocalDateTime ahoraEnUtc() {
        return LocalDateTime.ofInstant(reloj.instant().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);
    }

    @Override
    public List<RegistroDeAuditoriaDto> listar() {
        return registros.ultimos(MAXIMO_A_LISTAR)
                .stream()
                .map(ServicioDeAuditoria::aDto)
                .toList();
    }

    static RegistroDeAuditoriaDto aDto(RegistroDeAuditoria r) {
        return new RegistroDeAuditoriaDto(
                r.getMensajeId(),
                r.getTipoEvento(),
                r.getVentaId(),
                r.getCompradorId(),
                r.getEventoId(),
                r.getMontoTotal(),
                // La columna guarda hora UTC sin zona; hacia afuera sale como instante, con "Z".
                r.getRecibidoEn().toInstant(ZoneOffset.UTC));
    }
}
