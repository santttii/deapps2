package com.palco.ventas.negocio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import jakarta.ejb.Remove;
import jakarta.ejb.StatefulTimeout;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.ReglasDeNegocio;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
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
import com.palco.ventas.dominio.EstadoEntrada;
import com.palco.ventas.dominio.EstadoFactura;
import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.negocio.externos.ClienteDeEventos;
import com.palco.ventas.negocio.externos.ClienteDeEventos.Sector;
import com.palco.ventas.negocio.externos.Cobros;
import com.palco.ventas.negocio.externos.ResultadoDeCobro;

/**
 * Las reglas de abrir, de pagar y de cancelar una compra, sin servidor ni
 * base: los repositorios, el cliente de Eventos, el cobro y el índice de
 * compras son dobles de Mockito, y el publicador de mensajes es una lambda
 * que anota lo que se le pide publicar. Lo que hace el contenedor (crear y
 * destruir la instancia, las transacciones) no se prueba acá: se ve en el log
 * del servidor y en los contadores del broker.
 */
class ServicioDeVentasTest {

    private static final String COMPRADOR = "user-titular";
    private static final String CLAVE = "5d3c1f0e-8a4b-4c7d-9e2f-1a2b3c4d5e6f";
    private static final String CLAVE_DEL_PAGO = "0b7e2a55-3c1d-4e8f-a9b0-c1d2e3f4a5b6";
    private static final String PAGO_ID = "P-0a1b2c3d";

    private static final TitularDto ANA = new TitularDto("Ana Gómez", "30123456");
    private static final TitularDto LUIS = new TitularDto("Luis Pérez", "31234567");
    private static final TitularDto EVA = new TitularDto("Eva Ruiz", "32345678");

    private static final NuevoPagoDto CON_TARJETA = new NuevoPagoDto("tarjeta", "tok_test");
    private static final ResultadoDeCobro APROBADO = new ResultadoDeCobro(PAGO_ID, ResultadoDeCobro.APROBADO, null);
    private static final ResultadoDeCobro RECHAZADO =
            new ResultadoDeCobro(PAGO_ID, ResultadoDeCobro.RECHAZADO, "fondos_insuficientes");

