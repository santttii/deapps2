package com.palco.ventas.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.ventas.api.Dtos.EntradaDto;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.EstadoEntrada;
import com.palco.ventas.dominio.Venta;

/**
 * Las reglas del uso de una entrada en la puerta, con el repositorio
 * reemplazado por un doble. Que dos escaneos simultáneos no la usen dos veces
 * lo decide la base y se prueba en {@code UsoEnH2Test}.
 */
class UsoDeEntradasTest {

    private static final String CODIGO = "5b0c7a5e-3c1f-4b8e-9d2a-8f6e1c0b7a11";
    private static final String EVENTO = "ev-1";

    private final EntradaRepository entradas = mock(EntradaRepository.class);
    private final UsoDeEntradas usos = new UsoDeEntradas();

    @BeforeEach
    void conectar() {
        usos.entradas = entradas;
    }

    @Test
    void unaEntradaValidaDelEventoSeUsaYVuelveConElTitularYElSector() {
        when(entradas.buscarPorCodigoQr(CODIGO)).thenReturn(Optional.of(entrada(EVENTO, EstadoEntrada.VALIDA)));
        when(entradas.marcarUsada("T-00000001")).thenReturn(true);
        when(entradas.buscarPorId("T-00000001")).thenReturn(Optional.of(entrada(EVENTO, EstadoEntrada.USADA)));

        EntradaDto usada = usos.marcarUsada(CODIGO, EVENTO);

        assertEquals("USADA", usada.estado());
        assertEquals("Ana Gómez", usada.titular().nombre());
        assertEquals("30111222", usada.titular().dni());
        assertEquals("Campo", usada.sector());
        assertEquals(EVENTO, usada.eventoId());
    }

    @Test
    void unCodigoQueNoExisteEsUn404() {
        when(entradas.buscarPorCodigoQr(CODIGO)).thenReturn(Optional.empty());

        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> usos.marcarUsada(CODIGO, EVENTO));
        verify(entradas, never()).marcarUsada(anyString());
    }

    @Test
    void unaEntradaDeOtroEventoEsUn409YNoSeToca() {
        when(entradas.buscarPorCodigoQr(CODIGO)).thenReturn(Optional.of(entrada("ev-2", EstadoEntrada.VALIDA)));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> usos.marcarUsada(CODIGO, EVENTO));

        assertEquals(TiposDeProblema.ENTRADA_DE_OTRO_EVENTO, error.tipo());
        // Tiene que seguir sirviendo en la puerta de su evento.
        verify(entradas, never()).marcarUsada(anyString());
    }

    @Test
    void siOtroEscaneoLaUsoAntesEsUn409EntradaYaUsada() {
        // La lectura la ve VALIDA, pero entre la lectura y el UPDATE la usó otra puerta.
        when(entradas.buscarPorCodigoQr(CODIGO)).thenReturn(Optional.of(entrada(EVENTO, EstadoEntrada.VALIDA)));
        when(entradas.marcarUsada("T-00000001")).thenReturn(false);
        when(entradas.buscarPorId("T-00000001")).thenReturn(Optional.of(entrada(EVENTO, EstadoEntrada.USADA)));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> usos.marcarUsada(CODIGO, EVENTO));

        assertEquals(TiposDeProblema.ENTRADA_YA_USADA, error.tipo());
    }

    @Test
    void unaEntradaPublicadaEnLaReventaNoSirveParaEntrar() {
        when(entradas.buscarPorCodigoQr(CODIGO)).thenReturn(Optional.of(entrada(EVENTO, EstadoEntrada.PUBLICADA)));
        when(entradas.marcarUsada("T-00000001")).thenReturn(false);
        when(entradas.buscarPorId("T-00000001")).thenReturn(Optional.of(entrada(EVENTO, EstadoEntrada.PUBLICADA)));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> usos.marcarUsada(CODIGO, EVENTO));

        assertEquals(TiposDeProblema.ENTRADA_NO_HABILITADA, error.tipo());
    }

    @Test
    void unaEntradaAnuladaTampocoSirve() {
        when(entradas.buscarPorCodigoQr(CODIGO)).thenReturn(Optional.of(entrada(EVENTO, EstadoEntrada.ANULADA)));
        when(entradas.marcarUsada("T-00000001")).thenReturn(false);
        when(entradas.buscarPorId("T-00000001")).thenReturn(Optional.of(entrada(EVENTO, EstadoEntrada.ANULADA)));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> usos.marcarUsada(CODIGO, EVENTO));

        assertEquals(TiposDeProblema.ENTRADA_NO_HABILITADA, error.tipo());
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Una entrada del evento indicado, en el estado indicado. La entidad no
     * tiene "set" del estado (los cambios los hace el repositorio con UPDATE
     * condicionales), así que el test lo pone por reflexión.
     */
    static Entrada entrada(String eventoId, EstadoEntrada estado) {
        LocalDateTime ahora = LocalDateTime.of(2026, 11, 20, 21, 0);
        Venta venta = new Venta("V-3f9a1c2b", "U-comprador", eventoId, "clave", ahora, ahora.plusMinutes(10));
        venta.agregarItem("S-campo", "Campo", 55_000, 1);
        Entrada entrada = new Entrada("T-00000001", venta.getItems().get(0), "Ana Gómez", "30111222", 1);
        try {
            Field campo = Entrada.class.getDeclaredField("estado");
            campo.setAccessible(true);
            campo.set(entrada, estado);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entrada;
    }
}
