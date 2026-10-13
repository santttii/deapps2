package com.palco.notificaciones.negocio;

import java.util.logging.Logger;
import java.util.stream.Collectors;

import jakarta.ejb.Stateless;

import com.palco.common.mensajeria.PagoConfirmadoMensaje;

/**
 * Stateless: cada aviso se arma solo con el mensaje que llega y no depende del
 * aviso anterior, así que el contenedor reutiliza un pool de instancias.
 */
@Stateless
public class ServicioDeNotificaciones implements INotificaciones {

    private static final Logger LOG = Logger.getLogger(ServicioDeNotificaciones.class.getName());

    @Override
    public void enviarConfirmacion(PagoConfirmadoMensaje mensaje) {
        // TODO(09/11): mandarlo por email. El mensaje trae compradorId y no el email:
        // hay que pedírselo a Usuarios. Mientras tanto el aviso queda en el log del servidor.
        LOG.info(textoDelAviso(mensaje));
    }

    /**
     * El texto que recibiría el comprador. Lleva el nombre de cada titular pero
     * no su DNI: el log lo lee cualquiera que administre el servidor.
     */
    static String textoDelAviso(PagoConfirmadoMensaje mensaje) {
        String entradas = mensaje.entradas().stream()
                .map(e -> e.sector() + " (" + e.titular().nombre() + ")")
                .collect(Collectors.joining(", "));
        int cantidad = mensaje.entradas().size();

        return "Aviso al comprador " + mensaje.compradorId()
                + ": tu compra " + mensaje.ventaId() + " quedó confirmada. Evento " + mensaje.eventoId()
                + ", " + cantidad + (cantidad == 1 ? " entrada: " : " entradas: ") + entradas
                + ". Total: $" + mensaje.montoTotal() + ".";
    }
}
