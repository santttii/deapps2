package com.palco.ventas.dominio.estado;

import java.time.LocalDateTime;

import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.Venta;

/**
 * Patrón State de la venta. Cada estado es una clase que decide qué se puede
 * hacer con la venta en ese momento; la venta le delega la decisión a su
 * estado actual, {@code EstadoDeVenta.de(venta.getEstado())}, en lugar de
 * preguntar por el enum con ifs.
 *
 * Una operación que no corresponde al estado lanza {@link TransicionInvalida},
 * que la API responde con 409 en Problem Details. El cambio de estado en la
 * base lo sigue haciendo el repositorio con un UPDATE condicional: así, de dos
 * pedidos simultáneos, solo uno cambia la fila.
 *
 * <pre>
 * EN_CHECKOUT --pagar--> CONFIRMADA --emitir--> EMITIDA
 *      |--cancelar--> CANCELADA
 *      '--vencer----> VENCIDA
 * CONFIRMADA / EMITIDA --evento cancelado--> CANCELADA
 * </pre>
 */
public sealed interface EstadoDeVenta permits EnCheckout, Confirmada, Emitida, Cancelada, Vencida {

    /** El valor que se guarda en la columna {@code estado} y viaja en la API. */
    EstadoVenta codigo();

    /**
     * Controla que el comprador pueda pagar la venta. Si puede, la venta pasa
     * a {@code CONFIRMADA} cuando el cobro se aprueba. Si no, lanza
     * {@link TransicionInvalida} (409).
     */
    void pagar(Venta venta, LocalDateTime ahora);

    /**
     * Controla que el comprador pueda cancelar la venta. Devuelve {@code true}
     * si hay que cerrarla (pasa a {@code CANCELADA}) y {@code false} si ya
     * estaba cerrada, porque cancelar dos veces no es un error. Si está paga,
     * lanza {@link TransicionInvalida} (409).
     */
    boolean cancelar();

    /** El objeto estado que corresponde a lo que está guardado en la base. */
    static EstadoDeVenta de(EstadoVenta codigo) {
        return switch (codigo) {
            case EN_CHECKOUT -> EnCheckout.INSTANCIA;
            case CONFIRMADA -> Confirmada.INSTANCIA;
            case EMITIDA -> Emitida.INSTANCIA;
            case CANCELADA -> Cancelada.INSTANCIA;
            case VENCIDA -> Vencida.INSTANCIA;
        };
    }
}
