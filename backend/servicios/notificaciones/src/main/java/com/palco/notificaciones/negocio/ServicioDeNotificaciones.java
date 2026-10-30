package com.palco.notificaciones.negocio;

import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

import com.palco.common.Textos;
import com.palco.common.email.CorreoAlLog;
import com.palco.common.email.Correos;
import com.palco.common.email.Email;
import com.palco.common.mensajeria.CompraAnuladaMensaje;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.notificaciones.negocio.externos.ClienteDeUsuarios;

/**
 * Stateless: cada aviso se arma solo con el mensaje que llega y no depende del
 * aviso anterior, así que el contenedor reutiliza un pool de instancias.
 *
 * El mensaje trae el {@code compradorId} y no su email, así que el email se le
 * pide a Usuarios. Si Usuarios no contesta, el aviso queda en el log y el
 * mensaje se da por atendido igual: un aviso que no sale no puede hacer que el
 * mensaje se reintente y la compra se avise dos veces.
 */
@Stateless
public class ServicioDeNotificaciones implements INotificaciones {

    private static final Logger LOG = Logger.getLogger(ServicioDeNotificaciones.class.getName());

    @Inject
    ClienteDeUsuarios usuarios;

    /**
     * Por dónde salen los emails. En el servidor lo reemplaza CDI con lo que
     * elija {@code FabricaDeCorreos}; el valor de acá es el que usan los tests,
     * que no tienen contenedor.
     */
    @Inject
    Correos correos = new CorreoAlLog();

    @Override
    public void enviarConfirmacion(PagoConfirmadoMensaje mensaje) {
        avisar(mensaje, false);
    }

    @Override
    public void enviarConfirmacionDeReventa(PagoConfirmadoMensaje mensaje) {
        avisar(mensaje, true);
    }

    @Override
    public void enviarAlertaCancelacion(CompraAnuladaMensaje mensaje) {
        Optional<ClienteDeUsuarios.Usuario> comprador = usuarios.buscar(mensaje.compradorId());
        if (comprador.isEmpty()) {
            LOG.info(textoDeLaCancelacion(mensaje));
            return;
        }
        correos.enviar(new Email(comprador.get().email(),
                "Se canceló " + mensaje.titulo(),
                cuerpoDeLaCancelacion(mensaje, comprador.get().nombre())));
    }

    /**
     * El email de la cancelación. Dice si el dinero ya volvió o si está en
     * camino: {@code reembolsado} puede ser false porque la entrada se anula
     * aunque Pagos no haya contestado, y prometer una devolución hecha sería
     * mentirle al comprador.
     */
    static String cuerpoDeLaCancelacion(CompraAnuladaMensaje mensaje, String nombre) {
        return "Hola " + nombre + ": se canceló " + mensaje.titulo()
                + ", así que tu compra " + mensaje.ventaId() + " quedó anulada y tus entradas ya no sirven.\n\n"
                + (mensaje.motivo() == null ? "" : "Motivo: " + mensaje.motivo() + "\n\n")
                + (mensaje.reembolsado()
                        ? "Te devolvimos los $" + mensaje.monto() + " que habías pagado. Según tu banco, "
                                + "puede tardar unos días en aparecer en el resumen."
                        : "Estamos gestionando la devolución de los $" + mensaje.monto() + " que habías pagado. "
                                + "Te vamos a avisar cuando se acredite.")
                + "\n\nPerdón por el inconveniente.";
    }

    /** El texto de la cancelación para el log, cuando no se consiguió el email. Siempre en un solo renglón. */
    static String textoDeLaCancelacion(CompraAnuladaMensaje mensaje) {
        return Textos.enUnaLinea("Aviso al comprador " + mensaje.compradorId() + ": se canceló "
                + mensaje.titulo() + " (" + mensaje.eventoId() + ") y tu compra " + mensaje.ventaId()
                + " quedó anulada. Pagado: $" + mensaje.monto()
                + ". Reembolso: " + (mensaje.reembolsado() ? "hecho" : "pendiente") + ".");
    }

    /**
     * Le manda el aviso al comprador. Sin email no hay a dónde mandarlo: queda
     * el texto en el log, que es lo que hacía este servicio antes de tener
     * proveedor de correo.
     */
    private void avisar(PagoConfirmadoMensaje mensaje, boolean enLaReventa) {
        String texto = textoDelAviso(mensaje, enLaReventa);
        Optional<ClienteDeUsuarios.Usuario> comprador = usuarios.buscar(mensaje.compradorId());
        if (comprador.isEmpty()) {
            LOG.info(texto);
            return;
        }
        correos.enviar(new Email(comprador.get().email(),
                enLaReventa ? "Tu compra en la reventa de Palco está confirmada" : "Tu compra en Palco está confirmada",
                cuerpoDelEmail(mensaje, comprador.get().nombre(), enLaReventa)));
    }

    /**
     * El email que recibe el comprador. Lleva el nombre de cada titular pero no
     * su DNI: el titular lo presenta en la puerta, no hace falta repetirlo acá.
     */
    static String cuerpoDelEmail(PagoConfirmadoMensaje mensaje, String nombre, boolean enLaReventa) {
        int cantidad = mensaje.entradas().size();
        return "Hola " + nombre + ": tu compra " + (enLaReventa ? "en la reventa oficial " : "")
                + mensaje.ventaId() + " quedó confirmada.\n\n"
                + "Evento: " + mensaje.eventoId() + "\n"
                + (cantidad == 1 ? "Entrada:\n" : "Entradas (" + cantidad + "):\n")
                + mensaje.entradas().stream()
                        .map(e -> "  - " + e.sector() + " (" + e.titular().nombre() + ")")
                        .collect(Collectors.joining("\n"))
                + "\n\nTotal: $" + mensaje.montoTotal() + "\n\n"
                + "Las entradas están en \"Mis entradas\". En la puerta se controla el documento de cada titular.";
    }

    /**
     * El texto que recibiría el comprador, para el log. Lleva el nombre de cada
     * titular pero no su DNI: el log lo lee cualquiera que administre el servidor.
     *
     * Sale siempre en un solo renglón. Los ids, el sector y los nombres vienen
     * en el mensaje, y el aviso se escribe en el log: con un salto de línea
     * adentro de un nombre aparecería ahí un renglón falso (pasó en una
     * revisión). Ventas rechaza esos nombres al comprar, pero este servicio no
     * depende de eso: el mensaje podría publicarlo otro.
     */
    static String textoDelAviso(PagoConfirmadoMensaje mensaje, boolean enLaReventa) {
        String entradas = mensaje.entradas().stream()
                .map(e -> e.sector() + " (" + e.titular().nombre() + ")")
                .collect(Collectors.joining(", "));
        int cantidad = mensaje.entradas().size();

        return Textos.enUnaLinea("Aviso al comprador " + mensaje.compradorId()
                + ": tu compra " + (enLaReventa ? "en la reventa oficial " : "") + mensaje.ventaId() + " quedó confirmada. Evento " + mensaje.eventoId()
                + ", " + cantidad + (cantidad == 1 ? " entrada: " : " entradas: ") + entradas
                + ". Total: $" + mensaje.montoTotal() + ".");
    }
}
