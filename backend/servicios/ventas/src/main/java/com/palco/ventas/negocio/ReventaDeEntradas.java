package com.palco.ventas.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.ReglasDeNegocio;
import com.palco.common.Textos;
import com.palco.common.mensajeria.PublicadorPagoConfirmado;
import com.palco.ventas.api.DtosDeReventa.EntradaEnReventaDto;
import com.palco.ventas.api.DtosDeReventa.TransferenciaDto;
import com.palco.ventas.api.DtosDeReventa.TransferenciaHechaDto;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.datos.ReventaDeEntradasRepository;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.EstadoEntrada;
import com.palco.ventas.dominio.Venta;

/**
 * Lo que la reventa le hace a las entradas. Ventas es la dueña de las
 * entradas, así que los cambios de estado de la reventa se hacen acá;
 * ServicioDeReventa los pide por REST con el token de servicio y guarda en su
 * propio esquema las publicaciones y sus precios.
 *
 * Stateless: cada operación es independiente y no recuerda nada de la
 * anterior. Lo que dura (el estado de la entrada, la venta nueva del
 * comprador) queda en la base, así que cualquier instancia del pool atiende
 * cualquier pedido. Como {@link UsoDeEntradas}, no es parte de {@link IVentas}:
 * esa interfaz es la conversación de una compra, que vive en un bean stateful.
 *
 * Cada operación corre en una transacción (la de defecto de un Session Bean).
 * En la transferencia eso es lo que importa: la entrada original pasa a
 * {@code VENDIDA} y nace la venta del comprador con su entrada, o no pasa
 * nada de todo eso.
 */
@Stateless
public class ReventaDeEntradas {

    private static final Logger LOG = Logger.getLogger(ReventaDeEntradas.class.getName());

    /** 409: la entrada no está {@code VALIDA} (ya publicada, usada, vendida o sin emitir): no se puede publicar. */
    public static final String ENTRADA_NO_DISPONIBLE = "entrada-no-disponible";

    /** 409: la entrada no está {@code PUBLICADA}: no se puede retirar ni transferir. */
    public static final String ENTRADA_NO_PUBLICADA = "entrada-no-publicada";

    /**
     * Prefijo de la clave de idempotencia de la venta que nace de una reventa.
     * La clave es el prefijo más el id de la publicación: la misma publicación
     * vendida al mismo comprador da siempre la misma clave, y la restricción
     * única de la tabla {@code venta} impide crearla dos veces.
     */
    static final String PREFIJO_CLAVE = "reventa:";

    @Inject
    EntradaRepository entradas;

    @Inject
    VentaRepository ventas;

    @Inject
    ReventaDeEntradasRepository reventa;

    /** {@code MANDATORY}: se suma a la transacción de la transferencia. */
    @Inject
    PublicadorPagoConfirmado publicador;

    /** De dónde sale el "ahora" (UTC); los tests lo reemplazan por un reloj fijo. */
    Clock reloj = Clock.systemUTC();

    /** La entrada como la necesita la reventa. Una entrada ajena y una que no existe responden lo mismo: 404. */
    public EntradaEnReventaDto consultar(String entradaId, String vendedorId) {
        return aDto(entradaDe(entradaId, vendedorId));
    }

    /** {@code VALIDA} → {@code PUBLICADA}. Lanza 404 si no es del vendedor y 409 {@code entrada-no-disponible} si no está VALIDA. */
    public EntradaEnReventaDto publicar(String entradaId, String vendedorId) {
        entradaDe(entradaId, vendedorId);
        // Decide el UPDATE condicional, no el estado leído: de dos publicaciones a la vez pasa una sola.
        if (!reventa.publicar(entradaId, vendedorId)) {
            throw new ErroresDeNegocio.Conflicto(ENTRADA_NO_DISPONIBLE,
                    "La entrada no se puede publicar: solo se revende una entrada emitida y sin usar.");
        }
        return aDto(entradaDe(entradaId, vendedorId));
    }

    /**
     * {@code PUBLICADA} → {@code VALIDA}. Si ya estaba {@code VALIDA} responde
     * lo mismo: retirar dos veces deja la entrada igual, y así Reventa puede
     * repetir el pedido si no supo si llegó.
     */
    public EntradaEnReventaDto retirar(String entradaId, String vendedorId) {
        entradaDe(entradaId, vendedorId);
        reventa.retirar(entradaId, vendedorId);
        Entrada actual = entradaDe(entradaId, vendedorId);
        if (actual.getEstado() != EstadoEntrada.VALIDA) {
            throw new ErroresDeNegocio.Conflicto(ENTRADA_NO_PUBLICADA,
                    "La entrada no está publicada: está " + actual.getEstado().name() + ".");
        }
        return aDto(actual);
    }

