package com.palco.ventas.negocio;

import java.time.Clock;
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
import com.palco.ventas.negocio.externos.Cobros;

/**
 * Anula una compra pagada porque se canceló su evento: la venta pasa a
 * {@code CANCELADA}, sus entradas a {@code ANULADA}, se le pide a Pagos que
 * devuelva el pago y se publica el {@code COMPRA_ANULADA} que avisa al
 * comprador. Cada compra, en su propia transacción ({@code REQUIRES_NEW}).
 * La llama {@link CancelacionDeEventos}, una vez por compra del evento.
 *
 * <strong>Por qué es un bean aparte.</strong> El contenedor aplica el
 * atributo de transacción solo a las llamadas que pasan por él, es decir,
 * por la referencia que inyecta. Una llamada de un bean a un método suyo
 * ({@code this.anular(...)}) no pasa por el contenedor: el
 * {@code REQUIRES_NEW} se ignora y el método corre en la transacción de quien
 * llama. Cuando este método estaba en {@code CancelacionDeEventos}, todas las
 * compras de un evento se anulaban en la transacción del MDB, y un fallo en
 * una deshacía las que ya estaban anuladas. En un bean aparte, inyectado,
 * cada llamada entra por el contenedor y abre su transacción. Es la misma
 * razón por la que {@link ConfirmacionConciliada} está separada de
 * {@link ConciliacionDePagos}. Lo mismo se podía lograr llamándose a sí mismo
 * con {@code SessionContext.getBusinessObject}, pero un bean aparte se lee y
 * se prueba más fácil, y es como ya lo resuelve el servicio.
 *
 * Stateless: todo lo que necesita llega como parámetro o está en la base.
 */
@Stateless
public class AnulacionDeCompras {

    private static final Logger LOG = Logger.getLogger(AnulacionDeCompras.class.getName());

    /** Queda en el pago, para que se entienda por qué se devolvió. También lo usa {@link ReintentoDeReembolsos}. */
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
     * Una compra, en su propia transacción. Devuelve false si ya estaba
     * anulada: entonces no se pide el reembolso ni se publica el aviso otra vez.
     *
     * Los datos de la venta llegan por parámetro y no como entidad porque esta
     * transacción es nueva: la entidad que trajo la consulta pertenece al
     * contexto de persistencia de la otra.
     *
     * Si Pagos no contesta el reembolso, la venta queda con la marca
     * {@code reembolso_pendiente}, en esta misma transacción: la marca existe
     * solo si la anulación quedó confirmada. Con la marca, el barrido
     * {@link ReintentoDeReembolsos} lo vuelve a pedir. Una reentrega del
     * mensaje no lo haría: encuentra la venta ya anulada y no la toca.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public boolean anular(String ventaId, String compradorId, long monto, String pagoId,
            EventoCanceladoMensaje mensaje) {
        if (!ventas.anularPorCancelacionDelEvento(ventaId)) {
            return false;
        }
        int anuladas = entradas.anularDeLaVenta(ventaId);

        // El reembolso va antes de publicar, porque el aviso dice si el dinero volvió.
        Cobros.Devolucion devolucion = pagoId == null ? null : cobros.reembolsar(pagoId, ventaId, MOTIVO);
        boolean reembolsado = devolucion == Cobros.Devolucion.HECHA;
        String reembolso = "hecho";
        if (devolucion == Cobros.Devolucion.SIN_RESPUESTA) {
            reembolso = "pendiente";
            ventas.marcarReembolsoPendiente(ventaId);
            LOG.warning("AnulacionDeCompras: la venta " + Textos.enUnaLinea(ventaId) + " quedó anulada pero Pagos"
                    + " no contestó el reembolso (pagoId " + Textos.enUnaLinea(pagoId) + "). El aviso lo dice y"
                    + " ReintentoDeReembolsos lo vuelve a pedir cada minuto.");
        } else if (!reembolsado) {
            reembolso = "no se hizo";
            // Reintentar daría lo mismo: queda en un SEVERE para revisarlo a mano, como en DevolucionDeCupos.
            LOG.severe("AnulacionDeCompras: la venta " + Textos.enUnaLinea(ventaId) + " quedó anulada sin"
                    + " reembolso: " + (pagoId == null ? "no tiene un pago registrado"
                            : "Pagos contestó que el pago " + Textos.enUnaLinea(pagoId) + " no se puede devolver")
                    + ". Hay que revisarlo a mano.");
        }

        // En la misma transacción que la anulación: si esta se deshace, el aviso no sale.
        cancelaciones.publicarCompraAnulada(new CompraAnuladaMensaje(ventaId, compradorId, mensaje.eventoId(),
                mensaje.titulo(), mensaje.motivo(), monto, reembolsado, reloj.instant()));

        LOG.info("AnulacionDeCompras: anulada la venta " + Textos.enUnaLinea(ventaId) + " del evento "
                + Textos.enUnaLinea(mensaje.eventoId()) + ". Entradas anuladas: " + anuladas
                + ". Reembolso: " + reembolso + ".");
        return true;
    }
}
