package com.palco.ventas.dominio.estado;

import java.time.LocalDateTime;

import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.Venta;

/** Pasaron los minutos de la reserva sin pago. Cancelarla no hace nada; pagarla, no. */
public final class Vencida implements EstadoDeVenta {

    static final Vencida INSTANCIA = new Vencida();

    private Vencida() { }

    @Override
    public EstadoVenta codigo() { return EstadoVenta.VENCIDA; }

    @Override
    public void pagar(Venta venta, LocalDateTime ahora) {
        throw TransicionInvalida.reservaVencida(codigo());
    }

    @Override
    public boolean cancelar() { return false; }
}
