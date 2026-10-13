package com.palco.ventas.mensajeria;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.logging.Logger;

import jakarta.annotation.Resource;
import jakarta.ejb.ActivationConfigProperty;
import jakarta.ejb.MessageDriven;
import jakarta.ejb.MessageDrivenContext;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;

import com.palco.common.Textos;
import com.palco.common.mensajeria.DestinosJms;
import com.palco.common.mensajeria.MensajeInvalido;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.datos.VentaRepository;

/**
 * Consumidor de la cola de emisión de entradas. Por cada compra confirmada
 * llega un mensaje {@code PAGO_CONFIRMADO}; este componente emite las entradas
 * de esa venta (a cada una le pone su código QR y la deja {@code VALIDA}) y
 * pasa la venta de {@code CONFIRMADA} a {@code EMITIDA}.
 *
 * Es un punto de entrada delgado, como el recurso REST: lee el mensaje y
 * delega en los dos repositorios. Está en Ventas porque Ventas es el dueño de
 * las entradas. El pago no espera a la emisión: responde apenas confirma la
 * venta, y la emisión ocurre después, cuando el broker entrega el mensaje.
 *
 * Sin estado (stateless): como todo MDB, no guarda nada entre un mensaje y el
 * siguiente; todo lo que necesita viene en el mensaje. Por eso el contenedor
 * atiende con un pool de instancias. Lo que tiene que durar está en la base
 * (las entradas y la venta) o en el broker (los mensajes pendientes).
 *
 * Consumidor único: el destino es una cola, y el broker entrega cada mensaje
 * de una cola a un solo consumidor. Con un tópico, cada suscriptor recibiría
 * su copia y las entradas se emitirían más de una vez.
 *
 * La transacción la maneja el contenedor ({@code REQUIRED}): recibir el
 * mensaje, emitir las entradas y pasar la venta a {@code EMITIDA} se confirman
 * juntos. Si algo falla, no queda nada escrito (ni una entrada emitida a
 * medias) y el mensaje vuelve al broker, que lo entrega otra vez: son hasta 5
 * entregas en total, con 2 segundos entre una y otra, y después lo pasa a la
 * cola de mensajes fallidos (DLQ).
 *
 * Es idempotente, y lo garantiza la base, no este código. Un mensaje puede
 * llegar dos veces: la base no participa de un commit en dos fases, confirma
 * antes que el broker, y si el servidor se cayera justo entre los dos, el
 * broker volvería a entregar un mensaje cuyo trabajo ya quedó guardado. Por
 * eso cada cambio es un UPDATE con la condición adentro: la entrada tiene que
 * seguir {@code PENDIENTE_EMISION} y la venta {@code CONFIRMADA}. La segunda
 * vez no cambia ninguna fila, el código de la entrada queda como estaba y el
 * mensaje se confirma sin error. Vale también si dos copias llegan a la vez a
 * dos instancias: la base deja pasar a una sola.
 */
@MessageDriven(name = "EmisionDeEntradas", activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = DestinosJms.COLA_EMISION),
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "jakarta.jms.Queue"),
        @ActivationConfigProperty(propertyName = "messageSelector", propertyValue = DestinosJms.SELECTOR_PAGO_CONFIRMADO) })
public class EmisionDeEntradasMDB implements MessageListener {

    private static final Logger LOG = Logger.getLogger(EmisionDeEntradasMDB.class.getName());

    @Inject
    VentaRepository ventas;

    @Inject
    EntradaRepository entradas;

    @Resource
    MessageDrivenContext contexto;

    /** De dónde sale la fecha de emisión. No es estado: es fijo, y los tests lo reemplazan por un reloj detenido. */
    Clock reloj = Clock.systemUTC();

    @Override
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public void onMessage(Message recibido) {
        PagoConfirmadoMensaje mensaje;
        try {
            mensaje = PagoConfirmadoMensaje.desdeJson(texto(recibido));
        } catch (MensajeInvalido e) {
            // No se puede emitir lo que no se entiende, pero tampoco se lo puede perder. Se deshace la
            // transacción en lugar de lanzar una excepción (que dejaría una traza por intento): el broker
            // reintenta y después lo guarda en la DLQ, donde queda para revisarlo.
            LOG.warning("EmisionDeEntradas: mensaje inválido (entrega " + numeroDeEntrega(recibido)
                    + "), vuelve al broker. " + Textos.enUnaLinea(e.getMessage()));
            contexto.setRollbackOnly();
            return;
        }

        String ventaId = mensaje.ventaId();
        // El id viene en el mensaje, y puede no ser el de una venta de este servicio: al log va en
        // un solo renglón (ver Textos.enUnaLinea).
        String ventaEnElLog = Textos.enUnaLinea(ventaId);
        if (ventas.buscarPorId(ventaId).isEmpty()) {
            // Pasa de verdad: el broker guarda los mensajes en disco y la base de desarrollo se vacía al
            // reiniciar; también lo producen los mensajes de prueba. Reintentar no la haría aparecer (la
            // venta queda guardada antes de que el broker entregue su mensaje): se avisa y el mensaje
            // queda consumido, así no traba la cola ni termina en la DLQ.
            LOG.warning("EmisionDeEntradas: se descarta el mensaje de la venta " + ventaEnElLog
                    + ", que no existe en Ventas.");
            return;
        }

        // Primero las entradas y después la venta: las dos cosas en la transacción de este método.
        int emitidas = entradas.emitirPendientes(ventaId, ahora());
        boolean pasoAEmitida = ventas.marcarEmitida(ventaId);

        // Una línea por mensaje procesado, con el id de la venta. Si no cambió ninguna fila, el mensaje
        // ya se había procesado: no es un error, y se confirma igual para que el broker no lo repita.
        String resultado = emitidas == 0 && !pasoAEmitida
                ? "No había nada pendiente: es un mensaje repetido y no cambió nada."
                : "Entradas emitidas: " + emitidas + "." + (pasoAEmitida ? " La venta pasó a EMITIDA." : "");
        LOG.info("EmisionDeEntradas: procesado el mensaje de la venta " + ventaEnElLog + ". " + resultado);
    }

    /**
     * La fecha de emisión: el instante del reloj en UTC y sin fracción de
     * segundo, como todas las fechas que guarda este servicio. No depende de la
     * zona horaria del servidor, que corre en hora argentina.
     */
    private LocalDateTime ahora() {
        return LocalDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * El texto del mensaje. Lanza {@link MensajeInvalido} si no es un
     * {@code TextMessage} o si no se puede leer: {@code PAGO_CONFIRMADO} viaja
     * siempre como texto JSON.
     */
    private static String texto(Message recibido) {
        if (!(recibido instanceof TextMessage conTexto)) {
            throw new MensajeInvalido("El mensaje no es un TextMessage.");
        }
        try {
            return conTexto.getText();
        } catch (JMSException e) {
            throw new MensajeInvalido("No se pudo leer el texto del mensaje.", e);
        }
    }

    /**
     * Cuántas veces entregó el broker este mensaje: 1 la primera vez, 2 en el
     * primer reintento. Sale de {@code JMSXDeliveryCount}, una propiedad
     * estándar de JMS. Solo se usa para el log; si no se puede leer, devuelve 0.
     */
    private static int numeroDeEntrega(Message recibido) {
        try {
            return recibido.getIntProperty("JMSXDeliveryCount");
        } catch (JMSException | NumberFormatException e) {
            return 0;
        }
    }
}
