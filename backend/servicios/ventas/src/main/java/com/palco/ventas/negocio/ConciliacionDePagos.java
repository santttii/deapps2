package com.palco.ventas.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
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
import com.palco.common.Textos;
import com.palco.ventas.api.Dtos.TitularDto;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;
import com.palco.ventas.negocio.externos.Cobros;
import com.palco.ventas.negocio.externos.ResultadoDeCobro;

/**
 * La conciliación de los pagos sin resultado. Cierra las compras que quedaron
 * {@code EN_CHECKOUT} con un intento de pago anotado ({@code clave_pago}) del
 * que Ventas no supo cómo terminó: un 504 de Pagos, o una falla inesperada
 * después de cobrar que deshizo la confirmación. Esas ventas el barrido de
 * reservas no las toca (no se libera el cupo de algo que pudo cobrarse) y el
 * comprador solo puede reintentar mientras viva la instancia de su compra.
 *
 * Una vez por minuto le pregunta a Pagos por la clave de cada una
 * ({@code Cobros.buscarCobro}) y decide:
 * <ul>
 * <li>Hay un pago aprobado: confirma la venta con ese pago, crea sus entradas
 * y publica {@code PAGO_CONFIRMADO} en una sola transacción, igual que una
 * compra pagada ({@link ConfirmacionConciliada}). Las entradas se emiten por
 * la cola.</li>
 * <li>No hay pago, o el pago fue rechazado: quita la marca, vence la venta y
 * devuelve su cupo a Eventos, en ese orden.</li>
 * <li>Pagos no responde o no se puede saber: no cambia nada y la deja para la
 * corrida siguiente.</li>
 * </ul>
 *
 * Qué ventas toma. Las que tienen la marca y cuyo último intento de pago
 * empezó hace más de {@link #MINUTOS_DE_MARGEN} minutos: antes de eso, ese
 * cobro puede seguir en camino y preguntar daría un "no hay pago" falso. Y de
 * esas, solo las que ya no tienen dueño: o perdieron la instancia (no queda
 * nadie que pueda reintentar), o se les venció la reserva. Mientras la
 * instancia vive y la reserva vale, el comprador puede reintentar con la misma
 * clave, y es ese reintento el que resuelve la venta.
 *
 * Que dos cosas no cierren la misma venta lo deciden la base y el índice de
 * compras. Cada cierre es un UPDATE con la condición adentro: confirmar exige
 * {@code EN_CHECKOUT} con esa clave; vencer exige además que no haya llegado
 * un reintento después de la pregunta a Pagos. Si el UPDATE no cambia
 * ninguna fila, la venta la cerró otro y acá no se hace nada más (ni cupo ni
 * mensaje). Y mientras concilia una venta, la marca como ocupada en
 * {@link ComprasEnCurso}, la misma marca que usa pagar: un reintento
 * simultáneo del comprador recibe 409 {@code operacion-en-curso}.
 *
 * Sin estado (stateless): no guarda nada entre una corrida y la siguiente;
 * qué ventas conciliar se lo pregunta cada vez a la base y cómo terminó cada
 * cobro, a Pagos. Es un {@code @Singleton} por la misma razón que
 * {@link VencimientoDeReservas}: tiene que haber un solo barrido en el
 * servidor, y el contenedor no deja que una corrida empiece mientras la
 * anterior sigue. Es un componente aparte del barrido de reservas porque
 * responde otra pregunta (no "¿venció la hora?" sino "¿se cobró?") y espera a
 * otro servicio; las ventas que toma uno y otro no se cruzan: el barrido de
 * reservas solo toca las que no tienen clave de pago. Corre en el segundo 30
 * para no coincidir con él.
 *
 * Corre sin transacción propia ({@code NOT_SUPPORTED}): mientras espera a
 * Pagos no retiene una conexión de la base. La confirmación abre la suya en
 * {@link ConfirmacionConciliada}; el vencimiento corre en la transacción corta
 * del repositorio, que queda confirmada antes de devolver el cupo.
 */
