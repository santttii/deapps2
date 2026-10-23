package com.palco.reventa.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import jakarta.ejb.DependsOn;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.ReglasDeNegocio;
import com.palco.common.Textos;
import com.palco.reventa.datos.PublicacionRepository;
import com.palco.reventa.dominio.Publicacion;
import com.palco.reventa.negocio.externos.ClienteDePagos;
import com.palco.reventa.negocio.externos.ClienteDeVentas;

/**
 * La conciliación de las compras de reventa que no terminaron (limitación L1).
 *
 * Si al comprar una publicación Pagos no responde, la toma queda anotada: no
 * se sabe si se cobró. Antes, la toma vencía a los 2 minutos y otra persona
 * podía comprar la misma publicación aunque el primer cobro se hubiera hecho;
 * ese comprador quedaba con el pago hecho y sin entrada. Ahora la toma no vence
 * sola, y este barrido le pregunta a Pagos, una vez por minuto, cómo terminó
 * el cobro de cada toma que lleva más de {@link #MINUTOS_DE_MARGEN} minutos sin
 * cerrarse:
 * <ul>
 * <li>Pago aprobado: Ventas transfiere la entrada al titular que quedó anotado
 * en la toma y la publicación queda {@code VENDIDA}. Ventas transfiere una
 * sola vez por publicación, así que un reintento del comprador al mismo tiempo
 * no la duplica. Si Ventas rechaza la transferencia, la publicación queda
 * {@code ANULADA} y el pago se devuelve con {@link DevolucionDeCobros}.</li>
 * <li>Pago rechazado, o ningún pago con esa clave: la publicación se libera y
 * otro la puede comprar.</li>
 * <li>Pagos o Ventas no responden: no cambia nada y se pregunta de nuevo en un
 * minuto.</li>
 * </ul>
 *
 * El margen es para no preguntar mientras el cobro todavía puede estar en
 * camino (Pagos espera hasta 10 segundos y Ventas hasta 10 más por pedido):
 * preguntar antes daría un "no hay pago" falso. Liberar exige que la toma siga
 * siendo la que se miró: si el comprador reintentó mientras tanto, la toma es
 * más nueva y no se toca.
 *
 * Mismo diseño que la conciliación de Ventas: {@code @Singleton} para que haya
 * un solo barrido, {@code @Schedule} no persistente, {@code @DependsOn} de las
 * migraciones y sin transacción propia, para no retener una conexión de la base
 * mientras espera a Pagos o a Ventas. Corre en el segundo 45.
 *
 * El mismo singleton reintenta, en el segundo 15, los reembolsos que quedaron
 * pendientes ({@link #reintentarReembolsos}).
 */
