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
import com.palco.ventas.api.DtosDeReventa.ControlDeCompraDto;
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

    /**
     * Si la venta es la que nace de una compra en la reventa oficial
     * ({@link #transferir}). Se reconoce por el prefijo de su clave: la clave
     * de una compra del catálogo tiene que ser un UUID
     * ({@code VentasResource.claveValida}), así que nunca empieza así.
     */
    static boolean esCompraEnLaReventa(Venta venta) {
        return venta.getClaveIdempotencia().startsWith(PREFIJO_CLAVE);
    }

    /**
     * El {@code ventaId} con el que Pagos guardó el cobro de esta venta, que
     * es el que Pagos exige para devolverlo. Una compra del catálogo la cobra
     * Ventas con su propio id. Una compra en la reventa la cobra Reventa antes
     * de que la venta exista, así que Pagos la guardó con el id de la
     * publicación: el que está en la clave, después del prefijo. Sale de la
     * clave y no de una columna nueva porque la clave ya lo tiene, también en
     * las ventas que nacieron antes de esta regla.
     */
    static String ventaEnPagos(Venta venta) {
        return esCompraEnLaReventa(venta) ? venta.getClaveIdempotencia().substring(PREFIJO_CLAVE.length())
                : venta.getId();
    }

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
    @SuppressWarnings("java:S8983") // Se fija al crear el bean, no cambia entre llamadas y los tests lo reemplazan.
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
     * Antes de cobrar una compra en la reventa, las dos reglas entre compras
     * de una compra del catálogo ({@link ServicioDeVentas}), con los mismos
     * errores:
     * <ul>
     * <li>el comprador no puede pasar de
     * {@link ReglasDeNegocio#MAX_ENTRADAS_POR_EVENTO} entradas para el evento,
     * sumando todas sus compras: 409 {@code tope-por-evento};</li>
     * <li>el DNI del titular no puede tener ya una entrada en pie para ese
     * evento: 409 {@code dni-con-entrada}.</li>
     * </ul>
     * La entrada que se revende no cuenta para el DNI: al transferirse deja
     * de estar en pie, así que la persona a nombre de la que estaba puede
     * comprarla. Las demás entradas de la venta del vendedor sí cuentan.
     *
     * No cambia nada. Lanza 404 si la entrada no es del vendedor.
     *
     * Con dos compras a la vez esta mirada no alcanza, y un candado acá
     * tampoco: la compra en la reventa no deja nada en este esquema hasta la
     * transferencia, que llega después del cobro, así que otra compra que se
     * cruce con ella no la ve (DEF-46). Las del catálogo sí se ven entre
     * ellas: {@link ReglasEntreCompras}.
     */
    public void controlarCompra(ControlDeCompraDto pedido) {
        exigirId("compradorId", pedido.compradorId());
        validarTitular(pedido.titular().nombre(), pedido.titular().dni());
        Entrada entrada = entradaDe(pedido.entradaId(), pedido.vendedorId());
        String eventoId = entrada.getVenta().getEventoId();
        // Se compra una sola entrada: la que se revende.
        ServicioDeVentas.exigirTopePorEvento(
                ventas.entradasDelCompradorParaElEvento(pedido.compradorId(), eventoId, ahora()), 1);
        ServicioDeVentas.exigirDnisSinEntrada(
                ventas.dnisConOtraEntradaParaElEvento(eventoId, List.of(pedido.titular().dni()), entrada.getId()));
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
     *
     * No vuelve a mirar el tope ni el DNI: eso lo hace {@link #controlarCompra}
     * antes del cobro, y la transferencia llega con el cobro ya hecho. Es el
     * mismo criterio de la compra del catálogo, que mira el DNI antes de
     * cobrar y no después: cortar acá dejaría el pago sin compra.
     */
    public TransferenciaHechaDto transferir(TransferenciaDto pedido) {
        // Antes de la primera consulta, que ya usa la publicación y el comprador.
        exigirId("publicacionId", pedido.publicacionId());
        exigirId("compradorId", pedido.compradorId());
        exigirId("pagoId", pedido.pagoId());
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
        exigirId("entradaId", entradaId);
        exigirId("vendedorId", vendedorId);
        return entradas.buscarPorId(entradaId)
                .filter(e -> e.getVenta().getCompradorId().equals(vendedorId))
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe esa entrada."));
    }

    private static TransferenciaHechaDto resultado(Venta venta) {
        Entrada entrada = venta.getEntradas().get(0);
        return new TransferenciaHechaDto(venta.getId(), ServicioDeVentas.aDto(entrada));
    }

    /**
     * Un id que llega en el pedido, antes de usarlo en una consulta: sin
     * caracteres de control (la regla de {@code Textos.esDeControl}, la misma
     * de los ids de una compra del catálogo). Ningún id que genera Palco los
     * tiene, y PostgreSQL no acepta el carácter nulo (U+0000) en un texto: la
     * consulta fallaba y el pedido terminaba en un 500 en lugar de un 400.
     */
    private static void exigirId(String campo, String id) {
        if (id == null || id.codePoints().anyMatch(Textos::esDeControl)) {
            throw new ErroresDeNegocio.DatosInvalidos(campo, "El id no puede tener caracteres de control.");
        }
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
