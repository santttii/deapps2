package com.palco.ventas.dominio.estado;

import com.palco.common.ErroresDeNegocio;
import com.palco.ventas.dominio.EstadoVenta;

/**
 * Una operación que el estado actual de la venta no permite. Es un
 * {@link ErroresDeNegocio.Conflicto}, así que la API responde 409 con
 * Problem Details y el {@code type} de cada caso.
 */
public final class TransicionInvalida extends ErroresDeNegocio.Conflicto {

    /** Pagar o cancelar una venta que ya está paga. */
    public static final String VENTA_YA_PAGA = "venta-ya-paga";
    /** Pagar una venta cancelada, vencida o con la reserva vencida. */
    public static final String RESERVA_VENCIDA = "reserva-vencida";

    private final EstadoVenta desde;
    private final String operacion;

    public TransicionInvalida(EstadoVenta desde, String operacion, String tipo, String mensaje) {
        super(tipo, mensaje);
        this.desde = desde;
        this.operacion = operacion;
    }

    public EstadoVenta getDesde() { return desde; }
    public String getOperacion() { return operacion; }

    static TransicionInvalida yaPaga(EstadoVenta desde, String operacion, String mensaje) {
        return new TransicionInvalida(desde, operacion, VENTA_YA_PAGA, mensaje);
    }

    static TransicionInvalida reservaVencida(EstadoVenta desde) {
        return new TransicionInvalida(desde, "pagar", RESERVA_VENCIDA,
                "La reserva de esta compra ya no está vigente. Empezá la compra de nuevo.");
    }
}
