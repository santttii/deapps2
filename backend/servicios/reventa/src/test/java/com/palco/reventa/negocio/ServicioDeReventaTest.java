package com.palco.reventa.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.ReglasDeNegocio;
import com.palco.reventa.api.Dtos.CompraDto;
import com.palco.reventa.api.Dtos.CompraHechaDto;
import com.palco.reventa.api.Dtos.NuevaPublicacionDto;
import com.palco.reventa.api.Dtos.PublicacionDto;
import com.palco.reventa.api.Dtos.TitularDto;
import com.palco.reventa.datos.PublicacionRepository;
import com.palco.reventa.dominio.EstadoPublicacion;
import com.palco.reventa.dominio.Publicacion;
import com.palco.reventa.negocio.externos.ClienteDeEventos;
import com.palco.reventa.negocio.externos.ClienteDePagos;
import com.palco.reventa.negocio.externos.ClienteDeVentas;
import com.palco.reventa.negocio.externos.PedidoNoEnviado;

/**
 * Las reglas de la reventa sin servidor: los clientes de Ventas, Pagos y
 * Eventos y el repositorio son dobles. El reloj está fijo el 20/11/2026 a las
 * 12:00 de Argentina; el evento es ese mismo día a las 21:00.
 */
class ServicioDeReventaTest {

    private static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final LocalDateTime EVENTO = LocalDateTime.of(2026, 11, 20, 21, 0);
    private static final String VENDEDOR = "U-vendedor";
    private static final String COMPRADOR = "U-comprador";
    private static final String CLAVE = "5d3c1f0e-8a4b-4c7d-9e2f-1a2b3c4d5e6f";
    private static final long PAGADO = 50_000;

    private ServicioDeReventa servicio;
    private PublicacionRepository publicaciones;
    private ClienteDeVentas ventas;
    private ClienteDePagos pagos;
    private ClienteDeEventos eventos;
    private DevolucionDeCobros devoluciones;

    @BeforeEach
    void armar() {
        servicio = new ServicioDeReventa();
        publicaciones = mock(PublicacionRepository.class);
        ventas = mock(ClienteDeVentas.class);
        pagos = mock(ClienteDePagos.class);
        eventos = mock(ClienteDeEventos.class);
        servicio.publicaciones = publicaciones;
        servicio.ventas = ventas;
        servicio.pagos = pagos;
        servicio.eventos = eventos;
        devoluciones = mock(DevolucionDeCobros.class);
        servicio.devoluciones = devoluciones;
        relojALas(LocalDateTime.of(2026, 11, 20, 12, 0));
        when(eventos.fechaDelEvento("ev-1")).thenReturn(EVENTO);
        // Una entrada de la primera venta: el precio de lista del sector es lo que se pagó.
        when(eventos.precioDelSector("ev-1", "sec-1")).thenReturn(PAGADO);
        when(ventas.consultar("T-1", VENDEDOR)).thenReturn(entrada("VALIDA"));
    }

    private void relojALas(LocalDateTime enArgentina) {
        servicio.reloj = Clock.fixed(enArgentina.atZone(ARGENTINA).toInstant(), ARGENTINA);
    }

    private static ClienteDeVentas.Entrada entrada(String estado) {
        return new ClienteDeVentas.Entrada("T-1", "V-1", "ev-1", "sec-1", "Campo", PAGADO, estado);
    }

    private static Publicacion publicacion(long precio) {
        return new Publicacion("R-1", "T-1", VENDEDOR, "ev-1", "sec-1", "Campo", PAGADO, PAGADO, precio,
                LocalDateTime.of(2026, 11, 19, 15, 0));
    }

    private static CompraDto compra() {
        return new CompraDto(new TitularDto("Bruno Díaz", "40111222"), "tarjeta", "tok_test");
    }

    private static ClienteDeVentas.Transferencia transferencia() {
        return new ClienteDeVentas.Transferencia("V-nueva", new ClienteDeVentas.EntradaNueva("T-nueva", "ev-1",
                "sec-1", "Campo", new ClienteDeVentas.Titular("Bruno Díaz", "40111222"), 55_000, "VALIDA", "qr-nuevo"));
    }

    // ---- Publicar ----

    @Test
    void publicaDentroDelRangoConElTopeCalculado() {
        PublicacionDto publicada = servicio.publicar(VENDEDOR, new NuevaPublicacionDto("T-1", 55_000L));

        assertEquals("ACTIVA", publicada.estado());
        assertEquals(55_000, publicada.precio());
        assertEquals(ReglasDeNegocio.topeReventa(PAGADO), publicada.precioMaximo());
        assertEquals("2026-11-20T15:00:00Z", publicada.publicada());
        verify(ventas).publicar("T-1", VENDEDOR);
        verify(publicaciones).guardar(any());
    }

