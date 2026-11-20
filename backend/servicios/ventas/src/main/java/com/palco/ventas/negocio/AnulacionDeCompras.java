package com.palco.ventas.negocio;

import java.time.Clock;
import java.util.logging.Logger;

import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.common.ReglasDeNegocio;
import com.palco.common.Textos;
import com.palco.common.mensajeria.CompraAnuladaMensaje;
import com.palco.common.mensajeria.EventoCanceladoMensaje;
import com.palco.common.mensajeria.PublicadorDeCancelaciones;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Venta;
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
 * <strong>Las compras que pasaron por la reventa.</strong> Una entrada
 * revendida deja dos ventas pagadas del evento: la de quien la vendió, con esa
 * entrada {@code VENDIDA}, y la que nació para quien la compró
 * ({@link ReventaDeEntradas#transferir}). Las dos se anulan acá, con tres
 * diferencias:
 * <ul>
 * <li><em>El reembolso de quien compró en la reventa se pide con el id de la
 * publicación.</em> Ese cobro lo hizo Reventa antes de que la venta existiera,
 * y Pagos lo guardó con la publicación como venta. Pedido con el id de la
 * venta, Pagos contesta que el pago es de otra venta y el dinero no vuelve.
 * Lo pide Ventas, y no Reventa aunque haya cobrado ella, por dos razones: el
 * aviso al comprador sale de acá, en esta transacción, y tiene que decir si el
 * dinero volvió; y un reembolso sin respuesta lo retoma el mismo barrido que
 * el de cualquier compra ({@link ReintentoDeReembolsos}), sin otro estado en
 * Reventa ni dos servicios que se repartan quién devuelve qué.</li>
 * <li><em>La venta que nació en la reventa no devuelve cupo.</em> No reservó
 * ninguno: el lugar ya era de la compra original, que lo devuelve entero.</li>
 * <li><em>A quien vendió no se le devuelve lo que corresponde a las entradas
 * que ya revendió</em> ({@link #montoADevolver}). Esa parte se la pagó quien
 * se las compró, y es a esa persona a quien se le devuelve, en su propia
 * venta: así cada lugar se devuelve una sola vez. Si las revendió todas, no
 * hay nada que devolverle ni que avisarle.</li>
 * </ul>
 *
 * Stateless: todo lo que necesita llega como parámetro o está en la base.
 */
@Stateless
public class AnulacionDeCompras {

    private static final Logger LOG = Logger.getLogger(AnulacionDeCompras.class.getName());

    /** Queda en el pago, para que se entienda por qué se devolvió. También lo usa {@link ReintentoDeReembolsos}. */
    static final String MOTIVO = "Evento cancelado";

    /**
     * Lo que la anulación necesita de una venta pagada, copiado de la entidad
     * que trajo la consulta y no la entidad misma: la anulación corre en una
     * transacción nueva, y esa entidad pertenece al contexto de persistencia
     * de la otra.
     *
     * @param ventaEnPagos     con qué venta guardó Pagos el cobro ({@link ReventaDeEntradas#ventaEnPagos})
     * @param nacioEnLaReventa si la venta nació de una compra en la reventa: entonces no reservó cupo
     */
    public record CompraPagada(String ventaId, String compradorId, long montoTotal, String pagoId,
            String ventaEnPagos, boolean nacioEnLaReventa) {

        static CompraPagada de(Venta venta) {
            return new CompraPagada(venta.getId(), venta.getCompradorId(), venta.getMontoTotal(), venta.getPagoId(),
                    ReventaDeEntradas.ventaEnPagos(venta), ReventaDeEntradas.esCompraEnLaReventa(venta));
        }
    }

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
     * Si Pagos no contesta el reembolso, la venta queda con la marca
     * {@code reembolso_pendiente}, en esta misma transacción: la marca existe
     * solo si la anulación quedó confirmada. Con la marca, el barrido
     * {@link ReintentoDeReembolsos} lo vuelve a pedir. Una reentrega del
     * mensaje no lo haría: encuentra la venta ya anulada y no la toca.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public boolean anular(CompraPagada compra, EventoCanceladoMensaje mensaje) {
        String ventaId = compra.ventaId();
        if (!ventas.anularPorCancelacionDelEvento(ventaId, !compra.nacioEnLaReventa())) {
            return false;
        }
        int anuladas = entradas.anularDeLaVenta(ventaId);
        // Se suma después de anular: ya no le queda ninguna entrada PUBLICADA, así que ninguna puede pasar a
        // VENDIDA y lo revendido no cambia más. ReintentoDeReembolsos lo vuelve a sumar y le da lo mismo.
        long aDevolver = montoADevolver(compra.montoTotal(), entradas.precioDeLasRevendidas(ventaId));
        String cupo = compra.nacioEnLaReventa()
                ? "no se devuelve, porque la venta nació en la reventa y el lugar lo devuelve la compra original"
                : "queda para DevolucionDeCupos";

        if (aDevolver == 0) {
            // COMPRA_ANULADA exige un monto mayor que 0, y no hay nada que contarle: no tenía entradas que anular.
            LOG.info("AnulacionDeCompras: anulada la venta " + Textos.enUnaLinea(ventaId) + " del evento "
                    + Textos.enUnaLinea(mensaje.eventoId()) + " sin reembolso ni aviso: su comprador había revendido"
                    + " todas sus entradas. Cupo: " + cupo + ".");
            return true;
        }

        // El reembolso va antes de publicar, porque el aviso dice si el dinero volvió.
        String pagoId = compra.pagoId();
        Cobros.Devolucion devolucion = pagoId == null ? null
                : cobros.reembolsar(pagoId, compra.ventaEnPagos(), aDevolver, MOTIVO);
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
        cancelaciones.publicarCompraAnulada(new CompraAnuladaMensaje(ventaId, compra.compradorId(),
                mensaje.eventoId(), mensaje.titulo(), mensaje.motivo(), aDevolver, reembolsado, reloj.instant()));

        LOG.info("AnulacionDeCompras: anulada la venta " + Textos.enUnaLinea(ventaId) + " del evento "
                + Textos.enUnaLinea(mensaje.eventoId()) + ". Entradas anuladas: " + anuladas
                + ". Reembolso: " + reembolso + " ($" + aDevolver + " de $" + compra.montoTotal() + ", pedido a Pagos"
                + " con la venta " + Textos.enUnaLinea(compra.ventaEnPagos()) + "). Cupo: " + cupo + ".");
        return true;
    }

    /**
     * Cuánto se le devuelve a quien compró: lo que pagó, menos lo que
     * corresponde a las entradas que ya revendió, que es su precio pagado más
     * el cargo de servicio de ese precio (la misma regla con la que se cobró,
     * {@code ReglasDeNegocio.cargoServicio}). Sin revendidas es lo que pagó;
     * con todas revendidas, 0. Lo usa también {@link ReintentoDeReembolsos},
     * para volver a pedir el mismo monto.
     */
    static long montoADevolver(long montoTotal, long precioDeLasRevendidas) {
        return montoTotal - precioDeLasRevendidas - ReglasDeNegocio.cargoServicio(precioDeLasRevendidas);
    }
}
