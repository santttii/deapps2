package com.palco.arca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Las reglas del ARCA simulado, llamando a la clase directamente: la
 * numeración, el pedido repetido, los rechazos y el modo sin respuesta. Cada
 * test usa un talonario nuevo.
 */
class ArcaSimuladoTest {

    private static final int FACTURA_B = 6;
    private static final int FACTURA_C = 11;

    private final Talonario talonario = new Talonario();
    private final ArcaSimulado arca = new ArcaSimulado(talonario);

    @AfterEach
    void apagarElModoSinRespuesta() {
        System.clearProperty(ArcaSimulado.PROPIEDAD_SIN_RESPUESTA);
    }

    @Test
    void elPrimerComprobanteSeApruebaConElNumeroUnoYUnCaeDeCatorceDigitos() {
        RespuestaCae respuesta = arca.solicitarCae(FACTURA_B, 1, 60_500, "20261016", "V-3f9a1c2b");

        assertEquals(RespuestaCae.APROBADO, respuesta.getResultado());
        assertEquals(1, respuesta.getNumeroComprobante());
        assertTrue(respuesta.getCae().matches("\\d{14}"), respuesta.getCae());
        // Diez días desde la fecha del comprobante, en aaaammdd.
        assertEquals("20261026", respuesta.getVencimientoCae());
        assertNull(respuesta.getObservacion());
    }

    @Test
    void laNumeracionEsCorrelativaPorPuntoDeVentaYTipo() {
        assertEquals(1, numero(FACTURA_B, 1, "V-1"));
        assertEquals(2, numero(FACTURA_B, 1, "V-2"));
        // Otro punto de venta y otro tipo llevan su propia cuenta.
        assertEquals(1, numero(FACTURA_B, 2, "V-3"));
        assertEquals(1, numero(FACTURA_C, 1, "V-4"));
        assertEquals(3, numero(FACTURA_B, 1, "V-5"));
    }

    @Test
    void pedirOtraVezElCaeDeLaMismaVentaDevuelveElMismoComprobanteSinNumerarOtro() {
        RespuestaCae primero = arca.solicitarCae(FACTURA_B, 1, 60_500, "20261016", "V-3f9a1c2b");
        RespuestaCae repetido = arca.solicitarCae(FACTURA_B, 1, 60_500, "20261016", "V-3f9a1c2b");

        assertEquals(primero.getNumeroComprobante(), repetido.getNumeroComprobante());
        assertEquals(primero.getCae(), repetido.getCae());
        assertEquals(1, talonario.ultimoNumero(FACTURA_B, 1));
        // La venta siguiente sigue con el 2: el repetido no salteó ningún número.
        assertEquals(2, numero(FACTURA_B, 1, "V-otra"));
    }

    @Test
    void unPedidoInvalidoSeRechazaConUnaObservacionYNoConsumeNumero() {
        List<RespuestaCae> rechazos = List.of(
                arca.solicitarCae(2, 1, 60_500, "20261016", "V-1"),                  // tipo inexistente
                arca.solicitarCae(FACTURA_B, 0, 60_500, "20261016", "V-1"),          // punto de venta 0
                arca.solicitarCae(FACTURA_B, 99_999, 60_500, "20261016", "V-1"),     // fuera de rango
                arca.solicitarCae(FACTURA_B, 1, 0, "20261016", "V-1"),               // sin importe
                arca.solicitarCae(FACTURA_B, 1, -10, "20261016", "V-1"),
                arca.solicitarCae(FACTURA_B, 1, 60_500, "2026-10-16", "V-1"),        // otro formato
                arca.solicitarCae(FACTURA_B, 1, 60_500, "20261332", "V-1"),          // día que no existe
                arca.solicitarCae(FACTURA_B, 1, 60_500, null, "V-1"),
                arca.solicitarCae(FACTURA_B, 1, 60_500, "20261016", " "),            // sin venta
                arca.solicitarCae(FACTURA_B, 1, 60_500, "20261016", "V-" + "x".repeat(39)));

        for (RespuestaCae rechazo : rechazos) {
            assertEquals(RespuestaCae.RECHAZADO, rechazo.getResultado());
            assertEquals(0, rechazo.getNumeroComprobante());
            assertNull(rechazo.getCae());
            assertTrue(rechazo.getObservacion() != null && !rechazo.getObservacion().isBlank());
        }
        assertEquals(0, talonario.ultimoNumero(FACTURA_B, 1));
    }

    @Test
    void enElModoSinRespuestaEsperaYFallaSinNumerar() {
        arca.esperaSinRespuesta = Duration.ofMillis(200);
        System.setProperty(ArcaSimulado.PROPIEDAD_SIN_RESPUESTA, "true");

        long inicio = System.nanoTime();
        assertThrows(IllegalStateException.class,
                () -> arca.solicitarCae(FACTURA_B, 1, 60_500, "20261016", "V-3f9a1c2b"));

        assertTrue(Duration.ofNanos(System.nanoTime() - inicio).toMillis() >= 200, "no esperó antes de fallar");
        assertEquals(0, talonario.ultimoNumero(FACTURA_B, 1));
    }

    @Test
    void laPropiedadSeLeeEnCadaPedidoAsiQueSeApagaSinRedesplegar() {
        arca.esperaSinRespuesta = Duration.ZERO;
        System.setProperty(ArcaSimulado.PROPIEDAD_SIN_RESPUESTA, "true");
        assertThrows(IllegalStateException.class,
                () -> arca.solicitarCae(FACTURA_B, 1, 60_500, "20261016", "V-3f9a1c2b"));

        System.clearProperty(ArcaSimulado.PROPIEDAD_SIN_RESPUESTA);

        // El mismo pedido, repetido como lo haría el broker, ahora se aprueba con el número 1.
        assertEquals(1, numero(FACTURA_B, 1, "V-3f9a1c2b"));
    }

    @Test
    void conCualquierOtroValorLaPropiedadNoCambiaNada() {
        System.setProperty(ArcaSimulado.PROPIEDAD_SIN_RESPUESTA, "si");

        assertEquals(1, numero(FACTURA_B, 1, "V-3f9a1c2b"));
    }

    @Test
    void pedidosSimultaneosNoSeLlevanElMismoNumero() throws Exception {
        int pedidos = 60;
        ExecutorService hilos = Executors.newFixedThreadPool(8);
        try {
            List<Future<Long>> numeros = new ArrayList<>();
            for (int i = 0; i < pedidos; i++) {
                String venta = "V-" + i;
                numeros.add(hilos.submit(() -> numero(FACTURA_B, 1, venta)));
            }
            Set<Long> distintos = new TreeSet<>();
            for (Future<Long> numero : numeros) {
                distintos.add(numero.get());
            }
            // Del 1 al 60, sin repetidos ni huecos.
            assertEquals(pedidos, distintos.size());
            assertEquals(1L, ((TreeSet<Long>) distintos).first());
            assertEquals((long) pedidos, ((TreeSet<Long>) distintos).last());
        } finally {
            hilos.shutdownNow();
        }
    }

    private long numero(int tipo, int puntoDeVenta, String ventaId) {
        RespuestaCae respuesta = arca.solicitarCae(tipo, puntoDeVenta, 1_000, "20261016", ventaId);
        assertEquals(RespuestaCae.APROBADO, respuesta.getResultado(), respuesta.getObservacion());
        return respuesta.getNumeroComprobante();
    }
}
