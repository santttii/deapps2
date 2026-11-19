package com.palco.ventas.negocio;

import java.util.List;
import java.util.logging.Logger;

import jakarta.ejb.DependsOn;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.common.Textos;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.negocio.externos.Cobros;

/**
 * El barrido que vuelve a pedir los reembolsos que Pagos no contestó cuando
 * se canceló un evento.
 *
 * {@link AnulacionDeCompras} anula cada compra pagada del evento y le pide a
 * Pagos que devuelva su pago. Si Pagos no contesta, la compra se anula igual,
 * el aviso al comprador dice que la devolución está en curso y la venta queda
 * con la marca {@code reembolso_pendiente}. Sin este barrido nadie lo volvía a
 * pedir: una reentrega del mensaje encuentra la venta ya anulada y no la toca.
 *
 * Una vez por minuto, por cada venta con la marca, vuelve a pedir el reembolso:
 * <ul>
 * <li>Pagos lo devolvió: saca la marca y lo deja en el log. No sale otro
 * aviso: el comprador ya recibió el de "devolución en curso", y el contrato
 * de {@code COMPRA_ANULADA} no tiene un mensaje para esto.</li>
 * <li>Pagos contestó que ese pago no se puede devolver: saca la marca y deja
 * un SEVERE para revisarlo a mano. Reintentar cada minuto daría lo mismo.</li>
 * <li>Pagos no contestó: deja la marca para la corrida siguiente.</li>
 * </ul>
 *
 * Reintentar es seguro: Pagos devuelve un pago una sola vez, y si ya estaba
 * devuelto contesta lo mismo que la primera vez. Así, aunque un pedido
 * anterior se haya aplicado sin que Ventas supiera la respuesta, el dinero
 * vuelve una sola vez.
 *
 * Mismo diseño que {@link DevolucionDeCupos}. Sin estado (stateless): no
 * guarda nada entre corridas; qué reembolsos faltan se lo pregunta cada vez a
 * la base. Es un {@code @Singleton} para que haya un solo barrido en el
 * servidor y el contenedor no lo deje correr dos veces a la vez;
 * {@code @Schedule} no persistente (la primera corrida toma todo lo
 * atrasado); {@code @DependsOn} de las migraciones; y sin transacción propia
 * ({@code NOT_SUPPORTED}): mientras espera a Pagos no retiene una conexión, y
 * cada marca se saca en la transacción corta del repositorio. Corre en el
 * segundo 45 para no coincidir con los otros barridos de Ventas (0 y 30).
 */
@Singleton
@DependsOn("MigracionesVentas")
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class ReintentoDeReembolsos {

    private static final Logger LOG = Logger.getLogger(ReintentoDeReembolsos.class.getName());

    @Inject
    VentaRepository ventas;

    /** Quién cobra: el mismo al que se le pidió el reembolso (lo elige {@code FabricaDeCobros}). */
    @Inject
    Cobros cobros;

    /**
     * Una corrida del barrido. Cada venta se intenta por separado: si Pagos no
     * responde por una, o falla algo inesperado, se sigue con la próxima. Si
     * la base no responde, la corrida se corta con un aviso y se reintenta en
     * el próximo minuto.
     */
    @Schedule(hour = "*", minute = "*", second = "45", persistent = false)
    void reintentarReembolsos() {
        List<Venta> pendientes;
        try {
            pendientes = ventas.buscarReembolsosPendientes();
        } catch (RuntimeException e) {
            LOG.warning("ReintentoDeReembolsos: la corrida se cortó y se reintenta en el próximo minuto. Motivo: " + e);
            return;
        }
        for (Venta venta : pendientes) {
            try {
                reintentar(venta.getId(), venta.getPagoId());
            } catch (RuntimeException e) {
                // Una falla inesperada con una venta no frena a las demás: la marca sigue y la toma la corrida siguiente.
                LOG.warning("ReintentoDeReembolsos: la venta " + Textos.enUnaLinea(venta.getId())
                        + " queda para la próxima corrida. Motivo: " + Textos.enUnaLinea(String.valueOf(e)));
            }
        }
    }

    /** Vuelve a pedir el reembolso de una venta. Devuelve cómo contestó Pagos; no lanza. */
    Cobros.Devolucion reintentar(String ventaId, String pagoId) {
        Cobros.Devolucion devolucion = cobros.reembolsar(pagoId, ventaId, AnulacionDeCompras.MOTIVO);
        switch (devolucion) {
            case HECHA -> LOG.info("ReintentoDeReembolsos: Pagos devolvió el pago " + Textos.enUnaLinea(pagoId)
                    + " de la venta " + Textos.enUnaLinea(ventaId) + ", anulada por la cancelación de su evento."
                    + " El comprador ya tenía el aviso de devolución en curso.");
            case RECHAZADA -> LOG.severe("ReintentoDeReembolsos: Pagos contestó que el pago "
                    + Textos.enUnaLinea(pagoId) + " de la venta " + Textos.enUnaLinea(ventaId)
                    + " no se puede devolver. No se reintenta más: hay que revisarlo a mano.");
            case SIN_RESPUESTA -> {
                LOG.warning("ReintentoDeReembolsos: Pagos sigue sin contestar el reembolso del pago "
                        + Textos.enUnaLinea(pagoId) + " de la venta " + Textos.enUnaLinea(ventaId)
                        + ". Se vuelve a pedir en un minuto.");
                return devolucion;
            }
        }
        try {
            ventas.marcarReembolsoResuelto(ventaId);
        } catch (RuntimeException e) {
            // Pagos ya contestó. Si la marca no se pudo sacar, la corrida siguiente lo pide otra vez y Pagos
            // contesta lo mismo: no devuelve dos veces.
            LOG.warning("ReintentoDeReembolsos: Pagos ya contestó el reembolso de la venta "
                    + Textos.enUnaLinea(ventaId) + ", pero no se pudo anotar en la base; se vuelve a pedir sin"
                    + " devolver dos veces. Motivo: " + e);
        }
        return devolucion;
    }
}
