package com.palco.reventa.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.ReglasDeNegocio;
import com.palco.reventa.api.Dtos.CompraDto;
import com.palco.reventa.api.Dtos.CompraHechaDto;
import com.palco.reventa.api.Dtos.NuevaPublicacionDto;
import com.palco.reventa.api.Dtos.PublicacionDto;
import com.palco.reventa.api.Dtos.TitularDto;
import com.palco.reventa.datos.PublicacionRepository;
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
        relojALas(LocalDateTime.of(2026, 11, 20, 12, 0));
        when(eventos.fechaDelEvento("ev-1")).thenReturn(EVENTO);
        when(ventas.consultar("T-1", VENDEDOR)).thenReturn(entrada("VALIDA"));
    }

    private void relojALas(LocalDateTime enArgentina) {
        servicio.reloj = Clock.fixed(enArgentina.atZone(ARGENTINA).toInstant(), ARGENTINA);
    }

    private static ClienteDeVentas.Entrada entrada(String estado) {
        return new ClienteDeVentas.Entrada("T-1", "V-1", "ev-1", "sec-1", "Campo", PAGADO, estado);
    }

    private static Publicacion publicacion(long precio) {
        return new Publicacion("R-1", "T-1", VENDEDOR, "ev-1", "sec-1", "Campo", PAGADO, precio,
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
    void unaEntradaQueNoEstaValidaNoSePublica() {
        when(ventas.consultar("T-1", VENDEDOR)).thenReturn(entrada("USADA"));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.publicar(VENDEDOR, new NuevaPublicacionDto("T-1", 50_000L)));

        assertEquals(TiposDeProblema.ENTRADA_NO_DISPONIBLE, error.tipo());
        verify(ventas, never()).publicar(anyString(), anyString());
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

    @Test
    void siLaTransferenciaFallaConElCobroHechoLaPublicacionVuelveAActiva() {
        publicacionActivaTomable();
        when(pagos.cobrar(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new ClienteDePagos.Cobro("P-1", true, null));
        when(ventas.transferir(anyString(), anyString(), anyString(), anyString(), any(), anyLong(), anyString()))
                .thenThrow(new ErroresDeNegocio.SinRespuesta("Ventas no responde"));

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> servicio.comprar(COMPRADOR, "R-1", compra(), CLAVE));

        verify(publicaciones).liberar("R-1", CLAVE);
        verify(publicaciones, never()).marcarVendida(anyString(), anyString(), anyString(), anyString(), anyString(),
                any());
    }

    @Test
    void sinIdempotencyKeyOConUnDniInvalidoEsDatosInvalidos() {
        assertThrows(ErroresDeNegocio.DatosInvalidos.class, () -> servicio.comprar(COMPRADOR, "R-1", compra(), null));
        CompraDto conDniMalo = new CompraDto(new TitularDto("Bruno Díaz", "40.111.222"), "tarjeta", "tok_test");
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.comprar(COMPRADOR, "R-1", conDniMalo, CLAVE));
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
