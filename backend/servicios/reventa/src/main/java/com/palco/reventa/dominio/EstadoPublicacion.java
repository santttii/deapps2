package com.palco.reventa.dominio;

/**
 * Estados de una publicación. El nombre es lo que se guarda en la columna y lo
 * que viaja en la API; la tabla no lleva un check con esta lista.
 *
 * Nace {@code ACTIVA} y sale por uno de dos caminos: {@code VENDIDA} (alguien
 * la compró) o {@code RETIRADA} (el vendedor la sacó). Cada cambio es un
 * UPDATE condicional de {@code PublicacionRepository}.
 */
public enum EstadoPublicacion {
    /** En venta: aparece en el catálogo de la reventa del evento. */
    ACTIVA,
    /** Se vendió: la entrada pasó al comprador. */
    VENDIDA,
    /** El vendedor la retiró y la entrada volvió a ser suya, VALIDA. */
    RETIRADA
}
