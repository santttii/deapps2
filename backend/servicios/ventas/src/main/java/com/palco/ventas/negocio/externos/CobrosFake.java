package com.palco.ventas.negocio.externos;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import com.palco.common.ErroresDeNegocio;

/**
 * Cobro simulado: no llama a nadie ni mueve plata. El resultado lo decide el
 * token, para poder probar los tres caminos a pedido:
 *
 * <ul>
 * <li>{@code tok_rechazado}: rechazado, con motivo {@code fondos_insuficientes};</li>
 * <li>{@code tok_sin_respuesta}: quien cobra no contesta;</li>
 * <li>cualquier otro, por ejemplo {@code tok_test}: aprobado.</li>
 * </ul>
 *
 * Son los mismos tokens que usa la pasarela simulada del servicio de Pagos.
 *
 * Es un bean {@code @ApplicationScoped}: hay una sola instancia para todo el
 * servicio. No guarda nada entre una llamada y la siguiente (el resultado
 * depende solo de lo que recibe), así que no hace falta más de una ni un
 * Session Bean. Lleva la anotación porque el repo no tiene {@code beans.xml}:
 * sin ella no se podría inyectar.
 *
 * {@code @Typed} hace que solo se pueda inyectar por su clase y no como
 * {@code Cobros}: el {@code Cobros} que recibe la compra es el que entrega
 * {@link FabricaDeCobros}, que usa este simulador solo si la configuración
 * lo pide.
 */
@ApplicationScoped
@Typed(CobrosFake.class)
public class CobrosFake implements Cobros {

    /** Valor de {@code palco.ventas.cobros} que elige este cobro. */
    public static final String NOMBRE = "fake";

    public static final String TOKEN_RECHAZADO = "tok_rechazado";
    public static final String TOKEN_SIN_RESPUESTA = "tok_sin_respuesta";
    public static final String MOTIVO_DE_RECHAZO = "fondos_insuficientes";

    @Override
    public ResultadoDeCobro cobrar(String ventaId, long monto, String medioDePago, String token,
            String claveIdempotencia) {
        if (TOKEN_SIN_RESPUESTA.equals(token)) {
            throw new ErroresDeNegocio.SinRespuesta("El servicio de pagos no respondió. Probá de nuevo.");
        }
        String pagoId = idDelPago(claveIdempotencia);
        if (TOKEN_RECHAZADO.equals(token)) {
            return new ResultadoDeCobro(pagoId, ResultadoDeCobro.RECHAZADO, MOTIVO_DE_RECHAZO);
        }
        return new ResultadoDeCobro(pagoId, ResultadoDeCobro.APROBADO, null);
    }

    /**
     * El simulador no guarda los cobros, así que no sabe cómo terminó uno: un
     * cobro aprobado y luego perdido por una falla de Ventas no deja rastro.
     * Responde que no se sabe, y la venta queda retenida, como antes de que
     * existiera la conciliación. La conciliación de verdad se prueba con el
     * servicio de Pagos, que es el cobro por defecto.
     */
    @Override
    public Optional<ResultadoDeCobro> buscarCobro(String ventaId, String claveIdempotencia) {
        throw new ErroresDeNegocio.SinRespuesta("El simulador de cobros no guarda los cobros: no puede decir si la venta "
                + ventaId + " se cobró.");
    }

    /**
     * El id del pago sale de la clave de idempotencia, no del azar: el servicio
     * de Pagos devuelve el mismo pago cuando se repite la clave, y el simulador
     * hace lo mismo sin guardar nada. {@code nameUUIDFromBytes} da siempre el
     * mismo UUID para el mismo texto.
     */
    private static String idDelPago(String claveIdempotencia) {
        UUID uuid = UUID.nameUUIDFromBytes(claveIdempotencia.getBytes(StandardCharsets.UTF_8));
        return "P-" + uuid.toString().substring(0, 8);
    }
}
