package com.palco.arca;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * La numeración de los comprobantes y los que ya se autorizaron, en memoria.
 *
 * Como en el sistema real, el número lo asigna ARCA y es correlativo por punto
 * de venta y tipo de comprobante: el primer comprobante de cada par es el 1.
 * Facturación no lleva contador propio.
 *
 * Simulación: el ARCA real no autoriza dos veces la misma operación, y quien
 * no sabe si su pedido llegó consulta con {@code FECompConsultar} antes de
 * repetirlo. Acá se simplifica: si se vuelve a pedir el CAE de una venta que
 * ya tiene comprobante, se devuelve el mismo, sin numerar otro. Es lo que
 * permite que Facturación repita el pedido cuando no supo la respuesta (un
 * tiempo de espera vencido, o una falla al guardar la factura) sin saltear
 * números.
 *
 * Sin base de datos: todo vive mientras vive el despliegue. Al redesplegar el
 * simulado la numeración vuelve a empezar, como si fuera otro ARCA; se acepta
 * porque es un simulador.
 *
 * Los métodos son {@code synchronized}: los pedidos llegan en varios hilos a
 * la vez y dos no pueden llevarse el mismo número. Adentro solo hay cuentas en
 * memoria, así que el bloqueo dura nada.
 */
final class Talonario {

    /** Días de validez del CAE desde la fecha del comprobante (el real da diez). */
    static final int DIAS_DE_VALIDEZ_DEL_CAE = 10;

    static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.BASIC_ISO_DATE;

    /** Lo que ARCA autorizó: número, CAE de 14 dígitos y su vencimiento en {@code aaaammdd}. */
    record Comprobante(int tipo, int puntoDeVenta, long numero, String cae, String vencimientoCae) {
    }

    /** Clave "tipo/punto de venta" → último número asignado. */
    private final Map<String, Long> ultimos = new HashMap<>();

    /** Id de la venta → el comprobante que ya se le dio. */
    private final Map<String, Comprobante> porVenta = new HashMap<>();

    /**
     * El comprobante de esa venta: el que ya tenía o uno nuevo con el número
     * siguiente del punto de venta y tipo.
     */
    synchronized Comprobante autorizar(int tipo, int puntoDeVenta, LocalDate fecha, String ventaId) {
        Comprobante anterior = porVenta.get(ventaId);
        if (anterior != null) {
            return anterior;
        }
        long numero = ultimos.merge(tipo + "/" + puntoDeVenta, 1L, Long::sum);
        Comprobante nuevo = new Comprobante(tipo, puntoDeVenta, numero, nuevoCae(),
                fecha.plusDays(DIAS_DE_VALIDEZ_DEL_CAE).format(FORMATO_FECHA));
        porVenta.put(ventaId, nuevo);
        return nuevo;
    }

    /** El último número asignado en ese punto de venta y tipo; 0 si todavía no hay ninguno. */
    synchronized long ultimoNumero(int tipo, int puntoDeVenta) {
        return ultimos.getOrDefault(tipo + "/" + puntoDeVenta, 0L);
    }

    /** El CAE real es un código de 14 dígitos que da ARCA; el simulado lo inventa al azar. */
    private static String nuevoCae() {
        return Long.toString(ThreadLocalRandom.current().nextLong(10_000_000_000_000L, 100_000_000_000_000L));
    }
}
