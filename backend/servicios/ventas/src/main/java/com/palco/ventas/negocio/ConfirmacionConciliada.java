package com.palco.ventas.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.common.Textos;
import com.palco.common.mensajeria.PublicadorPagoConfirmado;
import com.palco.ventas.api.Dtos.TitularDto;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Entrada;
import com.palco.ventas.dominio.Venta;

/**
 * Confirma una venta cuyo cobro aprobado encontró la conciliación, sin la
 * instancia stateful de la compra: hace lo mismo que el tercer paso de
 * {@code ServicioDeVentas.confirmarCompra}, con los titulares que quedaron
 * copiados en la base antes del cobro.
 *
 * Stateless: no guarda nada entre llamadas. Todo lo que necesita (la venta,
 * la clave anotada, el pago y los titulares) llega como parámetro o se lee de
 * la base, así que cualquier instancia del pool sirve. No es parte de la
 * conversación de un comprador: la llama el barrido.
 *
 * Es un bean aparte de {@link ConciliacionDePagos} por la transacción. La
 * conciliación corre sin transacción, porque pregunta a Pagos y no tiene que
 * retener una conexión mientras espera. Esta operación, en cambio, necesita
 * una ({@code REQUIRED}): confirmar la venta, crear las entradas y publicar
 * {@code PAGO_CONFIRMADO} se confirman juntos o se deshacen juntos, igual que
 * en una compra pagada. El publicador ({@code MANDATORY}) se suma a ella y el
 * mensaje sale recién con el commit; las entradas las emite después la cola,
 * como siempre. Para que el contenedor abra esa transacción, la llamada tiene
 * que entrar por un bean distinto: una llamada de la conciliación a un método
 * propio no pasaría por el contenedor.
 */
@Stateless
public class ConfirmacionConciliada {

    private static final Logger LOG = Logger.getLogger(ConfirmacionConciliada.class.getName());

    @Inject
    VentaRepository ventas;

    @Inject
    EntradaRepository entradas;

    @Inject
    PublicadorPagoConfirmado publicador;

    /** De dónde sale el "ahora". No es estado: es fijo, y los tests lo reemplazan por un reloj detenido. */
    Clock reloj = Clock.systemUTC();

    /**
     * Pasa la venta a {@code CONFIRMADA} con ese pago, crea una entrada por
     * titular y publica {@code PAGO_CONFIRMADO}, en una sola transacción.
     * Devuelve {@code true} si esta llamada fue la que confirmó la venta.
     *
     * El UPDATE exige que la venta siga {@code EN_CHECKOUT} con esa misma clave
     * anotada. Si no cambia ninguna fila, otro (un reintento del comprador u
     * otra corrida) ya la cerró: devuelve {@code false} sin crear entradas ni
     * publicar, así el mensaje sale una sola vez por venta.
     *
     * @param titulares la copia de la base, en el orden del pedido; uno por entrada
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public boolean confirmar(String ventaId, String clavePago, String pagoId, List<TitularDto> titulares) {
        Optional<Venta> leida = ventas.buscarPorId(ventaId);
        if (leida.isEmpty()) {
            return false;
        }
        Venta venta = leida.get();
        LocalDateTime ahora = ServicioDeVentas.ahoraEnUtc(reloj);
        if (!ventas.confirmarConciliada(ventaId, clavePago, pagoId, ahora)) {
            return false;
        }
        List<Entrada> nuevas = ServicioDeVentas.entradasPara(venta, titulares);
        entradas.guardar(nuevas);
        publicador.publicar(ServicioDeVentas.mensaje(venta, nuevas, ahora));
        LOG.info("Conciliación: la venta " + ventaId + " quedó confirmada con el pago " + Textos.enUnaLinea(pagoId)
                + ". Entradas creadas: " + nuevas.size() + ". PAGO_CONFIRMADO publicado en la misma transacción.");
        return true;
    }
}
