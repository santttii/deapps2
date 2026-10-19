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
import java.util.function.BooleanSupplier;
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
import com.palco.common.Textos;
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
import com.palco.ventas.dominio.TitularDeVenta;
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
 * pago; hasta que la compra se manda a cobrar, esos datos viven solo acá. Justo
 * antes del cobro se deja una copia en la base, para que una compra con un
 * cobro hecho pueda terminarse aunque esta instancia se pierda (lo hace
 * {@link ConciliacionDePagos}). Guarda además lo que hace
 * falta para cobrar sin leer la base: el total y hasta cuándo vale la reserva.
 * Un stateless no serviría: el pago podría caer en otra instancia del pool,
 * que no conoce a los titulares.
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
 * Las transacciones son declarativas: las abre y las cierra el contenedor,
 * según la anotación de cada operación. Pagar y cancelar corren en una
 * transacción ({@code REQUIRED}). Abrir la compra no ({@code NOT_SUPPORTED}):
 * lo que hace es llamar a Eventos, y su única escritura es guardar la venta,
 * que corre en la transacción corta del repositorio. Ante un error de negocio
 * ({@code ErroresDeNegocio}, que son excepciones de aplicación con rollback)
 * la transacción se deshace y la instancia sigue viva. Ante cualquier otra
 * excepción el contenedor además descarta la instancia. La transacción que
 * más importa es la del pago: está explicada en {@link #confirmarCompra}.
 *
 * Una regla vale para todas las operaciones: mientras se espera a otro
 * servicio (Eventos al reservar, Pagos al cobrar) no se retiene una conexión
 * de la base. Todos los servicios del servidor comparten un solo pool de 10
 * conexiones, y el servicio al que se llama necesita una para contestar. Si
 * cada compra esperara con una conexión tomada, diez compras a la vez se
 * quedarían con todo el pool y nadie podría contestarles. Por eso las
 * llamadas a otros servicios van primero y la base se toca después.
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

    /** Quién cobra. Es una interfaz: acá no se sabe si es el servicio de Pagos o el simulador; lo elige FabricaDeCobros. */
    @Inject
    Cobros cobros;

    /** Publica el mensaje {@code PAGO_CONFIRMADO}. No abre una transacción propia: usa la de {@link #confirmarCompra}. */
    @Inject
    PublicadorPagoConfirmado publicador;

    @Inject
    ComprasEnCurso compras;

    /** De dónde sale el "ahora". No es estado de la compra: es fijo, y los tests lo reemplazan por un reloj detenido. */
    Clock reloj = Clock.systemUTC();

    /**
     * HERRAMIENTA DE PRUEBA, solo para desarrollo. Con la propiedad de sistema
     * {@code palco.ventas.fallar-despues-del-cobro=true}, {@link #confirmarCompra}
     * lanza una falla inesperada después de que el cobro se aprobó y de
     * confirmar la venta, crear las entradas y publicar el mensaje. Sirve para
     * ver, de forma repetible, qué pasa con una falla a mitad de la compra: el
     * contenedor deshace las tres cosas, el pago queda guardado en Pagos y la
     * venta la termina {@link ConciliacionDePagos}.
     *
     * Se lee en cada pago, así que se prende y se apaga con el servidor en
     * marcha, sin redesplegar ({@code /system-property=...:add} y
     * {@code :remove} en jboss-cli). Sin la propiedad, o con cualquier valor
     * que no sea {@code true}, no hace nada.
     */
    static final String PROPIEDAD_FALLA_DE_PRUEBA = "palco.ventas.fallar-despues-del-cobro";

    /** Si la herramienta de prueba está prendida. Es un campo para que el test la prenda sin tocar las propiedades de la JVM. */
    BooleanSupplier fallarDespuesDelCobro = () -> Boolean.getBoolean(PROPIEDAD_FALLA_DE_PRUEBA);

    // ---- El estado de la conversación: lo que esta instancia recuerda entre una llamada y la siguiente ----

    /** La venta de esta compra; null hasta que {@link #iniciarCompra} termina bien. */
    private String ventaId;

    /** A nombre de quién va cada entrada, en el orden del pedido. Todavía no están en la base. */
    private List<TitularDto> titulares = List.of();

    /** Lo que se cobra. Se calculó al abrir la compra, con los precios que devolvió Eventos, y no cambia más. */
    private long montoTotal;

    /** Hasta cuándo vale la reserva, en UTC. Es el mismo valor que quedó guardado en la venta. */
    private LocalDateTime venceReserva;

    /**
     * La clave del último intento de pago que esta instancia mandó a cobrar;
     * null si todavía no mandó ninguno. Sirve para reconocer un reintento sin
     * leer la base: ver {@link #exigirReservaVigente}.
     */
    private String claveDelUltimoCobro;

    /** Si los titulares ya quedaron copiados en la base, para no pedirlo de nuevo en cada intento de pago. */
    private boolean titularesRespaldados;

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

    /**
     * Abre la compra en tres pasos: validar, reservar el cupo y guardar la
     * venta. La venta se guarda al final, en una sola escritura, y no antes
     * de reservar: así ninguna llamada a Eventos espera con una conexión de la
     * base tomada (la regla está en el comentario de la clase).
     *
     * No lleva transacción propia ({@code NOT_SUPPORTED}). Con Eventos no hay
     * nada que una transacción de esta base pueda deshacer, y la única
     * escritura es {@code ventas.guardar}, que corre en la transacción corta
     * del repositorio: la venta y sus renglones se guardan juntos o no se
     * guarda nada. Si el guardado falla, esa transacción ya terminó cuando
     * acá se devuelve el cupo.
     */
    @Override
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public VentaDto iniciarCompra(String compradorId, NuevaVentaDto nueva, String claveIdempotencia) {
        if (ventaId != null) {
            // Cada instancia lleva una sola compra: para otra compra, ComprasEnCurso pide otra instancia.
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.OPERACION_EN_CURSO, "Esta compra ya está abierta.");
        }

        // 1. Se valida todo antes de tocar la base o llamar a Eventos.
        validar(nueva);

        // La venta se arma en memoria: todavía no se guarda nada.
        LocalDateTime ahora = ahora();
        Venta venta = new Venta(nuevoId(), compradorId, nueva.eventoId(), claveIdempotencia, ahora,
                ahora.plusMinutes(ReglasDeNegocio.MINUTOS_RESERVA));

        try {
            // 2. Se reserva sector por sector. La respuesta de Eventos trae el nombre y el precio: con
            //    eso se suma el renglón, y la venta recalcula subtotal, cargo de servicio y total.
            for (ItemPedidoDto item : nueva.items()) {
                Sector sector = eventos.reservarCupo(venta.getEventoId(), item.sectorId(), item.cantidad());
                venta.agregarItem(item.sectorId(), sector.nombre(), sector.precio(), item.cantidad());
            }
            // 3. Se guarda la venta completa, con sus renglones. Si otro pedido con la misma clave de
            //    idempotencia llegó a la vez y guardó primero, falla acá (Conflicto "clave-repetida").
            ventas.guardar(venta);
        } catch (RuntimeException e) {
            // Con Eventos no hay transacción: lo que ya se reservó quedó confirmado allá. Se devuelve
            // a mano y se relanza la excepción. De esta venta no quedó nada guardado.
            devolverCupo(venta);
            throw e;
        }

        // Recién ahora la instancia queda atada a la compra.
        this.ventaId = venta.getId();
        this.titulares = nueva.titulares().stream()
                .map(titular -> new TitularDto(titular.nombre().strip(), titular.dni()))
                .toList();
        this.montoTotal = venta.getMontoTotal();
        this.venceReserva = venta.getVenceReserva();
        LOG.info("Compra " + ventaId + " abierta por " + compradorId + ": total " + montoTotal
                + ", reserva hasta " + enUtc(venceReserva) + " (" + instancia() + ").");
        return aDto(venta);
    }

    /**
     * Cobra la compra y, si el cobro se aprueba, la confirma. Son tres pasos:
     * <ol>
     * <li>Validar, sin tocar la base: esta instancia tiene que tener una
     * compra abierta y la reserva vigente (salvo que el pedido repita un cobro
     * que quedó sin resultado). Lo sabe por su propio estado. Que
     * la venta siga {@code EN_CHECKOUT} ya lo aseguró {@link ComprasEnCurso}
     * antes de llamar acá, al anotar en la venta el intento de pago.</li>
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
     * Durante los dos primeros pasos la transacción ya está abierta, pero
     * todavía no tomó ninguna conexión de la base: la toma con la primera
     * consulta, que está en el tercer paso. Por eso el total y el vencimiento
     * salen del estado de la instancia y no de una lectura: así el cobro no
     * espera a Pagos con una conexión retenida. La copia de los titulares que
     * se escribe antes de cobrar no cambia esto: corre en una transacción
     * aparte ({@code REQUIRES_NEW}), que devuelve su conexión al terminar, y
     * por eso tampoco se deshace si después falla esta.
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
        // 1. Validar. Mira solo el estado de la instancia: no lee la base.
        exigirReservaVigente(claveIdempotencia);

        // 2. Cobrar. El monto es el total que se calculó al abrir la compra, no viene en el pedido de pago.
        //    Antes se copian los titulares a la base, en una transacción propia que termina acá: desde el
        //    cobro la compra tiene que poder terminarse sin esta instancia (ver ConciliacionDePagos).
        //    Y se recuerda la clave: si el cobro queda sin resultado, repetirla vale aunque venza la reserva.
        respaldarTitulares();
        claveDelUltimoCobro = claveIdempotencia;
        ResultadoDeCobro cobro = cobros.cobrar(ventaId, montoTotal, pago.medioDePago(), pago.token(),
                claveIdempotencia);
        if (!cobro.aprobado()) {
            // El motivo lo escribió quien cobra (otro servicio): al log va en un solo renglón.
            LOG.info("Compra " + ventaId + ": el cobro fue rechazado (" + Textos.enUnaLinea(cobro.motivo())
                    + "). Sigue abierta (" + instancia() + ").");
            throw new ErroresDeNegocio.PagoRechazado(detalleDelRechazo(cobro.motivo()));
        }

        // 3. Confirmar, crear las entradas y publicar: las tres cosas en la transacción de este método.
        //    Recién acá se toca la base. La venta se lee para conocer sus renglones.
        Venta venta = ventas.buscarPorId(ventaId).orElseThrow();
        LocalDateTime ahora = ahora();
        if (!ventas.confirmar(ventaId, cobro.pagoId(), ahora)) {
            // No debería pasar: antes de llamar acá, ComprasEnCurso anota en la venta la clave de este pago,
            // y con esa marca nadie la puede cancelar ni vencer. Si pasa, hay un cobro aprobado para una
            // venta que ya no está abierta. No es un error del comprador ni se arregla reintentando: se
            // deja en el log con el id del pago, para devolverlo, y sale como una falla inesperada.
            LOG.severe("El cobro " + Textos.enUnaLinea(cobro.pagoId()) + " se aprobó pero la venta " + ventaId
                    + " ya no estaba EN_CHECKOUT: hay que devolver ese cobro.");
            throw new IllegalStateException("La venta " + ventaId + " se cobró pero ya no estaba abierta.");
        }
        List<Entrada> nuevas = entradasPara(venta, titulares);
        entradas.guardar(nuevas);
        // Una sola vez por venta: solo llega hasta acá quien pasó la venta a CONFIRMADA con su UPDATE.
        publicador.publicar(mensaje(venta, nuevas, ahora));

        if (fallarDespuesDelCobro.getAsBoolean()) {
            // Herramienta de prueba (ver PROPIEDAD_FALLA_DE_PRUEBA). No es un error de negocio: el contenedor
            // deshace la transacción entera (la venta vuelve a EN_CHECKOUT, las entradas no quedan y el
            // mensaje no sale) y descarta esta instancia. El pago ya quedó guardado en Pagos.
            LOG.warning("Compra " + ventaId + ": " + PROPIEDAD_FALLA_DE_PRUEBA + "=true. Se lanza una falla de prueba"
                    + " después del cobro aprobado " + Textos.enUnaLinea(cobro.pagoId())
                    + ": se deshace la confirmación y la venta la termina la conciliación.");
            throw new IllegalStateException("Falla de prueba después del cobro de la venta " + ventaId + ".");
        }

        LOG.info("Compra " + ventaId + " confirmada con el pago " + Textos.enUnaLinea(cobro.pagoId())
                + ". Entradas creadas: "
                + nuevas.size() + ". PAGO_CONFIRMADO publicado en la misma transacción (" + instancia() + ").");
        // La venta que se leyó antes de confirmar quedó vieja: se la vuelve a leer para responder.
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
            // El nombre viaja en el mensaje PAGO_CONFIRMADO y quien lo consume lo escribe en su log:
            // con un salto de línea adentro, un comprador podría hacer aparecer ahí una línea falsa.
            if (tieneCaracteresDeControl(titular.nombre().strip())) {
                throw new ErroresDeNegocio.DatosInvalidos("titulares",
                        "El nombre de cada titular va en una sola línea, sin saltos de línea ni caracteres de control.");
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

    /** Los ids del pedido (evento y sector) también salen en el log, así que tampoco llevan caracteres de control. */
    private static boolean noEsUnId(String id) {
        return id == null || id.isBlank() || id.length() > LARGO_MAXIMO_ID || tieneCaracteresDeControl(id);
    }

    /**
     * Dice si el texto tiene un salto de línea, un tabulador u otro carácter
     * de control, o uno de los dos separadores de línea de Unicode. Qué
     * cuenta como carácter de control está en un solo lugar,
     * {@code Textos.esDeControl}: es la misma regla con la que los servicios
     * limpian lo que escriben en el log.
     */
    private static boolean tieneCaracteresDeControl(String texto) {
        return texto.chars().anyMatch(Textos::esDeControl);
    }

    /**
     * Devuelve a Eventos el cupo de los renglones que la venta tiene cargados.
     * Mientras se arma la compra son exactamente los sectores que respondieron
     * bien: el renglón se agrega recién después de la respuesta. Un sector
     * cuya llamada no respondió no está, y no se devuelve, porque no se sabe
     * si la reserva se aplicó. Cuando se llama, de esta venta no hay nada
     * guardado: su id sirve solo para reconocer el aviso en el log.
     */
    private void devolverCupo(Venta venta) {
        for (VentaItem item : venta.getItems()) {
            eventos.liberarCupo(venta.getId(), venta.getEventoId(), item.getSectorId(), item.getCantidad());
        }
    }

    /**
     * Comprueba que esta instancia tenga una compra abierta con la reserva
     * vigente, mirando solo su estado: no lee la base. Es la misma condición
     * de {@code Venta.reservaVencida}.
     *
     * La reserva vigente no se exige si la clave es la del último intento que
     * esta instancia mandó a cobrar. Ese pedido repite un cobro del que no se
     * supo el resultado, y {@link ComprasEnCurso} solo lo deja llegar hasta
     * acá si la venta sigue marcada con esa clave: con la marca nadie pudo
     * cancelarla ni vencerla, así que su cupo sigue reservado. Si se exigiera,
     * un cobro que Pagos sí hizo quedaría sin venta apenas vence la reserva.
     */
    private void exigirReservaVigente(String claveIdempotencia) {
        // Sin venta, esta instancia no llegó a abrir una compra: no hay nada que pagar.
        if (ventaId == null) {
            throw reservaVencida();
        }
        boolean reintento = claveIdempotencia.equals(claveDelUltimoCobro);
        if (!reintento && !ahora().isBefore(venceReserva)) {
            throw reservaVencida();
        }
    }

    /**
     * La regla de qué venta se puede pagar, escrita una sola vez: tiene que
     * estar {@code EN_CHECKOUT} y con la reserva vigente en el instante
     * {@code ahora}. Si no, lanza 409 {@code venta-ya-paga} o 409
     * {@code reserva-vencida} (venció o se canceló). La usa
     * {@link ComprasEnCurso} con la venta leída de la base, antes de anotar
     * el intento de pago.
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
     * Copia los titulares a la base antes del primer cobro de esta instancia
     * ({@code VentaRepository.respaldarTitulares}, en una transacción propia
     * que ya terminó al volver). Hasta acá vivían solo en la instancia; desde
     * el cobro puede haber plata cobrada, y si la instancia se pierde la
     * conciliación necesita saber a nombre de quién van las entradas.
     *
     * Un error al copiar sale antes del cobro: no se cobró nada.
     */
    private void respaldarTitulares() {
        if (titularesRespaldados) {
            return;
        }
        List<TitularDeVenta> copia = new ArrayList<>();
        for (TitularDto titular : titulares) {
            copia.add(new TitularDeVenta(ventaId, copia.size() + 1, titular.nombre(), titular.dni()));
        }
        ventas.respaldarTitulares(ventaId, copia);
        titularesRespaldados = true;
    }

    /**
     * Las entradas de la compra, una por titular. Los titulares se reparten
     * entre los sectores en el orden del pedido: si se pidieron 2 de Platea y
     * 1 de Popular, los dos primeros titulares van a Platea y el tercero a
     * Popular. Nacen {@code PENDIENTE_EMISION}: el código QR se lo pone
     * después la emisión, que es asincrónica.
     *
     * Es estático porque la usan las dos formas de confirmar: esta instancia,
     * con los titulares que guarda, y {@link ConfirmacionConciliada}, con la
     * copia que quedó en la base. Así el reparto está escrito una sola vez.
     */
    static List<Entrada> entradasPara(Venta venta, List<TitularDto> titulares) {
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
     * guarda en la venta. Lo usa también {@link ConfirmacionConciliada}.
     */
    static PagoConfirmadoMensaje mensaje(Venta venta, List<Entrada> entradas, LocalDateTime confirmada) {
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

    /**
     * El texto del 402. Si Pagos no aceptó la clave del intento
     * ({@code clave_reutilizada}), lo que hay que cambiar es la clave y no la
     * tarjeta: el texto no menciona otra venta ni otro medio de pago.
     */
    static String detalleDelRechazo(String motivo) {
        if (ResultadoDeCobro.MOTIVO_CLAVE_REUTILIZADA.equals(motivo)) {
            return "El pago no se procesó porque la clave de idempotencia de este intento no es válida."
                    + " Reintentá con una Idempotency-Key nueva.";
        }
        return "El pago fue rechazado (" + motivo + "). Podés probar con otro medio de pago.";
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
                .map(e -> new EntradaDeVentaDto(e.getId(), e.getSectorId(), e.getSector(),
                        new TitularDto(e.getTitularNombre(), e.getTitularDni()), e.getPrecioPagado(),
                        e.getEstado().name(), e.getCodigoQr(), Map.of()))
                .toList();
        return new VentaDto(v.getId(), v.getEstado().name(), v.getFacturaEstado().name(), v.getEventoId(), items,
                v.getSubtotal(), v.getCargoServicio(), v.getMontoTotal(), enUtc(v.getCreada()),
                enUtc(v.getVenceReserva()), v.getPagoId(), entradas, Map.of());
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