@Singleton
@DependsOn("MigracionesReventa")
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class ConciliacionDeReventas {

    private static final Logger LOG = Logger.getLogger(ConciliacionDeReventas.class.getName());

    /** Cuánto se espera desde la toma antes de preguntarle a Pagos. Tiempo técnico, no regla de negocio. */
    static final int MINUTOS_DE_MARGEN = 2;

    /** Cómo terminó la conciliación de una publicación. Sirve para el log y para los tests. */
    enum Desenlace {
        /** Había un pago aprobado: la entrada se transfirió y la publicación quedó VENDIDA. */
        VENDIDA,
        /** No había pago, o fue rechazado: la publicación quedó libre. */
        LIBERADA,
        /** El cobro se aprobó pero Ventas no aceptó la transferencia: la publicación se anuló y el pago se devuelve (L2). */
        ANULADA,
        /** El cobro se aprobó pero la toma no tiene titular (anterior a la migración V2): a mano. */
        SIN_TITULAR,
        /** Otro pedido la cerró o el comprador reintentó mientras tanto. */
        CERRADA_POR_OTRO,
        /** Pagos o Ventas no respondieron: queda para la corrida siguiente. */
        SIN_RESPUESTA
    }

    @Inject
    PublicacionRepository publicaciones;

    @Inject
    ClienteDePagos pagos;

    @Inject
    ClienteDeVentas ventas;

    @Inject
    DevolucionDeCobros devoluciones;

    /** De dónde sale el "ahora"; los tests lo reemplazan por un reloj fijo. */
    Clock reloj = Clock.systemUTC();

    /**
     * Una corrida. Una publicación que no se puede conciliar no frena a las
     * demás; si falla la búsqueda en la base, la corrida se corta con un aviso.
     */
    @Schedule(hour = "*", minute = "*", second = "45", persistent = false)
    void conciliarComprasSinResultado() {
        LocalDateTime ahora = LocalDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime intentadasHasta = ahora.minusMinutes(MINUTOS_DE_MARGEN);
        List<Publicacion> pendientes;
        try {
            pendientes = publicaciones.buscarTomasSinCerrar(intentadasHasta);
        } catch (RuntimeException e) {
            LOG.warning("ConciliacionDeReventas: la corrida se cortó y se reintenta en el próximo minuto. Motivo: " + e);
            return;
        }
        for (Publicacion publicacion : pendientes) {
            try {
                conciliar(publicacion, ahora, intentadasHasta);
            } catch (RuntimeException e) {
                LOG.warning("ConciliacionDeReventas: la publicación " + publicacion.getId()
                        + " queda para la próxima corrida. Motivo: " + Textos.enUnaLinea(String.valueOf(e)));
            }
        }
    }

    /**
     * Los reembolsos que quedaron {@code PENDIENTE} porque Pagos no respondió
     * (limitación L2). Corre en el segundo 15, lejos de la conciliación de las
     * tomas, y el trabajo lo hace {@link DevolucionDeCobros}.
     */
    @Schedule(hour = "*", minute = "*", second = "15", persistent = false)
    void reintentarReembolsos() {
        devoluciones.reintentarPendientes();
    }

    /** Concilia una publicación de la lista. Es un paso aparte para probarlo sin temporizador. */
    Desenlace conciliar(Publicacion publicacion, LocalDateTime ahora, LocalDateTime intentadasHasta) {
        String id = publicacion.getId();
        String clave = publicacion.getClaveCompra();
        Optional<ClienteDePagos.Cobro> cobro;
        try {
            cobro = pagos.buscarCobro(id, clave);
        } catch (ErroresDeNegocio.SinRespuesta e) {
            LOG.warning("ConciliacionDeReventas: no se sabe cómo terminó el cobro de la publicación " + id
                    + " (clave " + Textos.enUnaLinea(clave) + "). Sigue tomada y se pregunta de nuevo en un minuto.");
            return Desenlace.SIN_RESPUESTA;
        }

        if (cobro.isEmpty() || !cobro.get().aprobado()) {
            if (!publicaciones.liberarConciliada(id, clave, intentadasHasta)) {
                return Desenlace.CERRADA_POR_OTRO;
            }
            LOG.info("ConciliacionDeReventas: la publicación " + id + " tenía una compra sin terminar y "
                    + (cobro.isEmpty() ? "Pagos no tiene ningún cobro con esa clave"
                            : "Pagos tiene ese cobro rechazado (" + Textos.enUnaLinea(cobro.get().motivo()) + ")")
                    + ". Queda libre para otra compra.");
            return Desenlace.LIBERADA;
        }

        String pagoId = cobro.get().pagoId();
        if (devoluciones.estaAnotado(pagoId)) {
            // La compra ya se anuló antes (la base falló justo después de anotar el reembolso):
            // la entrada no se transfiere, y el reembolso lo reintenta el barrido de reembolsos.
            publicaciones.anular(id, clave, ahora);
            return Desenlace.ANULADA;
        }
        if (publicacion.getTitularNombre() == null || publicacion.getTitularDni() == null) {
            LOG.severe("ConciliacionDeReventas: la publicación " + id + " tiene el pago aprobado "
                    + Textos.enUnaLinea(pagoId) + " pero la toma no tiene titular. Queda tomada para resolverla a mano.");
            return Desenlace.SIN_TITULAR;
        }

        ClienteDeVentas.Transferencia transferencia;
        try {
            transferencia = ventas.transferir(id, publicacion.getEntradaId(), publicacion.getVendedorId(),
                    publicacion.getCompradorId(),
                    new ClienteDeVentas.Titular(publicacion.getTitularNombre(), publicacion.getTitularDni()),
                    publicacion.getPrecio(), pagoId);
        } catch (ErroresDeNegocio.Conflicto | ErroresDeNegocio.NoEncontrado | ErroresDeNegocio.ReglaIncumplida e) {
            // Ventas contestó que no: la entrada ya no se puede transferir. Se anula y se devuelve el pago (L2).
            String motivo = DevolucionDeCobros.motivo(publicacion.getEntradaId());
            long monto = publicacion.getPrecio() + ReglasDeNegocio.cargoServicio(publicacion.getPrecio());
            devoluciones.anotar(id, publicacion.getCompradorId(), pagoId, monto, motivo);
            publicaciones.anular(id, clave, ahora);
            LOG.warning("ConciliacionDeReventas: la publicación " + id + " tiene el pago aprobado "
                    + Textos.enUnaLinea(pagoId) + " del comprador " + Textos.enUnaLinea(publicacion.getCompradorId())
                    + " pero Ventas no transfirió la entrada " + publicacion.getEntradaId()
                    + ". La publicación quedó ANULADA y el pago se devuelve. Motivo: " + Textos.enUnaLinea(e.getMessage()));
            devoluciones.intentar(pagoId, id, motivo);
            return Desenlace.ANULADA;
        } catch (RuntimeException e) {
            LOG.warning("ConciliacionDeReventas: la publicación " + id + " tiene el pago aprobado "
                    + Textos.enUnaLinea(pagoId) + " pero Ventas no respondió al transferir. Se reintenta en un minuto.");
            return Desenlace.SIN_RESPUESTA;
        }

        if (!publicaciones.marcarVendida(id, publicacion.getCompradorId(), clave, transferencia.ventaId(), pagoId,
                ahora)) {
            // Ventas transfirió (una sola vez por publicación): el reintento del comprador la cerró antes.
            return Desenlace.CERRADA_POR_OTRO;
        }
        LOG.info("ConciliacionDeReventas: la publicación " + id + " tenía una compra sin terminar con el pago aprobado "
                + Textos.enUnaLinea(pagoId) + ". La entrada se transfirió a " + Textos.enUnaLinea(publicacion.getCompradorId())
                + " (venta " + Textos.enUnaLinea(transferencia.ventaId()) + ") y la publicación quedó VENDIDA.");
        return Desenlace.VENDIDA;
    }
}
