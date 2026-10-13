package com.palco.pagos.dominio;

/**
 * Estados de un pago. El nombre es lo que se guarda en la columna y lo que
 * viaja en la API. La tabla no lleva un check con esta lista: los valores
 * válidos los controla este enumerado, que es quien escribe la columna.
 */
public enum EstadoPago {
    /** La pasarela cobró. */
    APROBADO,
    /** La pasarela no cobró (fondos, tarjeta…). Es un resultado, no un error. */
    RECHAZADO,
    /** La pasarela todavía no resolvió. Llega con el webhook: hoy ninguna pasarela lo devuelve. */
    PENDIENTE,
    /** Quedó pendiente y la pasarela lo dio de baja. Llega con el webhook. */
    VENCIDO,
    /** Estaba aprobado y se devolvió la plata. Llega con el reembolso. */
    REEMBOLSADO
}
