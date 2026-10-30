package com.palco.notificaciones;

import java.util.ArrayList;
import java.util.List;

import com.palco.common.mensajeria.CompraAnuladaMensaje;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.notificaciones.negocio.INotificaciones;

/**
 * Doble de {@link INotificaciones} para los tests: anota a quién se le avisó,
 * por compra del catálogo, por reventa o por cancelación del evento.
 */
public class AvisosAnotados implements INotificaciones {

    public final List<PagoConfirmadoMensaje> compras = new ArrayList<>();
    public final List<PagoConfirmadoMensaje> reventas = new ArrayList<>();
    public final List<CompraAnuladaMensaje> cancelaciones = new ArrayList<>();

    @Override
    public void enviarConfirmacion(PagoConfirmadoMensaje mensaje) {
        compras.add(mensaje);
    }

    @Override
    public void enviarConfirmacionDeReventa(PagoConfirmadoMensaje mensaje) {
        reventas.add(mensaje);
    }

    @Override
    public void enviarAlertaCancelacion(CompraAnuladaMensaje mensaje) {
        cancelaciones.add(mensaje);
    }
}
