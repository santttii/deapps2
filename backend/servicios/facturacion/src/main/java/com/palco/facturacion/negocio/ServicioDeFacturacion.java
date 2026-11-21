package com.palco.facturacion.negocio;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;

import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.Textos;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.facturacion.api.Dtos.FacturaDto;
import com.palco.facturacion.datos.FacturaRepository;
import com.palco.facturacion.dominio.ComprobanteRechazado;
import com.palco.facturacion.dominio.Factura;
import com.palco.facturacion.negocio.externos.Arca;
import com.palco.facturacion.negocio.externos.Arca.ComprobanteAutorizado;
import com.palco.facturacion.negocio.externos.Arca.SolicitudDeCae;

/**
 * Stateless: cada factura se arma solo con el mensaje que llega, y lo que
 * tiene que durar (la factura, con su número y CAE, o el rechazo de ARCA)
 * queda en la base. No hay conversación con nadie, así que cualquier
 * instancia del pool atiende cualquier mensaje, también la reentrega de uno
 * anterior.
 *
 * Sin transacción propia ({@code NOT_SUPPORTED}): entre buscar la factura y
 * guardarla se espera a ARCA, un sistema externo que puede tardar hasta el
 * tiempo de espera de lectura. Con una transacción abierta, esa espera
 * retendría una de las pocas conexiones a la base que comparten los
 * servicios. Cada método del repositorio abre y cierra la suya, corta. La
 * transacción del MDB (la del mensaje) queda suspendida mientras tanto y se
 * confirma después: si se deshiciera con la factura ya guardada, la reentrega
 * encuentra la factura y no hace nada.
 *
 * Idempotencia, en dos pasos. Primero se busca la factura de la venta: un
 * mensaje repetido termina ahí, sin llamar a ARCA. Si dos entregas pasan ese
 * paso a la vez, la restricción única de la base deja guardar una sola y la
 * otra devuelve la que quedó. El ARCA simulado, por su lado, devuelve el
 * mismo comprobante si se le pide otra vez la misma venta, así que repetir el
 * pedido tampoco saltea números.
 *
 * Rechazos de ARCA (DEF-33). Que ARCA conteste "R" no es una falla
 * pasajera: el pedido sale del mensaje, es el mismo en cada intento, y ARCA
 * lo rechazaría todas las veces (el simulado rechaza un tipo de comprobante
 * inexistente, un importe que no es mayor que 0, una fecha mal escrita). Por
 * eso el rechazo se anota en la base, con el motivo, y el consumidor del
 * tópico no devuelve el mensaje al broker: así no se le mandan a ARCA cinco
 * pedidos que no sirven y la consulta de la factura puede decir qué pasó en
 * lugar del 404 de "todavía no". Resolverlo es tarea de una persona, que
 * tiene en la fila lo que se pidió y por qué no se autorizó; el log lo marca
 * como SEVERE. Si después se emite la factura a mano, la consulta la
 * devuelve: la factura manda sobre el rechazo.
 *
 * Cuando ARCA no responde, en cambio, no se anota nada: otro intento puede
 * andar y el broker reintenta. Si agota los intentos, el mensaje queda en la
 * DLQ y, al reprocesarlo (sección 10.6 del documento único), factura solo.
 * Mientras tanto la consulta responde 404, como con una factura en camino; la
 * pantalla de la factura distingue la demora por el tiempo que pasó desde la
 * compra. No se marca "demorada" acá porque el servicio no sabe cuál es el
 * último intento: eso lo decide el broker con {@code max-delivery-attempts}
 * ({@code configurar.cli}), y copiar ese número sería tener la política en
 * dos lugares.
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class ServicioDeFacturacion implements IFacturacion {

    private static final Logger LOG = Logger.getLogger(ServicioDeFacturacion.class.getName());

    /**
     * Factura B (código 6 de ARCA): la que emite un responsable inscripto a un
     * consumidor final, que es quien compra entradas.
     */
    static final int FACTURA_B = 6;

    /** El único punto de venta electrónico de Palco. */
    static final int PUNTO_DE_VENTA = 1;

    /** La fecha del comprobante es la del día en Argentina, no la de UTC. */
    static final ZoneId ZONA_FISCAL = ZoneId.of("America/Argentina/Buenos_Aires");

    /**
     * Lo que lee quien consulta la factura de una venta rechazada. No incluye
     * el motivo de ARCA: es un texto de un sistema externo con el que el
     * comprador no puede hacer nada; queda en la base y en el log para quien
     * lo revisa.
     */
    static final String MENSAJE_RECHAZADA =
            "ARCA rechazó la factura de esta compra y la estamos revisando. Las entradas valen igual.";

    @Inject
    FacturaRepository facturas;

    /** El que armó {@code FabricaDeArca}; acá no se sabe que es SOAP. */
    @Inject
    Arca arca;

    /** De dónde sale el "ahora". No es estado: es fijo, y los tests lo reemplazan por un reloj detenido. */
    @SuppressWarnings("java:S8983") // Se fija al crear el bean, no cambia entre llamadas y los tests lo reemplazan.
    Clock reloj = Clock.systemUTC();

    @Override
    public FacturaDto emitirFactura(PagoConfirmadoMensaje compra) {
        // 1. Si la venta ya tiene factura, el mensaje es una repetición: no se le pide nada a ARCA.
        Optional<Factura> anterior = facturas.buscarPorVenta(compra.ventaId());
        if (anterior.isPresent()) {
            LOG.info("La venta " + Textos.enUnaLinea(compra.ventaId())
                    + " ya tenía factura (mensaje repetido): no se factura otra vez.");
            return aDto(anterior.get());
        }

        // 2. Si ARCA ya la rechazó, también: pedirla otra vez sería mandarle el mismo pedido.
        Optional<ComprobanteRechazado> rechazoAnterior = facturas.buscarRechazoPorVenta(compra.ventaId());
        if (rechazoAnterior.isPresent()) {
            LOG.info("La venta " + Textos.enUnaLinea(compra.ventaId())
                    + " ya tenía un rechazo de ARCA anotado (mensaje repetido): no se le vuelve a pedir.");
            throw new ErroresDeNegocio.ReglaIncumplida(TiposDeProblema.COMPROBANTE_RECHAZADO,
                    rechazoAnterior.get().getMotivo());
        }

        // 3. Se pide el CAE, sin ninguna transacción abierta. Si ARCA no responde, la excepción
        //    sale por acá y no queda nada guardado. Si rechaza, se anota el rechazo y sale la misma.
        LocalDate fecha = LocalDate.ofInstant(compra.timestamp(), ZONA_FISCAL);
        ComprobanteAutorizado comprobante;
        try {
            comprobante = arca.autorizar(
                    new SolicitudDeCae(FACTURA_B, PUNTO_DE_VENTA, compra.montoTotal(), fecha, compra.ventaId()));
        } catch (ErroresDeNegocio.ReglaIncumplida rechazo) {
            anotarRechazo(compra, fecha, rechazo.getMessage());
            // El rechazo ya quedó guardado: la transacción del repositorio se confirmó al volver.
            // Esta excepción no la deshace, porque este bean no tiene transacción (NOT_SUPPORTED).
            throw rechazo;
        }

        // 4. Se guarda la factura.
        Factura factura = new Factura(nuevoId("F-"), compra.ventaId(), compra.compradorId(), compra.eventoId(),
                FACTURA_B, PUNTO_DE_VENTA, comprobante.numero(), comprobante.cae(), comprobante.vencimientoCae(),
                compra.montoTotal(), fecha, ahora());
        try {
            facturas.guardar(factura);
        } catch (ErroresDeNegocio.Conflicto choque) {
            // Dos entregas de la misma venta pasaron el paso 1 a la vez y la base dejó guardar una sola.
            Factura ganadora = facturas.buscarPorVenta(compra.ventaId()).orElseThrow(() -> choque);
            LOG.info("Dos entregas simultáneas de la venta " + Textos.enUnaLinea(compra.ventaId())
                    + ": vale la factura que ya estaba guardada.");
            return aDto(ganadora);
        }
        LOG.info("Factura " + numeroImpreso(factura) + " de la venta " + Textos.enUnaLinea(factura.getVentaId())
                + ", CAE " + factura.getCae() + ".");
        return aDto(factura);
    }

    /**
     * Anota el rechazo con lo que se le pidió a ARCA y su motivo. Si otra
     * entrega de la misma venta lo anotó primero, vale el que ya estaba.
     */
    private void anotarRechazo(PagoConfirmadoMensaje compra, LocalDate fecha, String motivo) {
        String enUnRenglon = Textos.enUnaLinea(motivo);
        try {
            facturas.guardarRechazo(new ComprobanteRechazado(nuevoId("R-"), compra.ventaId(), compra.compradorId(),
                    compra.eventoId(), compra.montoTotal(), fecha, enUnRenglon, ahora()));
        } catch (ErroresDeNegocio.Conflicto yaAnotado) {
            LOG.info("El rechazo de ARCA de la venta " + Textos.enUnaLinea(compra.ventaId())
                    + " ya estaba anotado: vale el que estaba.");
            return;
        }
        // SEVERE: nadie lo va a resolver solo. Es la marca que se busca en el log para revisarlo.
        // El motivo ya empieza con "ARCA rechazó el comprobante" (lo escribe ArcaSoap).
        LOG.severe("Venta " + Textos.enUnaLinea(compra.ventaId()) + " sin factura. " + enUnRenglon
                + " Quedó anotado en facturacion.comprobante_rechazado para revisarlo a mano;"
                + " el mensaje no se reintenta.");
    }

    @Override
    public FacturaDto consultarFactura(String ventaId) {
        // Otro servicio puede ver la factura de cualquier comprador.
        return consultar(ventaId, comprador -> true);
    }

    @Override
    public FacturaDto consultarFacturaDelComprador(String compradorId, String ventaId) {
        return consultar(ventaId, compradorId::equals);
    }

    /**
     * La factura de la venta, si quien consulta puede ver las compras de ese
     * comprador. Si no hay factura pero ARCA la rechazó, lo dice con un
     * {@code Conflicto} (409) de tipo {@code comprobante-rechazado}: así no se
     * confunde con una factura que todavía está en camino (404). Lo que no
     * puede ver responde lo mismo que lo que no existe.
     */
    private FacturaDto consultar(String ventaId, Predicate<String> puedeVerAlComprador) {
        Optional<Factura> factura = facturas.buscarPorVenta(ventaId);
        if (factura.isPresent()) {
            return factura.filter(f -> puedeVerAlComprador.test(f.getCompradorId()))
                    .map(ServicioDeFacturacion::aDto)
                    .orElseThrow(ServicioDeFacturacion::sinFactura);
        }
        boolean rechazada = facturas.buscarRechazoPorVenta(ventaId)
                .filter(r -> puedeVerAlComprador.test(r.getCompradorId()))
                .isPresent();
        if (rechazada) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.COMPROBANTE_RECHAZADO, MENSAJE_RECHAZADA);
        }
        throw sinFactura();
    }

    /** El mismo texto para "no existe", "todavía no" y "es de otro": no revela nada de ventas ajenas. */
    private static ErroresDeNegocio.NoEncontrado sinFactura() {
        return new ErroresDeNegocio.NoEncontrado("No hay una factura de esa venta.");
    }

    /** El instante del reloj, en UTC y sin fracción de segundo. */
    private LocalDateTime ahora() {
        return LocalDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    /** El prefijo más 8 caracteres de un UUID: "F-" para una factura, "R-" para un rechazo. */
    private static String nuevoId(String prefijo) {
        return prefijo + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Punto de venta y número como se imprimen en el comprobante: {@code 00001-00000001}. */
    static String numeroImpreso(Factura f) {
        return String.format("%05d-%08d", f.getPuntoDeVenta(), f.getNumero());
    }

    static FacturaDto aDto(Factura f) {
        return new FacturaDto(f.getVentaId(), f.getCompradorId(), f.getEventoId(), f.getTipoComprobante(),
                f.getPuntoDeVenta(), f.getNumero(), numeroImpreso(f), f.getCae(), f.getVencimientoCae().toString(),
                f.getImporte(), f.getFecha().toString(), f.getEmitida().toInstant(ZoneOffset.UTC).toString());
    }
}
