package com.palco.reventa.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.logging.Logger;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

import com.palco.common.Textos;
import com.palco.common.mensajeria.EventoCanceladoMensaje;
import com.palco.reventa.datos.PublicacionRepository;

/**
 * Lo que hace Reventa cuando Eventos avisa que se canceló un evento: saca de
 * la venta sus publicaciones activas. Sin esto, la publicación de una entrada
 * que Ventas ya anuló seguía figurando como activa.
 *
 * Es idempotente: el UPDATE solo toca las que siguen {@code ACTIVA}, así que
 * un mensaje repetido no cambia nada.
 *
 * Stateless: todo lo que necesita viene en el mensaje. Su transacción
 * ({@code REQUIRED}) es la del mensaje: si el UPDATE falla, el mensaje vuelve
 * al broker y se reintenta.
 */
@Stateless
public class RetiroPorCancelacion {

    private static final Logger LOG = Logger.getLogger(RetiroPorCancelacion.class.getName());

    @Inject
    PublicacionRepository publicaciones;

    /** No es privado para que el test fije la hora. */
    @SuppressWarnings("java:S8983") // Se fija al crear el bean, no cambia entre llamadas y los tests lo reemplazan.
    Clock reloj = Clock.systemUTC();

    /** Retira las publicaciones activas del evento cancelado y devuelve cuántas. */
    public int retirarDelEvento(EventoCanceladoMensaje mensaje) {
        LocalDateTime ahora = LocalDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        int retiradas = publicaciones.retirarPorCancelacionDelEvento(mensaje.eventoId(), ahora);
        LOG.info("Reventa: se canceló el evento " + Textos.enUnaLinea(mensaje.eventoId()) + ". "
                + (retiradas == 0 ? "No tenía publicaciones activas (o es un mensaje repetido)."
                        : "Publicaciones retiradas: " + retiradas + "."));
        return retiradas;
    }
}
