package com.palco.notificaciones;

import java.util.ArrayList;
import java.util.List;

import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.notificaciones.negocio.INotificaciones;

/** Doble de {@link INotificaciones} para los tests: anota a quién se le avisó, por compra o por reventa. */
public class AvisosAnotados implements INotificaciones {

    public final List<PagoConfirmadoMensaje> compras = new ArrayList<>();
    public final List<PagoConfirmadoMensaje> reventas = new ArrayList<>();

    @Override
    public void enviarConfirmacion(PagoConfirmadoMensaje mensaje) {
        compras.add(mensaje);
    }

    @Override
    public void enviarConfirmacionDeReventa(PagoConfirmadoMensaje mensaje) {
        reventas.add(mensaje);
    }
}
