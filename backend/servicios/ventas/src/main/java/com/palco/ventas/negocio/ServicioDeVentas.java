package com.palco.ventas.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.ejb.AccessTimeout;
import jakarta.ejb.Remove;
import jakarta.ejb.Stateful;
import jakarta.ejb.StatefulTimeout;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.ReglasDeNegocio;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.mensajeria.PublicadorPagoConfirmado;
import com.palco.ventas.api.Dtos.EntradaDeVentaDto;
import com.palco.ventas.api.Dtos.EntradaDto;
import com.palco.ventas.api.Dtos.ItemDto;
import com.palco.ventas.api.Dtos.ItemPedidoDto;
import com.palco.ventas.api.Dtos.NuevaVentaDto;
import com.palco.ventas.api.Dtos.NuevoPagoDto;
import com.palco.ventas.api.Dtos.TitularDto;
import com.palco.ventas.api.Dtos.VentaDto;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;
import com.palco.ventas.negocio.externos.ClienteDeEventos;
import com.palco.ventas.negocio.externos.ClienteDeEventos.Sector;
import com.palco.ventas.negocio.externos.Cobros;
import com.palco.ventas.negocio.externos.ResultadoDeCobro;

/**
 * Stateful: una instancia por compra en curso, que el contenedor mantiene viva
 * entre el pedido que abre la compra y el que la paga o la cancela. Guarda lo
 * que todavía no está en la base: cuál es su venta y a nombre de quién va cada
 * entrada (los titulares). Las entradas recién se crean cuando se aprueba el
 * pago; hasta entonces esos datos viven solo acá. Un stateless no serviría:
 * el pago podría caer en otra instancia del pool, que no conoce a los titulares.
 *
 * El ciclo de vida lo maneja el contenedor, no este código:
 * <ul>
 * <li>La instancia nace cuando {@link ComprasEnCurso} pide una referencia
 * nueva; {@code @PostConstruct} lo deja en el log.</li>
 * <li>Muere con {@code @Remove}, cuando la compra se paga o se cancela. Con
 * {@code retainIfException = true} sobrevive a un error de negocio: después de
 * un pago rechazado se puede pagar de nuevo.</li>
 * <li>O muere por {@code @StatefulTimeout}, si pasan los minutos de la reserva
 * sin que nadie la use: es una compra abandonada.</li>
 * <li>{@code @PreDestroy} deja la baja en el log y quita la referencia del
 * índice. No escribe en la base ni llama a otros servicios, porque la
 * especificación avisa que puede no ejecutarse. La reserva vence por la hora
 * guardada en la base ({@code vence_reserva}) y la libera un barrido aparte.</li>
 * </ul>
 *
 * {@code @AccessTimeout(0)}: si llegan dos llamadas a la vez sobre la misma
 * instancia, el contenedor rechaza la segunda al instante en lugar de hacerla
 * esperar. Es la última barrera: {@link ComprasEnCurso} frena antes esa
 * segunda llamada, porque en este servidor la llamada rechazada además hace
 * que el contenedor descarte la instancia.
 *
 * {@code passivationCapable = false}: la instancia no se guarda en disco. La
 * conversación dura como mucho lo que dura la reserva.
 *
 * Las transacciones son declarativas: cada operación corre en una transacción
 * que abre y cierra el contenedor ({@code REQUIRED}). Ante un error de negocio
 * ({@code ErroresDeNegocio}, que son excepciones de aplicación con rollback)
 * la transacción se deshace y la instancia sigue viva. Ante cualquier otra
 * excepción el contenedor además descarta la instancia. La transacción que
 * más importa es la del pago: está explicada en {@link #confirmarCompra}.
 */
@Stateful(passivationCapable = false)
@StatefulTimeout(value = ReglasDeNegocio.MINUTOS_RESERVA, unit = TimeUnit.MINUTES)
@AccessTimeout(0)
public class ServicioDeVentas implements IVentas {