@Singleton
@DependsOn("MigracionesVentas")
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class ConciliacionDePagos {

    private static final Logger LOG = Logger.getLogger(ConciliacionDePagos.class.getName());

    /**
     * Cuánto se espera desde el último intento de pago antes de preguntarle a
     * Pagos. Un cobro de Ventas espera a Pagos como mucho unos 48 segundos (2
     * de conexión y 10 de lectura por pedido, con hasta dos reintentos: el del
     * 401 y el del 409 {@code pago-duplicado}). Dos minutos lo cubren con
     * holgura. No es una regla de negocio sino un tiempo técnico, como los
     * timeouts de los clientes: por eso está acá y no en {@code ReglasDeNegocio}.
     */
    static final int MINUTOS_DE_MARGEN = 2;

    /** Cómo terminó la conciliación de una venta. Sirve para el log y para los tests. */
    enum Desenlace {
        /** Había un pago aprobado y esta corrida confirmó la venta. */
        CONFIRMADA,
        /** No había pago, o fue rechazado, y esta corrida venció la venta y devolvió su cupo. */
        VENCIDA,
        /** El UPDATE no cambió nada: otro pedido la cerró, o llegó un reintento. */
        CERRADA_POR_OTRO,
        /** Pagos no respondió o no se pudo saber: queda para la corrida siguiente. */
        SIN_RESPUESTA,
        /** Había un pago aprobado pero faltan los titulares para crear las entradas: hay que resolverla a mano. */
        SIN_TITULARES,
        /** La compra todavía tiene dueño (instancia viva y reserva vigente) u otra llamada en curso. */
        NO_SE_TOCA
    }

    @Inject
    VentaRepository ventas;

    @Inject
    ComprasEnCurso compras;

    /** Quién cobra: el mismo que cobró (lo elige {@code FabricaDeCobros}). Es a quien se le pregunta. */
    @Inject
    Cobros cobros;

    @Inject
    ConfirmacionConciliada confirmacion;

    /** De dónde sale el "ahora". No es estado: es fijo, y los tests lo reemplazan por un reloj detenido. */
    Clock reloj = Clock.systemUTC();

    /**
     * Una corrida. La llama el contenedor en el segundo 30 de cada minuto.
     * {@code persistent = false}, como el barrido de reservas: no hace falta
     * recuperar corridas perdidas, la primera concilia todo lo atrasado.
     *
     * Una venta que no se puede conciliar (Pagos no responde, una falla al
     * confirmarla) no frena a las demás. Si falla la búsqueda en la base, la
     * corrida se corta con un aviso y la siguiente vuelve a empezar.
     */
    @Schedule(hour = "*", minute = "*", second = "30", persistent = false)
    void conciliarPagosSinResultado() {
        LocalDateTime ahora = ServicioDeVentas.ahoraEnUtc(reloj);
        LocalDateTime intentadosHasta = ahora.minusMinutes(MINUTOS_DE_MARGEN);
        try {
            for (Venta venta : ventas.buscarPagosSinResultado(intentadosHasta)) {
                try {
                    conciliar(venta, ahora, intentadosHasta);
                } catch (RuntimeException e) {
                    // Lo que haya fallado en esta venta (una falla de la base al confirmarla, por ejemplo) se
                    // deshizo con su transacción: la venta sigue como estaba y la toma la corrida siguiente.
                    LOG.warning("ConciliacionDePagos: la venta " + venta.getId() + " queda para la próxima corrida."
                            + " Motivo: " + Textos.enUnaLinea(String.valueOf(e)));
                }
            }
        } catch (RuntimeException e) {
            LOG.warning("ConciliacionDePagos: la corrida se cortó y se reintenta en el próximo minuto. Motivo: " + e);
        }
    }

    /**
     * Concilia una venta de la lista. Es un paso aparte para poder probarlo
     * sin temporizador.
     */
    Desenlace conciliar(Venta venta, LocalDateTime ahora, LocalDateTime intentadosHasta) {
        String ventaId = venta.getId();
        if (compras.tieneInstancia(ventaId) && !venta.reservaVencida(ahora)) {
            // El comprador todavía puede reintentar con la misma clave: esta venta es suya.
            return Desenlace.NO_SE_TOCA;
        }
        if (!compras.tomarParaConciliar(ventaId)) {
            // Hay un pago u otra llamada en curso sobre esta compra: se mira en la corrida siguiente.
            return Desenlace.NO_SE_TOCA;
        }
        try {
            String clave = venta.getClavePago();
            Optional<ResultadoDeCobro> cobro;
            try {
                cobro = cobros.buscarCobro(ventaId, clave);
            } catch (ErroresDeNegocio.SinRespuesta e) {
                LOG.warning("ConciliacionDePagos: no se sabe cómo terminó el cobro de la venta " + ventaId
                        + " (clave " + clave + "). Queda como está y se pregunta de nuevo en un minuto. Motivo: "
                        + Textos.enUnaLinea(e.getMessage()));
                return Desenlace.SIN_RESPUESTA;
            }
            if (cobro.isPresent() && cobro.get().aprobado()) {
                return confirmar(venta, clave, cobro.get().pagoId());
            }
            return vencer(venta, clave, intentadosHasta, cobro);
        } finally {
            compras.soltarDeLaConciliacion(ventaId);
        }
    }

    /** Hubo cobro: la venta se confirma con ese pago y los titulares que quedaron copiados. */
    private Desenlace confirmar(Venta venta, String clave, String pagoId) {
        String ventaId = venta.getId();
        List<TitularDto> titulares = ventas.buscarTitulares(ventaId).stream()
                .map(titular -> new TitularDto(titular.getNombre(), titular.getDni()))
                .toList();
        if (titulares.size() != entradasCompradas(venta)) {
            // Pasa con una venta marcada antes de que existiera la copia de los titulares. Hay plata
            // cobrada y no se sabe a nombre de quién van las entradas: no se inventan ni se vence.
            LOG.severe("ConciliacionDePagos: la venta " + ventaId + " tiene el pago aprobado "
                    + Textos.enUnaLinea(pagoId) + " pero le faltan los titulares (hay " + titulares.size() + " de "
                    + entradasCompradas(venta) + "). Queda EN_CHECKOUT para resolverla a mano.");
            return Desenlace.SIN_TITULARES;
        }
        if (!confirmacion.confirmar(ventaId, clave, pagoId, titulares)) {
            return Desenlace.CERRADA_POR_OTRO;
        }
        // Si la instancia seguía en el índice, ya no sirve: la venta está paga.
        compras.olvidar(ventaId);
        return Desenlace.CONFIRMADA;
    }

    /**
     * No hubo cobro: se quita la marca y se vence la venta con un solo UPDATE,
     * que queda confirmado antes de devolver el cupo. Solo quien cambió la
     * fila devuelve el cupo.
     */
    private Desenlace vencer(Venta venta, String clave, LocalDateTime intentadosHasta,
            Optional<ResultadoDeCobro> cobro) {
        String ventaId = venta.getId();
        if (!ventas.vencerPagoSinCobro(ventaId, clave, intentadosHasta)) {
            return Desenlace.CERRADA_POR_OTRO;
        }
        String porQue = cobro.isPresent()
                ? "Pagos tiene ese cobro rechazado (" + Textos.enUnaLinea(cobro.get().motivo()) + ")"
                : "Pagos no tiene ningún cobro con esa clave";
        LOG.info("ConciliacionDePagos: la venta " + ventaId + " tenía un pago sin resultado (clave " + clave + ") y "
                + porQue + ". Pasó a VENCIDA y se devuelve su cupo a Eventos. Entradas a devolver: "
                + entradasCompradas(venta) + ".");
        compras.devolverCupo(venta);
        compras.olvidar(ventaId);
        return Desenlace.VENCIDA;
    }

    private static int entradasCompradas(Venta venta) {
        return venta.getItems().stream().mapToInt(VentaItem::getCantidad).sum();
    }
}
