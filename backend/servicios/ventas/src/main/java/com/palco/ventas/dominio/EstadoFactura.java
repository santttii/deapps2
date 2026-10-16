package com.palco.ventas.dominio;

/**
 * Estado de la factura de una venta. El nombre es lo que se guarda en la
 * columna y lo que viaja en la API; la tabla no lleva un check con esta lista.
 *
 * Hasta que exista la facturación toda venta queda en
 * {@code NO_APLICA}. Los otros tres valores ya están porque son parte del
 * contrato de la API.
 */
public enum EstadoFactura {
    /** Todavía no se pidió ninguna factura. */
    NO_APLICA,
    /** El servicio de facturación la autorizó. */
    AUTORIZADA,
    /** Se pidió y todavía no hay respuesta. */
    PENDIENTE,
    /** El servicio de facturación la rechazó. */
    RECHAZADA
}
