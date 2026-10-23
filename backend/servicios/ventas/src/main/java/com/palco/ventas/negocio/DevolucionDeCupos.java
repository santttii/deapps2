package com.palco.ventas.negocio;

import java.util.List;
import java.util.logging.Logger;

import jakarta.ejb.DependsOn;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Venta;

/**
 * El barrido que reintenta las devoluciones de cupo que no llegaron a Eventos.
 *
 * Cuando una venta se cancela o se vence, el mismo UPDATE que la cierra le
 * pone la marca {@code devolucion_pendiente}, y quien la cerró devuelve el
 * cupo enseguida. Si Eventos no respondió, o el servidor se cayó antes de
 * devolverlo, la marca queda puesta y este barrido lo vuelve a intentar cada
 * minuto hasta que Eventos lo acepte. Sin esto, esos lugares quedaban sin
 * vender para siempre (limitación L4).
 *
 * Reintentar es seguro: cada devolución lleva una clave fija por venta y
 * sector, y Eventos no resta dos veces con la misma clave. Así, aunque una
 * devolución anterior se haya aplicado sin que Ventas supiera la respuesta, o
 * aunque el barrido y quien cerró la venta devuelvan a la vez, el cupo se
 * devuelve una sola vez: nunca un lugar vendido dos veces.
 *
 * Mismo diseño que {@link VencimientoDeReservas}: {@code @Singleton} para que
 * haya un solo barrido en el servidor, {@code @Schedule} no persistente (la
 * primera corrida toma todo lo atrasado), {@code @DependsOn} de las
 * migraciones y sin transacción propia: cada marca se saca en la transacción
 * corta del repositorio. Corre en el segundo 30 para no coincidir con el
 * vencimiento de reservas, que corre en el 0.
 */
@Singleton
@DependsOn("MigracionesVentas")
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class DevolucionDeCupos {

    private static final Logger LOG = Logger.getLogger(DevolucionDeCupos.class.getName());

    @Inject
    VentaRepository ventas;

    @Inject
    ComprasEnCurso compras;

    /**
     * Una corrida del barrido. Cada venta se intenta por separado: si Eventos
     * no responde por una, se sigue con la próxima, y la que falló queda para
     * el minuto siguiente. Si la base no responde, la corrida se corta con un
     * aviso y se reintenta en el próximo minuto.
     */
    @Schedule(hour = "*", minute = "*", second = "30", persistent = false)
    void reintentarDevoluciones() {
        List<Venta> pendientes;
        try {
            pendientes = ventas.buscarDevolucionesPendientes();
        } catch (RuntimeException e) {
            LOG.warning("DevolucionDeCupos: la corrida se cortó y se reintenta en el próximo minuto. Motivo: " + e);
            return;
        }
        for (Venta venta : pendientes) {
            LOG.info("DevolucionDeCupos: la venta " + venta.getId() + " (" + venta.getEstado()
                    + ") tiene el cupo sin devolver; se reintenta.");
            compras.devolverCupo(venta);
        }
    }
}
