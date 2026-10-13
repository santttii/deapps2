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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.ejb.ConcurrentAccessTimeoutException;
import jakarta.ejb.EJBException;
import jakarta.ejb.NoSuchEJBException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.palco.common.ErroresDeNegocio;
import com.palco.ventas.api.Dtos.EntradaDto;
import com.palco.ventas.api.Dtos.ItemPedidoDto;
import com.palco.ventas.api.Dtos.NuevaVentaDto;
import com.palco.ventas.api.Dtos.NuevoPagoDto;
import com.palco.ventas.api.Dtos.TitularDto;
import com.palco.ventas.api.Dtos.VentaDto;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.EstadoFactura;
import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.negocio.externos.ClienteDeEventos;
import com.palco.ventas.negocio.externos.ClienteDeUsuarios;
import com.palco.ventas.negocio.externos.ClienteDeUsuarios.Usuario;
import com.palco.ventas.negocio.externos.CobroNoRealizado;

/**
 * La fachada y el índice de las compras, sin servidor: el repositorio, los
 * dos clientes y la instancia stateful son dobles de Mockito, y en lugar de la
 * búsqueda por JNDI hay un proveedor que cuenta cuántas instancias se pidieron.
 */
class ComprasEnCursoTest {

    private static final String COMPRADOR = "user-titular";
    private static final String OTRO_COMPRADOR = "user-otro";
    private static final String TOKEN = "token-del-comprador";
    private static final String CLAVE = "5d3c1f0e-8a4b-4c7d-9e2f-1a2b3c4d5e6f";
    private static final String VENTA = "V-3f9a1c2b";
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 19, 21, 0, 0);

    /** La compra se abrió a las 21:00:00 y su reserva vale hasta las 21:10:00. El pago llega un minuto después. */
    private static final LocalDateTime AL_PAGAR = AHORA.plusMinutes(1);
    private static final String CLAVE_DEL_PAGO = "0b7e2a55-3c1d-4e8f-a9b0-c1d2e3f4a5b6";
    private static final String OTRA_CLAVE_DE_PAGO = "9f8e7d6c-5b4a-4321-8fed-cba987654321";
    private static final NuevoPagoDto CON_TARJETA = new NuevoPagoDto("tarjeta", "tok_test");

    private static final NuevaVentaDto PEDIDO = new NuevaVentaDto("ev-1", List.of(new ItemPedidoDto("sec-1-4", 2)),
            List.of(new TitularDto("Ana Gómez", "30123456"), new TitularDto("Luis Pérez", "31234567")));

    private final VentaRepository ventas = mock(VentaRepository.class);
    private final EntradaRepository entradas = mock(EntradaRepository.class);
    private final ClienteDeEventos eventos = mock(ClienteDeEventos.class);
    private final ClienteDeUsuarios usuarios = mock(ClienteDeUsuarios.class);
    private final IVentas instancia = mock(IVentas.class);
    private final ComprasEnCurso compras = new ComprasEnCurso();
    private int instanciasPedidas;

    @BeforeEach
    void armarLaFachada() {
        compras.ventas = ventas;
        compras.entradas = entradas;
        compras.eventos = eventos;
        compras.usuarios = usuarios;
        compras.reloj = Clock.fixed(AL_PAGAR.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        compras.nuevaCompra = () -> {
            instanciasPedidas++;
            return instancia;
        };
        when(usuarios.yo(TOKEN)).thenReturn(usuarioConIdentidad(true));
    }

    // ---- Abrir ----

    @Test
    void abrirUnaCompraPideUnaInstanciaNuevaYLaAnotaPorElIdDeLaVenta() {
        VentaDto abierta = ServicioDeVentas.aDto(ventaEnCheckout(COMPRADOR));
        when(instancia.iniciarCompra(COMPRADOR, PEDIDO, CLAVE)).thenReturn(abierta);

        VentaDto venta = compras.iniciar(COMPRADOR, TOKEN, PEDIDO, CLAVE);

        assertSame(abierta, venta);
        assertEquals(1, instanciasPedidas);
        assertSame(instancia, compras.enCurso.get(VENTA));
        verify(instancia, never()).cancelarCompra();
    }

    @Test
    void sinLaIdentidadVerificadaNoSeAbreLaCompra() {
        when(usuarios.yo(TOKEN)).thenReturn(usuarioConIdentidad(false));

        ErroresDeNegocio.SinPermiso error = assertThrows(ErroresDeNegocio.SinPermiso.class,
                () -> compras.iniciar(COMPRADOR, TOKEN, PEDIDO, CLAVE));

        assertEquals("identidad-no-verificada", error.tipo());
        assertEquals(0, instanciasPedidas);
    }

    @Test
    void siUsuariosNoRespondeNoSeAbreLaCompra() {
        when(usuarios.yo(TOKEN)).thenThrow(new ErroresDeNegocio.SinRespuesta("El servicio de usuarios no responde."));

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> compras.iniciar(COMPRADOR, TOKEN, PEDIDO, CLAVE));

        assertEquals(0, instanciasPedidas);
    }

    @Test
    void repetirElPedidoConLaMismaClaveDevuelveLaVentaQueYaExiste() {
        when(ventas.buscarPorClave(COMPRADOR, CLAVE)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));

        VentaDto venta = compras.iniciar(COMPRADOR, TOKEN, PEDIDO, CLAVE);

        assertEquals(VENTA, venta.id());
        assertEquals(60_500, venta.montoTotal());
        // No se consulta a nadie ni se crea otra instancia: no hay segunda reserva.
        assertEquals(0, instanciasPedidas);
        verifyNoInteractions(usuarios, eventos);
    }

    @Test
    void repetirElPedidoDevuelveLaVentaComoEstaHoyAunqueYaEstePagaOCerrada() {
        // La compra de esa clave ya se pagó, se canceló o venció: la clave sigue siendo de esa venta.
        for (EstadoVenta estado : List.of(EstadoVenta.EMITIDA, EstadoVenta.CANCELADA, EstadoVenta.VENCIDA)) {
            Venta anterior = ventaEnEstado(estado);
            when(ventas.buscarPorClave(COMPRADOR, CLAVE)).thenReturn(Optional.of(anterior));

            VentaDto venta = compras.iniciar(COMPRADOR, TOKEN, PEDIDO, CLAVE);

            // Sale el estado de hoy, no una copia de la primera respuesta.
            assertEquals(VENTA, venta.id());
            assertEquals(estado.name(), venta.estado());
        }
        // Y no se abre otra compra en su lugar: para comprar de nuevo hace falta una clave nueva.
        assertEquals(0, instanciasPedidas);
        verifyNoInteractions(usuarios, eventos);
    }

    @Test
    void siDosPedidosConLaMismaClaveLleganALaVezValeLaVentaQueGuardoElOtro() {
        // La primera búsqueda no la encuentra; cuando la instancia quiere guardar, el otro pedido ya ganó.
        when(ventas.buscarPorClave(COMPRADOR, CLAVE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(instancia.iniciarCompra(COMPRADOR, PEDIDO, CLAVE)).thenThrow(
                new ErroresDeNegocio.Conflicto(VentaRepository.TIPO_CLAVE_REPETIDA, "Ya existe una compra con esa clave."));

        VentaDto venta = compras.iniciar(COMPRADOR, TOKEN, PEDIDO, CLAVE);

        assertEquals(VENTA, venta.id());
        verify(instancia).cancelarCompra(); // la instancia que perdió se suelta
        assertTrue(compras.enCurso.isEmpty());
    }

    @Test
    void siLaCompraNoSeAbreSeSueltaLaInstanciaYSeRelanzaElError() {
        ErroresDeNegocio.Conflicto sinCupo = new ErroresDeNegocio.Conflicto("cupo-agotado", "No quedan entradas.");
        when(instancia.iniciarCompra(COMPRADOR, PEDIDO, CLAVE)).thenThrow(sinCupo);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.iniciar(COMPRADOR, TOKEN, PEDIDO, CLAVE));

        assertSame(sinCupo, error);
        verify(instancia).cancelarCompra();
        assertTrue(compras.enCurso.isEmpty());
    }

    @Test
    void siLaFallaFueInesperadaElServidorYaDescartoLaInstanciaYSeRelanzaLaFallaOriginal() {
        EJBException fallaDeLaBase = new EJBException("se cayó la base");
        when(instancia.iniciarCompra(COMPRADOR, PEDIDO, CLAVE)).thenThrow(fallaDeLaBase);
        when(instancia.cancelarCompra()).thenThrow(new NoSuchEJBException("la instancia ya no existe"));

        EJBException error = assertThrows(EJBException.class, () -> compras.iniciar(COMPRADOR, TOKEN, PEDIDO, CLAVE));

        assertSame(fallaDeLaBase, error);
        assertTrue(compras.enCurso.isEmpty());
    }

    // ---- Consultar ----

    @Test
    void obtenerDevuelveLaVentaDelComprador() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));

        VentaDto venta = compras.obtener(COMPRADOR, VENTA);

        assertEquals(VENTA, venta.id());
        assertEquals("EN_CHECKOUT", venta.estado());
    }

    // ---- Listar las compras propias ----

    @Test
    void listarDevuelveLasComprasDelCompradorConSusEntradasYEnElOrdenDelRepositorio() {
        // Una compra ya paga, con su entrada, y otra más vieja todavía abierta.
        Venta paga = new Venta("V-7b2e4d10", COMPRADOR, "ev-1", CLAVE, AHORA, AHORA.plusMinutes(10));
        paga.agregarItem("sec-1-4", "Platea B", 27_500, 1);
        paga.getEntradas().add(new Entrada("T-9c41d07e", paga.getItems().get(0), "Ana Gómez", "30123456", 1));
        when(ventas.buscarPorComprador(COMPRADOR)).thenReturn(List.of(paga, ventaEnCheckout(COMPRADOR)));

        List<VentaDto> lista = compras.listar(COMPRADOR);

        assertEquals(List.of("V-7b2e4d10", VENTA), lista.stream().map(VentaDto::id).toList());
        // La fecha de la compra y, en cada entrada, el sector, lo que se pagó y el código (todavía sin emitir).
        assertEquals("2026-10-19T21:00:00Z", lista.get(0).creada());
        assertEquals("sec-1-4", lista.get(0).entradas().get(0).sectorId());
        assertEquals(27_500, lista.get(0).entradas().get(0).precioPagado());
        assertNull(lista.get(0).entradas().get(0).codigoQr());
    }

    @Test
    void listarBuscaSoloPorElCompradorDelPedidoYNoNecesitaLaInstanciaDeLaCompra() {
        when(ventas.buscarPorComprador(COMPRADOR)).thenReturn(List.of());

        // Quien no compró nada recibe una lista vacía, no un error.
        assertTrue(compras.listar(COMPRADOR).isEmpty());

        // La única consulta es la de ese comprador: no hay otra forma de que aparezca una venta ajena.
        verify(ventas).buscarPorComprador(COMPRADOR);
        verifyNoMoreInteractions(ventas);
        // Se lee de la base: no se pide ninguna instancia ni se llama a otros servicios.
        assertEquals(0, instanciasPedidas);
        verifyNoInteractions(instancia, eventos);
    }

    @Test
    void unaVentaAjenaRespondeLoMismoQueUnaQueNoExiste() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(OTRO_COMPRADOR)));
        compras.enCurso.put(VENTA, instancia);

        ErroresDeNegocio.NoEncontrado ajena = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> compras.obtener(COMPRADOR, VENTA));
        ErroresDeNegocio.NoEncontrado inexistente = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> compras.obtener(COMPRADOR, "V-noexiste"));
        assertEquals(inexistente.getMessage(), ajena.getMessage());

        // Tampoco se puede cancelar: ni se toca la instancia ni la base ni el cupo.
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> compras.cancelar(COMPRADOR, VENTA));
        verifyNoInteractions(instancia, eventos);
        verify(ventas, never()).cancelar(anyString());
        assertSame(instancia, compras.enCurso.get(VENTA));
    }

    // ---- Cancelar ----

    @Test
    void cancelarUsaLaInstanciaDeLaCompraYDespuesDevuelveElCupoDeCadaSector() {
        Venta venta = ventaEnCheckout(COMPRADOR);
        venta.agregarItem("sec-1-1", "Popular", 9_000, 1);
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(venta));
        when(instancia.cancelarCompra()).thenReturn(true);
        compras.enCurso.put(VENTA, instancia);

        compras.cancelar(COMPRADOR, VENTA);

        verify(eventos).liberarCupo(VENTA, "ev-1", "sec-1-4", 2);
        verify(eventos).liberarCupo(VENTA, "ev-1", "sec-1-1", 1);
        verify(ventas, never()).cancelar(anyString()); // el UPDATE lo hizo la instancia
        assertFalse(compras.enCurso.containsKey(VENTA));
    }

    @Test
    void sinInstanciaCancelaPorElRepositorioYDevuelveElCupo() {
        // Es lo que pasa después de redesplegar Ventas: la venta sigue en la base y el índice está vacío.
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(ventas.cancelar(VENTA)).thenReturn(true);

        compras.cancelar(COMPRADOR, VENTA);

        verify(eventos).liberarCupo(VENTA, "ev-1", "sec-1-4", 2);
    }

    @Test
    void siElServidorYaDestruyoLaInstanciaSeQuitaLaReferenciaYSeCancelaPorElRepositorio() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(instancia.cancelarCompra()).thenThrow(new NoSuchEJBException("venció por inactividad"));
        when(ventas.cancelar(VENTA)).thenReturn(true);
        compras.enCurso.put(VENTA, instancia);

        compras.cancelar(COMPRADOR, VENTA);

        assertFalse(compras.enCurso.containsKey(VENTA));
        verify(ventas).cancelar(VENTA);
        verify(eventos).liberarCupo(VENTA, "ev-1", "sec-1-4", 2);
    }

    @Test
    void mientrasHayUnaLlamadaSobreLaInstanciaLaSegundaNoLlegaAlStateful() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        compras.enCurso.put(VENTA, instancia);
        AtomicReference<ErroresDeNegocio.Conflicto> segunda = new AtomicReference<>();
        when(instancia.cancelarCompra()).thenAnswer(llamada -> {
            // La primera cancelación está adentro de la instancia y llega otra para la misma compra.
            segunda.set(assertThrows(ErroresDeNegocio.Conflicto.class, () -> compras.cancelar(COMPRADOR, VENTA)));
            return true;
        });

        compras.cancelar(COMPRADOR, VENTA);

        assertEquals("operacion-en-curso", segunda.get().tipo());
        // En el servidor, una llamada simultánea rechazada por el stateful haría que se descarte la instancia.
        verify(instancia, times(1)).cancelarCompra();
        verify(eventos, times(1)).liberarCupo(VENTA, "ev-1", "sec-1-4", 2);
    }

    @Test
    void cuandoLaLlamadaTerminaLaCompraQuedaLibreParaLaSiguiente() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(instancia.cancelarCompra())
                .thenThrow(new ErroresDeNegocio.Conflicto("operacion-en-curso", "Hay un pago en curso para esta compra."))
                .thenReturn(true);
        compras.enCurso.put(VENTA, instancia);

        // La primera termina con un error; la marca de "ocupada" no puede quedar puesta.
        assertThrows(ErroresDeNegocio.Conflicto.class, () -> compras.cancelar(COMPRADOR, VENTA));
        compras.cancelar(COMPRADOR, VENTA);

        verify(instancia, times(2)).cancelarCompra();
        verify(eventos).liberarCupo(VENTA, "ev-1", "sec-1-4", 2);
    }

    @Test
    void siElServidorIgualRechazaLaLlamadaPorSimultaneaEsOperacionEnCurso() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(instancia.cancelarCompra()).thenThrow(new ConcurrentAccessTimeoutException("la instancia está ocupada"));
        compras.enCurso.put(VENTA, instancia);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.cancelar(COMPRADOR, VENTA));

        assertEquals("operacion-en-curso", error.tipo());
        verify(ventas, never()).cancelar(anyString());
        verifyNoInteractions(eventos);
    }

    @Test
    void siLaInstanciaAvisaQueHayUnPagoEnCursoLaReferenciaSeConserva() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(instancia.cancelarCompra()).thenThrow(
                new ErroresDeNegocio.Conflicto("operacion-en-curso", "Hay un pago en curso para esta compra."));
        compras.enCurso.put(VENTA, instancia);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.cancelar(COMPRADOR, VENTA));

        assertEquals("operacion-en-curso", error.tipo());
        assertSame(instancia, compras.enCurso.get(VENTA)); // el pago la necesita para terminar
        verifyNoInteractions(eventos);
    }

    @Test
    void unaVentaPagaNoSeCancela() {
        Venta paga = ventaEnEstado(EstadoVenta.CONFIRMADA);
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(paga));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.cancelar(COMPRADOR, VENTA));

        assertEquals("venta-ya-paga", error.tipo());
        verify(ventas, never()).cancelar(anyString());
        verifyNoInteractions(eventos);
    }

    @Test
    void cancelarUnaVentaYaCanceladaOVencidaNoFallaNiDevuelveElCupoOtraVez() {
        Venta cancelada = ventaEnEstado(EstadoVenta.CANCELADA);
        Venta vencida = ventaEnEstado(EstadoVenta.VENCIDA);
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(cancelada)).thenReturn(Optional.of(vencida));

        assertDoesNotThrow(() -> compras.cancelar(COMPRADOR, VENTA));
        assertDoesNotThrow(() -> compras.cancelar(COMPRADOR, VENTA));

        verify(ventas, never()).cancelar(anyString());
        verifyNoInteractions(eventos);
    }

    @Test
    void siElPagoEmpezoJustoAntesDeCancelarEsOperacionEnCurso() {
        // El UPDATE no cambia ninguna fila porque la venta ya tiene anotada la clave de un pago.
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(ventas.cancelar(VENTA)).thenReturn(false);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.cancelar(COMPRADOR, VENTA));

        assertEquals("operacion-en-curso", error.tipo());
        verifyNoInteractions(eventos);
    }

    @Test
    void siOtroPedidoLaCerroPrimeroNoSeDevuelveElCupoDosVeces() {
        Venta cancelada = ventaEnEstado(EstadoVenta.CANCELADA);
        when(ventas.buscarPorId(VENTA))
                .thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)))
                .thenReturn(Optional.of(cancelada));
        when(ventas.cancelar(VENTA)).thenReturn(false);

        assertDoesNotThrow(() -> compras.cancelar(COMPRADOR, VENTA));

        // Solo devuelve cupo quien cambió la fila con su propio UPDATE.
        verify(eventos, never()).liberarCupo(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void siSePagoJustoAntesDeCancelarEsVentaYaPaga() {
        Venta paga = ventaEnEstado(EstadoVenta.EMITIDA);
        when(ventas.buscarPorId(VENTA))
                .thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)))
                .thenReturn(Optional.of(paga));
        when(ventas.cancelar(VENTA)).thenReturn(false);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.cancelar(COMPRADOR, VENTA));

        assertEquals("venta-ya-paga", error.tipo());
        verifyNoInteractions(eventos);
    }

    // ---- Pagar ----

    @Test
    void pagarAnotaLaClaveDelIntentoYDespuesLlamaALaInstanciaDeLaCompra() {
        VentaDto pagada = ventaPagada();
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(true);
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO)).thenReturn(pagada);
        compras.enCurso.put(VENTA, instancia);

        VentaDto venta = compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO);

        assertSame(pagada, venta);
        // La marca queda guardada antes de que la instancia cobre.
        InOrder orden = inOrder(ventas, instancia);
        orden.verify(ventas).guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR);
        orden.verify(instancia).confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO);
        // La venta quedó paga: la marca no se quita, la conversación terminó y el cupo no se devuelve.
        verify(ventas, never()).borrarClaveDePago(anyString(), anyString());
        assertFalse(compras.enCurso.containsKey(VENTA));
        verifyNoInteractions(eventos);
    }

    @Test
    void pagarUnaVentaAjenaRespondeLoMismoQueUnaQueNoExisteYNoTocaNada() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(OTRO_COMPRADOR)));
        compras.enCurso.put(VENTA, instancia);

        ErroresDeNegocio.NoEncontrado ajena = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));
        ErroresDeNegocio.NoEncontrado inexistente = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> compras.pagar(COMPRADOR, "V-noexiste", CON_TARJETA, CLAVE_DEL_PAGO));

        assertEquals(inexistente.getMessage(), ajena.getMessage());
        // Ni se anota el intento, ni se toca la instancia del dueño, ni se cobra.
        verify(ventas, never()).guardarClaveDePago(anyString(), anyString(), any());
        verifyNoInteractions(instancia);
        assertSame(instancia, compras.enCurso.get(VENTA));
    }

    @Test
    void siElCobroEsRechazadoSeQuitaLaMarcaYSePuedePagarDeNuevoConOtraClave() {
        ErroresDeNegocio.PagoRechazado rechazo =
                new ErroresDeNegocio.PagoRechazado("El pago fue rechazado (fondos_insuficientes).");
        VentaDto pagada = ventaPagada();
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(ventas.guardarClaveDePago(eq(VENTA), anyString(), eq(AL_PAGAR))).thenReturn(true);
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO)).thenThrow(rechazo);
        when(instancia.confirmarCompra(CON_TARJETA, OTRA_CLAVE_DE_PAGO)).thenReturn(pagada);
        compras.enCurso.put(VENTA, instancia);

        ErroresDeNegocio.PagoRechazado error = assertThrows(ErroresDeNegocio.PagoRechazado.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        assertSame(rechazo, error);
        // Se sabe que no se cobró: sin la marca, la compra se puede pagar de nuevo, cancelar o vencer.
        verify(ventas).borrarClaveDePago(VENTA, CLAVE_DEL_PAGO);
        // La instancia sigue en el índice: es la que guarda los titulares.
        assertSame(instancia, compras.enCurso.get(VENTA));

        // El segundo intento entra: la compra no quedó marcada como ocupada.
        assertSame(pagada, compras.pagar(COMPRADOR, VENTA, CON_TARJETA, OTRA_CLAVE_DE_PAGO));
        assertFalse(compras.enCurso.containsKey(VENTA));
    }

    @Test
    void repetirConLaMismaClaveUnPagoRechazadoDaElMismoRechazoYNoDejaLaMarca() {
        ErroresDeNegocio.PagoRechazado rechazo =
                new ErroresDeNegocio.PagoRechazado("El pago fue rechazado (fondos_insuficientes).");
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(true);
        // Quien cobra responde lo mismo para la misma clave: el rechazo se repite.
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO)).thenThrow(rechazo);
        compras.enCurso.put(VENTA, instancia);

        assertSame(rechazo, assertThrows(ErroresDeNegocio.PagoRechazado.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO)));
        assertSame(rechazo, assertThrows(ErroresDeNegocio.PagoRechazado.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO)));

        // Las dos veces la marca se anotó y se quitó: la venta no queda trabada por un pago que no se cobró.
        verify(ventas, times(2)).guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR);
        verify(ventas, times(2)).borrarClaveDePago(VENTA, CLAVE_DEL_PAGO);
        // Y la compra sigue abierta, con su instancia, para pagar con otro medio de pago.
        assertSame(instancia, compras.enCurso.get(VENTA));
    }

    @Test
    void siQuienCobraNoRespondeLaMarcaQuedaYSeReintentaConLaMismaClave() {
        VentaDto pagada = ventaPagada();
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(true);
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO))
                .thenThrow(new ErroresDeNegocio.SinRespuesta("El servicio de pagos no respondió."))
                .thenReturn(pagada);
        compras.enCurso.put(VENTA, instancia);

        assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        // No se sabe si se cobró: la marca queda, y con ella nadie puede cancelar ni vencer la venta.
        verify(ventas, never()).borrarClaveDePago(anyString(), anyString());
        assertSame(instancia, compras.enCurso.get(VENTA));

        // Con la misma clave el UPDATE de la marca vuelve a pasar y quien cobra no cobra dos veces.
        assertSame(pagada, compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));
        verify(ventas, times(2)).guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR);
    }

    @Test
    void anteUnaFallaInesperadaDelPagoLaMarcaQueda() {
        EJBException fallaAlConfirmar = new EJBException("falló el commit después de cobrar");
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(true);
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO)).thenThrow(fallaAlConfirmar);
        compras.enCurso.put(VENTA, instancia);

        EJBException error = assertThrows(EJBException.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        assertSame(fallaAlConfirmar, error);
        // Es el rastro de que hubo un intento de cobro del que no se conoce el resultado.
        verify(ventas, never()).borrarClaveDePago(anyString(), anyString());
        // El servidor descartó la instancia: la referencia no sirve más y se quita del índice.
        assertFalse(compras.enCurso.containsKey(VENTA));
    }

    @Test
    void unConflictoQueNoEsReservaVencidaTampocoQuitaLaMarca() {
        // Por ejemplo, la red de la base al guardar las entradas: salta después de cobrar.
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(true);
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO)).thenThrow(
                new ErroresDeNegocio.Conflicto("entrada-repetida", "La compra ya tiene una entrada para ese DNI."));
        compras.enCurso.put(VENTA, instancia);

        assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        verify(ventas, never()).borrarClaveDePago(anyString(), anyString());
    }

    @Test
    void sinInstanciaElPagoEsReservaVencidaYNoSeAnotaNada() {
        // Es lo que pasa después de redesplegar Ventas: la venta sigue abierta en la base, pero los
        // titulares vivían en la instancia. La venta queda sin marca y la vence el barrido.
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        assertEquals("reserva-vencida", error.tipo());
        verify(ventas, never()).guardarClaveDePago(anyString(), anyString(), any());
    }

    @Test
    void unaVentaCanceladaOVencidaNoSePaga() {
        Venta cancelada = ventaEnEstado(EstadoVenta.CANCELADA);
        Venta vencida = ventaEnEstado(EstadoVenta.VENCIDA);
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(cancelada)).thenReturn(Optional.of(vencida));
        compras.enCurso.put(VENTA, instancia);

        assertEquals("reserva-vencida", assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO)).tipo());
        assertEquals("reserva-vencida", assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO)).tipo());

        verify(ventas, never()).guardarClaveDePago(anyString(), anyString(), any());
        verifyNoInteractions(instancia);
    }

    @Test
    void conLaReservaVencidaNoSePagaAunqueLaInstanciaSigaViva() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        compras.enCurso.put(VENTA, instancia);
        // La reserva valía hasta las 21:10:00 y el barrido todavía no pasó: la venta sigue EN_CHECKOUT.
        compras.reloj = Clock.fixed(Instant.parse("2026-10-19T21:10:00Z"), ZoneOffset.UTC);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        assertEquals("reserva-vencida", error.tipo());
        verify(ventas, never()).guardarClaveDePago(anyString(), anyString(), any());
        verifyNoInteractions(instancia);
    }

    @Test
    void repetirElPagoConLaMismaClaveDevuelveLaVentaYaPagaSinCobrarOtraVez() {
        Venta paga = ventaEnEstado(EstadoVenta.CONFIRMADA);
        when(paga.getClavePago()).thenReturn(CLAVE_DEL_PAGO);
        when(paga.getPagoId()).thenReturn("P-0a1b2c3d");
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(paga));

        VentaDto venta = compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO);

        // Se devuelve el estado actual de la venta, no una copia de la primera respuesta.
        assertEquals("CONFIRMADA", venta.estado());
        assertEquals("P-0a1b2c3d", venta.pagoId());
        verify(ventas, never()).guardarClaveDePago(anyString(), anyString(), any());
        verifyNoInteractions(instancia);
    }

    @Test
    void pagarConOtraClaveUnaVentaYaPagaEsUnConflicto() {
        Venta paga = ventaEnEstado(EstadoVenta.EMITIDA);
        when(paga.getClavePago()).thenReturn(CLAVE_DEL_PAGO);
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(paga));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, OTRA_CLAVE_DE_PAGO));

        assertEquals("venta-ya-paga", error.tipo());
        verify(ventas, never()).guardarClaveDePago(anyString(), anyString(), any());
    }

    @Test
    void siLaVentaTieneAnotadoOtroIntentoDePagoEsOperacionEnCurso() {
        // Un intento anterior con otra clave quedó sin resultado conocido: el UPDATE de la marca no pasa.
        Venta conOtroIntento = ventaEnEstado(EstadoVenta.EN_CHECKOUT);
        when(conOtroIntento.getClavePago()).thenReturn(OTRA_CLAVE_DE_PAGO);
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(conOtroIntento));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(false);
        compras.enCurso.put(VENTA, instancia);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        assertEquals("operacion-en-curso", error.tipo());
        // No se cobra con una segunda clave, y la marca del otro intento no se toca.
        verifyNoInteractions(instancia);
        verify(ventas, never()).borrarClaveDePago(anyString(), anyString());
    }

    @Test
    void siLaVentaSeCerroJustoAntesDeAnotarElIntentoEsReservaVencida() {
        Venta cancelada = ventaEnEstado(EstadoVenta.CANCELADA);
        when(ventas.buscarPorId(VENTA))
                .thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)))
                .thenReturn(Optional.of(cancelada));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(false);
        compras.enCurso.put(VENTA, instancia);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        assertEquals("reserva-vencida", error.tipo());
        verifyNoInteractions(instancia);
    }

    @Test
    void siElServidorYaDestruyoLaInstanciaNoSeCobroYSeQuitaLaMarcaQueSeAcabaDeAnotar() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(true);
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO))
                .thenThrow(new NoSuchEJBException("la instancia ya no existe"));
        compras.enCurso.put(VENTA, instancia);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        // Igual que cuando no hay instancia: reserva vencida, y la venta queda sin marca para que venza sola.
        assertEquals("reserva-vencida", error.tipo());
        assertFalse(compras.enCurso.containsKey(VENTA));
        verify(ventas).borrarClaveDePago(VENTA, CLAVE_DEL_PAGO);
    }

    @Test
    void enUnReintentoLaMarcaDelIntentoAnteriorNoSeQuitaAunqueLaInstanciaYaNoExista() {
        // La venta ya traía anotada esta clave: hubo un intento anterior del que no se supo si cobró.
        Venta conEsteIntento = ventaEnEstado(EstadoVenta.EN_CHECKOUT);
        when(conEsteIntento.getClavePago()).thenReturn(CLAVE_DEL_PAGO);
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(conEsteIntento));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(true);
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO))
                .thenThrow(new NoSuchEJBException("la instancia ya no existe"));
        compras.enCurso.put(VENTA, instancia);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        // No es "reserva vencida": con la marca puesta no se le dice al comprador que empiece de nuevo.
        esPagoSinResultado(error);
        verify(ventas, never()).borrarClaveDePago(anyString(), anyString());
        assertFalse(compras.enCurso.containsKey(VENTA));
    }

    // ---- Pagar una venta que quedó con un intento de pago sin resultado ----

    @Test
    void elReintentoConLaMismaClaveValeAunqueLaReservaHayaVencido() {
        // El primer intento terminó en 504 y dejó la marca. El reintento llega con la reserva ya vencida.
        VentaDto pagada = ventaPagada();
        Venta conEsteIntento = ventaConPagoSinResultado(CLAVE_DEL_PAGO, true);
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(conEsteIntento));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(true);
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO)).thenReturn(pagada);
        compras.enCurso.put(VENTA, instancia);

        // La marca retiene el cupo: nadie pudo cancelar ni vencer la venta, así que confirmarla es seguro.
        assertSame(pagada, compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        // El UPDATE de la marca se hace igual: es el que confirma, en la base, que la clave sigue anotada.
        verify(ventas).guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR);
        assertFalse(compras.enCurso.containsKey(VENTA));
    }

    @Test
    void conUnPagoSinResultadoYSinInstanciaNoSeLeDiceAlCompradorQueEmpieceDeNuevo() {
        // Es lo que pasa si se redespliega Ventas después de un 504: la marca quedó y los titulares se perdieron.
        Venta conEsteIntento = ventaConPagoSinResultado(CLAVE_DEL_PAGO, false);
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(conEsteIntento));

        ErroresDeNegocio.Conflicto conLaMismaClave = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));
        ErroresDeNegocio.Conflicto conOtraClave = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, OTRA_CLAVE_DE_PAGO));

        esPagoSinResultado(conLaMismaClave);
        esPagoSinResultado(conOtraClave);
        // No se cobra ni se toca la marca: es el rastro para resolverla a mano.
        verify(ventas, never()).guardarClaveDePago(anyString(), anyString(), any());
        verify(ventas, never()).borrarClaveDePago(anyString(), anyString());
    }

    @Test
    void conUnPagoSinResultadoYLaReservaVencidaOtraClaveNoPagaNiSeInvitaAComprarDeNuevo() {
        Venta conOtroIntento = ventaConPagoSinResultado(OTRA_CLAVE_DE_PAGO, true);
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(conOtroIntento));
        compras.enCurso.put(VENTA, instancia);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        esPagoSinResultado(error);
        verify(ventas, never()).guardarClaveDePago(anyString(), anyString(), any());
        verifyNoInteractions(instancia);
        // La instancia se conserva: el reintento con la clave anotada todavía puede cerrar la venta.
        assertSame(instancia, compras.enCurso.get(VENTA));
    }

    @Test
    void siEnUnReintentoLaInstanciaDiceReservaVencidaElCompradorRecibePagoSinResultado() {
        // La instancia no reconoce la clave (no llegó a mandarla a cobrar) y encuentra la reserva vencida.
        Venta conEsteIntento = ventaConPagoSinResultado(CLAVE_DEL_PAGO, true);
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(conEsteIntento));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(true);
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO)).thenThrow(ServicioDeVentas.reservaVencida());
        compras.enCurso.put(VENTA, instancia);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        esPagoSinResultado(error);
        verify(ventas, never()).borrarClaveDePago(anyString(), anyString());
    }

    // ---- Pagar cuando se sabe que el pedido no llegó a cobrarse ----

    @Test
    void siElPedidoSeFrenoAntesDeCobrarSeQuitaLaMarcaYElCompradorRecibeElMismo504() {
        // Por ejemplo, Pagos no está desplegado: se sabe que no se cobró.
        CobroNoRealizado sinCobrar = new CobroNoRealizado("El servicio de pagos no responde. Probá de nuevo.");
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(true);
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO)).thenThrow(sinCobrar);
        compras.enCurso.put(VENTA, instancia);

        ErroresDeNegocio.SinRespuesta error = assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        assertSame(sinCobrar, error);
        assertEquals("sin-respuesta", error.tipo());
        // Sin la marca, la venta se puede cancelar o vencer: no queda trabada por una caída de Pagos.
        verify(ventas).borrarClaveDePago(VENTA, CLAVE_DEL_PAGO);
        // La instancia sigue en el índice: cuando Pagos vuelva, se paga sobre la misma compra.
        assertSame(instancia, compras.enCurso.get(VENTA));
    }

    @Test
    void enUnReintentoUnPedidoQueNoLlegoACobrarNoQuitaLaMarcaDelIntentoAnterior() {
        // De este pedido se sabe que no cobró; del anterior con la misma clave, no.
        Venta conEsteIntento = ventaConPagoSinResultado(CLAVE_DEL_PAGO, false);
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(conEsteIntento));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(true);
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO))
                .thenThrow(new CobroNoRealizado("El servicio de pagos no responde. Probá de nuevo."));
        compras.enCurso.put(VENTA, instancia);

        assertThrows(CobroNoRealizado.class, () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        verify(ventas, never()).borrarClaveDePago(anyString(), anyString());
        assertSame(instancia, compras.enCurso.get(VENTA));
    }

    @Test
    void siLaReservaVenceJustoAntesDeCobrarSeQuitaLaMarcaQueSeAcabaDeAnotar() {
        // La marca se anotó con la reserva vigente y la instancia, un instante después, ya la encontró vencida.
        ErroresDeNegocio.Conflicto vencida = ServicioDeVentas.reservaVencida();
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(true);
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO)).thenThrow(vencida);
        compras.enCurso.put(VENTA, instancia);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        assertSame(vencida, error);
        // La instancia revisa la reserva antes de cobrar: no se cobró, y sin la marca la venta puede vencer.
        verify(ventas).borrarClaveDePago(VENTA, CLAVE_DEL_PAGO);
    }

    @Test
    void mientrasSePagaNoEntraOtraLlamadaALaInstanciaDeEsaCompra() {
        VentaDto pagada = ventaPagada();
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(true);
        compras.enCurso.put(VENTA, instancia);
        AtomicReference<ErroresDeNegocio.Conflicto> otroPago = new AtomicReference<>();
        AtomicReference<ErroresDeNegocio.Conflicto> cancelacion = new AtomicReference<>();
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO)).thenAnswer(llamada -> {
            // El cobro está en curso y llegan un doble clic en "pagar" y un "cancelar" para la misma compra.
            otroPago.set(assertThrows(ErroresDeNegocio.Conflicto.class,
                    () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO)));
            cancelacion.set(assertThrows(ErroresDeNegocio.Conflicto.class, () -> compras.cancelar(COMPRADOR, VENTA)));
            return pagada;
        });

        assertSame(pagada, compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        assertEquals("operacion-en-curso", otroPago.get().tipo());
        assertEquals("operacion-en-curso", cancelacion.get().tipo());
        // En el servidor, una llamada simultánea rechazada por el stateful haría que se descarte la instancia.
        verify(instancia, times(1)).confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO);
        verify(instancia, never()).cancelarCompra();
        // Los pedidos frenados no anotaron ni quitaron la marca, ni cancelaron la venta.
        verify(ventas, times(1)).guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR);
        verify(ventas, never()).borrarClaveDePago(anyString(), anyString());
        verify(ventas, never()).cancelar(anyString());
    }

    @Test
    void siElServidorIgualRechazaElPagoPorSimultaneoEsOperacionEnCursoYLaMarcaNoSeToca() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaEnCheckout(COMPRADOR)));
        when(ventas.guardarClaveDePago(VENTA, CLAVE_DEL_PAGO, AL_PAGAR)).thenReturn(true);
        when(instancia.confirmarCompra(CON_TARJETA, CLAVE_DEL_PAGO))
                .thenThrow(new ConcurrentAccessTimeoutException("la instancia está ocupada"));
        compras.enCurso.put(VENTA, instancia);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> compras.pagar(COMPRADOR, VENTA, CON_TARJETA, CLAVE_DEL_PAGO));

        assertEquals("operacion-en-curso", error.tipo());
        verify(ventas, never()).borrarClaveDePago(anyString(), anyString());
    }

    // ---- Entradas ----

    @Test
    void laEntradaSoloLaVeQuienLaCompro() {
        Venta venta = ventaEnCheckout(COMPRADOR);
        Entrada entrada = new Entrada("T-9c41d07e", venta.getItems().get(0), "Ana Gómez", "30123456", 1);
        when(entradas.buscarPorId("T-9c41d07e")).thenReturn(Optional.of(entrada));

        EntradaDto delComprador = compras.obtenerEntrada(COMPRADOR, "T-9c41d07e");

        assertEquals(new EntradaDto("T-9c41d07e", VENTA, "ev-1", "sec-1-4", "Platea B",
                new TitularDto("Ana Gómez", "30123456"), 27_500, "PENDIENTE_EMISION", null, null, Map.of()),
                delComprador);

        // Para otro usuario es lo mismo que una entrada que no existe.
        ErroresDeNegocio.NoEncontrado ajena = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> compras.obtenerEntrada(OTRO_COMPRADOR, "T-9c41d07e"));
        ErroresDeNegocio.NoEncontrado inexistente = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> compras.obtenerEntrada(COMPRADOR, "T-noexiste"));
        assertEquals(inexistente.getMessage(), ajena.getMessage());
    }

    @Test
    void olvidarQuitaLaReferenciaDeUnaCompraQueTermino() {
        compras.enCurso.put(VENTA, instancia);

        compras.olvidar(VENTA);
        compras.olvidar("V-noestaba"); // no falla si no estaba

        assertTrue(compras.enCurso.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------

    /** Una venta real, recién abierta: dos entradas de Platea B, total 60500. */
    private static Venta ventaEnCheckout(String compradorId) {
        Venta venta = new Venta(VENTA, compradorId, "ev-1", CLAVE, AHORA, AHORA.plusMinutes(10));
        venta.agregarItem("sec-1-4", "Platea B", 27_500, 2);
        return venta;
    }

    /**
     * Una venta del comprador en otro estado. El estado y la clave de pago no
     * tienen "set": se simulan con un doble. Como es un doble, su reserva
     * nunca figura vencida.
     */
    private static Venta ventaEnEstado(EstadoVenta estado) {
        Venta venta = mock(Venta.class);
        when(venta.getId()).thenReturn(VENTA);
        when(venta.getCompradorId()).thenReturn(COMPRADOR);
        when(venta.getEstado()).thenReturn(estado);
        when(venta.estaPaga()).thenReturn(estado == EstadoVenta.CONFIRMADA || estado == EstadoVenta.EMITIDA);
        // Lo que hace falta para convertirla en la respuesta de la API.
        when(venta.getFacturaEstado()).thenReturn(EstadoFactura.NO_APLICA);
        when(venta.getVenceReserva()).thenReturn(AHORA.plusMinutes(10));
        return venta;
    }

    /**
     * Una venta abierta que quedó marcada con la clave de un intento de pago
     * del que no se supo el resultado, con la reserva vencida o todavía vigente.
     */
    private static Venta ventaConPagoSinResultado(String claveAnotada, boolean reservaVencida) {
        Venta venta = ventaEnEstado(EstadoVenta.EN_CHECKOUT);
        when(venta.getClavePago()).thenReturn(claveAnotada);
        when(venta.reservaVencida(any())).thenReturn(reservaVencida);
        return venta;
    }

    /** El 409 de una venta con un pago sin resultado que ya no se puede pagar: avisa que no se pague de nuevo. */
    private static void esPagoSinResultado(ErroresDeNegocio.Conflicto error) {
        assertEquals("operacion-en-curso", error.tipo());
        assertTrue(error.getMessage().contains("No pagues de nuevo"), error.getMessage());
    }

    /** Lo que devuelve la instancia de la compra cuando el pago se aprueba. */
    private static VentaDto ventaPagada() {
        return new VentaDto(VENTA, "CONFIRMADA", "NO_APLICA", "ev-1", List.of(), 55_000, 5_500, 60_500,
                "2026-10-19T21:00:00Z", "2026-10-19T21:10:00Z", "P-0a1b2c3d", List.of(), Map.of());
    }

    private static Usuario usuarioConIdentidad(boolean verificada) {
        return new Usuario(COMPRADOR, "Ana", "Gómez", "30123456", "titular@palco.test", "user", verificada);
    }
}