    private static final Logger LOG = Logger.getLogger(ServicioDeVentas.class.getName());

    /** Largo de las columnas de id en todas las tablas. */
    private static final int LARGO_MAXIMO_ID = 40;

    /** Largo de la columna {@code entrada.titular_nombre}. */
    private static final int LARGO_MAXIMO_NOMBRE = 200;

    @Inject
    VentaRepository ventas;

    @Inject
    EntradaRepository entradas;

    @Inject
    ClienteDeEventos eventos;

    /** Quién cobra. Es una interfaz: hoy la implementa un simulador y después un cliente del servicio de Pagos. */
    @Inject
    Cobros cobros;

    /** Publica el mensaje {@code PAGO_CONFIRMADO}. No abre una transacción propia: usa la de {@link #confirmarCompra}. */
    @Inject
    PublicadorPagoConfirmado publicador;

    @Inject
    ComprasEnCurso compras;

    /** De dónde sale el "ahora". No es estado de la compra: es fijo, y los tests lo reemplazan por un reloj detenido. */
    Clock reloj = Clock.systemUTC();

    // ---- El estado de la conversación: lo que esta instancia recuerda entre una llamada y la siguiente ----

    /** La venta de esta compra; null hasta que {@link #iniciarCompra} termina bien. */
    private String ventaId;

    /** A nombre de quién va cada entrada, en el orden del pedido. Todavía no están en la base. */
    private List<TitularDto> titulares = List.of();

    @PostConstruct
    void alCrear() {
        LOG.info("Compra en curso: el contenedor creó la " + instancia() + ".");
    }

    @PreDestroy
    void alDestruir() {
        LOG.info("Compra en curso: el contenedor destruye la " + instancia()
                + (ventaId == null ? ", que no llegó a abrir una venta." : " de la venta " + ventaId + "."));
        if (ventaId == null) {
            return;
        }
        try {
            compras.olvidar(ventaId);
        } catch (RuntimeException e) {
            // Pasa al detener el servicio con compras abiertas: el índice ya no existe. No hay nada que limpiar.
            LOG.fine("No se pudo quitar la venta " + ventaId + " del índice de compras en curso: " + e);
        }
    }

    @Override
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public VentaDto iniciarCompra(String compradorId, NuevaVentaDto nueva, String claveIdempotencia) {
        if (ventaId != null) {
            // Cada instancia lleva una sola compra: para otra compra, ComprasEnCurso pide otra instancia.
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.OPERACION_EN_CURSO, "Esta compra ya está abierta.");
        }

        // 1. Se valida todo antes de tocar la base o llamar a Eventos.
        validar(nueva);

        // 2. Se guarda la cabecera de la venta con la clave de idempotencia. Si el comprador ya usó
        //    esa clave, falla acá (Conflicto "clave-repetida"), antes de reservar nada.
        LocalDateTime ahora = ahora();
        Venta venta = new Venta(nuevoId(), compradorId, nueva.eventoId(), claveIdempotencia, ahora,
                ahora.plusMinutes(ReglasDeNegocio.MINUTOS_RESERVA));
        ventas.guardar(venta);

        // 3. Se reserva sector por sector. La respuesta de Eventos trae el nombre y el precio.
        // 4. Con eso se completan los renglones; la venta recalcula subtotal, cargo de servicio y total.
        try {
            for (ItemPedidoDto item : nueva.items()) {
                Sector sector = eventos.reservarCupo(venta.getEventoId(), item.sectorId(), item.cantidad());
                venta.agregarItem(item.sectorId(), sector.nombre(), sector.precio(), item.cantidad());
            }
            ventas.actualizar(venta);
        } catch (RuntimeException e) {
            // Con Eventos no hay transacción: lo que ya reservó quedó confirmado allá y el rollback de
            // esta base no lo deshace. Se devuelve a mano y se relanza la excepción; el rollback borra
            // la cabecera de la venta.
            devolverCupo(venta);
            throw e;
        }

