package com.palco.ventas.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.logging.Logger;

import jakarta.ejb.DependsOn;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;

/**
 * El barrido que vence las reservas. Una vez por minuto busca las compras que
 * siguen {@code EN_CHECKOUT} con la reserva vencida, las pasa a
 * {@code VENCIDA} y le devuelve su cupo a Eventos.
 *
 * La reserva tiene un solo reloj: la hora guardada en la base
 * ({@code vence_reserva}). El componente stateful de la compra no vence nada:
 * su {@code @PreDestroy} puede no ejecutarse (la especificación lo avisa) y su
 * timeout mide inactividad, no una hora fija. Por eso la limpieza es este
 * proceso periódico, que no depende de que la instancia siga viva: funciona
 * igual después de redesplegar el servicio.
 *
 * Sin estado (stateless): no guarda nada entre una corrida y la siguiente. Qué
 * reservas vencieron se lo pregunta cada vez a la base. Es un
 * {@code @Singleton} porque tiene que haber un solo barrido en el servidor: el
 * contenedor crea una única instancia y no la deja correr dos veces a la vez.
 * Si una corrida tarda más de un minuto, la siguiente se saltea.
 *
 * {@code @Schedule}: el temporizador lo maneja el contenedor, sin hilos
 * propios. Con {@code persistent = false} no se guarda en disco: se crea al
 * desplegar y no "recupera" las corridas que se perdió con el servidor
 * apagado. No hace falta, porque la primera corrida vence todo lo atrasado.
 *
 * {@code @DependsOn}: no arranca hasta que {@link MigracionesVentas} terminó
 * de crear las tablas que consulta.
 *
 * Corre sin transacción propia ({@code NOT_SUPPORTED}): cada venta se cierra
 * en la transacción corta del repositorio, que queda confirmada antes de
 * llamar a Eventos. Entre los dos servicios no hay transacción; con este orden
 * el cupo se devuelve solo por una venta que ya quedó {@code VENCIDA}.
 */
@Singleton
@DependsOn("MigracionesVentas")
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class VencimientoDeReservas {

    private static final Logger LOG = Logger.getLogger(VencimientoDeReservas.class.getName());

    @Inject
    VentaRepository ventas;

    @Inject
    ComprasEnCurso compras;

    /** De dónde sale el "ahora". No es estado: es fijo, y los tests lo reemplazan por un reloj detenido. */
    Clock reloj = Clock.systemUTC();

    /**
     * Una corrida del barrido. La llama el contenedor en el segundo 0 de cada
     * minuto.
     *
     * Por cada reserva vencida hace un UPDATE con la condición adentro
     * ({@code EN_CHECKOUT}, vencida y sin un pago en curso). Solo si ese UPDATE
     * cambió la fila devuelve el cupo: si justo antes la compra se pagó o se
     * canceló, la base no cambia nada y el cupo no se toca. Así el cupo de una
     * venta lo devuelve uno solo.
     *
     * Las ventas con un intento de pago sin resultado ({@code clave_pago}) no
     * se vencen: no se libera el cupo de algo que pudo haberse cobrado.
     *
     * Si la base no responde, la corrida se corta con un aviso y no pasa nada
     * más: las reservas siguen vencidas por su hora y las toma la corrida del
     * minuto siguiente.
     */
    @Schedule(hour = "*", minute = "*", persistent = false)
    void vencerReservas() {
        LocalDateTime ahora = ServicioDeVentas.ahoraEnUtc(reloj);
        try {
            for (Venta venta : ventas.buscarReservasVencidas(ahora)) {
                if (ventas.vencer(venta.getId(), ahora)) {
                    // La venta ya quedó VENCIDA en la base: recién ahora se devuelve su cupo.
                    LOG.info("VencimientoDeReservas: la venta " + venta.getId()
                            + " no se pagó a tiempo (la reserva valía hasta "
                            + ServicioDeVentas.enUtc(venta.getVenceReserva())
                            + "). Pasó a VENCIDA y se devuelve su cupo a Eventos. Entradas a devolver: "
                            + entradasReservadas(venta) + ".");
                    compras.devolverCupo(venta);
                    // Si la conversación de esa compra seguía en el índice, ya no sirve: con la venta
                    // vencida no se puede pagar ni cancelar. A la instancia la destruye el contenedor.
                    compras.olvidar(venta.getId());
                }
            }
        } catch (RuntimeException e) {
            LOG.warning("VencimientoDeReservas: la corrida se cortó y se reintenta en el próximo minuto. Motivo: " + e);
        }
    }

    private static int entradasReservadas(Venta venta) {
        return venta.getItems().stream().mapToInt(VentaItem::getCantidad).sum();
    }
}
