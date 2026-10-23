package com.palco.pagos.pasarela;

import com.palco.common.ErroresDeNegocio;
import com.palco.pagos.dominio.EstadoPago;

/**
 * Pasarela simulada: no llama a nadie ni mueve plata. El resultado lo decide
 * el token, para poder probar los tres caminos a pedido:
 *
 * <ul>
 * <li>{@code tok_rechazado}: rechazado, con motivo {@code fondos_insuficientes};</li>
 * <li>{@code tok_sin_respuesta}: la pasarela no contesta;</li>
 * <li>cualquier otro, por ejemplo {@code tok_test}: aprobado.</li>
 * </ul>
 *
 * Un reembolso siempre sale bien: el token no se guarda, así que no hay con
 * qué elegir otro resultado. El camino de una pasarela que no contesta al
 * devolver se prueba con un doble en los tests.
 *
 * Son los mismos tokens que usa el simulador de cobros de Ventas.
 *
 * No tiene estado: no guarda ningún dato entre una llamada y la siguiente, y
 * el resultado depende solo de lo que recibe. Por eso el mismo pedido da
 * siempre el mismo resultado y alcanza con una sola instancia para todos.
 */
public class PagosFake implements PasarelaDePagos {

    /** Valor de {@code palco.pagos.proveedor} que elige esta pasarela. */
    public static final String NOMBRE = "fake";

    public static final String TOKEN_RECHAZADO = "tok_rechazado";
    public static final String TOKEN_SIN_RESPUESTA = "tok_sin_respuesta";
    public static final String MOTIVO_DE_RECHAZO = "fondos_insuficientes";

    @Override
    public String nombre() {
        return NOMBRE;
    }

    @Override
    public ResultadoPasarela cobrar(String ventaId, long monto, String medioDePago, String token,
            String claveIdempotencia) {
        if (TOKEN_SIN_RESPUESTA.equals(token)) {
            throw new ErroresDeNegocio.SinRespuesta("La pasarela de pagos no respondió. Probá de nuevo.");
        }
        // Una pasarela real devuelve su propio id del pago. Acá sale de la clave,
        // así repetir el pedido devuelve también el mismo id.
        String idExterno = "fake-" + claveIdempotencia;
        if (TOKEN_RECHAZADO.equals(token)) {
            return new ResultadoPasarela(EstadoPago.RECHAZADO, MOTIVO_DE_RECHAZO, idExterno);
        }
        return new ResultadoPasarela(EstadoPago.APROBADO, null, idExterno);
    }

    @Override
    public void reembolsar(String idExterno, long monto, String claveIdempotencia) {
        // No mueve plata: no hay nada que hacer, y repetirlo da lo mismo.
    }
}
