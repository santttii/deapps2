package com.palco.ventas.dominio.estado;

import java.time.LocalDateTime;

import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.Venta;

/** La canceló el comprador o se canceló el evento. Cancelarla de nuevo no hace nada; pagarla, no. */
public final class Cancelada implements EstadoDeVenta {

    static final Cancelada INSTANCIA = new Cancelada();

    private Cancelada() { }

    @Override
    public EstadoVenta codigo() { return EstadoVenta.CANCELADA; }

    @Override
    public void pagar(Venta venta, LocalDateTime ahora) {
        throw TransicionInvalida.reservaVencida(codigo());
    }

    @Override
    public boolean cancelar() { return false; }
}
