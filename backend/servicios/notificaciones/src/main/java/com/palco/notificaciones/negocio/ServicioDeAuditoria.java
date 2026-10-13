package com.palco.notificaciones.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.logging.Logger;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.palco.common.mensajeria.DestinosJms;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.notificaciones.api.Dtos.RegistroDeAuditoriaDto;
import com.palco.notificaciones.dominio.RegistroDeAuditoria;

/**
 * Stateless: registrar un mensaje no depende del anterior, y lo que tiene que
 * durar queda en la base, así que el contenedor reutiliza un pool de
 * instancias. El reloj no es estado de una conversación: es el mismo para
 * todas las instancias y solo se reemplaza en los tests.
 */
@Stateless
public class ServicioDeAuditoria implements IAuditoria {

    private static final Logger LOG = Logger.getLogger(ServicioDeAuditoria.class.getName());

    static final int MAXIMO_A_LISTAR = 50;

    @PersistenceContext(unitName = "notificaciones")
    EntityManager em;

    /** De acá sale el "ahora". El servidor corre en hora argentina y la columna guarda hora universal (UTC). */
    Clock reloj = Clock.systemUTC();

    @Override
    public void registrar(String mensajeId, PagoConfirmadoMensaje mensaje, String cuerpo) {
        // El id del mensaje es la clave primaria y no cambia cuando el broker reentrega:
        // si la fila ya está, este mensaje ya se registró y no hay nada que hacer.
        if (em.find(RegistroDeAuditoria.class, mensajeId) != null) {
            LOG.info("El mensaje " + mensajeId + " ya estaba registrado (reentrega del broker): no se repite.");
            return;
        }
        em.persist(new RegistroDeAuditoria(
                mensajeId,
                // El MDB filtra por tipoEvento: lo único que llega hasta acá es PAGO_CONFIRMADO.
                DestinosJms.PAGO_CONFIRMADO,
                mensaje.ventaId(),
                mensaje.compradorId(),
                mensaje.eventoId(),
                mensaje.montoTotal(),
                cuerpo,
                ahoraEnUtc()));
        // Como el id lo asigna el código, el INSERT sale recién acá. Se fuerza ahora para que,
        // si la base lo rechaza, la falla salga de este método: quien llama no llega a
        // escribir en el log que procesó un mensaje cuya fila no existe.
        em.flush();
    }

    /** La hora del reloj pasada a UTC a mano, para no depender de la zona con que se lo haya creado. */
    private LocalDateTime ahoraEnUtc() {
        return LocalDateTime.ofInstant(reloj.instant().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);
    }

    @Override
    public List<RegistroDeAuditoriaDto> listar() {
        // recibidoEn está truncada a segundos: el mensajeId solo desempata para que la
        // respuesta sea siempre la misma, no dice cuál de los dos mensajes llegó primero.
        return em.createQuery("""
                        select r from RegistroDeAuditoria r
                        order by r.recibidoEn desc, r.mensajeId desc""", RegistroDeAuditoria.class)
                .setMaxResults(MAXIMO_A_LISTAR)
                .getResultList()
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