    @Test
    void losBordesDelRangoSeAceptanYUnPesoAfueraNo() {
        long minimo = ReglasDeNegocio.minimoReventa(PAGADO);
        long maximo = ReglasDeNegocio.topeReventa(PAGADO);
        servicio.publicar(VENDEDOR, new NuevaPublicacionDto("T-1", minimo));
        servicio.publicar(VENDEDOR, new NuevaPublicacionDto("T-1", maximo));

        for (long precio : new long[] {minimo - 1, maximo + 1}) {
            ErroresDeNegocio.ReglaIncumplida error = assertThrows(ErroresDeNegocio.ReglaIncumplida.class,
                    () -> servicio.publicar(VENDEDOR, new NuevaPublicacionDto("T-1", precio)));
            assertEquals(TiposDeProblema.PRECIO_FUERA_DE_RANGO, error.tipo());
        }
    }

    @Test
    void unaEntradaRevendidaConservaElTopeYElMinimoDelPrecioDeLista() {
        // Se compró en la reventa al tope (55.000 sobre una lista de 50.000): Ventas la registra con ese precio
        // pagado. Si el rango saliera de ahí, el tope pasaría a 60.500 y el mínimo a 27.500.
        long pagadoEnLaReventa = ReglasDeNegocio.topeReventa(PAGADO);
        when(ventas.consultar("T-1", VENDEDOR)).thenReturn(
                new ClienteDeVentas.Entrada("T-1", "V-2", "ev-1", "sec-1", "Campo", pagadoEnLaReventa, "VALIDA"));

        PublicacionDto publicada = servicio.publicar(VENDEDOR, new NuevaPublicacionDto("T-1", pagadoEnLaReventa));
        assertEquals(ReglasDeNegocio.topeReventa(PAGADO), publicada.precioMaximo());

        ErroresDeNegocio.ReglaIncumplida error = assertThrows(ErroresDeNegocio.ReglaIncumplida.class,
                () -> servicio.publicar(VENDEDOR, new NuevaPublicacionDto("T-1", pagadoEnLaReventa + 1)));
        assertEquals(TiposDeProblema.PRECIO_FUERA_DE_RANGO, error.tipo());

        // El mínimo también es el de la lista: el 50% de 50.000, aunque se haya pagado 55.000.
        servicio.publicar(VENDEDOR, new NuevaPublicacionDto("T-1", ReglasDeNegocio.minimoReventa(PAGADO)));
        verify(ventas, times(2)).publicar("T-1", VENDEDOR);
    }

    @Test
    void siEventosNoRespondeNoSePublicaNadaComoAntes() {
        // Eventos caído: no contesta ni el precio del sector ni la fecha. Antes del arreglo, la fecha ya
        // cortaba la publicación con SinRespuesta (503) sin tocar Ventas; ahora corta el precio, igual.
        ErroresDeNegocio.SinRespuesta caido = new ErroresDeNegocio.SinRespuesta(
                "El servicio de eventos no responde. Probá de nuevo.");
        when(eventos.precioDelSector(anyString(), anyString())).thenThrow(caido);
        when(eventos.fechaDelEvento(anyString())).thenThrow(caido);

        ErroresDeNegocio.SinRespuesta error = assertThrows(ErroresDeNegocio.SinRespuesta.class,
                () -> servicio.publicar(VENDEDOR, new NuevaPublicacionDto("T-1", 55_000L)));

        assertEquals("El servicio de eventos no responde. Probá de nuevo.", error.getMessage());
        verify(ventas, never()).publicar(anyString(), anyString());
        verify(publicaciones, never()).guardar(any());
    }

    @Test
    void unaEntradaQueNoEstaValidaNoSePublica() {
        when(ventas.consultar("T-1", VENDEDOR)).thenReturn(entrada("USADA"));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.publicar(VENDEDOR, new NuevaPublicacionDto("T-1", 50_000L)));

