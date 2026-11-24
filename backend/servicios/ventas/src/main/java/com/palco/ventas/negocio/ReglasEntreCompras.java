package com.palco.ventas.negocio;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.TitularDeVenta;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;

/**
 * Las dos reglas entre compras de una compra del catálogo, miradas en la
 * misma transacción que escribe la compra y con un candado tomado antes
 * (DEF-46):
 * <ul>
 * <li>hasta {@code MAX_ENTRADAS_POR_EVENTO} entradas por comprador y evento,
 * sumando todas sus compras;</li>
 * <li>un DNI, una entrada por evento.</li>
 * </ul>
 *
 * {@link ServicioDeVentas} las mira también antes, al abrir la compra, para
 * no reservar cupo de más en el caso común. Esa primera mirada no alcanza con
 * dos compras a la vez: las dos cuentan lo que hay guardado, ninguna ve a la
 * otra y pasan las dos. Por eso acá se miran de nuevo con un candado por
 * comprador y evento (el tope) o por DNI y evento (el DNI): la segunda compra
 * espera en el candado hasta que la primera confirma, y cuenta con la primera
 * ya guardada.
 *
 * Stateless: cada operación es independiente y no recuerda nada. Es un bean
 * aparte, y no un método más del stateful, para que la transacción la abra el
 * contenedor con la anotación de cada operación: una llamada de un bean a sí
 * mismo no pasa por el contenedor y no abriría ninguna. Cada transacción es
 * corta, sin llamadas a otros servicios adentro: el candado se suelta en
 * cuanto se escribe la compra, y la conexión vuelve al pool.
 *
 * Las reglas en sí (cuándo se pasa el tope, qué error se responde) siguen
 * escritas una sola vez, en {@link ServicioDeVentas}.
 */
@Stateless
public class ReglasEntreCompras {

    @Inject
    VentaRepository ventas;

    /**
     * Guarda la venta recién reservada solo si con ella el comprador no pasa
     * del tope de entradas para el evento; si lo pasa, lanza 409
     * {@code tope-por-evento} y no queda nada guardado. Quien llama devuelve
     * el cupo.
     *
     * {@code REQUIRED}: la compra se abre sin transacción
     * ({@code NOT_SUPPORTED}), así que el contenedor abre una solo para esto y
     * la confirma al volver. Adentro, en este orden:
     * <ol>
     * <li>El candado del comprador y el evento. Otra compra suya para el mismo
     * evento espera acá hasta que esta transacción termine.</li>
     * <li>La venta se guarda. Si otro pedido con la misma clave de
     * idempotencia ya guardó la suya, falla acá con {@code clave-repetida},
     * como antes del candado, y {@link ComprasEnCurso} devuelve esa venta. Por
     * eso va antes de contar: contada contra la otra, un pedido repetido
     * respondería {@code tope-por-evento}.</li>
     * <li>Se cuenta, ya con esta venta adentro (está en checkout y con la
     * reserva recién empezada), y se aplica la regla con lo que tenía antes.
     * Si no pasa, el error deshace la transacción y la venta no queda.</li>
     * </ol>
     */
    public void guardarDentroDelTope(Venta venta, LocalDateTime ahora) {
        ventas.tomarCandados(List.of(candadoDelComprador(venta.getCompradorId(), venta.getEventoId())));
        ventas.guardar(venta);
        long pedidas = venta.getItems().stream().mapToLong(VentaItem::getCantidad).sum();
        long conEsta = ventas.entradasDelCompradorParaElEvento(venta.getCompradorId(), venta.getEventoId(), ahora);
        ServicioDeVentas.exigirTopePorEvento(conEsta - pedidas, pedidas);
    }

    /**
     * La última mirada al DNI de los titulares antes de cobrar y la copia de
     * los titulares en la base, juntas: con el candado de cada DNI para el
     * evento, se mira que ninguno tenga una entrada en pie o esté en otra
     * compra que se está cobrando, y recién entonces se guarda la copia. Desde
     * que la copia queda confirmada, la otra compra que espera en el candado
     * ya la ve. Si algún DNI está tomado lanza 409 {@code dni-con-entrada} y
     * no copia nada.
     *
     * La copia es la que necesita la conciliación si el cobro sale y la
     * transacción del pago falla ({@code VentaRepository.respaldarTitulares}).
     * Si la venta ya la tenía, no se escribe otra.
     *
     * {@code REQUIRES_NEW}: el contenedor suspende la transacción del pago
     * (que todavía no tomó ninguna conexión), abre una nueva solo para esto y
     * la confirma al volver. Así la copia sobrevive a una falla posterior del
     * pago, que es justo el caso en el que hace falta, y el candado y la
     * conexión se sueltan antes de esperar a Pagos.
     */
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void exigirDnisSinEntradaYCopiarTitulares(String ventaId, String eventoId, List<TitularDeVenta> titulares) {
        List<String> dnis = titulares.stream().map(TitularDeVenta::getDni).toList();
        ventas.tomarCandados(dnis.stream().map(dni -> candadoDelDni(dni, eventoId)).toList());
        ServicioDeVentas.exigirDnisSinEntrada(ventas.dnisConEntradaParaElEvento(eventoId, dnis, ventaId));
        ventas.respaldarTitulares(ventaId, titulares);
    }

    /**
     * La clave del candado de un comprador para un evento. Si dos claves
     * llegaran a coincidir sin ser de la misma persona y el mismo evento, esas
     * dos compras solo se esperarían una a la otra: un candado de más nunca
     * deja pasar una compra.
     */
    static String candadoDelComprador(String compradorId, String eventoId) {
        return "comprador|" + eventoId + "|" + compradorId;
    }

    /** La clave del candado de un DNI para un evento. */
    static String candadoDelDni(String dni, String eventoId) {
        return "dni|" + eventoId + "|" + dni;
    }
}
