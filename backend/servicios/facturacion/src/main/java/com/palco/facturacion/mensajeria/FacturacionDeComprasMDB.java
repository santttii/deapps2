package com.palco.facturacion.mensajeria;

import java.util.logging.Logger;

import jakarta.annotation.Resource;
import jakarta.ejb.ActivationConfigProperty;
import jakarta.ejb.MessageDriven;
import jakarta.ejb.MessageDrivenContext;
import jakarta.inject.Inject;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.Textos;
import com.palco.common.mensajeria.DestinosJms;
import com.palco.common.mensajeria.MensajeInvalido;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.facturacion.api.Dtos.FacturaDto;
import com.palco.facturacion.negocio.IFacturacion;

/**
 * Tercer suscriptor del tópico de compras confirmadas: factura cada compra.
 *
 * Es un punto de entrada delgado, como el recurso REST: lee el mensaje y
 * delega en {@link IFacturacion}. Como todo MDB, no guarda nada entre un
 * mensaje y el siguiente.
 *
 * Por qué el tópico y no una llamada desde Ventas: la compra no necesita la
 * factura para confirmarse, así que esperar a ARCA dentro de la confirmación
 * solo la haría más lenta y más frágil. El tópico ya existía: sumar este
 * suscriptor no cambió nada en Ventas.
 *
 * Suscripción durable ({@code clientId} {@code palco-facturacion} y
 * {@code subscriptionName} {@code facturacion-compras-confirmadas}, un par que
 * no se repite en otro MDB): si Facturación está detenido, el broker le guarda
 * los mensajes. Una compra no se puede quedar sin factura.
 *
 * {@code maxSession = 1}: un solo consumidor, que factura de a una compra.
 * Así no se le piden a ARCA dos facturas de la misma venta a la vez, y los
 * números salen en el orden en que llegan los mensajes.
 *
 * La transacción la maneja el contenedor. Si ARCA no responde o rechaza el
 * comprobante, se deshace con {@code setRollbackOnly()} en lugar de lanzar una
 * excepción (que dejaría una traza por intento): el mensaje vuelve al broker,
 * que lo reentrega a los 2 segundos y al quinto intento lo pasa a la cola de
 * mensajes fallidos (DLQ). No hay una cola de reintento propia: es la misma
 * política que tienen los demás consumidores ({@code configurar.cli}).
 */
@MessageDriven(name = "FacturacionDeCompras", activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = DestinosJms.TOPICO_COMPRA),
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "jakarta.jms.Topic"),
        @ActivationConfigProperty(propertyName = "messageSelector", propertyValue = DestinosJms.SELECTOR_PAGO_CONFIRMADO),
        @ActivationConfigProperty(propertyName = "subscriptionDurability", propertyValue = "Durable"),
        @ActivationConfigProperty(propertyName = "clientId", propertyValue = FacturacionDeComprasMDB.CLIENT_ID),
        @ActivationConfigProperty(propertyName = "subscriptionName", propertyValue = FacturacionDeComprasMDB.SUSCRIPCION),
        @ActivationConfigProperty(propertyName = "maxSession", propertyValue = "1") })
public class FacturacionDeComprasMDB implements MessageListener {

    private static final Logger LOG = Logger.getLogger(FacturacionDeComprasMDB.class.getName());

    static final String CLIENT_ID = "palco-facturacion";
    static final String SUSCRIPCION = "facturacion-compras-confirmadas";

    @Inject
    IFacturacion facturacion;

    @Resource
    MessageDrivenContext contexto;

    @Override
    public void onMessage(Message recibido) {
        PagoConfirmadoMensaje mensaje;
        try {
            mensaje = PagoConfirmadoMensaje.desdeJson(LectorDeMensajes.texto(recibido));
        } catch (MensajeInvalido e) {
            // No se puede facturar lo que no se entiende, pero tampoco se lo puede perder:
            // va a la DLQ después de los reintentos, como en auditoría.
            LOG.warning("FacturacionDeCompras: mensaje inválido (entrega " + LectorDeMensajes.numeroDeEntrega(recibido)
                    + "), vuelve al broker. " + Textos.enUnaLinea(e.getMessage()));
            contexto.setRollbackOnly();
            return;
        }
        try {
            FacturaDto factura = facturacion.emitirFactura(mensaje);
            // El id viene en el mensaje: va al log en un solo renglón (ver Textos.enUnaLinea).
            LOG.info("FacturacionDeCompras: procesado el mensaje de la venta " + Textos.enUnaLinea(mensaje.ventaId())
                    + " (factura " + factura.comprobante() + ").");
        } catch (ErroresDeNegocio.SinRespuesta | ErroresDeNegocio.ReglaIncumplida e) {
            // ARCA no respondió (se puede reintentar) o rechazó el comprobante (no se arregla
            // reintentando, pero la compra no puede quedar sin factura: termina en la DLQ para revisarla).
            LOG.warning("FacturacionDeCompras: no se pudo facturar la venta " + Textos.enUnaLinea(mensaje.ventaId())
                    + " (entrega " + LectorDeMensajes.numeroDeEntrega(recibido) + "), vuelve al broker. "
                    + Textos.enUnaLinea(e.getMessage()));
            contexto.setRollbackOnly();
        }
    }
}
