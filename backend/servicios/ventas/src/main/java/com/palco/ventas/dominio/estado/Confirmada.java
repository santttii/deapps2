package com.palco.ventas.dominio.estado;

import java.time.LocalDateTime;

import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.Venta;

/** El pago se aprobó: las entradas existen pero todavía no tienen QR. No se puede volver a pagar ni cancelar. */
public final class Confirmada implements EstadoDeVenta {

    static final Confirmada INSTANCIA = new Confirmada();

    private Confirmada() { }

    @Override
    public EstadoVenta codigo() { return EstadoVenta.CONFIRMADA; }

    @Override
    public void pagar(Venta venta, LocalDateTime ahora) {
        throw TransicionInvalida.yaPaga(codigo(), "pagar", "La compra ya está paga.");
    }

    @Override
    public boolean cancelar() {
        throw TransicionInvalida.yaPaga(codigo(), "cancelar", "La compra ya está paga: no se puede cancelar.");
    }
}
