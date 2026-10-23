package com.palco.reventa.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.logging.Logger;

import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.Textos;
import com.palco.reventa.datos.ReembolsoRepository;
import com.palco.reventa.datos.ReembolsoRepositoryJpa;
import com.palco.reventa.dominio.EstadoReembolso;
import com.palco.reventa.dominio.Reembolso;
import com.palco.reventa.negocio.externos.ClienteDePagos;
import com.palco.reventa.negocio.externos.ClienteDePagos.Devolucion;

/**
 * La devolución de los cobros de reventa cuya entrada Ventas no transfirió
 * (limitación L2). La usan la compra ({@code ServicioDeReventa}) y la
 * conciliación ({@code ConciliacionDeReventas}), en dos pasos:
 *
 * <ol>
 * <li>{@link #anotar}: el reembolso queda en la base, {@code PENDIENTE}, antes
 * de anular la publicación. Si la base falla acá, la publicación sigue tomada
 * y la conciliación lo vuelve a intentar: nunca queda una publicación anulada
 * con un cobro que nadie va a devolver.</li>
 * <li>{@link #intentar}: se le pide a Pagos. Si no responde, el reembolso sigue
 * {@code PENDIENTE} y {@link #reintentarPendientes} lo repite cada minuto. Pagos
 * devuelve un pago una sola vez, así que repetir es seguro.</li>
 * </ol>
 *
 * Stateless y sin transacción propia: entre la base y Pagos no hay una
 * transacción que compartir, y no se retiene una conexión mientras se espera a
 * Pagos. Cada método del repositorio corre en una corta propia.
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class DevolucionDeCobros {

    private static final Logger LOG = Logger.getLogger(DevolucionDeCobros.class.getName());

    @Inject
    ReembolsoRepository reembolsos;

    @Inject
    ClienteDePagos pagos;

    /** De dónde sale el "ahora"; los tests lo reemplazan por un reloj fijo. */
    Clock reloj = Clock.systemUTC();

    /** El motivo que viaja a Pagos y queda con el pago. Entra en las 200 posiciones de Pagos. */
    static String motivo(String entradaId) {
        return "Reventa: Ventas no transfirió la entrada " + entradaId + " al comprador (ya no estaba publicada).";
    }

    /** Si ese pago ya tiene un reembolso anotado, en cualquier estado. */
    public boolean estaAnotado(String pagoId) {
        return reembolsos.buscar(pagoId).isPresent();
    }

    /**
     * Anota el reembolso, {@code PENDIENTE}. Si ya estaba anotado (por otro
     * pedido, o por este mismo en un intento anterior), no cambia nada. Una
     * falla de la base sale como excepción: quien llama no anula la publicación.
     */
    public void anotar(String publicacionId, String compradorId, String pagoId, long monto, String motivo) {
        try {
            if (!reembolsos.anotar(new Reembolso(pagoId, publicacionId, compradorId, monto, motivo, ahora()))) {
                LOG.info("El reembolso del pago " + Textos.enUnaLinea(pagoId) + " ya estaba anotado.");
            }
        } catch (ErroresDeNegocio.Conflicto e) {
            if (!ReembolsoRepositoryJpa.REEMBOLSO_YA_ANOTADO.equals(e.tipo())) {
                throw e;
            }
            // Otro pedido lo anotó en el mismo instante: vale ese.
        }
    }

    /**
     * Le pide a Pagos que devuelva el pago y anota cómo terminó. No lanza: si
     * algo falla, el reembolso queda {@code PENDIENTE} para el barrido.
     */
    public Devolucion intentar(String pagoId, String publicacionId, String motivo) {
        try {
            Devolucion devolucion = pagos.reembolsar(pagoId, publicacionId, motivo);
            switch (devolucion) {
                case HECHA -> {
                    reembolsos.resolver(pagoId, EstadoReembolso.HECHO, ahora());
                    LOG.info("Reventa " + publicacionId + ": el pago " + Textos.enUnaLinea(pagoId)
                            + " se devolvió (REEMBOLSADO en Pagos).");
                }
                case RECHAZADA -> {
                    reembolsos.resolver(pagoId, EstadoReembolso.RECHAZADO, ahora());
                    LOG.severe("Reventa " + publicacionId + ": Pagos no acepta devolver el pago "
                            + Textos.enUnaLinea(pagoId) + ". El reembolso quedó RECHAZADO: hay que revisarlo a mano.");
                }
                case SIN_RESPUESTA -> LOG.warning("Reventa " + publicacionId + ": Pagos no respondió al devolver el pago "
                        + Textos.enUnaLinea(pagoId) + ". El reembolso sigue PENDIENTE y se reintenta en un minuto.");
            }
            return devolucion;
        } catch (RuntimeException e) {
            LOG.warning("Reventa " + publicacionId + ": el reembolso del pago " + Textos.enUnaLinea(pagoId)
                    + " sigue PENDIENTE y se reintenta en un minuto. Motivo: " + Textos.enUnaLinea(String.valueOf(e)));
            return Devolucion.SIN_RESPUESTA;
        }
    }

    /**
     * Repite los reembolsos {@code PENDIENTE}. Lo llama el barrido de
     * {@code ConciliacionDeReventas}. Uno que falla no frena a los demás; si
     * falla la búsqueda en la base, la corrida se corta con un aviso.
     */
    public void reintentarPendientes() {
        List<Reembolso> pendientes;
        try {
            pendientes = reembolsos.buscarPendientes();
        } catch (RuntimeException e) {
            LOG.warning("DevolucionDeCobros: la corrida se cortó y se reintenta en el próximo minuto. Motivo: " + e);
            return;
        }
        for (Reembolso reembolso : pendientes) {
            intentar(reembolso.getPagoId(), reembolso.getPublicacionId(), reembolso.getMotivo());
        }
    }

    private LocalDateTime ahora() {
        return LocalDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }
}