    /** El instante del reloj detenido, como lo ve el servicio: en UTC y sin fracción de segundo. */
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 19, 21, 0, 7);

    private final VentaRepository ventas = mock(VentaRepository.class);
    private final EntradaRepository entradas = mock(EntradaRepository.class);
    private final ClienteDeEventos eventos = mock(ClienteDeEventos.class);
    private final Cobros cobros = mock(Cobros.class);
    private final ComprasEnCurso compras = mock(ComprasEnCurso.class);
    private final ServicioDeVentas servicio = new ServicioDeVentas();

    /** Lo que el servicio mandó a guardar y a publicar. */
    private final List<Entrada> entradasGuardadas = new ArrayList<>();
    private final List<PagoConfirmadoMensaje> publicados = new ArrayList<>();

    @BeforeEach
    void armarElServicio() {
        servicio.ventas = ventas;
        servicio.entradas = entradas;
        servicio.eventos = eventos;
        servicio.cobros = cobros;
        servicio.compras = compras;
        // El publicador es una interfaz de un solo método: en lugar del que manda por JMS va una lambda.
        servicio.publicador = publicados::add;
        // El servidor corre en hora argentina: el reloj fijo comprueba que igual se calcula en UTC.
        servicio.reloj = Clock.fixed(Instant.parse("2026-10-19T21:00:07.900Z"), ZoneOffset.ofHours(-3));

        doAnswer(llamada -> {
            entradasGuardadas.addAll(llamada.getArgument(0));
            return null;
        }).when(entradas).guardar(any());
    }

    // ---- Abrir la compra ----

    @Test
    void abrirUnaCompraGuardaLaVentaReservaElCupoYCalculaElTotal() {
        eventosVende("sec-1-4", "Platea B", 27_500);

        VentaDto venta = servicio.iniciarCompra(COMPRADOR, pedido(List.of(item("sec-1-4", 2)), ANA, LUIS), CLAVE);

        assertTrue(venta.id().matches("V-[0-9a-f]{8}"), venta.id());
        assertEquals("EN_CHECKOUT", venta.estado());
        assertEquals("NO_APLICA", venta.facturaEstado());
        assertEquals("ev-1", venta.eventoId());
        assertEquals(List.of(new ItemDto("sec-1-4", "Platea B", 2, 27_500)), venta.items());
        assertEquals(55_000, venta.subtotal());
        assertEquals(5_500, venta.cargoServicio()); // 10% del subtotal
        assertEquals(60_500, venta.montoTotal());
        assertEquals("2026-10-19T21:10:07Z", venta.venceReserva()); // 10 minutos, en UTC y sin fracción de segundo
        assertNull(venta.pagoId());
        assertTrue(venta.entradas().isEmpty());

        // Primero la cabecera con la clave, después la reserva y recién entonces los renglones.
        ArgumentCaptor<Venta> guardada = ArgumentCaptor.forClass(Venta.class);
        InOrder orden = inOrder(ventas, eventos);
        orden.verify(ventas).guardar(guardada.capture());
        orden.verify(eventos).reservarCupo("ev-1", "sec-1-4", 2);
        orden.verify(ventas).actualizar(guardada.getValue());
        assertEquals(venta.id(), guardada.getValue().getId());
        assertEquals(COMPRADOR, guardada.getValue().getCompradorId());
        assertEquals(CLAVE, guardada.getValue().getClaveIdempotencia());
        assertEquals(LocalDateTime.of(2026, 10, 19, 21, 0, 7), guardada.getValue().getCreada());
        verify(eventos, never()).liberarCupo(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void conVariosSectoresElCargoDeServicioSeCalculaSobreElTotalDeLaCompra() {
        eventosVende("sec-1-4", "Platea B", 27_500);
        eventosVende("sec-1-1", "Popular", 9_005);

        VentaDto venta = servicio.iniciarCompra(COMPRADOR,
                pedido(List.of(item("sec-1-4", 2), item("sec-1-1", 1)), ANA, LUIS, EVA), CLAVE);

        assertEquals(List.of(
                new ItemDto("sec-1-4", "Platea B", 2, 27_500),
                new ItemDto("sec-1-1", "Popular", 1, 9_005)), venta.items());
        assertEquals(64_005, venta.subtotal());
        assertEquals(6_401, venta.cargoServicio()); // 6400,5 redondeado al peso más cercano
        assertEquals(70_406, venta.montoTotal());
    }

    @Test
    void seisEntradasEsElMaximoYSePuede() {
        eventosVende("sec-1-4", "Platea B", 27_500);

        VentaDto venta = servicio.iniciarCompra(COMPRADOR, pedido(List.of(item("sec-1-4", 6)),
                ANA, LUIS, EVA, titular("33000001"), titular("33000002"), titular("33000003")), CLAVE);

        assertEquals(6, venta.items().get(0).cantidad());
    }

    // ---- Lo que se valida antes de tocar nada ----

    @Test
    void masDeSeisEntradasRompeLaReglaDelMaximoYNoSeTocaNada() {
        NuevaVentaDto sieteEnUnSector = pedido(List.of(item("sec-1-4", 7)), ANA, LUIS, EVA,
                titular("33000001"), titular("33000002"), titular("33000003"), titular("33000004"));
        NuevaVentaDto sieteEntreDosSectores = pedido(List.of(item("sec-1-4", 4), item("sec-1-1", 3)), ANA, LUIS, EVA,
                titular("33000001"), titular("33000002"), titular("33000003"), titular("33000004"));

        assertEquals("maximo-de-entradas", reglaIncumplida(sieteEnUnSector).tipo());
        assertEquals("maximo-de-entradas", reglaIncumplida(sieteEntreDosSectores).tipo());
        verifyNoInteractions(ventas, eventos);
    }

    @Test
    void haceFaltaExactamenteUnTitularPorEntrada() {
        assertEquals("titulares-no-coinciden", reglaIncumplida(pedido(List.of(item("sec-1-4", 2)), ANA)).tipo());
        assertEquals("titulares-no-coinciden",
                reglaIncumplida(pedido(List.of(item("sec-1-4", 2)), ANA, LUIS, EVA)).tipo());
        verifyNoInteractions(ventas, eventos);
    }

    @Test
    void dosEntradasNoPuedenIrAlMismoDni() {
        NuevaVentaDto mismoDni = pedido(List.of(item("sec-1-4", 2)), ANA, new TitularDto("Otra Persona", ANA.dni()));

        assertEquals("dni-repetido", reglaIncumplida(mismoDni).tipo());
        verifyNoInteractions(ventas, eventos);
    }

    @Test
    void unTitularSinNombreOConUnDniMalEscritoEsUnDatoInvalido() {
        datoInvalido("titulares", pedido(List.of(item("sec-1-4", 1)), new TitularDto("Ana Gómez", "30.123.456")));
        datoInvalido("titulares", pedido(List.of(item("sec-1-4", 1)), new TitularDto("Ana Gómez", "123")));
        datoInvalido("titulares", pedido(List.of(item("sec-1-4", 1)), new TitularDto("Ana Gómez", null)));
        datoInvalido("titulares", pedido(List.of(item("sec-1-4", 1)), new TitularDto("   ", "30123456")));
        datoInvalido("titulares", pedido(List.of(item("sec-1-4", 1)), new TitularDto("A".repeat(201), "30123456")));
        verifyNoInteractions(ventas, eventos);
    }

    @Test
    void unaCantidadEnCeroONegativaNuncaLlegaAEventos() {
        // Una cantidad negativa le pediría a Eventos que libere cupo: se rechaza antes.
        datoInvalido("items", pedido(List.of(item("sec-1-4", 0))));
        datoInvalido("items", pedido(List.of(item("sec-1-4", -2))));
        datoInvalido("items", pedido(List.of(item("sec-1-4", 2), item("sec-1-1", -1)), ANA));
        verifyNoInteractions(ventas, eventos);
    }

    @Test
    void elMismoSectorNoPuedeAparecerDosVeces() {
        datoInvalido("items", pedido(List.of(item("sec-1-4", 1), item("sec-1-4", 1)), ANA, LUIS));
        verifyNoInteractions(ventas, eventos);
    }

    @Test
    void sinEventoSinRenglonesOSinSectorEsUnDatoInvalido() {
        datoInvalido("eventoId", new NuevaVentaDto(" ", List.of(item("sec-1-4", 1)), List.of(ANA)));
        datoInvalido("eventoId", new NuevaVentaDto("e".repeat(41), List.of(item("sec-1-4", 1)), List.of(ANA)));
        datoInvalido("items", new NuevaVentaDto("ev-1", List.of(), List.of()));
        datoInvalido("items", new NuevaVentaDto("ev-1", null, List.of(ANA)));
        datoInvalido("items", pedido(List.of(item(null, 1)), ANA));
        verifyNoInteractions(ventas, eventos);
    }

    // ---- Lo que puede fallar después de validar ----

    @Test
    void siElCompradorYaUsoLaClaveNoSeReservaNada() {
        doThrow(new ErroresDeNegocio.Conflicto(VentaRepository.TIPO_CLAVE_REPETIDA, "Ya existe una compra con esa clave."))
                .when(ventas).guardar(any());

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.iniciarCompra(COMPRADOR, pedido(List.of(item("sec-1-4", 2)), ANA, LUIS), CLAVE));

        assertEquals(VentaRepository.TIPO_CLAVE_REPETIDA, error.tipo());
        verifyNoInteractions(eventos);
    }

    @Test
    void siUnSectorNoTieneCupoSeDevuelveLoQueYaSeHabiaReservado() {
        eventosVende("sec-1-4", "Platea B", 27_500);
        when(eventos.reservarCupo("ev-1", "sec-1-3", 1))
                .thenThrow(new ErroresDeNegocio.Conflicto("cupo-agotado", "No quedan suficientes entradas en ese sector."));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.iniciarCompra(COMPRADOR,
                        pedido(List.of(item("sec-1-4", 2), item("sec-1-3", 1)), ANA, LUIS, EVA), CLAVE));

        assertEquals("cupo-agotado", error.tipo());
        String ventaId = idDeLaVentaGuardada();
        verify(eventos).liberarCupo(ventaId, "ev-1", "sec-1-4", 2);
        // El sector que falló no se devuelve: no llegó a reservarse.
        verify(eventos, never()).liberarCupo(anyString(), anyString(), eq("sec-1-3"), anyInt());
        verify(ventas, never()).actualizar(any());
    }

    @Test
    void unSectorQueNoRespondioNoSeDevuelvePorqueNoSeSabeSiSeReservo() {
        eventosVende("sec-1-4", "Platea B", 27_500);
        when(eventos.reservarCupo("ev-1", "sec-1-1", 1))
                .thenThrow(new ErroresDeNegocio.SinRespuesta("El servicio de eventos no responde."));

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> servicio.iniciarCompra(COMPRADOR,
                pedido(List.of(item("sec-1-4", 2), item("sec-1-1", 1)), ANA, LUIS, EVA), CLAVE));

        String ventaId = idDeLaVentaGuardada();
        verify(eventos, times(1)).liberarCupo(anyString(), anyString(), anyString(), anyInt());
        verify(eventos).liberarCupo(ventaId, "ev-1", "sec-1-4", 2);
    }

    @Test
    void siFallaElPrimerSectorNoHayNadaQueDevolver() {
        when(eventos.reservarCupo("ev-1", "sec-no-existe", 1))
                .thenThrow(new ErroresDeNegocio.ReglaIncumplida("sector-inexistente", "El evento no tiene ese sector."));

        ErroresDeNegocio.ReglaIncumplida error = assertThrows(ErroresDeNegocio.ReglaIncumplida.class,
                () -> servicio.iniciarCompra(COMPRADOR, pedido(List.of(item("sec-no-existe", 1)), ANA), CLAVE));

        assertEquals("sector-inexistente", error.tipo());
        verify(eventos, never()).liberarCupo(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void siFallaLaBaseDespuesDeReservarSeDevuelveTodoYSeRelanzaLaMismaFalla() {
        eventosVende("sec-1-4", "Platea B", 27_500);
        eventosVende("sec-1-1", "Popular", 9_000);
        IllegalStateException fallaDeLaBase = new IllegalStateException("se cayó la base");
        doThrow(fallaDeLaBase).when(ventas).actualizar(any());

        IllegalStateException relanzada = assertThrows(IllegalStateException.class, () -> servicio.iniciarCompra(
                COMPRADOR, pedido(List.of(item("sec-1-4", 2), item("sec-1-1", 1)), ANA, LUIS, EVA), CLAVE));

        assertSame(fallaDeLaBase, relanzada);
        String ventaId = idDeLaVentaGuardada();
        verify(eventos).liberarCupo(ventaId, "ev-1", "sec-1-4", 2);
        verify(eventos).liberarCupo(ventaId, "ev-1", "sec-1-1", 1);
    }

    @Test
    void unaCompraQueNoSeAbrioNoDejaLaInstanciaAtadaANingunaVenta() {
        when(eventos.reservarCupo("ev-1", "sec-1-3", 1))
                .thenThrow(new ErroresDeNegocio.Conflicto("cupo-agotado", "No quedan suficientes entradas en ese sector."));
        assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.iniciarCompra(COMPRADOR, pedido(List.of(item("sec-1-3", 1)), ANA), CLAVE));

        // ComprasEnCurso la cancela para que el servidor la libere: no hay venta que cancelar.
        assertFalse(servicio.cancelarCompra());
        verify(ventas, never()).cancelar(anyString());

        servicio.alDestruir();
        verifyNoInteractions(compras);
    }

    @Test
    void unaInstanciaLlevaUnaSolaCompra() {
        compraAbierta();

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.iniciarCompra(COMPRADOR, pedido(List.of(item("sec-1-4", 2)), ANA, LUIS), "otra-clave"));

        assertEquals("operacion-en-curso", error.tipo());
        verify(ventas, times(1)).guardar(any());
    }

    // ---- Cancelar ----

    @Test
    void cancelarPasaLaVentaACanceladaYAvisaQueLaCanceloEstaLlamada() {
        String ventaId = compraAbierta();
        when(ventas.cancelar(ventaId)).thenReturn(true);

        assertTrue(servicio.cancelarCompra());

        // El cupo no lo devuelve la instancia: lo devuelve ComprasEnCurso cuando la cancelación ya quedó confirmada.
        verify(eventos, never()).liberarCupo(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void siHayUnPagoEnCursoCancelarEsUnConflictoParaQueLaInstanciaSobreviva() {
        String ventaId = compraAbierta();
        Venta conPagoEnCurso = ventaEnEstado(EstadoVenta.EN_CHECKOUT);
        when(ventas.cancelar(ventaId)).thenReturn(false);
        when(ventas.buscarPorId(ventaId)).thenReturn(Optional.of(conPagoEnCurso));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class, servicio::cancelarCompra);

        assertEquals("operacion-en-curso", error.tipo());
    }

    @Test
    void siLaVentaYaEstabaVencidaCancelarDevuelveFalse() {
        String ventaId = compraAbierta();
        Venta vencida = ventaEnEstado(EstadoVenta.VENCIDA);
        when(ventas.cancelar(ventaId)).thenReturn(false);
        when(ventas.buscarPorId(ventaId)).thenReturn(Optional.of(vencida));

        // Quien la venció es quien devuelve el cupo: esta llamada no cambió nada.
        assertFalse(servicio.cancelarCompra());
    }

    @Test
    void siLaVentaNoQuedoGuardadaCancelarDevuelveFalse() {
        String ventaId = compraAbierta();
        when(ventas.cancelar(ventaId)).thenReturn(false);
        when(ventas.buscarPorId(ventaId)).thenReturn(Optional.empty());

        assertFalse(servicio.cancelarCompra());
    }

    // ---- Pagar ----

    @Test
    void pagarCobraConfirmaLaVentaCreaLasEntradasYPublicaElMensajeUnaSolaVez() {
        String ventaId = compraAbierta();
        Venta confirmada = ventaConfirmada(ventaId);
        Venta abierta = ventaGuardada();
        when(ventas.buscarPorId(ventaId)).thenReturn(Optional.of(abierta)).thenReturn(Optional.of(confirmada));
        when(cobros.cobrar(ventaId, 60_500, "tarjeta", "tok_test", CLAVE_DEL_PAGO)).thenReturn(APROBADO);
        when(ventas.confirmar(ventaId, PAGO_ID, AHORA)).thenReturn(true);

        VentaDto venta = servicio.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO);

        // La respuesta sale de la venta que se volvió a leer después de confirmar.
        assertEquals(ventaId, venta.id());
        assertEquals("CONFIRMADA", venta.estado());
        assertEquals(PAGO_ID, venta.pagoId());

        // Primero se cobra el total de la venta guardada; recién después se escribe.
        InOrder orden = inOrder(cobros, ventas, entradas);
        orden.verify(cobros).cobrar(ventaId, 60_500, "tarjeta", "tok_test", CLAVE_DEL_PAGO);
        orden.verify(ventas).confirmar(ventaId, PAGO_ID, AHORA);
        orden.verify(entradas).guardar(any());

        // Una entrada por titular, en el orden del pedido, todavía sin emitir.
        assertEquals(2, entradasGuardadas.size());
        Entrada deAna = entradasGuardadas.get(0);
        Entrada deLuis = entradasGuardadas.get(1);
        assertTrue(deAna.getId().matches("T-[0-9a-f]{8}"), deAna.getId());
        assertTrue(deLuis.getId().matches("T-[0-9a-f]{8}"), deLuis.getId());
        assertFalse(deAna.getId().equals(deLuis.getId()));
        assertEquals(List.of("Ana Gómez", "30123456"), List.of(deAna.getTitularNombre(), deAna.getTitularDni()));
        assertEquals(List.of("Luis Pérez", "31234567"), List.of(deLuis.getTitularNombre(), deLuis.getTitularDni()));
        assertEquals(1, deAna.getOrden());
        assertEquals(2, deLuis.getOrden());
        for (Entrada entrada : entradasGuardadas) {
            assertEquals(ventaId, entrada.getVenta().getId());
            assertEquals("sec-1-4", entrada.getSectorId());
            assertEquals("Platea B", entrada.getSector());
            assertEquals(27_500, entrada.getPrecioPagado()); // el precio del sector, sin el cargo de servicio
            assertEquals(EstadoEntrada.PENDIENTE_EMISION, entrada.getEstado());
            assertNull(entrada.getCodigoQr());
        }

        // Un solo mensaje, con los ids de las entradas que se acaban de crear.
        assertEquals(1, publicados.size());
        PagoConfirmadoMensaje mensaje = publicados.get(0);
        assertEquals(ventaId, mensaje.ventaId());
        assertEquals(COMPRADOR, mensaje.compradorId());
        assertEquals("ev-1", mensaje.eventoId());
        assertEquals(60_500, mensaje.montoTotal()); // lo cobrado, con el cargo de servicio
        assertEquals(Instant.parse("2026-10-19T21:00:07Z"), mensaje.timestamp());
        assertEquals(List.of(
                new PagoConfirmadoMensaje.Entrada(deAna.getId(), "Platea B",
                        new PagoConfirmadoMensaje.Titular("Ana Gómez", "30123456")),
                new PagoConfirmadoMensaje.Entrada(deLuis.getId(), "Platea B",
                        new PagoConfirmadoMensaje.Titular("Luis Pérez", "31234567"))), mensaje.entradas());
        // El publicador real lo valida antes de mandarlo: tiene que estar completo.
        assertDoesNotThrow(mensaje::validar);
    }

    @Test
    void conVariosSectoresLosTitularesSeRepartenEnElOrdenDelPedido() {
        eventosVende("sec-1-4", "Platea B", 27_500);
        eventosVende("sec-1-1", "Popular", 9_005);
        String ventaId = servicio.iniciarCompra(COMPRADOR,
                pedido(List.of(item("sec-1-4", 2), item("sec-1-1", 1)), ANA, LUIS, EVA), CLAVE).id();
        Venta confirmada = ventaConfirmada(ventaId);
        Venta abierta = ventaGuardada();
        when(ventas.buscarPorId(ventaId)).thenReturn(Optional.of(abierta)).thenReturn(Optional.of(confirmada));
        when(cobros.cobrar(ventaId, 70_406, "tarjeta", "tok_test", CLAVE_DEL_PAGO)).thenReturn(APROBADO);
        when(ventas.confirmar(ventaId, PAGO_ID, AHORA)).thenReturn(true);

        servicio.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO);

        // Los dos primeros titulares van al primer sector del pedido y el tercero al segundo.
        assertEquals(List.of("Ana Gómez", "Luis Pérez", "Eva Ruiz"),
                entradasGuardadas.stream().map(Entrada::getTitularNombre).toList());
        assertEquals(List.of("Platea B", "Platea B", "Popular"),
                entradasGuardadas.stream().map(Entrada::getSector).toList());
        assertEquals(List.of(27_500L, 27_500L, 9_005L),
                entradasGuardadas.stream().map(Entrada::getPrecioPagado).toList());
        assertEquals(List.of(1, 2, 3), entradasGuardadas.stream().map(Entrada::getOrden).toList());
        // En el mensaje viaja el nombre del sector, no su id.
        assertEquals(List.of("Platea B", "Platea B", "Popular"),
                publicados.get(0).entradas().stream().map(PagoConfirmadoMensaje.Entrada::sector).toList());
        assertEquals(70_406, publicados.get(0).montoTotal());
    }

    @Test
    void unCobroRechazadoNoEscribeNiPublicaNadaYLaCompraSePuedePagarDeNuevo() {
        String ventaId = compraAbierta();
        Venta abierta = ventaGuardada();
        Venta confirmada = ventaConfirmada(ventaId);
        when(ventas.buscarPorId(ventaId))
                .thenReturn(Optional.of(abierta))      // primer intento
                .thenReturn(Optional.of(abierta))      // segundo intento
                .thenReturn(Optional.of(confirmada));  // la respuesta del segundo
        when(cobros.cobrar(eq(ventaId), eq(60_500L), anyString(), anyString(), anyString()))
                .thenReturn(RECHAZADO)
                .thenReturn(APROBADO);
        when(ventas.confirmar(ventaId, PAGO_ID, AHORA)).thenReturn(true);

        ErroresDeNegocio.PagoRechazado rechazo = assertThrows(ErroresDeNegocio.PagoRechazado.class,
                () -> servicio.confirmarCompra(new NuevoPagoDto("tarjeta", "tok_rechazado"), CLAVE_DEL_PAGO));

        assertEquals("pago-rechazado", rechazo.tipo());
        assertTrue(rechazo.getMessage().contains("fondos_insuficientes"), rechazo.getMessage());
        verify(ventas, never()).confirmar(anyString(), anyString(), any());
        verifyNoInteractions(entradas);
        assertTrue(publicados.isEmpty());

        // La instancia no perdió nada: sigue sabiendo cuál es su venta y quiénes son los titulares.
        VentaDto venta = servicio.confirmarCompra(CON_TARJETA, "otra-clave-de-pago");

        assertEquals("CONFIRMADA", venta.estado());
        assertEquals(List.of("Ana Gómez", "Luis Pérez"),
                entradasGuardadas.stream().map(Entrada::getTitularNombre).toList());
        assertEquals(1, publicados.size());
    }

    @Test
    void conLaReservaVencidaNoSeCobra() {
        String ventaId = compraAbierta();
        Venta abierta = ventaGuardada();
        when(ventas.buscarPorId(ventaId)).thenReturn(Optional.of(abierta));
        // La reserva valía hasta las 21:10:07: a esa hora exacta ya está vencida.
        servicio.reloj = Clock.fixed(Instant.parse("2026-10-19T21:10:07Z"), ZoneOffset.UTC);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO));

        assertEquals("reserva-vencida", error.tipo());
        nadaSeCobroNiSeEscribio();
    }

    @Test
    void unSegundoAntesDeQueVenzaLaReservaTodaviaSePuedePagar() {
        String ventaId = compraAbierta();
        Venta confirmada = ventaConfirmada(ventaId);
        Venta abierta = ventaGuardada();
        when(ventas.buscarPorId(ventaId)).thenReturn(Optional.of(abierta)).thenReturn(Optional.of(confirmada));
        servicio.reloj = Clock.fixed(Instant.parse("2026-10-19T21:10:06Z"), ZoneOffset.UTC);
        LocalDateTime justoAntes = LocalDateTime.of(2026, 10, 19, 21, 10, 6);
        when(cobros.cobrar(ventaId, 60_500, "tarjeta", "tok_test", CLAVE_DEL_PAGO)).thenReturn(APROBADO);
        when(ventas.confirmar(ventaId, PAGO_ID, justoAntes)).thenReturn(true);

        assertEquals("CONFIRMADA", servicio.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO).estado());
    }

    @Test
    void unaVentaCanceladaOVencidaNoSeCobra() {
        String ventaId = compraAbierta();
        Venta cancelada = ventaEnEstado(EstadoVenta.CANCELADA);
        Venta vencida = ventaEnEstado(EstadoVenta.VENCIDA);
        when(ventas.buscarPorId(ventaId)).thenReturn(Optional.of(cancelada)).thenReturn(Optional.of(vencida));

        assertEquals("reserva-vencida", assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO)).tipo());
        assertEquals("reserva-vencida", assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO)).tipo());
        nadaSeCobroNiSeEscribio();
    }

    @Test
    void unaVentaYaPagaNoSeCobraOtraVez() {
        String ventaId = compraAbierta();
        Venta paga = ventaEnEstado(EstadoVenta.CONFIRMADA);
        when(ventas.buscarPorId(ventaId)).thenReturn(Optional.of(paga));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO));

        assertEquals("venta-ya-paga", error.tipo());
        nadaSeCobroNiSeEscribio();
    }

    @Test
    void unaInstanciaQueNoAbrioNingunaCompraNoTieneNadaQuePagar() {
        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO));

        assertEquals("reserva-vencida", error.tipo());
        verifyNoInteractions(ventas);
        nadaSeCobroNiSeEscribio();
    }

    @Test
    void siQuienCobraNoRespondeNoSeEscribeNiSePublicaNada() {
        String ventaId = compraAbierta();
        Venta abierta = ventaGuardada();
        when(ventas.buscarPorId(ventaId)).thenReturn(Optional.of(abierta));
        when(cobros.cobrar(ventaId, 60_500, "tarjeta", "tok_sin_respuesta", CLAVE_DEL_PAGO))
                .thenThrow(new ErroresDeNegocio.SinRespuesta("El servicio de pagos no respondió."));

        assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> servicio.confirmarCompra(new NuevoPagoDto("tarjeta", "tok_sin_respuesta"), CLAVE_DEL_PAGO));

        verify(ventas, never()).confirmar(anyString(), anyString(), any());
        verifyNoInteractions(entradas);
        assertTrue(publicados.isEmpty());
    }

    @Test
    void siDespuesDeCobrarLaVentaYaNoEstabaAbiertaEsUnaFallaInesperadaYNoSePublica() {
        // No debería pasar: ComprasEnCurso anota la clave del pago antes, y con esa marca nadie cierra la venta.
        String ventaId = compraAbierta();
        Venta abierta = ventaGuardada();
        when(ventas.buscarPorId(ventaId)).thenReturn(Optional.of(abierta));
        when(cobros.cobrar(ventaId, 60_500, "tarjeta", "tok_test", CLAVE_DEL_PAGO)).thenReturn(APROBADO);
        when(ventas.confirmar(ventaId, PAGO_ID, AHORA)).thenReturn(false);

        // No es un error de negocio: el contenedor deshace la transacción y descarta la instancia.
        assertThrows(IllegalStateException.class, () -> servicio.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO));

        verifyNoInteractions(entradas);
        assertTrue(publicados.isEmpty());
    }

    @Test
    void siFallaElGuardadoDeLasEntradasNoSePublicaElMensaje() {
        String ventaId = compraAbierta();
        Venta abierta = ventaGuardada();
        when(ventas.buscarPorId(ventaId)).thenReturn(Optional.of(abierta));
        when(cobros.cobrar(ventaId, 60_500, "tarjeta", "tok_test", CLAVE_DEL_PAGO)).thenReturn(APROBADO);
        when(ventas.confirmar(ventaId, PAGO_ID, AHORA)).thenReturn(true);
        IllegalStateException fallaDeLaBase = new IllegalStateException("se cayó la base");
        doThrow(fallaDeLaBase).when(entradas).guardar(any());

        // La falla sale tal cual: con ella el contenedor deshace también el UPDATE que confirmó la venta.
        assertSame(fallaDeLaBase,
                assertThrows(IllegalStateException.class, () -> servicio.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO)));
        assertTrue(publicados.isEmpty());
    }

    @Test
    void elPagoCorreEnUnaTransaccionYLaInstanciaSobreviveAUnCobroRechazado() throws NoSuchMethodException {
        Method pagar = ServicioDeVentas.class.getMethod("confirmarCompra", NuevoPagoDto.class, String.class);

        // REQUIRED: el contenedor abre la transacción en la que se confirma, se crean las entradas y se publica.
        assertEquals(TransactionAttributeType.REQUIRED, pagar.getAnnotation(TransactionAttribute.class).value());
        // @Remove termina la conversación al pagar; retainIfException la conserva si el cobro se rechaza.
        assertTrue(pagar.getAnnotation(Remove.class).retainIfException());
    }

    // ---- El ciclo de vida que maneja el contenedor ----

    @Test
    void alDestruirseLaInstanciaQuitaSuReferenciaDelIndice() {
        String ventaId = compraAbierta();

        servicio.alDestruir();

        verify(compras).olvidar(ventaId);
    }

    @Test
    void alDestruirseNoFallaAunqueElIndiceYaNoExista() {
        compraAbierta();
        doThrow(new IllegalStateException("el servicio se está deteniendo")).when(compras).olvidar(anyString());

        assertDoesNotThrow(servicio::alDestruir);
    }

    @Test
    void laInstanciaDuraLoQueLaReservaYSobreviveAUnErrorDeNegocioAlCancelar() throws NoSuchMethodException {
        StatefulTimeout inactividad = ServicioDeVentas.class.getAnnotation(StatefulTimeout.class);
        assertEquals(ReglasDeNegocio.MINUTOS_RESERVA, inactividad.value());
        assertEquals(TimeUnit.MINUTES, inactividad.unit());

        Remove alCancelar = ServicioDeVentas.class.getMethod("cancelarCompra").getAnnotation(Remove.class);
        assertTrue(alCancelar.retainIfException());
    }

    // ---- La venta hacia afuera ----

    @Test
    void laVentaSaleConSusEntradasCuandoLasTiene() {
        Venta venta = new Venta("V-3f9a1c2b", COMPRADOR, "ev-1", CLAVE, LocalDateTime.of(2026, 10, 19, 21, 0, 0),
                LocalDateTime.of(2026, 10, 19, 21, 10, 0));
        venta.agregarItem("sec-1-4", "Platea B", 27_500, 1);
        venta.getEntradas().add(new Entrada("T-9c41d07e", venta.getItems().get(0), "Ana Gómez", "30123456", 1));

        VentaDto dto = ServicioDeVentas.aDto(venta);

        assertEquals("2026-10-19T21:10:00Z", dto.venceReserva());
        // Los enlaces salen vacíos del negocio: los completa el recurso REST.
        assertEquals(List.of(new EntradaDeVentaDto("T-9c41d07e", "Platea B", ANA, "PENDIENTE_EMISION", Map.of())),
                dto.entradas());
        assertTrue(dto.enlaces().isEmpty());
    }

    @Test
    void unaEntradaRecienPagadaSaleCompletaYTodaviaSinCodigo() {
        Venta venta = new Venta("V-3f9a1c2b", COMPRADOR, "ev-1", CLAVE, LocalDateTime.of(2026, 10, 19, 21, 0, 0),
                LocalDateTime.of(2026, 10, 19, 21, 10, 0));
        venta.agregarItem("sec-1-4", "Platea B", 27_500, 1);
        Entrada entrada = new Entrada("T-9c41d07e", venta.getItems().get(0), "Ana Gómez", "30123456", 1);

        EntradaDto dto = ServicioDeVentas.aDto(entrada);

        // codigoQr y emitida son null hasta que la emisión la procesa; los enlaces los completa el recurso REST.
        assertEquals(new EntradaDto("T-9c41d07e", "V-3f9a1c2b", "ev-1", "sec-1-4", "Platea B", ANA, 27_500,
                "PENDIENTE_EMISION", null, null, Map.of()), dto);
    }

    @Test
    void unaEntradaEmitidaSaleConSuCodigoYLaFechaDeEmisionEnUtc() {
        Venta venta = new Venta("V-3f9a1c2b", COMPRADOR, "ev-1", CLAVE, LocalDateTime.of(2026, 10, 19, 21, 0, 0),
                LocalDateTime.of(2026, 10, 19, 21, 10, 0));
        // El código y la fecha de emisión no tienen "set": los escribe la emisión con un UPDATE. Se simula con un doble.
        Entrada emitida = mock(Entrada.class);
        when(emitida.getId()).thenReturn("T-9c41d07e");
        when(emitida.getVenta()).thenReturn(venta);
        when(emitida.getEstado()).thenReturn(EstadoEntrada.VALIDA);
        when(emitida.getCodigoQr()).thenReturn("0f8fad5b-d9cb-469f-a165-70867728950e");
        when(emitida.getEmitida()).thenReturn(LocalDateTime.of(2026, 10, 19, 21, 0, 9));

        EntradaDto dto = ServicioDeVentas.aDto(emitida);

        assertEquals("VALIDA", dto.estado());
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", dto.codigoQr());
        assertEquals("2026-10-19T21:00:09Z", dto.emitida());
        assertEquals("V-3f9a1c2b", dto.ventaId());
        assertEquals("ev-1", dto.eventoId());
    }

    // ---------------------------------------------------------------------------------------------

    /** Eventos acepta cualquier reserva de ese sector y contesta con su nombre y su precio. */
    private void eventosVende(String sectorId, String nombre, long precio) {
        when(eventos.reservarCupo(eq("ev-1"), eq(sectorId), anyInt()))
                .thenReturn(new Sector(sectorId, nombre, precio, 2000, 1442));
    }

    /** Abre una compra de dos entradas en Platea B y devuelve el id de la venta. */
    private String compraAbierta() {
        eventosVende("sec-1-4", "Platea B", 27_500);
        return servicio.iniciarCompra(COMPRADOR, pedido(List.of(item("sec-1-4", 2)), ANA, LUIS), CLAVE).id();
    }

    /** El id que el servicio le puso a la venta que mandó a guardar. */
    private String idDeLaVentaGuardada() {
        ArgumentCaptor<Venta> guardada = ArgumentCaptor.forClass(Venta.class);
        verify(ventas).guardar(guardada.capture());
        return guardada.getValue().getId();
    }

    private ErroresDeNegocio.ReglaIncumplida reglaIncumplida(NuevaVentaDto pedido) {
        return assertThrows(ErroresDeNegocio.ReglaIncumplida.class,
                () -> servicio.iniciarCompra(COMPRADOR, pedido, CLAVE));
    }

    private void datoInvalido(String campo, NuevaVentaDto pedido) {
        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.iniciarCompra(COMPRADOR, pedido, CLAVE));
        assertTrue(error.campos().containsKey(campo), "campos: " + error.campos());
    }

    /** La venta real que el servicio mandó a guardar al abrir la compra: EN_CHECKOUT, con sus renglones y su total. */
    private Venta ventaGuardada() {
        ArgumentCaptor<Venta> guardada = ArgumentCaptor.forClass(Venta.class);
        verify(ventas).guardar(guardada.capture());
        return guardada.getValue();
    }

    /** Comprueba que el pago no pasó de la validación: ni cobro, ni venta confirmada, ni entradas, ni mensaje. */
    private void nadaSeCobroNiSeEscribio() {
        verifyNoInteractions(cobros, entradas);
        verify(ventas, never()).confirmar(anyString(), anyString(), any());
        assertTrue(publicados.isEmpty());
    }

    /** Una venta que la base devuelve en ese estado. El estado no tiene "set": se simula con un doble. */
    private static Venta ventaEnEstado(EstadoVenta estado) {
        Venta venta = mock(Venta.class);
        when(venta.getEstado()).thenReturn(estado);
        when(venta.estaPaga()).thenReturn(estado == EstadoVenta.CONFIRMADA || estado == EstadoVenta.EMITIDA);
        return venta;
    }

    /** La venta como la devuelve la base después del pago, con lo que hace falta para armar la respuesta. */
    private static Venta ventaConfirmada(String ventaId) {
        Venta venta = ventaEnEstado(EstadoVenta.CONFIRMADA);
        when(venta.getId()).thenReturn(ventaId);
        when(venta.getFacturaEstado()).thenReturn(EstadoFactura.NO_APLICA);
        when(venta.getVenceReserva()).thenReturn(AHORA.plusMinutes(10));
        when(venta.getPagoId()).thenReturn(PAGO_ID);
        return venta;
    }

    private static NuevaVentaDto pedido(List<ItemPedidoDto> items, TitularDto... titulares) {
        return new NuevaVentaDto("ev-1", items, List.of(titulares));
    }

    private static ItemPedidoDto item(String sectorId, int cantidad) {
        return new ItemPedidoDto(sectorId, cantidad);
    }

    private static TitularDto titular(String dni) {
        return new TitularDto("Titular " + dni, dni);
    }
}