        assertEquals(TiposDeProblema.ENTRADA_NO_DISPONIBLE, error.tipo());
        verify(ventas, never()).publicar(anyString(), anyString());
    }

    @Test
    void unaEntradaConUnIdQueNoEsUnIdNoSeLePreguntaAVentas() {
        // El id va en la ruta del pedido a Ventas: con una barra o un espacio podría apuntar a otra ruta.
        for (String id : new String[] {"../T-1", "T 1", "", null, "T-" + "x".repeat(39)}) {
            assertThrows(ErroresDeNegocio.NoEncontrado.class,
                    () -> servicio.publicar(VENDEDOR, new NuevaPublicacionDto(id, 50_000L)), "id: " + id);
        }

        verifyNoInteractions(ventas, eventos, publicaciones);
    }

    @Test
    void laReventaCierraTresHorasAntesDelEvento() {
        // A las 17:59 faltan 3 horas y 1 minuto: está abierta.
        relojALas(LocalDateTime.of(2026, 11, 20, 17, 59));
        servicio.publicar(VENDEDOR, new NuevaPublicacionDto("T-1", 50_000L));

        // A las 18:00 faltan justo 3 horas: ya cerró.
        relojALas(LocalDateTime.of(2026, 11, 20, 18, 0));
        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.publicar(VENDEDOR, new NuevaPublicacionDto("T-1", 50_000L)));
        assertEquals(TiposDeProblema.REVENTA_CERRADA, error.tipo());
    }

    @Test
    void siNoSeGuardaLaPublicacionLaEntradaSeRetira() {
        org.mockito.Mockito.doThrow(new IllegalStateException("base caída")).when(publicaciones).guardar(any());

        assertThrows(IllegalStateException.class,
                () -> servicio.publicar(VENDEDOR, new NuevaPublicacionDto("T-1", 50_000L)));

        verify(ventas).retirar("T-1", VENDEDOR);
    }

    // ---- Retirar ----

    @Test
    void retirarUnaAjenaEsNoEncontradaYUnaPropiaLaDevuelveAValida() {
        when(publicaciones.buscarPorId("R-1")).thenReturn(Optional.of(publicacion(50_000)));
        when(publicaciones.retirar(eq("R-1"), eq(VENDEDOR), any())).thenReturn(true);

        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> servicio.retirar("U-otro", "R-1"));
        servicio.retirar(VENDEDOR, "R-1");

        verify(ventas).retirar("T-1", VENDEDOR);
    }

    @Test
    void noSeRetiraUnaQueNoEstaActiva() {
        when(publicaciones.buscarPorId("R-1")).thenReturn(Optional.of(publicacion(50_000)));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.retirar(VENDEDOR, "R-1"));

        assertEquals(TiposDeProblema.PUBLICACION_NO_ACTIVA, error.tipo());
        verify(ventas, never()).retirar(anyString(), anyString());
    }

    // ---- Comprar ----

    private void publicacionActivaTomable() {
        when(publicaciones.buscarPorId("R-1")).thenReturn(Optional.of(publicacion(55_000)));
        when(publicaciones.tomar(eq("R-1"), eq(COMPRADOR), eq(CLAVE), any(), any(), any())).thenReturn(true);
    }

    @Test
    void comprarCobraPrecioMasCargoYTransfiere() {
        publicacionActivaTomable();
        long monto = 55_000 + ReglasDeNegocio.cargoServicio(55_000);
        when(pagos.cobrar("R-1", monto, "tarjeta", "tok_test", CLAVE))
                .thenReturn(new ClienteDePagos.Cobro("P-1", true, null));
        when(ventas.transferir(eq("R-1"), eq("T-1"), eq(VENDEDOR), eq(COMPRADOR), any(), eq(55_000L), eq("P-1")))
                .thenReturn(transferencia());
        when(publicaciones.marcarVendida(eq("R-1"), eq(COMPRADOR), eq(CLAVE), eq("V-nueva"), eq("P-1"), any()))
                .thenReturn(true);

        CompraHechaDto hecha = servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE);

        assertEquals("V-nueva", hecha.ventaId());
        // La toma anota el titular antes de cobrar: si la compra no termina, la conciliación lo usa.
        verify(publicaciones).tomar(eq("R-1"), eq(COMPRADOR), eq(CLAVE), eq("Bruno Díaz"), eq("40111222"), any());
        assertEquals("T-nueva", hecha.entrada().id());
        assertEquals("qr-nuevo", hecha.entrada().codigoQr());
        verify(publicaciones).marcarVendida(eq("R-1"), eq(COMPRADOR), eq(CLAVE), eq("V-nueva"), eq("P-1"), any());
        verify(publicaciones, never()).liberar(anyString(), anyString());
    }

    // ---- Comprar: el tope por evento y un DNI por evento, que controla Ventas (DEF-30) ----

    @Test
    void antesDeTomarYDeCobrarVentasControlaAlCompradorYAlTitular() {
        publicacionActivaTomable();
        when(pagos.cobrar(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new ClienteDePagos.Cobro("P-1", true, null));
        when(ventas.transferir(anyString(), anyString(), anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(transferencia());
        when(publicaciones.marcarVendida(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

        servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE);

        InOrder orden = inOrder(ventas, publicaciones, pagos);
        orden.verify(ventas).controlarCompra("T-1", VENDEDOR, COMPRADOR,
                new ClienteDeVentas.Titular("Bruno Díaz", "40111222"));
        orden.verify(publicaciones).tomar(eq("R-1"), eq(COMPRADOR), eq(CLAVE), any(), any(), any());
        orden.verify(pagos).cobrar(anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    private void ventasRechazaElControl(String tipo, String detalle) {
        org.mockito.Mockito.doThrow(new ErroresDeNegocio.Conflicto(tipo, detalle)).when(ventas)
                .controlarCompra(anyString(), anyString(), anyString(), any());
    }

    private void noSeTomoNiSeCobroNiSeTransfirio() {
        verify(publicaciones, never()).tomar(anyString(), anyString(), anyString(), any(), any(), any());
        verifyNoInteractions(pagos, devoluciones);
        verify(ventas, never()).transferir(anyString(), anyString(), anyString(), anyString(), any(), anyLong(),
                anyString());
    }

    @Test
    void unTitularQueYaTieneEntradaParaElEventoNoCompraEnLaReventa() {
        publicacionActivaTomable();
        String detalle = "El DNI 40111222 ya tiene una entrada para este evento. Cada persona puede tener una sola"
                + " entrada por evento.";
        ventasRechazaElControl(TiposDeProblema.DNI_CON_ENTRADA, detalle);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        assertEquals(TiposDeProblema.DNI_CON_ENTRADA, error.tipo());
        assertEquals(detalle, error.getMessage());
        noSeTomoNiSeCobroNiSeTransfirio();
    }

    @Test
    void unCompradorConSeisEntradasParaElEventoNoCompraOtraEnLaReventa() {
        publicacionActivaTomable();
        String detalle = "Cada persona puede tener hasta 6 entradas por evento, sumando todas sus compras. Ya tenés 6:"
                + " no podés comprar más para este evento.";
        ventasRechazaElControl(TiposDeProblema.TOPE_POR_EVENTO, detalle);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        assertEquals(TiposDeProblema.TOPE_POR_EVENTO, error.tipo());
        assertEquals(detalle, error.getMessage());
        noSeTomoNiSeCobroNiSeTransfirio();
    }

    @Test
    void siVentasNoRespondeAlControlarNoSeTomaNiSeCobra() {
        publicacionActivaTomable();
        org.mockito.Mockito.doThrow(new ErroresDeNegocio.SinRespuesta("Ventas no responde")).when(ventas)
                .controlarCompra(anyString(), anyString(), anyString(), any());

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        noSeTomoNiSeCobroNiSeTransfirio();
    }

    @Test
    void elReintentoDeUnaCompraQueYaTomoLaPublicacionNoSeVuelveAControlar() {
        // Pagos no respondió y la toma quedó: el cobro pudo haber salido. Aunque Ventas ahora dijera que no,
        // el reintento con la misma clave termina la compra en lugar de dejar el pago sin entrada.
        Publicacion tomada = mock(Publicacion.class);
        when(tomada.getId()).thenReturn("R-1");
        when(tomada.getEntradaId()).thenReturn("T-1");
        when(tomada.getVendedorId()).thenReturn(VENDEDOR);
        when(tomada.getEventoId()).thenReturn("ev-1");
        when(tomada.getPrecio()).thenReturn(55_000L);
        when(tomada.getEstado()).thenReturn(EstadoPublicacion.ACTIVA);
        when(tomada.getCompradorId()).thenReturn(COMPRADOR);
        when(tomada.getClaveCompra()).thenReturn(CLAVE);
        // La toma quedó a nombre del mismo DNI que trae el reintento (el de compra()).
        when(tomada.getTitularDni()).thenReturn("40111222");
        when(tomada.getPublicada()).thenReturn(LocalDateTime.of(2026, 11, 19, 15, 0));
        when(publicaciones.buscarPorId("R-1")).thenReturn(Optional.of(tomada));
        when(publicaciones.tomar(eq("R-1"), eq(COMPRADOR), eq(CLAVE), any(), any(), any())).thenReturn(true);
        ventasRechazaElControl(TiposDeProblema.TOPE_POR_EVENTO, "Ya tenés 6.");
        when(pagos.cobrar(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new ClienteDePagos.Cobro("P-1", true, null));
        when(ventas.transferir(anyString(), anyString(), anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(transferencia());
        when(publicaciones.marcarVendida(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

        CompraHechaDto hecha = servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE);

        assertEquals("V-nueva", hecha.ventaId());
        verify(ventas, never()).controlarCompra(anyString(), anyString(), anyString(), any());
    }

    @Test
    void elReintentoConOtroDniSeControlaYSiVentasDiceQueNoLaTomaQuedaComoEstaba() {
        // Mismo comprador y misma clave, pero a nombre de otro DNI: sin control, la entrada quedaría a nombre de
        // alguien que nadie miró. Con el 409 la toma no se toca: sigue con el titular anterior y la cierra un
        // reintento con ese titular o la conciliación.
        Publicacion tomada = mock(Publicacion.class);
        when(tomada.getId()).thenReturn("R-1");
        when(tomada.getEntradaId()).thenReturn("T-1");
        when(tomada.getVendedorId()).thenReturn(VENDEDOR);
        when(tomada.getEventoId()).thenReturn("ev-1");
        when(tomada.getEstado()).thenReturn(EstadoPublicacion.ACTIVA);
        when(tomada.getCompradorId()).thenReturn(COMPRADOR);
        when(tomada.getClaveCompra()).thenReturn(CLAVE);
        when(tomada.getTitularDni()).thenReturn("30999888");
        when(publicaciones.buscarPorId("R-1")).thenReturn(Optional.of(tomada));
        ventasRechazaElControl(TiposDeProblema.DNI_CON_ENTRADA, "El DNI 40111222 ya tiene una entrada.");

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        assertEquals(TiposDeProblema.DNI_CON_ENTRADA, error.tipo());
        verify(ventas).controlarCompra("T-1", VENDEDOR, COMPRADOR,
                new ClienteDeVentas.Titular("Bruno Díaz", "40111222"));
        noSeTomoNiSeCobroNiSeTransfirio();
        verify(publicaciones, never()).liberar(anyString(), anyString());
    }

    @Test
    void otroCompradorConLaMismaClaveNoEsUnReintentoYSeControla() {
        // La toma es de otra persona: para este comprador es una compra nueva, y se controla.
        Publicacion tomada = mock(Publicacion.class);
        when(tomada.getId()).thenReturn("R-1");
        when(tomada.getEntradaId()).thenReturn("T-1");
        when(tomada.getVendedorId()).thenReturn(VENDEDOR);
        when(tomada.getEventoId()).thenReturn("ev-1");
        when(tomada.getEstado()).thenReturn(EstadoPublicacion.ACTIVA);
        when(tomada.getCompradorId()).thenReturn("U-otro");
        when(tomada.getClaveCompra()).thenReturn(CLAVE);
        when(publicaciones.buscarPorId("R-1")).thenReturn(Optional.of(tomada));
        ventasRechazaElControl(TiposDeProblema.DNI_CON_ENTRADA, "El DNI 40111222 ya tiene una entrada.");

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        assertEquals(TiposDeProblema.DNI_CON_ENTRADA, error.tipo());
        noSeTomoNiSeCobroNiSeTransfirio();
    }

    @Test
    void nadieCompraSuPropiaPublicacion() {
        when(publicaciones.buscarPorId("R-1")).thenReturn(Optional.of(publicacion(55_000)));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.comprar(VENDEDOR, "R-1", compra(), CLAVE));

        assertEquals(TiposDeProblema.COMPRA_PROPIA, error.tipo());
    }

    @Test
    void siOtraCompraLaTomoNoSeCobra() {
        when(publicaciones.buscarPorId("R-1")).thenReturn(Optional.of(publicacion(55_000)));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        assertEquals(TiposDeProblema.PUBLICACION_NO_ACTIVA, error.tipo());
        verify(pagos, never()).cobrar(anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void conLaReventaCerradaNoSeCompra() {
        publicacionActivaTomable();
        relojALas(LocalDateTime.of(2026, 11, 20, 19, 0));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        assertEquals(TiposDeProblema.REVENTA_CERRADA, error.tipo());
    }

    @Test
    void unPagoRechazadoLiberaLaPublicacion() {
        publicacionActivaTomable();
        when(pagos.cobrar(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new ClienteDePagos.Cobro("P-1", false, "fondos_insuficientes"));

        assertThrows(ErroresDeNegocio.PagoRechazado.class, () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        verify(publicaciones).liberar("R-1", CLAVE);
        verify(ventas, never()).transferir(anyString(), anyString(), anyString(), anyString(), any(), anyLong(),
                anyString());
    }

    @Test
    void siPagosNoRespondeLaTomaQuedaYSiNoSeEnvioSeLibera() {
        publicacionActivaTomable();
        when(pagos.cobrar(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new ErroresDeNegocio.SinRespuesta("sin respuesta"));
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));
        verify(publicaciones, never()).liberar(anyString(), anyString());

        org.mockito.Mockito.doThrow(new PedidoNoEnviado("no salió")).when(pagos)
                .cobrar(anyString(), anyLong(), anyString(), anyString(), anyString());
        assertThrows(PedidoNoEnviado.class, () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));
        verify(publicaciones).liberar("R-1", CLAVE);
    }

    private void cobroAprobado() {
        publicacionActivaTomable();
        when(pagos.cobrar(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new ClienteDePagos.Cobro("P-1", true, null));
    }

    private void ventasRechazaLaTransferencia() {
        when(ventas.transferir(anyString(), anyString(), anyString(), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new ErroresDeNegocio.Conflicto(TiposDeProblema.PUBLICACION_NO_ACTIVA,
                        "La entrada ya no está publicada en la reventa."));
    }

    @Test
    void siVentasNoRespondeAlTransferirLaTomaQuedaYNoSeDevuelveNada() {
        // Ventas pudo haber transferido: con plata cobrada, la toma queda para el reintento o la conciliación.
        cobroAprobado();
        when(ventas.transferir(anyString(), anyString(), anyString(), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new ErroresDeNegocio.SinRespuesta("Ventas no responde"));

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        verify(publicaciones, never()).liberar(anyString(), anyString());
        verify(publicaciones, never()).anular(anyString(), anyString(), any());
        verify(devoluciones, never()).anotar(any(), any(), any(), anyLong(), any());
        verify(publicaciones, never()).marcarVendida(anyString(), anyString(), anyString(), anyString(), anyString(),
                any());
    }

    @Test
    void siVentasRechazaLaTransferenciaSeAnotaElReembolsoSeAnulaYSeDevuelveElPago() {
        cobroAprobado();
        ventasRechazaLaTransferencia();
        when(devoluciones.intentar(eq("P-1"), eq("R-1"), anyString())).thenReturn(ClienteDePagos.Devolucion.HECHA);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        assertEquals(TiposDeProblema.PUBLICACION_NO_ACTIVA, error.tipo());
        assertEquals("La entrada ya no está disponible. Te devolvimos el pago.", error.getMessage());
        // El orden importa: el reembolso queda anotado antes de anular, y recién después se le pide a Pagos.
        long monto = 55_000 + ReglasDeNegocio.cargoServicio(55_000);
        InOrder orden = inOrder(devoluciones, publicaciones);
        orden.verify(devoluciones).anotar(eq("R-1"), eq(COMPRADOR), eq("P-1"), eq(monto), anyString());
        orden.verify(publicaciones).anular(eq("R-1"), eq(CLAVE), any());
        orden.verify(devoluciones).intentar(eq("P-1"), eq("R-1"), anyString());
        verify(publicaciones, never()).liberar(anyString(), anyString());
    }

    @Test
    void siPagosNoRespondeAlDevolverLaCompraIgualSeAnulaYElPagoSeVaADevolver() {
        cobroAprobado();
        ventasRechazaLaTransferencia();
        when(devoluciones.intentar(anyString(), anyString(), anyString()))
                .thenReturn(ClienteDePagos.Devolucion.SIN_RESPUESTA);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        assertEquals("La entrada ya no está disponible. El pago se va a devolver.", error.getMessage());
        verify(publicaciones).anular(eq("R-1"), eq(CLAVE), any());
    }

    @Test
    void siLaBaseFallaAlAnotarElReembolsoLaPublicacionNoSeAnulaYQuedaTomada() {
        // Así la conciliación lo vuelve a intentar: no queda anulada con un cobro que nadie devuelve.
        cobroAprobado();
        ventasRechazaLaTransferencia();
        org.mockito.Mockito.doThrow(new IllegalStateException("la base no responde")).when(devoluciones)
                .anotar(any(), any(), any(), anyLong(), any());

        assertThrows(IllegalStateException.class, () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        verify(publicaciones, never()).anular(anyString(), anyString(), any());
        verify(publicaciones, never()).liberar(anyString(), anyString());
        verify(devoluciones, never()).intentar(anyString(), anyString(), anyString());
    }

    @Test
    void unPagoConElReembolsoAnotadoNoTransfiereLaEntrada() {
        cobroAprobado();
        when(devoluciones.estaAnotado("P-1")).thenReturn(true);

        assertThrows(ErroresDeNegocio.Conflicto.class, () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        verify(ventas, never()).transferir(anyString(), anyString(), anyString(), anyString(), any(), anyLong(),
                anyString());
        verify(publicaciones).anular(eq("R-1"), eq(CLAVE), any());
    }

    // ---- DEF-46: otra compra se cruzó entre el control y la transferencia ----

    private static final String YA_TENES_SEIS = "Cada persona puede tener hasta 6 entradas por evento, sumando todas"
            + " sus compras. Ya tenés 6: no podés comprar más para este evento.";

    @Test
    void siVentasNoTransfierePorElTopeSeDevuelveElPagoYLaPublicacionQuedaLibre() {
        cobroAprobado();
        when(ventas.transferir(anyString(), anyString(), anyString(), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new ClienteDeVentas.CompraFueraDeLasReglas(TiposDeProblema.TOPE_POR_EVENTO, YA_TENES_SEIS));
        when(devoluciones.intentar(eq("P-1"), eq("R-1"), anyString())).thenReturn(ClienteDePagos.Devolucion.HECHA);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        // El comprador recibe el error de la regla, con el texto de Ventas y lo que pasa con su pago.
        assertEquals(TiposDeProblema.TOPE_POR_EVENTO, error.tipo());
        assertEquals(YA_TENES_SEIS + " Te devolvimos el pago.", error.getMessage());
        // El reembolso se anota antes de soltar la publicación, con el motivo de una regla: la entrada sigue en la
        // reventa, así que la publicación queda libre para otra persona y no anulada.
        long monto = 55_000 + ReglasDeNegocio.cargoServicio(55_000);
        String motivo = DevolucionDeCobros.motivoPorRegla("T-1", TiposDeProblema.TOPE_POR_EVENTO);
        InOrder orden = inOrder(devoluciones, publicaciones);
        orden.verify(devoluciones).anotar("R-1", COMPRADOR, "P-1", monto, motivo);
        orden.verify(publicaciones).liberar("R-1", CLAVE);
        orden.verify(devoluciones).intentar("P-1", "R-1", motivo);
        verify(publicaciones, never()).anular(anyString(), anyString(), any());
        verify(publicaciones, never()).marcarVendida(anyString(), anyString(), anyString(), anyString(), anyString(),
                any());
    }

    @Test
    void siVentasNoTransfierePorElDniYPagosNoRespondeElPagoSeVaADevolver() {
        cobroAprobado();
        String yaTiene = "El DNI 40111222 ya tiene una entrada para este evento. Cada persona puede tener una sola"
                + " entrada por evento.";
        when(ventas.transferir(anyString(), anyString(), anyString(), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new ClienteDeVentas.CompraFueraDeLasReglas(TiposDeProblema.DNI_CON_ENTRADA, yaTiene));
        when(devoluciones.intentar(anyString(), anyString(), anyString()))
                .thenReturn(ClienteDePagos.Devolucion.SIN_RESPUESTA);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        assertEquals(TiposDeProblema.DNI_CON_ENTRADA, error.tipo());
        assertEquals(yaTiene + " El pago se va a devolver.", error.getMessage());
        verify(devoluciones).anotar(eq("R-1"), eq(COMPRADOR), eq("P-1"), anyLong(),
                eq(DevolucionDeCobros.motivoPorRegla("T-1", TiposDeProblema.DNI_CON_ENTRADA)));
        verify(publicaciones).liberar("R-1", CLAVE);
        verify(publicaciones, never()).anular(anyString(), anyString(), any());
    }

    @Test
    void unPagoConElReembolsoPorUnaReglaAnotadoLiberaLaPublicacionSinTransferir() {
        // Un reintento con la misma clave después del corte: el cobro se devuelve y la entrada sigue en la reventa.
        cobroAprobado();
        when(devoluciones.estaAnotado("P-1")).thenReturn(true);
        when(devoluciones.esPorReglaEntreCompras("P-1")).thenReturn(true);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        assertEquals(TiposDeProblema.PUBLICACION_NO_ACTIVA, error.tipo());
        assertEquals("Esta compra ya se anuló y el pago se devuelve. Para comprar de nuevo usá otra Idempotency-Key.",
                error.getMessage());
        verify(publicaciones).liberar("R-1", CLAVE);
        verify(publicaciones, never()).anular(anyString(), anyString(), any());
        verify(ventas, never()).transferir(anyString(), anyString(), anyString(), anyString(), any(), anyLong(),
                anyString());
    }

    @Test
    void unCobroYaReembolsadoPideOtraClaveSinTransferir() {
        publicacionActivaTomable();
        when(pagos.cobrar(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new ClienteDePagos.Cobro("P-1", false, ClienteDePagos.MOTIVO_REEMBOLSADO));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        assertEquals(TiposDeProblema.PUBLICACION_NO_ACTIVA, error.tipo());
        verify(publicaciones).liberar("R-1", CLAVE);
        verify(ventas, never()).transferir(anyString(), anyString(), anyString(), anyString(), any(), anyLong(),
                anyString());
    }

    @Test
    void sinIdempotencyKeyOConUnDniInvalidoEsDatosInvalidos() {
        assertThrows(ErroresDeNegocio.DatosInvalidos.class, () -> servicio.comprar(COMPRADOR, "R-1", compra(), null));
        CompraDto conDniMalo = new CompraDto(new TitularDto("Bruno Díaz", "40.111.222"), "tarjeta", "tok_test");
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.comprar(COMPRADOR, "R-1", conDniMalo, CLAVE));
    }

    @Test
    void unaPublicacionQueYaNoEstaActivaNoSeTomaNiSeCobra() {
        // Vendida a otra persona, retirada por el vendedor o anulada: no llega ni a la toma.
        for (EstadoPublicacion estado : new EstadoPublicacion[] {EstadoPublicacion.VENDIDA, EstadoPublicacion.RETIRADA,
                EstadoPublicacion.ANULADA}) {
            Publicacion cerrada = mock(Publicacion.class);
            when(cerrada.getId()).thenReturn("R-1");
            when(cerrada.getVendedorId()).thenReturn(VENDEDOR);
            when(cerrada.getEventoId()).thenReturn("ev-1");
            when(cerrada.getPrecio()).thenReturn(55_000L);
            when(cerrada.getEstado()).thenReturn(estado);
            when(cerrada.getCompradorId()).thenReturn("U-otro");
            when(publicaciones.buscarPorId("R-1")).thenReturn(Optional.of(cerrada));

            ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                    () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE), "estado: " + estado);

            assertEquals(TiposDeProblema.PUBLICACION_NO_ACTIVA, error.tipo());
            assertEquals("La publicación ya no está disponible.", error.getMessage());
        }

        verify(publicaciones, never()).tomar(anyString(), anyString(), anyString(), any(), any(), any());
        verifyNoInteractions(pagos);
    }

    @Test
    void unaIdempotencyKeyVaciaEnBlancoOConUnSaltoDeLineaEsDatosInvalidos() {
        for (String clave : new String[] {"", "   ", "clave\nmala", "clave\tmala"}) {
            ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                    () -> servicio.comprar(COMPRADOR, "R-1", compra(), clave), "clave: " + clave);
            assertTrue(error.campos().containsKey("Idempotency-Key"), "campos: " + error.campos());
        }

        // Se corta antes de buscar la publicación: sin clave no hay compra que reconocer en un reintento.
        verifyNoInteractions(publicaciones, pagos);
    }

    @Test
    void laIdempotencyKeyLlegaHasta64Caracteres() {
        // Con "R-xxxxxxxx:" adelante, 64 entran en las 80 de Pagos; una más ya no.
        String larga = "k".repeat(ServicioDeReventa.LARGO_MAXIMO_CLAVE + 1);
        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.comprar(COMPRADOR, "R-1", compra(), larga));
        assertTrue(error.campos().containsKey("Idempotency-Key"), "campos: " + error.campos());
        verifyNoInteractions(publicaciones);

        // Con 64 pasa la validación y la compra sigue: acá se corta porque la publicación no existe.
        String justa = "k".repeat(ServicioDeReventa.LARGO_MAXIMO_CLAVE);
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> servicio.comprar(COMPRADOR, "R-1", compra(), justa));
        verify(publicaciones).buscarPorId("R-1");
    }

    @Test
    void unTitularConUnSaltoDeLineaEnElNombreEsDatosInvalidos() {
        // El nombre va impreso en la entrada y en el log: tiene que ser una sola línea.
        CompraDto conSalto = new CompraDto(new TitularDto("Bruno\nDíaz", "40111222"), "tarjeta", "tok_test");

        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.comprar(COMPRADOR, "R-1", conSalto, CLAVE));

        assertTrue(error.campos().containsKey("titular.nombre"), "campos: " + error.campos());
        verifyNoInteractions(publicaciones, pagos);
    }

    @Test
    void unaPublicacionQueNoExisteEsNoEncontrada() {
        when(publicaciones.buscarPorId(anyString())).thenReturn(Optional.empty());
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> servicio.comprar(COMPRADOR, "R-9", compra(), CLAVE));
        // Un id con una barra no llega ni a la base: no puede apuntar a otra ruta.
        assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> servicio.comprar(COMPRADOR, "../R-1", compra(), CLAVE));
    }
}
