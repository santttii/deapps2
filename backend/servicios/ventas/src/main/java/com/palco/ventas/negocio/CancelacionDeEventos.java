package com.palco.ventas.negocio;

import java.util.List;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

import com.palco.common.mensajeria.EventoCanceladoMensaje;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Venta;

/**
 * Lo que hace Ventas cuando Eventos avisa que se canceló un evento: por cada
 * compra pagada de ese evento anula sus entradas, le pide a Pagos que devuelva
 * el dinero y publica un {@code COMPRA_ANULADA}, que es lo que escucha
 * Notificaciones para avisarle a cada comprador.
 *
 * Está acá y no en el MDB porque el MDB es un punto de entrada delgado, como
 * el recurso REST: lee el mensaje y delega.
 *
 * Stateless: todo lo que necesita viene en el mensaje.
 *
 * <strong>Una transacción por compra</strong>
 * ({@code REQUIRES_NEW} en {@link AnulacionDeCompras#anular}): si las N
 * compras de un evento fueran una sola transacción, un fallo en la última
 * desharía las anteriores y el reintento del mensaje volvería a pedirle a
 * Pagos todos los reembolsos. Con una por compra, lo que quedó anulado queda
 * anulado, y el reintento solo retoma las que faltan, porque los UPDATE son
 * condicionales. Cada compra se anula en otro bean, y no en un método de
 * este, para que la llamada pase por el contenedor: si no, el
 * {@code REQUIRES_NEW} no se aplica (lo explica {@link AnulacionDeCompras}).
 *
 * <strong>El reembolso no bloquea la anulación.</strong> Si Pagos no contesta,
 * la entrada se anula igual y el aviso sale diciendo que el dinero todavía no
 * volvió ({@code reembolsado = false}). Lo contrario sería peor: entradas que
 * siguen valiendo para un evento que no existe. El reembolso que quedó sin
 * respuesta queda marcado en la venta y lo vuelve a pedir el barrido
 * {@link ReintentoDeReembolsos}; Pagos devuelve un pago una sola vez.
 */
@Stateless
public class CancelacionDeEventos {

    @Inject
    VentaRepository ventas;

    /** Por esta referencia, y no sobre {@code this}: así cada compra pasa por el contenedor y abre su transacción. */
    @Inject
    AnulacionDeCompras anulacion;

    /**
     * Anula y reembolsa todas las compras pagadas del evento. Devuelve cuántas
     * anuló en esta pasada: 0 si el mensaje ya se había procesado.
     *
     * Si falla la anulación de una compra, la excepción llega al MDB, que
     * deshace la recepción del mensaje y el broker lo reentrega. Las compras
     * que ya se anularon quedaron confirmadas en su propia transacción, y la
     * reentrega solo encuentra las que faltan.
     */
    public int anularComprasDelEvento(EventoCanceladoMensaje mensaje) {
        List<Venta> pagadas = ventas.pagadasDelEvento(mensaje.eventoId());
        if (pagadas.isEmpty()) {
            return 0;
        }
        int anuladas = 0;
        for (Venta venta : pagadas) {
            if (anulacion.anular(venta.getId(), venta.getCompradorId(), venta.getMontoTotal(), venta.getPagoId(),
                    mensaje)) {
                anuladas++;
            }
        }
        return anuladas;
    }
}
