package com.palco.ventas.datos;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.ventas.dominio.Venta;

/**
 * Dónde se guardan y se buscan las ventas. El negocio conoce solo esta
 * interfaz: en el servidor la implementa {@link VentaRepositoryJpa} contra la
 * base, y en los tests un doble.
 *
 * Tiene tres grupos de operaciones:
 * <ul>
 * <li>Guardar una venta nueva y completarla con sus renglones.</li>
 * <li>Buscar. Toda búsqueda devuelve la venta con sus renglones y sus entradas
 * ya cargados, para que se la pueda leer fuera de una transacción.</li>
 * <li>Cambiar de estado. Cada cambio es un solo UPDATE con la condición
 * adentro y devuelve si cambió una fila: solo sigue quien la cambió. Si dos
 * pedidos llegan a la vez (pagar y cancelar, o pagar y el vencimiento), la
 * base deja pasar a uno solo. Después de un cambio, la venta que se había
 * buscado antes quedó vieja: hay que buscarla de nuevo.</li>
 * </ul>
 *
 * El "ahora" llega siempre como parámetro, en UTC: el repositorio no mira el
 * reloj del servidor.
 */
@Local
public interface VentaRepository {

    /** Tipo del {@code Conflicto} que lanza {@link #guardar} cuando ese comprador ya usó esa clave de idempotencia. */
    String TIPO_CLAVE_REPETIDA = "clave-repetida";

    /**
     * Guarda una venta nueva; el INSERT sale en el momento. Lanza
     * {@code ErroresDeNegocio.Conflicto} de tipo {@link #TIPO_CLAVE_REPETIDA} si
     * el comprador ya tiene una venta con esa clave de idempotencia: es la base
     * la que lo impide, con una restricción única.
     */
    void guardar(Venta venta);

    /**
     * Escribe en la base los renglones y los montos que se le sumaron a una
     * venta ya guardada. Sale en el momento, no al cerrar la transacción: si la
     * base rechaza algo, quien llama se entera a tiempo de devolver el cupo que
     * reservó.
     */
    void actualizar(Venta venta);

    Optional<Venta> buscarPorId(String ventaId);

    /** La venta que ese comprador creó con esa clave de idempotencia, si existe. */
    Optional<Venta> buscarPorClave(String compradorId, String claveIdempotencia);

    /**
     * Las reservas que ya vencieron en el instante {@code ahora} y nadie
     * intentó pagar: ventas {@code EN_CHECKOUT}, con {@code venceReserva} en
     * {@code ahora} o antes y sin clave de pago. Las usa el barrido.
     */
    List<Venta> buscarReservasVencidas(LocalDateTime ahora);

    /**
     * Anota la clave del intento de pago, antes de cobrar. Exige que la venta
     * esté {@code EN_CHECKOUT}, que la reserva siga vigente y que no haya otra
     * clave anotada (con la misma clave vuelve a pasar: es un reintento).
     * Desde ese momento la venta no se puede cancelar ni vencer.
     */
    boolean guardarClaveDePago(String ventaId, String clavePago, LocalDateTime ahora);

    /** Quita la clave del intento de pago cuando el cobro fue rechazado, para que se pueda pagar de nuevo o cancelar. */
    boolean borrarClaveDePago(String ventaId, String clavePago);

    /** {@code EN_CHECKOUT} → {@code CONFIRMADA}: anota el pago aprobado y el instante de la confirmación. */
    boolean confirmar(String ventaId, String pagoId, LocalDateTime ahora);

    /** {@code EN_CHECKOUT} → {@code CANCELADA}. No cambia nada si hay un pago en curso (clave de pago anotada). */
    boolean cancelar(String ventaId);

    /**
     * {@code EN_CHECKOUT} → {@code VENCIDA}, solo si la reserva ya venció en el
     * instante {@code ahora}. No cambia nada si hay un pago en curso.
     */
    boolean vencer(String ventaId, LocalDateTime ahora);

    /** {@code CONFIRMADA} → {@code EMITIDA}, cuando sus entradas ya tienen código. */
    boolean marcarEmitida(String ventaId);
}
