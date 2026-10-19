package com.palco.facturacion.negocio.externos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jakarta.jws.WebService;

/**
 * Un servicio SOAP de prueba con el mismo contrato que el ARCA simulado
 * ({@link PuertoArca}, mismo servicio y mismo puerto), para probar
 * {@link ArcaSoap} sin WildFly. Se publica con {@code Endpoint.publish} y se
 * le puede fijar la respuesta, una demora o una falla.
 */
@WebService(endpointInterface = "com.palco.facturacion.negocio.externos.PuertoArca",
        serviceName = "ArcaSimulado", portName = "ArcaSimuladoPort", targetNamespace = PuertoArca.ESPACIO_DE_NOMBRES)
public class ArcaDePrueba implements PuertoArca {

    /** Lo que llegó en cada pedido, tal como lo leyó el servidor. */
    public record Pedido(int tipo, int puntoDeVenta, long importeTotal, String fecha, String ventaId) {
    }

    final List<Pedido> pedidos = new ArrayList<>();
    volatile RespuestaCaeXml respuesta = new RespuestaCaeXml("A", 1, "75123456789012", "20261026", null);
    volatile Duration demora = Duration.ZERO;
    volatile boolean falla;

    @Override
    public RespuestaCaeXml solicitarCae(int tipo, int puntoDeVenta, long importeTotal, String fecha, String ventaId) {
        synchronized (pedidos) {
            pedidos.add(new Pedido(tipo, puntoDeVenta, importeTotal, fecha, ventaId));
        }
        if (!demora.isZero()) {
            try {
                Thread.sleep(demora);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (falla) {
            throw new IllegalStateException("falla de prueba");
        }
        return respuesta;
    }
}
