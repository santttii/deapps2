package com.palco.ventas.dominio.estado;

import java.time.LocalDateTime;

import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.Venta;

/** El cupo está reservado y falta pagar: se puede pagar (si la reserva vale) y cancelar. */
public final class EnCheckout implements EstadoDeVenta {

    static final EnCheckout INSTANCIA = new EnCheckout();

    private EnCheckout() { }

    @Override
    public EstadoVenta codigo() { return EstadoVenta.EN_CHECKOUT; }

    @Override
    public void pagar(Venta venta, LocalDateTime ahora) {
        if (venta.reservaVencida(ahora)) {
            throw TransicionInvalida.reservaVencida(codigo());
        }
    }

    @Override
    public boolean cancelar() { return true; }
}
