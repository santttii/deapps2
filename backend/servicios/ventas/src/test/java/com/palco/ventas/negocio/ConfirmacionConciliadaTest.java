package com.palco.ventas.negocio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.util.Optional;

import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.mensajeria.PublicadorPagoConfirmado;
import com.palco.ventas.api.Dtos.TitularDto;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.EstadoEntrada;
import com.palco.ventas.dominio.Venta;

/**
 * La confirmación de una venta que encontró la conciliación, sin servidor:
 * los repositorios son dobles de Mockito y el publicador es una lambda que
 * anota lo que se le pide publicar. Que las tres escrituras se deshagan
 * juntas lo hace el contenedor con la transacción de {@code confirmar}; acá se
 * comprueba que la operación la pide y en qué orden escribe.
 */
class ConfirmacionConciliadaTest {

    private static final String VENTA = "V-3f9a1c2b";
    private static final String CLAVE = "0b7e2a55-3c1d-4e8f-a9b0-c1d2e3f4a5b6";
    private static final String PAGO_ID = "P-598c58a0";
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 19, 21, 20, 30);

    private static final List<TitularDto> TITULARES = List.of(
            new TitularDto("Ana Gómez", "30123456"), new TitularDto("Luis Pérez", "31234567"),
            new TitularDto("Eva Ruiz", "32345678"));

    private final VentaRepository ventas = mock(VentaRepository.class);
    private final EntradaRepository entradas = mock(EntradaRepository.class);
    private final List<Entrada> guardadas = new ArrayList<>();
    private final List<PagoConfirmadoMensaje> publicados = new ArrayList<>();
    private final ConfirmacionConciliada confirmacion = new ConfirmacionConciliada();

    ConfirmacionConciliadaTest() {
        confirmacion.ventas = ventas;
        confirmacion.entradas = entradas;
        confirmacion.publicador = new PublicadorPagoConfirmado() {
            @Override
            public void publicar(PagoConfirmadoMensaje mensaje) {
                publicados.add(mensaje);
            }

            @Override
            public void publicarReventa(PagoConfirmadoMensaje mensaje) {
                throw new AssertionError("Una compra del catálogo no publica REVENTA_CONFIRMADA.");
            }
        };
        confirmacion.reloj = Clock.fixed(Instant.parse("2026-10-19T21:20:30.600Z"), ZoneOffset.ofHours(-3));
        doAnswer(llamada -> {
            guardadas.addAll(llamada.getArgument(0));
            return null;
        }).when(entradas).guardar(any());
    }

    @Test
    void confirmaConElPagoCreaUnaEntradaPorTitularYPublicaUnaSolaVez() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaDeTresEntradas()));
        when(ventas.confirmarConciliada(VENTA, CLAVE, PAGO_ID, AHORA)).thenReturn(true);

        assertTrue(confirmacion.confirmar(VENTA, CLAVE, PAGO_ID, TITULARES));

        // El UPDATE primero: solo quien cambió la fila crea las entradas y publica.
        InOrder orden = inOrder(ventas, entradas);
        orden.verify(ventas).confirmarConciliada(VENTA, CLAVE, PAGO_ID, AHORA);
        orden.verify(entradas).guardar(any());

        // El mismo reparto que una compra pagada: los dos primeros titulares a Platea B y el tercero a Popular.
        assertEquals(List.of("Ana Gómez|30123456|Platea B|27500|1", "Luis Pérez|31234567|Platea B|27500|2",
                "Eva Ruiz|32345678|Popular|9005|3"),
                guardadas.stream().map(e -> e.getTitularNombre() + "|" + e.getTitularDni() + "|" + e.getSector()
                        + "|" + e.getPrecioPagado() + "|" + e.getOrden()).toList());
        assertTrue(guardadas.stream().allMatch(e -> e.getEstado() == EstadoEntrada.PENDIENTE_EMISION));

        assertEquals(1, publicados.size());
        PagoConfirmadoMensaje mensaje = publicados.get(0);
        assertEquals(VENTA, mensaje.ventaId());
        assertEquals("user-titular", mensaje.compradorId());
        assertEquals(Instant.parse("2026-10-19T21:20:30Z"), mensaje.timestamp());
        assertEquals(guardadas.stream().map(Entrada::getId).toList(),
                mensaje.entradas().stream().map(PagoConfirmadoMensaje.Entrada::entradaId).toList());
        assertDoesNotThrow(mensaje::validar);
    }

    @Test
    void siElUpdateNoConfirmoNadaNoCreaEntradasNiPublica() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaDeTresEntradas()));
        when(ventas.confirmarConciliada(VENTA, CLAVE, PAGO_ID, AHORA)).thenReturn(false);

        assertFalse(confirmacion.confirmar(VENTA, CLAVE, PAGO_ID, TITULARES));

        verifyNoInteractions(entradas);
        assertTrue(publicados.isEmpty());
    }

    @Test
    void unaVentaQueNoExisteNoSeConfirma() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.empty());

        assertFalse(confirmacion.confirmar(VENTA, CLAVE, PAGO_ID, TITULARES));

        verify(ventas, never()).confirmarConciliada(anyString(), anyString(), anyString(), any());
        assertTrue(publicados.isEmpty());
    }

    @Test
    void siFallaElGuardadoDeLasEntradasNoSePublica() {
        when(ventas.buscarPorId(VENTA)).thenReturn(Optional.of(ventaDeTresEntradas()));
        when(ventas.confirmarConciliada(VENTA, CLAVE, PAGO_ID, AHORA)).thenReturn(true);
        doThrow(new IllegalStateException("se cayó la base")).when(entradas).guardar(any());

        // Sale tal cual: con ella el contenedor deshace también el UPDATE que confirmó la venta.
        assertThrows(IllegalStateException.class, () -> confirmacion.confirmar(VENTA, CLAVE, PAGO_ID, TITULARES));

        assertTrue(publicados.isEmpty());
    }

    @Test
    void esStatelessYConfirmaEnUnaTransaccion() throws NoSuchMethodException {
        assertNotNull(ConfirmacionConciliada.class.getAnnotation(Stateless.class));
        Method confirmar = ConfirmacionConciliada.class.getMethod("confirmar", String.class, String.class,
                String.class, List.class);
        // REQUIRED: confirmar, crear las entradas y publicar se confirman o se deshacen juntos.
        assertEquals(TransactionAttributeType.REQUIRED, confirmar.getAnnotation(TransactionAttribute.class).value());
    }

    /** Una venta real de tres entradas: dos en Platea B y una en Popular. */
    private static Venta ventaDeTresEntradas() {
        LocalDateTime abierta = AHORA.minusMinutes(20);
        Venta venta = new Venta(VENTA, "user-titular", "ev-1", "clave-de-la-compra", abierta, abierta.plusMinutes(10));
        venta.agregarItem("sec-1-4", "Platea B", 27_500, 2);
        venta.agregarItem("sec-1-1", "Popular", 9_005, 1);
        return venta;
    }
}
