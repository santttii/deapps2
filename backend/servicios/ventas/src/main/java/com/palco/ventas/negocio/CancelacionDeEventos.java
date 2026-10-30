package com.palco.ventas.negocio;

import java.time.Clock;
import java.util.List;
import java.util.logging.Logger;

import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.common.Textos;
import com.palco.common.mensajeria.CompraAnuladaMensaje;
import com.palco.common.mensajeria.EventoCanceladoMensaje;
import com.palco.common.mensajeria.PublicadorDeCancelaciones;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.negocio.externos.Cobros;

/**
 * Lo que hace Ventas cuando Eventos avisa que se canceló un evento: por cada
 * compra pagada de ese evento anula sus entradas, le pide a Pagos que devuelva
 * el dinero y publica un {@code COMPRA_ANULADA}, que es lo que escucha
 * Notificaciones para avisarle a cada comprador.
 *
 * Está acá y no en el MDB porque el MDB es un punto de entrada delgado, como
 * el recurso REST: lee el mensaje y delega.
 *
 * Stateless: todo lo que necesita viene en el mensaje.
 *
 * <strong>Una transacción por compra</strong>
 * ({@code REQUIRES_NEW} en {@link #anular}): si las N compras de un evento
 * fueran una sola transacción, un fallo en la última desharía las anteriores y
 * el reintento del mensaje volvería a pedirle a Pagos todos los reembolsos. Con
 * una por compra, lo que quedó anulado queda anulado, y el reintento solo
 * retoma las que faltan, porque los UPDATE son condicionales.
 *
 * <strong>El reembolso no bloquea la anulación.</strong> Si Pagos no contesta,
 * la entrada se anula igual y el aviso sale diciendo que el dinero todavía no
 * volvió ({@code reembolsado = false}). Lo contrario sería peor: entradas que
 * siguen valiendo para un evento que no existe. El reembolso que quedó sin
 * respuesta se vuelve a pedir si el mensaje se reintenta, y Pagos devuelve un
 * pago una sola vez.
 */
@Stateless
public class CancelacionDeEventos {

    private static final Logger LOG = Logger.getLogger(CancelacionDeEventos.class.getName());

    /** Queda en el pago, para que se entienda por qué se devolvió. */
    static final String MOTIVO = "Evento cancelado";

    @Inject
    VentaRepository ventas;

    @Inject
    EntradaRepository entradas;

    @Inject
    Cobros cobros;

    @Inject
    PublicadorDeCancelaciones cancelaciones;

    /** De dónde sale el instante de la anulación; los tests lo reemplazan por un reloj detenido. */
    Clock reloj = Clock.systemUTC();

    /**
     * Anula y reembolsa todas las compras pagadas del evento. Devuelve cuántas
     * anuló en esta pasada: 0 si el mensaje ya se había procesado.
     */
    public int anularComprasDelEvento(EventoCanceladoMensaje mensaje) {
        List<Venta> pagadas = ventas.pagadasDelEvento(mensaje.eventoId());
        if (pagadas.isEmpty()) {
            return 0;
        }
        int anuladas = 0;
        for (Venta venta : pagadas) {
            if (anular(venta.getId(), venta.getCompradorId(), venta.getMontoTotal(), venta.getPagoId(), mensaje)) {
                anuladas++;
            }
        }
        return anuladas;
    }

    /**
     * Una compra, en su propia transacción. Devuelve false si ya estaba
     * anulada: entonces no se pide el reembolso ni se publica el aviso otra vez.
     *
     * Los datos de la venta llegan por parámetro y no como entidad porque esta
     * transacción es nueva: la entidad que trajo la consulta pertenece al
     * contexto de persistencia de la otra.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public boolean anular(String ventaId, String compradorId, long monto, String pagoId,
            EventoCanceladoMensaje mensaje) {
        if (!ventas.anularPorCancelacionDelEvento(ventaId)) {
            return false;
        }
        int anuladas = entradas.anularDeLaVenta(ventaId);

        // El reembolso va antes de publicar, porque el aviso dice si el dinero volvió.
        boolean reembolsado = pagoId != null && cobros.reembolsar(pagoId, ventaId, MOTIVO) == Cobros.Devolucion.HECHA;
        if (!reembolsado) {
            LOG.warning("CancelacionDeEventos: la venta " + Textos.enUnaLinea(ventaId) + " quedó anulada pero su"
                    + " pago no se devolvió todavía (pagoId " + Textos.enUnaLinea(String.valueOf(pagoId))
                    + "). El aviso lo dice y el reembolso se vuelve a pedir si el mensaje se reintenta.");
        }

        // En la misma transacción que la anulación: si esta se deshace, el aviso no sale.
        cancelaciones.publicarCompraAnulada(new CompraAnuladaMensaje(ventaId, compradorId, mensaje.eventoId(),
                mensaje.titulo(), mensaje.motivo(), monto, reembolsado, reloj.instant()));

        LOG.info("CancelacionDeEventos: anulada la venta " + Textos.enUnaLinea(ventaId) + " del evento "
                + Textos.enUnaLinea(mensaje.eventoId()) + ". Entradas anuladas: " + anuladas
                + ". Reembolso: " + (reembolsado ? "hecho" : "pendiente") + ".");
        return true;
    }
}