        // Recién ahora la instancia queda atada a la compra.
        this.ventaId = venta.getId();
        this.titulares = nueva.titulares().stream()
                .map(titular -> new TitularDto(titular.nombre().strip(), titular.dni()))
                .toList();
        LOG.info("Compra " + ventaId + " abierta por " + compradorId + ": total " + venta.getMontoTotal()
                + ", reserva hasta " + enUtc(venta.getVenceReserva()) + " (" + instancia() + ").");
        return aDto(venta);
    }

    /**
     * Cobra la compra y, si el cobro se aprueba, la confirma. Son tres pasos:
     * <ol>
     * <li>Validar, sin escribir nada: la compra tiene que seguir abierta y con
     * la reserva vigente.</li>
     * <li>Cobrar. Es una llamada hacia afuera: un rollback de esta base no
     * devuelve la plata. Por eso va antes de escribir: si el cobro se rechaza,
     * todavía no hay nada que deshacer.</li>
     * <li>Pasar la venta a {@code CONFIRMADA}, crear las entradas y publicar el
     * mensaje {@code PAGO_CONFIRMADO}.</li>
     * </ol>
     *
     * La transacción protege el tercer paso. El contenedor la abre al entrar
     * a este método ({@code REQUIRED}) y la cierra al salir. Los dos
     * repositorios y el publicador se suman a ella; el publicador ni siquiera
     * puede abrir una propia ({@code MANDATORY}). Si algo falla, el contenedor
     * deshace todo junto: no queda una venta confirmada sin entradas ni un
     * mensaje de una venta que no se confirmó. El mensaje sale del servidor
     * recién cuando la transacción termina bien.
     *
     * No es un commit en dos fases: la base confirma primero y el broker de
     * mensajes después. Si el servidor se cayera justo entre los dos, quedaría
     * una venta {@code CONFIRMADA} con sus entradas sin emitir y sin mensaje.
     *
     * Un cobro rechazado sale como {@code PagoRechazado}, que es un error de
     * negocio: la instancia sigue viva ({@code retainIfException}) y se puede
     * pagar de nuevo con otro medio de pago. Si el cobro se aprueba, el
     * {@code @Remove} termina la conversación.
     */
    @Override
    @Remove(retainIfException = true)
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public VentaDto confirmarCompra(NuevoPagoDto pago, String claveIdempotencia) {
        // 1. Validar. Solo lee.
        Venta venta = ventaParaPagar();

        // 2. Cobrar. El monto sale de la venta guardada, no del pedido de pago.
        ResultadoDeCobro cobro = cobros.cobrar(ventaId, venta.getMontoTotal(), pago.medioDePago(), pago.token(),
                claveIdempotencia);
        if (!cobro.aprobado()) {
            LOG.info("Compra " + ventaId + ": el cobro fue rechazado (" + cobro.motivo() + "). Sigue abierta ("
                    + instancia() + ").");
            throw new ErroresDeNegocio.PagoRechazado("El pago fue rechazado (" + cobro.motivo()
                    + "). Podés probar con otro medio de pago.");
        }

        // 3. Confirmar, crear las entradas y publicar: las tres cosas en la transacción de este método.
        LocalDateTime ahora = ahora();
        if (!ventas.confirmar(ventaId, cobro.pagoId(), ahora)) {
            // No debería pasar: antes de llamar acá, ComprasEnCurso anota en la venta la clave de este pago,
            // y con esa marca nadie la puede cancelar ni vencer. Si pasa, hay un cobro aprobado para una
            // venta que ya no está abierta. No es un error del comprador ni se arregla reintentando: se
            // deja en el log con el id del pago, para devolverlo, y sale como una falla inesperada.
            LOG.severe("El cobro " + cobro.pagoId() + " se aprobó pero la venta " + ventaId
                    + " ya no estaba EN_CHECKOUT: hay que devolver ese cobro.");
            throw new IllegalStateException("La venta " + ventaId + " se cobró pero ya no estaba abierta.");
        }
        List<Entrada> nuevas = entradasPara(venta);
        entradas.guardar(nuevas);
        // Una sola vez por venta: solo llega hasta acá quien pasó la venta a CONFIRMADA con su UPDATE.
        publicador.publicar(mensaje(venta, nuevas, ahora));

        LOG.info("Compra " + ventaId + " confirmada con el pago " + cobro.pagoId() + ". Entradas creadas: "
                + nuevas.size() + ". PAGO_CONFIRMADO publicado en la misma transacción (" + instancia() + ").");
        // La venta que se leyó al principio quedó vieja: se la vuelve a leer para responder.
        return aDto(ventas.buscarPorId(ventaId).orElseThrow());
    }

    @Override
    @Remove(retainIfException = true)
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public boolean cancelarCompra() {
        if (ventaId == null) {
            // La compra no llegó a abrirse: no hay nada que cancelar. Al volver, el @Remove libera la instancia.
            return false;
        }
        // Un solo UPDATE con la condición adentro (EN_CHECKOUT y sin pago en curso): solo sigue quien cambió la fila.
        if (ventas.cancelar(ventaId)) {
            LOG.info("Compra " + ventaId + " cancelada (" + instancia() + ").");
            return true;
        }
        // No cambió ninguna fila. Si la venta sigue EN_CHECKOUT es porque hay un pago en curso: se avisa
        // con un error de negocio, así la instancia sobrevive (retainIfException) y el pago puede terminar.
        boolean pagoEnCurso = ventas.buscarPorId(ventaId)
                .map(venta -> venta.getEstado() == EstadoVenta.EN_CHECKOUT)
                .orElse(false);
        if (pagoEnCurso) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.OPERACION_EN_CURSO,
                    "Hay un pago en curso para esta compra. Esperá a que termine.");
        }
        // Ya estaba cancelada o vencida: quien la cerró es quien devuelve el cupo.
        return false;
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Las reglas del pedido, en el orden en que se responden. No toca la base
     * ni llama a nadie: si algo está mal, no queda nada que deshacer.
     *
     * La forma del pedido ya la valida el recurso REST (400). Acá se repite
     * lo que no puede fallar: una cantidad negativa le pediría a Eventos que
     * libere cupo en lugar de reservarlo.
     */
    private static void validar(NuevaVentaDto nueva) {
        if (nueva == null || noEsUnId(nueva.eventoId())) {
            throw new ErroresDeNegocio.DatosInvalidos("eventoId", "Indicá el evento de la compra.");
        }
        if (nueva.items() == null || nueva.items().isEmpty()) {
            throw new ErroresDeNegocio.DatosInvalidos("items", "Elegí al menos una entrada.");
        }
        long entradas = 0;
        Set<String> sectores = new HashSet<>();
        for (ItemPedidoDto item : nueva.items()) {
            if (item == null || noEsUnId(item.sectorId()) || item.cantidad() < 1) {
                throw new ErroresDeNegocio.DatosInvalidos("items",
                        "Cada renglón necesita un sector y una cantidad de al menos 1.");
            }
            if (!sectores.add(item.sectorId())) {
                throw new ErroresDeNegocio.DatosInvalidos("items", "Cada sector puede aparecer una sola vez en la compra.");
            }
            entradas += item.cantidad();
        }
        if (entradas > ReglasDeNegocio.MAX_ENTRADAS_POR_ORDEN) {
            throw new ErroresDeNegocio.ReglaIncumplida(TiposDeProblema.MAXIMO_DE_ENTRADAS,
                    "Se pueden comprar hasta " + ReglasDeNegocio.MAX_ENTRADAS_POR_ORDEN + " entradas por compra.");
        }

        List<TitularDto> titulares = nueva.titulares() == null ? List.of() : nueva.titulares();
        if (titulares.size() != entradas) {
            throw new ErroresDeNegocio.ReglaIncumplida(TiposDeProblema.TITULARES_NO_COINCIDEN,
                    "Hace falta un titular por entrada: son " + entradas + " entradas y " + titulares.size()
                            + " titulares.");
        }
        Set<String> dnis = new HashSet<>();
        for (TitularDto titular : titulares) {
            if (titular == null || titular.nombre() == null || titular.nombre().isBlank()
                    || titular.nombre().strip().length() > LARGO_MAXIMO_NOMBRE) {
                throw new ErroresDeNegocio.DatosInvalidos("titulares", "Ingresá el nombre y apellido de cada titular.");
            }
            // La regla del DNI está en un solo lugar: es la misma del registro de usuarios y del mensaje.
            if (!ReglasDeNegocio.esDniValido(titular.dni())) {
                throw new ErroresDeNegocio.DatosInvalidos("titulares", "El DNI de cada titular debe tener 7 u 8 dígitos.");
            }
            if (!dnis.add(titular.dni())) {
                throw new ErroresDeNegocio.ReglaIncumplida(TiposDeProblema.DNI_REPETIDO,
                        "Cada entrada va a nombre de una persona distinta: el DNI " + titular.dni() + " está repetido.");
            }
        }
    }

    private static boolean noEsUnId(String id) {
        return id == null || id.isBlank() || id.length() > LARGO_MAXIMO_ID;
    }

    /**
     * Devuelve a Eventos el cupo de los renglones que la venta tiene cargados.
     * Mientras se arma la compra son exactamente los sectores que respondieron
     * bien: el renglón se agrega recién después de la respuesta. Un sector
     * cuya llamada no respondió no está, y no se devuelve, porque no se sabe
     * si la reserva se aplicó.
     */
    private void devolverCupo(Venta venta) {
        for (VentaItem item : venta.getItems()) {
            eventos.liberarCupo(venta.getId(), venta.getEventoId(), item.getSectorId(), item.getCantidad());
        }
    }

    /** La venta de esta compra, si todavía se puede pagar. Solo lee. */
    private Venta ventaParaPagar() {
        if (ventaId == null) {
            // Esta instancia no llegó a abrir una compra: no hay nada que pagar.
            throw reservaVencida();
        }
        Venta venta = ventas.buscarPorId(ventaId).orElseThrow(ServicioDeVentas::reservaVencida);
        exigirQueSePuedaPagar(venta, ahora());
        return venta;
    }

    /**
     * La regla de qué venta se puede pagar, escrita una sola vez: tiene que
     * estar {@code EN_CHECKOUT} y con la reserva vigente en el instante
     * {@code ahora}. Si no, lanza 409 {@code venta-ya-paga} o 409
     * {@code reserva-vencida} (venció o se canceló). La usa también
     * {@link ComprasEnCurso}, antes de anotar el intento de pago.
     */
    static void exigirQueSePuedaPagar(Venta venta, LocalDateTime ahora) {
        if (venta.estaPaga()) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.VENTA_YA_PAGA, "La compra ya está paga.");
        }
        if (venta.getEstado() != EstadoVenta.EN_CHECKOUT || venta.reservaVencida(ahora)) {
            throw reservaVencida();
        }
    }

    /**
     * Las entradas de la compra, una por titular. Los titulares se reparten
     * entre los sectores en el orden del pedido: si se pidieron 2 de Platea y
     * 1 de Popular, los dos primeros titulares van a Platea y el tercero a
     * Popular. Nacen {@code PENDIENTE_EMISION}: el código QR se lo pone
     * después la emisión, que es asincrónica.
     */
    private List<Entrada> entradasPara(Venta venta) {
        List<Entrada> nuevas = new ArrayList<>();
        for (VentaItem item : venta.getItems()) {
            for (int i = 0; i < item.getCantidad(); i++) {
                // El próximo titular sin entrada; "orden" es su posición en el pedido, empezando en 1.
                TitularDto titular = titulares.get(nuevas.size());
                nuevas.add(new Entrada(nuevoIdDeEntrada(), item, titular.nombre(), titular.dni(), nuevas.size() + 1));
            }
        }
        return nuevas;
    }

    /**
     * El mensaje {@code PAGO_CONFIRMADO} de una venta. Lleva el id de cada
     * entrada, por eso las entradas se crean acá y no al emitirlas.
     * {@code timestamp} es el instante de la confirmación, el mismo que se
     * guarda en la venta.
     */
    private static PagoConfirmadoMensaje mensaje(Venta venta, List<Entrada> entradas, LocalDateTime confirmada) {
        List<PagoConfirmadoMensaje.Entrada> delMensaje = entradas.stream()
                .map(e -> new PagoConfirmadoMensaje.Entrada(e.getId(), e.getSector(),
                        new PagoConfirmadoMensaje.Titular(e.getTitularNombre(), e.getTitularDni())))
                .toList();
        return new PagoConfirmadoMensaje(venta.getId(), venta.getCompradorId(), venta.getEventoId(), delMensaje,
                venta.getMontoTotal(), confirmada.toInstant(ZoneOffset.UTC));
    }

    /**
     * El error de una compra que ya no se puede pagar. Lo usan esta clase y
     * {@link ComprasEnCurso}: para el comprador es el mismo caso.
     */
    static ErroresDeNegocio.Conflicto reservaVencida() {
        return new ErroresDeNegocio.Conflicto(TiposDeProblema.RESERVA_VENCIDA,
                "La reserva de esta compra ya no está vigente. Empezá la compra de nuevo.");
    }

    private LocalDateTime ahora() {
        return ahoraEnUtc(reloj);
    }

    /** El instante de un reloj, en UTC y sin fracción de segundo: no depende de la zona horaria del servidor. */
    static LocalDateTime ahoraEnUtc(Clock reloj) {
        return LocalDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String nuevoId() {
        return "V-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String nuevoIdDeEntrada() {
        return "T-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Para reconocer en el log las líneas de una misma instancia. */
    private String instancia() {
        return "instancia " + Integer.toHexString(System.identityHashCode(this));
    }

    /** Las fechas se guardan en UTC y sin zona; hacia afuera salen como instante, con la Z al final. */
    static String enUtc(LocalDateTime fecha) {
        return fecha.toInstant(ZoneOffset.UTC).toString();
    }

    /**
     * La venta como sale por la API. Los enlaces ({@code _links}) van vacíos:
     * son rutas HTTP y los completa el recurso REST al responder.
     */
    static VentaDto aDto(Venta v) {
        List<ItemDto> items = v.getItems().stream()
                .map(i -> new ItemDto(i.getSectorId(), i.getSector(), i.getCantidad(), i.getPrecioUnitario()))
                .toList();
        List<EntradaDeVentaDto> entradas = v.getEntradas().stream()
                .map(e -> new EntradaDeVentaDto(e.getId(), e.getSector(),
                        new TitularDto(e.getTitularNombre(), e.getTitularDni()), e.getEstado().name(), Map.of()))
                .toList();
        return new VentaDto(v.getId(), v.getEstado().name(), v.getFacturaEstado().name(), v.getEventoId(), items,
                v.getSubtotal(), v.getCargoServicio(), v.getMontoTotal(), enUtc(v.getVenceReserva()), v.getPagoId(),
                entradas, Map.of());
    }

    /**
     * La entrada completa como sale por la API, también sin enlaces. La venta
     * y el evento salen de su venta, que tiene que venir cargada.
     */
    static EntradaDto aDto(Entrada e) {
        return new EntradaDto(e.getId(), e.getVenta().getId(), e.getVenta().getEventoId(), e.getSectorId(),
                e.getSector(), new TitularDto(e.getTitularNombre(), e.getTitularDni()), e.getPrecioPagado(),
                e.getEstado().name(), e.getCodigoQr(), e.getEmitida() == null ? null : enUtc(e.getEmitida()),
                Map.of());
    }
}
