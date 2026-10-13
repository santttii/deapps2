package com.palco.pagos.pasarela;

import com.palco.pagos.dominio.EstadoPago;

/**
 * Lo que contesta la pasarela después de intentar un cobro.
 *
 * @param estado    {@code APROBADO} o {@code RECHAZADO}
 * @param motivo    por qué se rechazó; null si se aprobó
 * @param idExterno el id del pago en la pasarela
 */
public record ResultadoPasarela(EstadoPago estado, String motivo, String idExterno) {
}
