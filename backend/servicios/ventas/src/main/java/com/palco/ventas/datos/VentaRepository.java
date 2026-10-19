package com.palco.ventas.datos;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.ejb.Local;

import com.palco.ventas.dominio.TitularDeVenta;
import com.palco.ventas.dominio.Venta;

/**
 * Dónde se guardan y se buscan las ventas. El negocio conoce solo esta
 * interfaz: en el servidor la implementa {@link VentaRepositoryJpa} contra la
 * base, y en los tests un doble.
 *
 * Tiene tres grupos de operaciones:
 * <ul>
 * <li>Guardar una venta nueva, con sus renglones.</li>
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
     * Guarda una venta nueva junto con sus renglones; los INSERT salen en el
     * momento, no al cerrar la transacción: si la base rechaza algo, quien
     * llama se entera a tiempo de devolver el cupo que reservó. Lanza
     * {@code ErroresDeNegocio.Conflicto} de tipo {@link #TIPO_CLAVE_REPETIDA} si
     * el comprador ya tiene una venta con esa clave de idempotencia: es la base
     * la que lo impide, con una restricción única.
     */
    void guardar(Venta venta);

    Optional<Venta> buscarPorId(String ventaId);

    /** La venta que ese comprador creó con esa clave de idempotencia, si existe. */
    Optional<Venta> buscarPorClave(String compradorId, String claveIdempotencia);

    /**
     * Todas las ventas de ese comprador, en cualquier estado, de la más nueva
     * a la más vieja. Nunca trae ventas de otro usuario: el filtro por
     * comprador está en la consulta.
     */
    List<Venta> buscarPorComprador(String compradorId);

    /**
     * Las reservas que ya vencieron en el instante {@code ahora} y nadie
     * intentó pagar: ventas {@code EN_CHECKOUT}, con {@code venceReserva} en
     * {@code ahora} o antes y sin clave de pago. Las usa el barrido.
     */
    List<Venta> buscarReservasVencidas(LocalDateTime ahora);

    /**
     * Anota la clave del intento de pago, antes de cobrar. Exige que la venta
     * esté {@code EN_CHECKOUT}, que la reserva siga vigente y que no haya otra
     * clave anotada. Si la clave anotada ya es esta misma vuelve a pasar,
     * aunque la reserva haya vencido: es el reintento de un pago del que no se
     * supo el resultado, y esa marca siguió reteniendo el cupo.
     * Desde ese momento la venta no se puede cancelar ni vencer.
     *
     * En el mismo UPDATE anota {@code ahora} como el instante del último
     * intento ({@code pago_intentado}): un reintento lo vuelve a anotar.
     */
    boolean guardarClaveDePago(String ventaId, String clavePago, LocalDateTime ahora);

    /**
     * Quita la clave del intento de pago cuando se sabe que no se cobró: el
     * cobro fue rechazado o el pedido no llegó a cobrar. Sin la clave, la
     * compra se puede pagar de nuevo, cancelar o vencer. Solo la quita si la
     * venta sigue {@code EN_CHECKOUT} y la clave anotada es esa misma.
     */
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

    // ---- Lo que usa la conciliación de los pagos sin resultado ----

    /**
     * Guarda la copia de los titulares de una compra que está por cobrarse.
     * Corre en una transacción propia, que termina al volver: la copia queda
     * confirmada antes del cobro y no se deshace si después se deshace la
     * transacción del pago. Si la venta ya tenía su copia (un reintento, o un
     * pago nuevo después de un rechazo) no hace nada: los titulares de una
     * compra no cambian.
     */
    void respaldarTitulares(String ventaId, List<TitularDeVenta> titulares);

    /** La copia de los titulares de la venta, en el orden del pedido; vacía si nunca se intentó pagar. */
    List<TitularDeVenta> buscarTitulares(String ventaId);

    /**
     * Las ventas con un intento de pago sin resultado que ya se pueden
     * conciliar: {@code EN_CHECKOUT}, con clave de pago, y cuyo último intento
     * empezó en {@code intentadosHasta} o antes (o no tiene fecha, porque es
     * anterior a esa columna). Las de reserva más vieja primero.
     */
    List<Venta> buscarPagosSinResultado(LocalDateTime intentadosHasta);

    /**
     * {@code EN_CHECKOUT} → {@code CONFIRMADA} con el pago que encontró la
     * conciliación. Exige además que la clave anotada sea la de ese pago: si
     * otro pedido ya cerró la venta, no cambia nada.
     */
    boolean confirmarConciliada(String ventaId, String clavePago, String pagoId, LocalDateTime ahora);

    /**
     * {@code EN_CHECKOUT} → {@code VENCIDA} y sin clave de pago, cuando la
     * conciliación comprobó que ese intento no cobró. Exige que la clave
     * anotada sea esa y que el último intento haya empezado en
     * {@code intentadosHasta} o antes: si mientras tanto llegó un reintento,
     * el UPDATE no cambia nada y la venta no se vence con un cobro en camino.
     */
    boolean vencerPagoSinCobro(String ventaId, String clavePago, LocalDateTime intentadosHasta);
}
