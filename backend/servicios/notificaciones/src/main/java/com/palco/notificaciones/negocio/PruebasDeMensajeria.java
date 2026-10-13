package com.palco.notificaciones.negocio;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.Resource;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.jms.JMSConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.Topic;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.mensajeria.DestinosJms;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Entrada;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Titular;
import com.palco.common.mensajeria.PublicadorPagoConfirmado;

/**
 * Publica mensajes de prueba para poder ver la mensajería funcionando sin
 * hacer una compra. Es una herramienta de desarrollo: por eso no tiene una
 * interfaz {@code I...} como los componentes de negocio.
 *
 * Stateless: cada prueba arma su mensaje y no depende de la anterior.
 *
 * Es un Session Bean, y no código suelto en el recurso REST, porque el
 * publicador exige que quien lo llama ya esté en una transacción
 * ({@code MANDATORY}): la abre el contenedor al entrar a cada método de esta
 * clase, igual que la va a abrir en {@code ServicioDeVentas.confirmarCompra}.
 */
@Stateless
public class PruebasDeMensajeria {

    static final String PREFIJO_DE_PRUEBA = "prueba-";

    /** El publicador real, el mismo que usa Ventas: manda a la cola y al tópico. */
    @Inject
    PublicadorPagoConfirmado publicador;

    /** Solo para el mensaje inválido, que el publicador real se negaría a mandar. */
    @Inject
    @JMSConnectionFactory(DestinosJms.FABRICA_XA)
    JMSContext contexto;

    @Resource(lookup = DestinosJms.TOPICO_COMPRA)
    Topic topico;

    Clock reloj = Clock.systemUTC();

    /** Publica un mensaje de ejemplo y lo devuelve. Sale del servidor cuando este método termina bien. */
    public PagoConfirmadoMensaje publicarEjemplo() {
        PagoConfirmadoMensaje mensaje = ejemplo(reloj.instant());
        publicador.publicar(mensaje);
        return mensaje;
    }

    /**
     * Publica y después falla a propósito. {@code Conflicto} deshace la
     * transacción, y como el mensaje era parte de ella, no tiene que llegarle a
     * nadie: es lo que pasaría si la venta fallara después de publicar.
     */
    public void publicarYDespuesFallar() {
        publicador.publicar(ejemplo(reloj.instant()));
        throw new ErroresDeNegocio.Conflicto("Falla provocada a propósito después de publicar: "
                + "la transacción se deshace y el mensaje no sale.");
    }

    /**
     * Manda solo al tópico un texto que no es un {@code PAGO_CONFIRMADO}, para
     * ver los reintentos y la cola de mensajes fallidos. Lleva la propiedad
     * {@code tipoEvento}: sin ella el filtro de los MDB lo dejaría afuera y no
     * lo recibiría nadie.
     */
    public void publicarMensajeInvalido() {
        contexto.createProducer()
                .setProperty(DestinosJms.PROPIEDAD_TIPO_EVENTO, DestinosJms.PAGO_CONFIRMADO)
                .send(topico, "esto no es un PAGO_CONFIRMADO");
    }

    /**
     * Un mensaje completo y válido. El {@code ventaId} empieza con "prueba-"
     * para que se lo reconozca en el log y en la auditoría; esa venta no existe
     * en Ventas, que tiene que descartar el mensaje al recibirlo por la cola.
     */
    static PagoConfirmadoMensaje ejemplo(Instant ahora) {
        String ventaId = PREFIJO_DE_PRUEBA + UUID.randomUUID().toString().substring(0, 8);
        return new PagoConfirmadoMensaje(
                ventaId,
                "user-titular",
                "ev-1",
                List.of(new Entrada(ventaId + "-1", "Platea B", new Titular("Ana Gómez", "30123456")),
                        new Entrada(ventaId + "-2", "Platea B", new Titular("Luis Pérez", "31234567"))),
                60500,
                ahora.truncatedTo(ChronoUnit.SECONDS));
    }
}
