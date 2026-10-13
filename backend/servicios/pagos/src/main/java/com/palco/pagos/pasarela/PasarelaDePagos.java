package com.palco.pagos.pasarela;

/**
 * La pasarela es el servicio externo que cobra. Esta interfaz es nuestra: dice
 * qué le pide Palco, con los nombres de Palco. {@code ServicioDePagos} conoce
 * solo esto; no sabe si del otro lado hay un simulador o Mercado Pago.
 *
 * Cada implementación habla con un proveedor y nada más: no guarda el pago ni
 * decide si el pedido está repetido. Hoy existe {@link PagosFake}. Cuando se
 * sume Mercado Pago va a ser un Adapter, porque traduce una API ajena a esta
 * interfaz. Cuál se usa lo elige {@link FabricaDePasarela}.
 */
public interface PasarelaDePagos {

    /** Nombre del proveedor. Es el valor de la configuración y lo que se guarda con cada pago. */
    String nombre();

    /**
     * Intenta el cobro. Un rechazo vuelve como resultado, no como excepción.
     * Si la pasarela no contesta lanza {@code ErroresDeNegocio.SinRespuesta}.
     *
     * @param token             el que entrega la pasarela al cargar la tarjeta; sirve para un solo cobro
     * @param claveIdempotencia identifica el pedido: repetirla no tiene que repetir el cobro
     */
    ResultadoPasarela cobrar(String ventaId, long monto, String medioDePago, String token, String claveIdempotencia);
}
