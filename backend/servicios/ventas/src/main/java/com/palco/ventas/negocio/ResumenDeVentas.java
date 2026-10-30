package com.palco.ventas.negocio;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

import com.palco.common.ErroresDeNegocio;
import com.palco.ventas.api.Dtos.DiaDeVentasDto;
import com.palco.ventas.api.Dtos.LiquidacionDto;
import com.palco.ventas.api.Dtos.ResumenDeVentasDto;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;
import com.palco.ventas.negocio.externos.ClienteDeEventos;

/**
 * Las ventas por día y la liquidación de un evento, para el panel del
 * organizador.
 *
 * Está en Ventas porque Ventas es la dueña de las ventas: Eventos conoce el
 * cupo vendido de cada sector, pero no cuándo se vendió ni a cuánto. Lo único
 * que se le pregunta a Eventos es de quién es el evento, para que un
 * organizador vea solo el resumen de los suyos.
 *
 * El agrupado por día se hace en Java y no con un {@code group by} en la
 * consulta, por la misma razón por la que Eventos busca por texto en Java:
 * sacar la fecha de un timestamp se escribe distinto en cada base, y el
 * conjunto de ventas de un evento es chico.
 *
 * Stateless: cada resumen se arma con lo que trae la consulta.
 */
@Stateless
public class ResumenDeVentas {

    private static final DateTimeFormatter DIA = DateTimeFormatter.ISO_LOCAL_DATE;

    @Inject
    VentaRepository ventas;

    @Inject
    ClienteDeEventos eventos;

    /**
     * El resumen del evento, si es del organizador que pregunta. Para otro
     * organizador el evento no existe (404), igual que uno que no existe de
     * verdad: es el mismo criterio que usa el resto de la API con lo ajeno.
     *
     * Si Eventos no responde, lanza {@code SinRespuesta}: sin saber de quién
     * es el evento no se muestran sus ventas.
     */
    public ResumenDeVentasDto delEvento(String eventoId, String organizadorId) {
        if (!organizadorId.equals(eventos.organizadorDe(eventoId))) {
            throw new ErroresDeNegocio.NoEncontrado("No existe ese evento entre los tuyos.");
        }
        return resumir(eventoId, ventas.pagadasDelEventoConItems(eventoId));
    }

    /**
     * Arma el resumen a partir de las ventas. No es privado para poder probarlo
     * con una lista armada a mano, sin base ni llamadas a Eventos.
     */
    static ResumenDeVentasDto resumir(String eventoId, List<Venta> pagadas) {
        // LinkedHashMap: la consulta viene ordenada por fecha de confirmación, y el resumen
        // conserva ese orden, del día más viejo al más nuevo.
        Map<LocalDate, Acumulado> porDia = new LinkedHashMap<>();
        Acumulado total = new Acumulado();

        for (Venta venta : pagadas) {
            // Una venta pagada siempre tiene fecha de confirmación; si faltara, se la cuenta
            // por la fecha de creación antes que dejarla afuera del resumen.
            LocalDate dia = (venta.getConfirmada() != null ? venta.getConfirmada() : venta.getCreada())
                    .toLocalDate();
            int entradas = venta.getItems().stream().mapToInt(VentaItem::getCantidad).sum();
            porDia.computeIfAbsent(dia, d -> new Acumulado()).sumar(venta, entradas);
            total.sumar(venta, entradas);
        }

        List<DiaDeVentasDto> dias = new ArrayList<>();
        porDia.forEach((dia, acumulado) -> dias.add(new DiaDeVentasDto(dia.format(DIA), acumulado.ventas,
                acumulado.entradas, acumulado.subtotal, acumulado.cargo, acumulado.total)));

        return new ResumenDeVentasDto(eventoId, List.copyOf(dias), new LiquidacionDto(total.ventas, total.entradas,
                total.total, total.cargo, total.subtotal));
    }

    /** Los totales que se van sumando, de un día o de todo el evento. */
    private static final class Acumulado {
        private int ventas;
        private int entradas;
        private long subtotal;
        private long cargo;
        private long total;

        void sumar(Venta venta, int entradasDeLaVenta) {
            ventas++;
            entradas += entradasDeLaVenta;
            subtotal += venta.getSubtotal();
            cargo += venta.getCargoServicio();
            total += venta.getMontoTotal();
        }
    }
}
