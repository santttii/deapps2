package com.palco.facturacion.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.facturacion.Ejemplos;
import com.palco.facturacion.api.Dtos.FacturaDto;
import com.palco.facturacion.datos.FacturaRepositoryEnMemoria;
import com.palco.facturacion.dominio.Factura;
import com.palco.facturacion.negocio.externos.Arca.SolicitudDeCae;
import com.palco.facturacion.negocio.externos.ArcaEnMemoria;

/**
 * La emisión y la consulta de facturas, sin servidor ni base: el repositorio
 * es uno en memoria que rechaza una segunda factura de la misma venta, como la
 * base, y ARCA es uno en memoria que cuenta los pedidos.
 */
class ServicioDeFacturacionTest {

    private final FacturaRepositoryEnMemoria repositorio = new FacturaRepositoryEnMemoria();
    private final ArcaEnMemoria arca = new ArcaEnMemoria();
    private final ServicioDeFacturacion servicio = new ServicioDeFacturacion();

    @BeforeEach
    void armarElServicio() {
        servicio.facturas = repositorio;
        servicio.arca = arca;
        // El servidor corre en hora argentina: el reloj fijo comprueba que igual se guarda en UTC.
        servicio.reloj = Clock.fixed(Instant.parse("2026-10-17T01:30:05.700Z"), ZoneOffset.ofHours(-3));
    }

    // ---- Emitir ----

    @Test
    void unaCompraSeFacturaConElNumeroYElCaeQueDaArca() {
        FacturaDto factura = servicio.emitirFactura(Ejemplos.pagoConfirmado());

        assertEquals(Ejemplos.VENTA, factura.ventaId());
        assertEquals(Ejemplos.COMPRADOR, factura.compradorId());
        assertEquals("ev-1", factura.eventoId());
        assertEquals(6, factura.tipoComprobante());
        assertEquals(1, factura.puntoDeVenta());
        assertEquals(1, factura.numero());
        assertEquals("00001-00000001", factura.comprobante());
        assertEquals("75123456780001", factura.cae());
        assertEquals(60500, factura.importe());
        // El pago se confirmó el 17/10 a la 01:30 UTC: en Argentina era el 16/10.
        assertEquals("2026-10-16", factura.fecha());
        assertEquals("2026-10-26", factura.vencimientoCae());
        assertEquals("2026-10-17T01:30:05Z", factura.emitida());

        // A ARCA se le pidió una factura B del punto de venta 1, por el total y con el id de la venta.
        assertEquals(1, arca.pedidos.size());
        assertEquals(new SolicitudDeCae(6, 1, 60500, LocalDate.of(2026, 10, 16), Ejemplos.VENTA), arca.pedidos.get(0));

        Factura guardada = repositorio.buscarPorVenta(Ejemplos.VENTA).orElseThrow();
        assertEquals(LocalDateTime.of(2026, 10, 17, 1, 30, 5), guardada.getEmitida());
        assertEquals(1, repositorio.cantidad());
    }

    @Test
    void unMensajeRepetidoDevuelveLaMismaFacturaSinVolverAPedirleAArca() {
        FacturaDto primera = servicio.emitirFactura(Ejemplos.pagoConfirmado());
        FacturaDto repetida = servicio.emitirFactura(Ejemplos.pagoConfirmado());

        assertEquals(primera, repetida);
        assertEquals(1, arca.pedidos.size());
        assertEquals(1, repositorio.cantidad());
    }

    @Test
    void siOtraEntregaGuardoPrimeroValeLaQueYaEstaba() {
        // Las dos entregas pasaron la búsqueda a la vez; la otra guarda justo antes que esta.
        Factura deLaOtraEntrega = Ejemplos.factura(Ejemplos.VENTA, Ejemplos.COMPRADOR);
        repositorio.antesDeGuardar = () -> repositorio.guardar(deLaOtraEntrega);

        FacturaDto factura = servicio.emitirFactura(Ejemplos.pagoConfirmado());

        assertEquals(7, factura.numero());
        assertEquals("75123456789012", factura.cae());
        assertEquals(1, repositorio.cantidad());
    }

    @Test
    void ventasDistintasLlevanNumerosDistintos() {
        servicio.emitirFactura(Ejemplos.pagoConfirmado("V-1"));
        FacturaDto segunda = servicio.emitirFactura(Ejemplos.pagoConfirmado("V-2"));

        assertEquals(2, segunda.numero());
        assertEquals(2, repositorio.cantidad());
    }

    @Test
    void siArcaNoRespondeLaFallaSaleYNoSeGuardaNada() {
        arca.modo = ArcaEnMemoria.Modo.NO_RESPONDE;

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> servicio.emitirFactura(Ejemplos.pagoConfirmado()));
        assertEquals(0, repositorio.cantidad());

        // Cuando ARCA vuelve, la reentrega del mismo mensaje factura.
        arca.modo = ArcaEnMemoria.Modo.RESPONDE;
        assertEquals(1, servicio.emitirFactura(Ejemplos.pagoConfirmado()).numero());
        assertEquals(1, repositorio.cantidad());
    }

    @Test
    void siArcaRechazaElComprobanteLaFallaSaleYNoSeGuardaNada() {
        arca.modo = ArcaEnMemoria.Modo.RECHAZA;

        ErroresDeNegocio.ReglaIncumplida error = assertThrows(ErroresDeNegocio.ReglaIncumplida.class,
                () -> servicio.emitirFactura(Ejemplos.pagoConfirmado()));
        assertEquals(TiposDeProblema.COMPROBANTE_RECHAZADO, error.tipo());
        assertEquals(0, repositorio.cantidad());
    }

    // ---- Consultar ----

    @Test
    void elCompradorConsultaLaFacturaDeSuCompra() {
        servicio.emitirFactura(Ejemplos.pagoConfirmado());

        FacturaDto factura = servicio.consultarFacturaDelComprador(Ejemplos.COMPRADOR, Ejemplos.VENTA);

        assertEquals("00001-00000001", factura.comprobante());
    }

    @Test
    void unaFacturaAjenaRespondeLoMismoQueUnaQueNoExiste() {
        servicio.emitirFactura(Ejemplos.pagoConfirmado());

        ErroresDeNegocio.NoEncontrado ajena = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> servicio.consultarFacturaDelComprador("user-otro", Ejemplos.VENTA));
        ErroresDeNegocio.NoEncontrado inexistente = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> servicio.consultarFacturaDelComprador(Ejemplos.COMPRADOR, "V-no-existe"));

        assertEquals(inexistente.getMessage(), ajena.getMessage());
        assertEquals("no-encontrado", ajena.tipo());
    }

    @Test
    void otroServicioConsultaCualquierFacturaYUnaQueTodaviaNoEstaEs404() {
        servicio.emitirFactura(Ejemplos.pagoConfirmado());

        assertEquals(Ejemplos.COMPRADOR, servicio.consultarFactura(Ejemplos.VENTA).compradorId());
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> servicio.consultarFactura("V-sin-facturar"));
    }
}
