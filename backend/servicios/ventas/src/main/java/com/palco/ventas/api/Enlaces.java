package com.palco.ventas.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.palco.ventas.api.Dtos.EnlaceDto;
import com.palco.ventas.api.Dtos.EntradaDeVentaDto;
import com.palco.ventas.api.Dtos.EntradaDto;
import com.palco.ventas.api.Dtos.VentaDto;
import com.palco.ventas.dominio.EstadoVenta;

/**
 * Arma las direcciones de los recursos de Ventas y los {@code _links} de sus
 * respuestas. Los {@code _links} le dicen al cliente qué puede hacer con el
 * recurso en el estado en que está, así no tiene que armar las rutas ni
 * conocer las reglas: una venta paga, por ejemplo, ya no trae "cancelar".
 *
 * Está en la capa REST porque las rutas son cosa de HTTP: el negocio devuelve
 * la venta sin enlaces y el recurso los completa con esta clase al responder.
 * Todas las rutas de Ventas se escriben acá, una sola vez.
 *
 * No es un componente del servidor: es un objeto común, que el recurso crea
 * en cada pedido a partir de la raíz con la que llegó ese pedido. No guarda
 * nada más que esa raíz.
 */
final class Enlaces {

    static final String VENTAS = "ventas";
    static final String ENTRADAS = "entradas";
    static final String PAGOS = "pagos";

    /** La raíz de la API como llegó en el pedido: "http://localhost:8080/api/v1/". */
    private final URI raiz;

    Enlaces(URI raiz) {
        this.raiz = raiz;
    }

    /** La dirección completa de una venta. Es la que va en la cabecera {@code Location} al crearla. */
    URI aLaVenta(String ventaId) {
        return raiz.resolve(VENTAS + "/" + ventaId);
    }

    /**
     * La dirección completa del pago de una venta. Es la que va en la cabecera
     * {@code Location} al pagar. Todavía no hay una operación que la consulte.
     */
    URI alPago(String ventaId, String pagoId) {
        return raiz.resolve(VENTAS + "/" + ventaId + "/" + PAGOS + "/" + pagoId);
    }

    /**
     * La venta con sus enlaces: {@code self} siempre; {@code pagar} y
     * {@code cancelar} solo mientras está {@code EN_CHECKOUT}, que es cuando
     * esas dos operaciones se pueden hacer. Cada entrada lleva su {@code self}.
     * Los {@code href} son rutas sin host ("/api/v1/ventas/V-3f9a1c2b").
     */
    VentaDto completar(VentaDto venta) {
        String ruta = aLaVenta(venta.id()).getPath();

        // LinkedHashMap: los enlaces salen en el orden en que se cargan.
        Map<String, EnlaceDto> enlaces = new LinkedHashMap<>();
        enlaces.put("self", new EnlaceDto(ruta, "GET"));
        if (EstadoVenta.EN_CHECKOUT.name().equals(venta.estado())) {
            enlaces.put("pagar", new EnlaceDto(ruta + "/" + PAGOS, "POST"));
            enlaces.put("cancelar", new EnlaceDto(ruta, "DELETE"));
        }

        List<EntradaDeVentaDto> entradas = venta.entradas().stream()
                .map(this::completar)
                .toList();
        return venta.conEnlaces(entradas, enlaces);
    }

    /** La entrada completa con su enlace {@code self}, que es el único que tiene. */
    EntradaDto completar(EntradaDto entrada) {
        return entrada.conEnlaces(deLaEntrada(entrada.id()));
    }

    private EntradaDeVentaDto completar(EntradaDeVentaDto entrada) {
        return entrada.conEnlaces(deLaEntrada(entrada.id()));
    }

    /** Los enlaces de una entrada, iguales para la entrada suelta y para la que va dentro de la venta. */
    private Map<String, EnlaceDto> deLaEntrada(String entradaId) {
        String ruta = raiz.resolve(ENTRADAS + "/" + entradaId).getPath();
        return Map.of("self", new EnlaceDto(ruta, "GET"));
    }
}
