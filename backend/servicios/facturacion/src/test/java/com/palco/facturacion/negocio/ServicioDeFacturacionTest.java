package com.palco.facturacion.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.palco.facturacion.dominio.ComprobanteRechazado;
import com.palco.facturacion.dominio.Factura;
import com.palco.facturacion.negocio.externos.Arca.SolicitudDeCae;
import com.palco.facturacion.negocio.externos.ArcaEnMemoria;

/**
 * La emisión y la consulta de facturas, sin servidor ni base: el repositorio
 * es uno en memoria que rechaza una segunda factura (o un segundo rechazo) de
 * la misma venta, como la base, y ARCA es uno en memoria que cuenta los
 * pedidos.
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
    void siArcaRechazaElComprobanteSeAnotaElRechazoConElMotivoYNoUnaFactura() {
        arca.modo = ArcaEnMemoria.Modo.RECHAZA;

        ErroresDeNegocio.ReglaIncumplida error = assertThrows(ErroresDeNegocio.ReglaIncumplida.class,
                () -> servicio.emitirFactura(Ejemplos.pagoConfirmado()));
        assertEquals(TiposDeProblema.COMPROBANTE_RECHAZADO, error.tipo());
        assertEquals(0, repositorio.cantidad());

        // DEF-33: queda anotado lo que se le pidió a ARCA y por qué no lo autorizó.
        ComprobanteRechazado rechazo = repositorio.buscarRechazoPorVenta(Ejemplos.VENTA).orElseThrow();
        assertEquals(1, repositorio.cantidadDeRechazos());
        assertTrue(rechazo.getId().startsWith("R-"), rechazo.getId());
        assertEquals(Ejemplos.COMPRADOR, rechazo.getCompradorId());
        assertEquals("ev-1", rechazo.getEventoId());
        assertEquals(60500, rechazo.getImporte());
        assertEquals(LocalDate.of(2026, 10, 16), rechazo.getFecha());
        assertEquals("ARCA rechazó el comprobante: importe inválido", rechazo.getMotivo());
        assertEquals(LocalDateTime.of(2026, 10, 17, 1, 30, 5), rechazo.getRechazado());
    }

    @Test
    void unMensajeRepetidoDeUnaVentaRechazadaNoLeVuelveAPedirAArca() {
        arca.modo = ArcaEnMemoria.Modo.RECHAZA;
        assertThrows(ErroresDeNegocio.ReglaIncumplida.class, () -> servicio.emitirFactura(Ejemplos.pagoConfirmado()));

        // Aunque ARCA ahora contestara que sí: el pedido sería el mismo, y el rechazo ya está anotado.
        arca.modo = ArcaEnMemoria.Modo.RESPONDE;
        ErroresDeNegocio.ReglaIncumplida repetido = assertThrows(ErroresDeNegocio.ReglaIncumplida.class,
                () -> servicio.emitirFactura(Ejemplos.pagoConfirmado()));

        assertEquals(TiposDeProblema.COMPROBANTE_RECHAZADO, repetido.tipo());
        assertEquals("ARCA rechazó el comprobante: importe inválido", repetido.getMessage());
        assertEquals(1, arca.pedidos.size());
        assertEquals(1, repositorio.cantidadDeRechazos());
        assertEquals(0, repositorio.cantidad());
    }

    @Test
    void siOtraEntregaAnotoElRechazoPrimeroValeElQueYaEstaba() {
        // Lo anotó otra entrega de la misma venta entre la búsqueda y el pedido a ARCA.
        arca.modo = ArcaEnMemoria.Modo.RECHAZA;
        arca.antesDeResponder = () -> repositorio.guardarRechazo(Ejemplos.rechazo(Ejemplos.VENTA, Ejemplos.COMPRADOR));

        ErroresDeNegocio.ReglaIncumplida error = assertThrows(ErroresDeNegocio.ReglaIncumplida.class,
                () -> servicio.emitirFactura(Ejemplos.pagoConfirmado()));

        assertEquals(TiposDeProblema.COMPROBANTE_RECHAZADO, error.tipo());
        assertEquals(1, repositorio.cantidadDeRechazos());
        assertEquals("R-0a1b2c3d", repositorio.buscarRechazoPorVenta(Ejemplos.VENTA).orElseThrow().getId());
    }

    @Test
    void siArcaNoRespondeNoSeAnotaNingunRechazo() {
        arca.modo = ArcaEnMemoria.Modo.NO_RESPONDE;

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> servicio.emitirFactura(Ejemplos.pagoConfirmado()));

        // Otro intento puede andar: la consulta tiene que seguir diciendo "todavía no" (404).
        assertEquals(0, repositorio.cantidadDeRechazos());
        assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> servicio.consultarFacturaDelComprador(Ejemplos.COMPRADOR, Ejemplos.VENTA));
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

    @Test
    void elCompradorDeUnaVentaRechazadaRecibeUnConflictoComprobanteRechazadoYNoUn404() {
        arca.modo = ArcaEnMemoria.Modo.RECHAZA;
        assertThrows(ErroresDeNegocio.ReglaIncumplida.class, () -> servicio.emitirFactura(Ejemplos.pagoConfirmado()));

        ErroresDeNegocio.Conflicto rechazada = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.consultarFacturaDelComprador(Ejemplos.COMPRADOR, Ejemplos.VENTA));

        assertEquals(TiposDeProblema.COMPROBANTE_RECHAZADO, rechazada.tipo());
        assertEquals("ARCA rechazó la factura de esta compra y la estamos revisando. Las entradas valen igual.",
                rechazada.getMessage());
        // El motivo de ARCA queda para quien lo revisa; no se le muestra al comprador.
        assertFalse(rechazada.getMessage().contains("importe inválido"), rechazada.getMessage());
    }

    @Test
    void otroUsuarioNoSeEnteraDeQueUnaVentaAjenaFueRechazada() {
        repositorio.guardarRechazo(Ejemplos.rechazo(Ejemplos.VENTA, Ejemplos.COMPRADOR));

        ErroresDeNegocio.NoEncontrado ajena = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> servicio.consultarFacturaDelComprador("user-otro", Ejemplos.VENTA));
        ErroresDeNegocio.NoEncontrado inexistente = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> servicio.consultarFacturaDelComprador("user-otro", "V-no-existe"));

        assertEquals(inexistente.getMessage(), ajena.getMessage());
    }

    @Test
    void otroServicioTambienVeQueArcaRechazoLaFactura() {
        repositorio.guardarRechazo(Ejemplos.rechazo(Ejemplos.VENTA, Ejemplos.COMPRADOR));

        ErroresDeNegocio.Conflicto rechazada = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.consultarFactura(Ejemplos.VENTA));

        assertEquals(TiposDeProblema.COMPROBANTE_RECHAZADO, rechazada.tipo());
    }

    @Test
    void siLaVentaTieneFacturaYRechazoLaConsultaDevuelveLaFactura() {
        // Un rechazo que después se resolvió a mano: la factura manda.
        repositorio.guardarRechazo(Ejemplos.rechazo(Ejemplos.VENTA, Ejemplos.COMPRADOR));
        repositorio.guardar(Ejemplos.factura(Ejemplos.VENTA, Ejemplos.COMPRADOR));

        assertEquals("00001-00000007",
                servicio.consultarFacturaDelComprador(Ejemplos.COMPRADOR, Ejemplos.VENTA).comprobante());
        assertEquals("00001-00000007", servicio.consultarFactura(Ejemplos.VENTA).comprobante());
        // Y una de otro comprador sigue sin verse.
        assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> servicio.consultarFacturaDelComprador("user-otro", Ejemplos.VENTA));
    }
}
