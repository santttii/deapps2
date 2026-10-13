package com.palco.ventas.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import org.junit.jupiter.api.Test;

import com.palco.ventas.api.Dtos.EnlaceDto;
import com.palco.ventas.api.Dtos.EntradaDeVentaDto;
import com.palco.ventas.api.Dtos.EntradaDto;
import com.palco.ventas.api.Dtos.ItemDto;
import com.palco.ventas.api.Dtos.TitularDto;
import com.palco.ventas.api.Dtos.VentaDto;
import com.palco.ventas.dominio.EstadoVenta;

/**
 * Los {@code _links} de la venta: qué enlaces lleva en cada estado y a dónde
 * apuntan. No hace falta servidor: {@link Enlaces} solo necesita la raíz de la API.
 */
class EnlacesTest {

    private static final String VENTA = "V-3f9a1c2b";
    private static final String RUTA = "/api/v1/ventas/V-3f9a1c2b";

    private final Enlaces enlaces = new Enlaces(URI.create("http://localhost:8080/api/v1/"));

    @Test
    void laDireccionDeLaVentaCuelgaDeLaRaizDeLaApi() {
        assertEquals(URI.create("http://localhost:8080" + RUTA), enlaces.aLaVenta(VENTA));
    }

    @Test
    void laDireccionDelPagoCuelgaDeSuVenta() {
        assertEquals(URI.create("http://localhost:8080" + RUTA + "/pagos/P-0a1b2c3d"),
                enlaces.alPago(VENTA, "P-0a1b2c3d"));
    }

    @Test
    void laEntradaSueltaLlevaElMismoEnlaceQueDentroDeLaVenta() {
        EntradaDto sinEnlaces = new EntradaDto("T-9c41d07e", VENTA, "ev-1", "sec-1-4", "Platea B",
                new TitularDto("Ana Gómez", "30123456"), 27_500, "PENDIENTE_EMISION", null, null, Map.of());
        VentaDto venta = enlaces.completar(
                venta(EstadoVenta.CONFIRMADA, List.of(entrada("T-9c41d07e", "Ana Gómez", "30123456"))));

        EntradaDto entrada = enlaces.completar(sinEnlaces);

        assertEquals(Map.of("self", new EnlaceDto("/api/v1/entradas/T-9c41d07e", "GET")), entrada.enlaces());
        assertEquals(venta.entradas().get(0).enlaces(), entrada.enlaces());
        assertEquals(27_500, entrada.precioPagado());
    }

    @Test
    void unaVentaEnCheckoutSePuedeConsultarPagarYCancelar() {
        VentaDto venta = enlaces.completar(venta(EstadoVenta.EN_CHECKOUT, List.of()));

        // Los href son rutas sin host.
        assertEquals(Map.of(
                "self", new EnlaceDto(RUTA, "GET"),
                "pagar", new EnlaceDto(RUTA + "/pagos", "POST"),
                "cancelar", new EnlaceDto(RUTA, "DELETE")), venta.enlaces());
        assertEquals(List.of("self", "pagar", "cancelar"), List.copyOf(venta.enlaces().keySet()));
    }

    @Test
    void unaVentaQueYaNoEstaEnCheckoutSoloSePuedeConsultar() {
        for (EstadoVenta estado : EstadoVenta.values()) {
            if (estado == EstadoVenta.EN_CHECKOUT) {
                continue;
            }
            VentaDto venta = enlaces.completar(venta(estado, List.of()));

            assertEquals(Map.of("self", new EnlaceDto(RUTA, "GET")), venta.enlaces(), estado.name());
        }
    }

    @Test
    void cadaEntradaDeLaVentaLlevaElEnlaceASiMisma() {
        EntradaDeVentaDto ana = entrada("T-9c41d07e", "Ana Gómez", "30123456");
        EntradaDeVentaDto luis = entrada("T-0b7e2a55", "Luis Pérez", "31234567");

        VentaDto venta = enlaces.completar(venta(EstadoVenta.CONFIRMADA, List.of(ana, luis)));

        assertEquals(Map.of("self", new EnlaceDto("/api/v1/entradas/T-9c41d07e", "GET")),
                venta.entradas().get(0).enlaces());
        assertEquals(Map.of("self", new EnlaceDto("/api/v1/entradas/T-0b7e2a55", "GET")),
                venta.entradas().get(1).enlaces());
        // Lo demás de la entrada no cambia.
        assertEquals(ana.titular(), venta.entradas().get(0).titular());
        assertEquals("PENDIENTE_EMISION", venta.entradas().get(1).estado());
    }

    @Test
    void completarLosEnlacesNoCambiaLosDatosDeLaVenta() {
        VentaDto original = venta(EstadoVenta.EN_CHECKOUT, List.of());

        VentaDto venta = enlaces.completar(original);

        assertEquals(original.conEnlaces(List.of(), venta.enlaces()), venta);
        assertEquals(60_500, venta.montoTotal());
    }

    @Test
    void enElJsonLosEnlacesSalenConElNombreDelContrato() throws Exception {
        String json;
        try (Jsonb jsonb = JsonbBuilder.create()) {
            json = jsonb.toJson(enlaces.completar(
                    venta(EstadoVenta.CONFIRMADA, List.of(entrada("T-9c41d07e", "Ana Gómez", "30123456")))));
        }

        assertTrue(json.contains("\"_links\":{\"self\":{\"href\":\"" + RUTA + "\",\"method\":\"GET\"}}"), json);
        assertTrue(json.contains("\"_links\":{\"self\":{\"href\":\"/api/v1/entradas/T-9c41d07e\",\"method\":\"GET\"}}"),
                json);
        // "enlaces" es el nombre en Java; hacia afuera no sale.
        assertFalse(json.contains("enlaces"), json);
    }

    // ---------------------------------------------------------------------------------------------

    /** Una venta como la devuelve el negocio: sin enlaces. */
    private static VentaDto venta(EstadoVenta estado, List<EntradaDeVentaDto> entradas) {
        return new VentaDto(VENTA, estado.name(), "NO_APLICA", "ev-1",
                List.of(new ItemDto("sec-1-4", "Platea B", 2, 27_500)), 55_000, 5_500, 60_500,
                "2026-10-19T21:10:00Z", null, entradas, Map.of());
    }

    private static EntradaDeVentaDto entrada(String id, String nombre, String dni) {
        return new EntradaDeVentaDto(id, "Platea B", new TitularDto(nombre, dni), "PENDIENTE_EMISION", Map.of());
    }
}
