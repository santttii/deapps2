package com.palco.ventas.dominio.estado;

import java.time.LocalDateTime;

import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.Venta;

/** Las entradas ya tienen su QR. No se puede volver a pagar ni cancelar. */
public final class Emitida implements EstadoDeVenta {

    static final Emitida INSTANCIA = new Emitida();

    private Emitida() { }

    @Override
    public EstadoVenta codigo() { return EstadoVenta.EMITIDA; }

    @Override
    public void pagar(Venta venta, LocalDateTime ahora) {
        throw TransicionInvalida.yaPaga(codigo(), "pagar", "La compra ya está paga.");
    }

    @Override
    public boolean cancelar() {
        throw TransicionInvalida.yaPaga(codigo(), "cancelar", "La compra ya está paga: no se puede cancelar.");
    }
}
