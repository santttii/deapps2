package com.palco.reventa.negocio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import jakarta.ejb.DependsOn;
import jakarta.ejb.EJBException;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.ReglasDeNegocio;
import com.palco.reventa.datos.PublicacionRepository;
import com.palco.reventa.dominio.Publicacion;
import com.palco.reventa.negocio.ConciliacionDeReventas.Desenlace;
import com.palco.reventa.negocio.externos.ClienteDePagos;
import com.palco.reventa.negocio.externos.ClienteDeVentas;

/**
 * La conciliación de las compras de reventa que no terminaron, sin servidor:
 * el repositorio y los clientes de Pagos y Ventas son dobles y el reloj está
 * fijo. Que la toma no la retome otro y que liberar respete un reintento lo
 * comprueba {@code PublicacionesEnH2Test} contra la base.
 */
class ConciliacionDeReventasTest {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 11, 20, 15, 0);
    private static final LocalDateTime HASTA = AHORA.minusMinutes(ConciliacionDeReventas.MINUTOS_DE_MARGEN);
    private static final String CLAVE = "5d3c1f0e-8a4b-4c7d-9e2f-1a2b3c4d5e6f";

    private ConciliacionDeReventas conciliacion;
    private PublicacionRepository publicaciones;
    private ClienteDePagos pagos;
    private ClienteDeVentas ventas;
    private DevolucionDeCobros devoluciones;

    @BeforeEach
    void armar() {
        conciliacion = new ConciliacionDeReventas();
        publicaciones = mock(PublicacionRepository.class);
        pagos = mock(ClienteDePagos.class);
        ventas = mock(ClienteDeVentas.class);
        conciliacion.publicaciones = publicaciones;
        conciliacion.pagos = pagos;
        conciliacion.ventas = ventas;
        devoluciones = mock(DevolucionDeCobros.class);
        conciliacion.devoluciones = devoluciones;
        conciliacion.reloj = Clock.fixed(AHORA.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    /** Una publicación con una toma anotada hace 5 minutos, con o sin titular. */
    private static Publicacion tomada(String id, boolean conTitular) {
        Publicacion publicacion = mock(Publicacion.class);
        when(publicacion.getId()).thenReturn(id);
        when(publicacion.getEntradaId()).thenReturn("T-1");
        when(publicacion.getVendedorId()).thenReturn("U-vendedor");
        when(publicacion.getCompradorId()).thenReturn("U-comprador");
        when(publicacion.getClaveCompra()).thenReturn(CLAVE);
        when(publicacion.getPrecio()).thenReturn(55_000L);
        when(publicacion.getCompraIntentada()).thenReturn(AHORA.minusMinutes(5));
        when(publicacion.getTitularNombre()).thenReturn(conTitular ? "Luis Pérez" : null);
        when(publicacion.getTitularDni()).thenReturn(conTitular ? "25231472" : null);
        return publicacion;
    }

    private static ClienteDeVentas.Transferencia transferencia() {
        return new ClienteDeVentas.Transferencia("V-nueva", null);
    }

    @Test
    void conElPagoAprobadoTransfiereAlTitularDeLaTomaYLaMarcaVendida() {
        Publicacion publicacion = tomada("R-1", true);
        when(pagos.buscarCobro("R-1", CLAVE)).thenReturn(Optional.of(new ClienteDePagos.Cobro("P-1", true, null)));
        when(ventas.transferir(eq("R-1"), eq("T-1"), eq("U-vendedor"), eq("U-comprador"), any(), eq(55_000L), eq("P-1")))
                .thenReturn(transferencia());
        when(publicaciones.marcarVendida("R-1", "U-comprador", CLAVE, "V-nueva", "P-1", AHORA)).thenReturn(true);

        assertEquals(Desenlace.VENDIDA, conciliacion.conciliar(publicacion, AHORA, HASTA));

        ArgumentCaptor<ClienteDeVentas.Titular> titular = ArgumentCaptor.forClass(ClienteDeVentas.Titular.class);
        verify(ventas).transferir(anyString(), anyString(), anyString(), anyString(), titular.capture(), anyLong(),
                anyString());
        assertEquals(new ClienteDeVentas.Titular("Luis Pérez", "25231472"), titular.getValue());
        verify(publicaciones, never()).liberarConciliada(anyString(), anyString(), any());
    }

    @Test
    void sinNingunPagoConEsaClaveLaLiberaParaOtraCompra() {
        Publicacion publicacion = tomada("R-1", true);
        when(pagos.buscarCobro("R-1", CLAVE)).thenReturn(Optional.empty());
        when(publicaciones.liberarConciliada("R-1", CLAVE, HASTA)).thenReturn(true);

        assertEquals(Desenlace.LIBERADA, conciliacion.conciliar(publicacion, AHORA, HASTA));
        verify(ventas, never()).transferir(any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void conElPagoRechazadoTambienLaLibera() {
        Publicacion publicacion = tomada("R-1", true);
        when(pagos.buscarCobro("R-1", CLAVE)).thenReturn(Optional.of(new ClienteDePagos.Cobro("P-1", false, "fondos")));
        when(publicaciones.liberarConciliada("R-1", CLAVE, HASTA)).thenReturn(true);

        assertEquals(Desenlace.LIBERADA, conciliacion.conciliar(publicacion, AHORA, HASTA));
    }

    @Test
    void siElCompradorReintentoMientrasTantoNoSeLibera() {
        Publicacion publicacion = tomada("R-1", true);
        when(pagos.buscarCobro("R-1", CLAVE)).thenReturn(Optional.empty());
        when(publicaciones.liberarConciliada("R-1", CLAVE, HASTA)).thenReturn(false);

        assertEquals(Desenlace.CERRADA_POR_OTRO, conciliacion.conciliar(publicacion, AHORA, HASTA));
    }

    @Test
    void siPagosNoRespondeNoCambiaNada() {
        Publicacion publicacion = tomada("R-1", true);
        when(pagos.buscarCobro("R-1", CLAVE)).thenThrow(new ErroresDeNegocio.SinRespuesta("no responde"));

        assertEquals(Desenlace.SIN_RESPUESTA, conciliacion.conciliar(publicacion, AHORA, HASTA));
        verify(publicaciones, never()).liberarConciliada(anyString(), anyString(), any());
        verify(ventas, never()).transferir(any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void siVentasNoRespondeAlTransferirSeReintentaSinLiberar() {
        // Hay plata cobrada: no se libera la publicación hasta transferir la entrada.
        Publicacion publicacion = tomada("R-1", true);
        when(pagos.buscarCobro("R-1", CLAVE)).thenReturn(Optional.of(new ClienteDePagos.Cobro("P-1", true, null)));
        when(ventas.transferir(any(), any(), any(), any(), any(), anyLong(), any()))
                .thenThrow(new ErroresDeNegocio.SinRespuesta("Ventas no responde"));

        assertEquals(Desenlace.SIN_RESPUESTA, conciliacion.conciliar(publicacion, AHORA, HASTA));
        verify(publicaciones, never()).liberarConciliada(anyString(), anyString(), any());
        verify(publicaciones, never()).marcarVendida(any(), any(), any(), any(), any(), any());
    }

    @Test
    void siVentasRechazaLaTransferenciaAnotaElReembolsoLaAnulaYDevuelveElPago() {
        Publicacion publicacion = tomada("R-1", true);
        when(pagos.buscarCobro("R-1", CLAVE)).thenReturn(Optional.of(new ClienteDePagos.Cobro("P-1", true, null)));
        when(ventas.transferir(any(), any(), any(), any(), any(), anyLong(), any()))
                .thenThrow(new ErroresDeNegocio.Conflicto("publicacion-no-activa", "La entrada ya no está publicada."));

        assertEquals(Desenlace.ANULADA, conciliacion.conciliar(publicacion, AHORA, HASTA));

        long monto = 55_000 + ReglasDeNegocio.cargoServicio(55_000);
        InOrder orden = inOrder(devoluciones, publicaciones);
        orden.verify(devoluciones).anotar(eq("R-1"), eq("U-comprador"), eq("P-1"), eq(monto), anyString());
        orden.verify(publicaciones).anular("R-1", CLAVE, AHORA);
        orden.verify(devoluciones).intentar(eq("P-1"), eq("R-1"), anyString());
        verify(publicaciones, never()).liberarConciliada(anyString(), anyString(), any());
    }

    @Test
    void conElReembolsoYaAnotadoLaAnulaSinTransferirNiPedirloDeNuevo() {
        // La base falló justo después de anotarlo: la toma quedó, pero el pago ya se está devolviendo.
        Publicacion publicacion = tomada("R-1", true);
        when(pagos.buscarCobro("R-1", CLAVE)).thenReturn(Optional.of(new ClienteDePagos.Cobro("P-1", true, null)));
        when(devoluciones.estaAnotado("P-1")).thenReturn(true);

        assertEquals(Desenlace.ANULADA, conciliacion.conciliar(publicacion, AHORA, HASTA));

        verify(publicaciones).anular("R-1", CLAVE, AHORA);
        verify(ventas, never()).transferir(any(), any(), any(), any(), any(), anyLong(), any());
        verify(devoluciones, never()).intentar(anyString(), anyString(), anyString());
    }

    @Test
    void unCobroYaReembolsadoLaLiberaComoUnRechazo() {
        Publicacion publicacion = tomada("R-1", true);
        when(pagos.buscarCobro("R-1", CLAVE)).thenReturn(
                Optional.of(new ClienteDePagos.Cobro("P-1", false, ClienteDePagos.MOTIVO_REEMBOLSADO)));
        when(publicaciones.liberarConciliada("R-1", CLAVE, HASTA)).thenReturn(true);

        assertEquals(Desenlace.LIBERADA, conciliacion.conciliar(publicacion, AHORA, HASTA));
    }

    @Test
    void conElPagoAprobadoYSinTitularNoInventaNadaYLaDejaTomada() {
        Publicacion publicacion = tomada("R-1", false);
        when(pagos.buscarCobro("R-1", CLAVE)).thenReturn(Optional.of(new ClienteDePagos.Cobro("P-1", true, null)));

        assertEquals(Desenlace.SIN_TITULAR, conciliacion.conciliar(publicacion, AHORA, HASTA));
        verify(ventas, never()).transferir(any(), any(), any(), any(), any(), anyLong(), any());
        verify(publicaciones, never()).liberarConciliada(anyString(), anyString(), any());
    }

    @Test
    void siElReintentoDelCompradorLaVendioAntesNoEsUnError() {
        Publicacion publicacion = tomada("R-1", true);
        when(pagos.buscarCobro("R-1", CLAVE)).thenReturn(Optional.of(new ClienteDePagos.Cobro("P-1", true, null)));
        when(ventas.transferir(any(), any(), any(), any(), any(), anyLong(), any())).thenReturn(transferencia());
        when(publicaciones.marcarVendida(any(), any(), any(), any(), any(), any())).thenReturn(false);

        assertEquals(Desenlace.CERRADA_POR_OTRO, conciliacion.conciliar(publicacion, AHORA, HASTA));
    }

    @Test
    void laCorridaBuscaLasTomasConElMargenYUnaQueFallaNoFrenaALasDemas() {
        Publicacion primera = tomada("R-1", true);
        Publicacion segunda = tomada("R-2", true);
        when(publicaciones.buscarTomasSinCerrar(HASTA)).thenReturn(List.of(primera, segunda));
        when(pagos.buscarCobro("R-1", CLAVE)).thenThrow(new IllegalStateException("algo inesperado"));
        when(pagos.buscarCobro("R-2", CLAVE)).thenReturn(Optional.empty());
        when(publicaciones.liberarConciliada("R-2", CLAVE, HASTA)).thenReturn(true);

        conciliacion.conciliarComprasSinResultado();

        verify(publicaciones).liberarConciliada("R-2", CLAVE, HASTA);
    }

    @Test
    void siLaBaseNoRespondeLaCorridaSeCortaSinLanzar() {
        when(publicaciones.buscarTomasSinCerrar(any())).thenThrow(new EJBException("la base no responde"));

        assertDoesNotThrow(conciliacion::conciliarComprasSinResultado);
    }

    @Test
    void esUnSingletonQueCorreCadaMinutoEnElSegundo45SinTransaccionPropia() throws Exception {
        assertNotNull(ConciliacionDeReventas.class.getAnnotation(Singleton.class));
        assertArrayEquals(new String[] { "MigracionesReventa" },
                ConciliacionDeReventas.class.getAnnotation(DependsOn.class).value());
        assertEquals(TransactionAttributeType.NOT_SUPPORTED,
                ConciliacionDeReventas.class.getAnnotation(TransactionAttribute.class).value());
        Schedule agenda = ConciliacionDeReventas.class.getDeclaredMethod("conciliarComprasSinResultado")
                .getAnnotation(Schedule.class);
        assertEquals("45", agenda.second());
        assertFalse(agenda.persistent());
    }

    @Test
    void losReembolsosPendientesSeReintentanCadaMinutoEnElSegundo15() throws Exception {
        Schedule agenda = ConciliacionDeReventas.class.getDeclaredMethod("reintentarReembolsos")
                .getAnnotation(Schedule.class);
        assertEquals("15", agenda.second());
        assertEquals("*", agenda.minute());
        assertFalse(agenda.persistent());

        conciliacion.reintentarReembolsos();
        verify(devoluciones).reintentarPendientes();
    }
}