    /**
     * Transfiere una entrada vendida en la reventa, todo en esta transacción:
     * la original pasa de {@code PUBLICADA} a {@code VENDIDA} y nace una venta
     * {@code EMITIDA} del comprador con una entrada nueva, {@code VALIDA}, con
     * código QR nuevo, el titular nuevo y como precio pagado el de la reventa.
     * Así aparece en {@code GET /api/v1/ventas} del comprador como cualquier
     * compra.
     *
     * La venta nueva se arma con las mismas piezas que una compra del
     * catálogo (un renglón de un sector, el cargo de servicio de
     * {@code ReglasDeNegocio}, confirmar con el pago y emitir), así su total
     * es el mismo que cobró Reventa: precio más cargo de servicio.
     *
     * Es idempotente: si el comprador ya tiene la venta de esa publicación
     * (Reventa repite el pedido porque no supo la respuesta), la devuelve sin
     * cambiar nada. Lanza 404 si la entrada no es del vendedor y 409
     * {@code entrada-no-publicada} si no está publicada.
     */
    public TransferenciaHechaDto transferir(TransferenciaDto pedido) {
        String clave = PREFIJO_CLAVE + pedido.publicacionId();
        Optional<Venta> yaHecha = ventas.buscarPorClave(pedido.compradorId(), clave);
        if (yaHecha.isPresent()) {
            return resultado(yaHecha.get());
        }
        validarTitular(pedido.titular().nombre(), pedido.titular().dni());

        Entrada original = entradaDe(pedido.entradaId(), pedido.vendedorId());
        if (!reventa.marcarVendida(pedido.entradaId(), pedido.vendedorId())) {
            throw new ErroresDeNegocio.Conflicto(ENTRADA_NO_PUBLICADA,
                    "La entrada ya no está publicada en la reventa.");
        }

        LocalDateTime ahora = ahora();
        // La reserva vence en el mismo instante en que nace: no hay cupo que retener, porque el
        // lugar ya existía. Y nadie más la ve EN_CHECKOUT: todo pasa en esta transacción.
        Venta venta = new Venta(nuevoId("V-"), pedido.compradorId(), original.getVenta().getEventoId(), clave,
                ahora, ahora);
        venta.agregarItem(original.getSectorId(), original.getSector(), pedido.precio(), 1);
        ventas.guardar(venta);
        String ventaId = venta.getId();
        List<Entrada> nuevas = List.of(new Entrada(nuevoId("T-"), venta.getItems().get(0),
                pedido.titular().nombre().strip(), pedido.titular().dni(), 1));
        entradas.guardar(nuevas);
        if (!ventas.confirmar(ventaId, pedido.pagoId(), ahora)) {
            throw new IllegalStateException("La venta " + ventaId + " de la reventa no se pudo confirmar.");
        }
        entradas.emitirPendientes(ventaId, ahora);
        ventas.marcarEmitida(ventaId);
        // REVENTA_CONFIRMADA al tópico, en esta misma transacción: el aviso, la auditoría y la factura
        // de la compra en la reventa salen solo si la transferencia queda confirmada (limitación L13).
        // No va a la cola de emisión: la entrada nueva ya quedó emitida acá arriba.
        publicador.publicarReventa(ServicioDeVentas.mensaje(venta, nuevas, ahora));

        LOG.info("Reventa " + Textos.enUnaLinea(pedido.publicacionId()) + ": la entrada " + original.getId()
                + " pasó a VENDIDA y nació la venta " + ventaId + " con el pago "
                + Textos.enUnaLinea(pedido.pagoId()) + ".");
        return resultado(ventas.buscarPorId(ventaId).orElseThrow());
    }

    /** La entrada, si existe y es de ese vendedor. Una ajena responde igual que una que no existe. */
    private Entrada entradaDe(String entradaId, String vendedorId) {
        return entradas.buscarPorId(entradaId)
                .filter(e -> e.getVenta().getCompradorId().equals(vendedorId))
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe esa entrada."));
    }

    private static TransferenciaHechaDto resultado(Venta venta) {
        Entrada entrada = venta.getEntradas().get(0);
        return new TransferenciaHechaDto(venta.getId(), ServicioDeVentas.aDto(entrada));
    }

    /** Las mismas reglas que un titular del catálogo: el nombre en una línea y el DNI de ReglasDeNegocio. */
    private static void validarTitular(String nombre, String dni) {
        if (nombre.strip().codePoints().anyMatch(Textos::esDeControl)) {
            throw new ErroresDeNegocio.DatosInvalidos("titular.nombre",
                    "El nombre del titular va en una sola línea, sin saltos de línea ni caracteres de control.");
        }
        if (!ReglasDeNegocio.esDniValido(dni)) {
            throw new ErroresDeNegocio.DatosInvalidos("titular.dni", "El DNI debe tener 7 u 8 dígitos.");
        }
    }

    private static EntradaEnReventaDto aDto(Entrada e) {
        return new EntradaEnReventaDto(e.getId(), e.getVenta().getId(), e.getVenta().getEventoId(), e.getSectorId(),
                e.getSector(), e.getPrecioPagado(), e.getEstado().name());
    }

    private LocalDateTime ahora() {
        return LocalDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    /** El prefijo más 8 caracteres de un UUID, como las ventas y las entradas del catálogo. */
    private static String nuevoId(String prefijo) {
        return prefijo + UUID.randomUUID().toString().substring(0, 8);
    }
}
