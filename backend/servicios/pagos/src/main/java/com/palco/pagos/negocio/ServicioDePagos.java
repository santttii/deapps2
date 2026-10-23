package com.palco.pagos.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.Textos;
import com.palco.pagos.api.Dtos.NuevoPagoDto;
import com.palco.pagos.api.Dtos.PagoDto;
import com.palco.pagos.api.Dtos.ReembolsoDto;
import com.palco.pagos.datos.PagoRepository;
import com.palco.pagos.dominio.EstadoPago;
import com.palco.pagos.dominio.Pago;
import com.palco.pagos.pasarela.PasarelaDePagos;
import com.palco.pagos.pasarela.ResultadoPasarela;

/**
 * Stateless: no recuerda nada entre una llamada y la siguiente. Lo que tiene
 * que durar (el pago y la clave con la que se pidió) queda en la base, así que
 * cualquier instancia del pool puede atender cualquier pedido, también la
 * repetición de uno anterior.
 *
 * Sin transacción propia ({@code NOT_SUPPORTED}): entre buscar la clave y
 * guardar el pago se espera a la pasarela, que es un tercero y puede tardar.
 * Con una transacción abierta, esa espera retendría una de las pocas
 * conexiones a la base que comparten los 8 servicios. Cada método del
 * repositorio abre y cierra su propia transacción, corta.
 *
 * El reparto con la pasarela: este servicio decide y guarda; la pasarela solo
 * habla con el exterior. Por eso la idempotencia está escrita una sola vez,
 * acá, y vale para cualquier pasarela.
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class ServicioDePagos implements IPagos {

    private static final Logger LOG = Logger.getLogger(ServicioDePagos.class.getName());

    /** El {@code type} del 409 cuando una clave ya usada llega con otra venta u otro monto. */
    static final String CLAVE_REUTILIZADA = "clave-reutilizada";

    /** El {@code type} del 409 cuando se pide devolver un pago que no está aprobado o es de otra venta. */
    static final String PAGO_NO_REEMBOLSABLE = "pago-no-reembolsable";

    /** Lo que va adelante del id del pago en la clave del reembolso ante la pasarela. */
    static final String PREFIJO_CLAVE_REEMBOLSO = "reembolso:";

    @Inject
    PagoRepository pagos;

    /** La que eligió {@code FabricaDePasarela}; acá no se sabe cuál es. */
    @Inject
    PasarelaDePagos pasarela;

    /** De dónde sale el "ahora". No es estado: es fijo, y los tests lo reemplazan por un reloj detenido. */
    Clock reloj = Clock.systemUTC();

    @Override
    public PagoDto procesarPago(NuevoPagoDto pedido, String claveIdempotencia) {
        // 1. Si este pedido ya se procesó, vale el pago guardado: no se cobra otra vez.
        Optional<Pago> anterior = pagos.buscarPorClave(claveIdempotencia);
        if (anterior.isPresent()) {
            return aDto(siEsElMismoPedido(anterior.get(), pedido));
        }

        // 2. Se cobra, sin ninguna transacción abierta. Si la pasarela no contesta,
        //    SinRespuesta sale por acá y no queda nada guardado.
        ResultadoPasarela resultado = pasarela.cobrar(pedido.ventaId(), pedido.monto(), pedido.medioDePago(),
                pedido.token(), claveIdempotencia);

        // 3. Se guarda el resultado, sea aprobado o rechazado.
        Pago pago = new Pago(nuevoId(), pedido.ventaId(), pedido.monto(), pedido.medioDePago(), pasarela.nombre(),
                resultado.estado(), resultado.motivo(), claveIdempotencia, resultado.idExterno(), ahora());
        try {
            pagos.guardar(pago);
        } catch (ErroresDeNegocio.Conflicto choque) {
            // Dos pedidos con la misma clave llegaron a la vez y los dos pasaron el paso 1.
            // La restricción única de la base dejó guardar a uno solo: vale ese.
            Pago ganador = pagos.buscarPorClave(claveIdempotencia).orElseThrow(() -> choque);
            LOG.info("Dos pedidos simultáneos con la misma clave: se devuelve el pago " + ganador.getId() + ".");
            return aDto(siEsElMismoPedido(ganador, pedido));
        }
        // El id de la venta vino en el pedido: al log va en un solo renglón (ver Textos.enUnaLinea).
        LOG.info("Pago " + pago.getId() + " de la venta " + Textos.enUnaLinea(pago.getVentaId()) + ": "
                + pago.getEstado() + " (pasarela " + pago.getProveedor() + ").");
        return aDto(pago);
    }

    @Override
    public PagoDto obtener(String pagoId) {
        return pagos.buscarPorId(pagoId)
                .map(ServicioDePagos::aDto)
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe el pago " + pagoId + "."));
    }

    @Override
    public PagoDto buscarPorClave(String claveIdempotencia) {
        // Solo lee: no cobra ni guarda nada, así que preguntar dos veces da la misma respuesta.
        return pagos.buscarPorClave(claveIdempotencia)
                .map(ServicioDePagos::aDto)
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No hay ningún pago con esa clave de idempotencia."));
    }

    @Override
    public PagoDto reembolsar(String pagoId, ReembolsoDto pedido) {
        Pago pago = pagos.buscarPorId(pagoId)
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe el pago " + pagoId + "."));
        if (!pago.getVentaId().equals(pedido.ventaId())) {
            throw new ErroresDeNegocio.Conflicto(PAGO_NO_REEMBOLSABLE, "El pago " + pagoId + " es de otra venta.");
        }
        // Un reintento de un reembolso que ya se hizo: vale el que quedó guardado.
        if (pago.getEstado() == EstadoPago.REEMBOLSADO) {
            return aDto(pago);
        }
        if (pago.getEstado() != EstadoPago.APROBADO) {
            throw new ErroresDeNegocio.Conflicto(PAGO_NO_REEMBOLSABLE,
                    "Solo se devuelve un pago aprobado; el " + pagoId + " está " + pago.getEstado() + ".");
        }

        // Sin transacción abierta, como el cobro. Si la pasarela no contesta, SinRespuesta sale por acá
        // y el pago sigue APROBADO: repetir el pedido manda la misma clave y la pasarela no devuelve dos veces.
        pasarela.reembolsar(pago.getIdExterno(), pago.getMonto(), PREFIJO_CLAVE_REEMBOLSO + pago.getId());

        if (!pagos.marcarReembolsado(pagoId, pedido.motivo().strip(), ahora())) {
            // Otro pedido lo marcó entre la lectura y acá. Si quedó REEMBOLSADO, es el mismo resultado.
            Pago releido = pagos.buscarPorId(pagoId).orElseThrow();
            if (releido.getEstado() != EstadoPago.REEMBOLSADO) {
                throw new IllegalStateException("El pago " + pagoId + " se devolvió en la pasarela pero quedó "
                        + releido.getEstado() + ".");
            }
            return aDto(releido);
        }
        LOG.info("Pago " + pagoId + " de la venta " + Textos.enUnaLinea(pago.getVentaId()) + ": REEMBOLSADO ($"
                + pago.getMonto() + "). Motivo: " + Textos.enUnaLinea(pedido.motivo()));
        return aDto(pagos.buscarPorId(pagoId).orElseThrow());
    }

    /**
     * Una clave identifica un solo pedido. Si vuelve con otra venta u otro
     * monto no es una repetición sino un pedido distinto con una clave ajena:
     * devolverle el pago guardado sería decirle "cobrado" a algo que no se cobró.
     *
     * El token no entra en la comparación porque no se guarda. Por eso, con la
     * misma clave, venta y monto vale el pago guardado aunque sea un rechazo y
     * ahora llegue otro token: un intento nuevo necesita una clave nueva.
     */
    private static Pago siEsElMismoPedido(Pago guardado, NuevoPagoDto pedido) {
        if (!guardado.getVentaId().equals(pedido.ventaId()) || guardado.getMonto() != pedido.monto()) {
            throw new ErroresDeNegocio.Conflicto(CLAVE_REUTILIZADA,
                    "Esa clave de idempotencia ya se usó para otra venta o con otro monto.");
        }
        return guardado;
    }

    /** El instante del reloj, en UTC y sin fracción de segundo: no depende de la zona horaria del servidor. */
    private LocalDateTime ahora() {
        return LocalDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String nuevoId() {
        return "P-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static PagoDto aDto(Pago p) {
        return new PagoDto(p.getId(), p.getVentaId(), p.getMonto(), p.getEstado().name(), p.getMotivo(),
                p.getIdExterno());
    }
}
