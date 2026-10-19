package com.palco.facturacion.negocio;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.facturacion.api.Dtos.FacturaDto;
import com.palco.facturacion.datos.FacturaRepository;
import com.palco.facturacion.dominio.Factura;
import com.palco.facturacion.negocio.externos.Arca;
import com.palco.facturacion.negocio.externos.Arca.ComprobanteAutorizado;
import com.palco.facturacion.negocio.externos.Arca.SolicitudDeCae;

/**
 * Stateless: cada factura se arma solo con el mensaje que llega, y lo que
 * tiene que durar (la factura, con su número y CAE) queda en la base. No hay
 * conversación con nadie, así que cualquier instancia del pool atiende
 * cualquier mensaje, también la reentrega de uno anterior.
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

    @Inject
    FacturaRepository facturas;

    /** El que armó {@code FabricaDeArca}; acá no se sabe que es SOAP. */
    @Inject
    Arca arca;

    /** De dónde sale el "ahora". No es estado: es fijo, y los tests lo reemplazan por un reloj detenido. */
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

        // 2. Se pide el CAE, sin ninguna transacción abierta. Si ARCA no responde o rechaza,
        //    la excepción sale por acá y no queda nada guardado.
        LocalDate fecha = LocalDate.ofInstant(compra.timestamp(), ZONA_FISCAL);
        ComprobanteAutorizado comprobante = arca.autorizar(
                new SolicitudDeCae(FACTURA_B, PUNTO_DE_VENTA, compra.montoTotal(), fecha, compra.ventaId()));

        // 3. Se guarda la factura.
        Factura factura = new Factura(nuevoId(), compra.ventaId(), compra.compradorId(), compra.eventoId(),
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

    @Override
    public FacturaDto consultarFactura(String ventaId) {
        return facturas.buscarPorVenta(ventaId)
                .map(ServicioDeFacturacion::aDto)
                .orElseThrow(ServicioDeFacturacion::sinFactura);
    }

    @Override
    public FacturaDto consultarFacturaDelComprador(String compradorId, String ventaId) {
        return facturas.buscarPorVenta(ventaId)
                .filter(factura -> factura.esDe(compradorId))
                .map(ServicioDeFacturacion::aDto)
                .orElseThrow(ServicioDeFacturacion::sinFactura);
    }

    /** El mismo texto para "no existe", "todavía no" y "es de otro": no revela nada de ventas ajenas. */
    private static ErroresDeNegocio.NoEncontrado sinFactura() {
        return new ErroresDeNegocio.NoEncontrado("No hay una factura de esa venta.");
    }

    /** El instante del reloj, en UTC y sin fracción de segundo. */
    private LocalDateTime ahora() {
        return LocalDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String nuevoId() {
        return "F-" + UUID.randomUUID().toString().substring(0, 8);
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
