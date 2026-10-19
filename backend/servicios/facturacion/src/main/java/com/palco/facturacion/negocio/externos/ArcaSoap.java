package com.palco.facturacion.negocio.externos;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.logging.Logger;

import javax.xml.namespace.QName;

import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.WebServiceException;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.Textos;
import com.palco.facturacion.negocio.TiposDeProblema;

/**
 * Adapter de {@link Arca} sobre SOAP: llama a {@code FECAESolicitar} del
 * ARCA simulado y traduce la respuesta a los nombres de Palco.
 *
 * El cliente se arma con {@link Service#create(URL, QName)} sobre el WSDL que
 * publica el simulado y la interfaz anotada {@link PuertoArca}: no hay código
 * generado. El WSDL se descarga la primera vez que se usa, no al desplegar,
 * así Facturación despliega aunque ARCA esté caído; el {@code Service} queda
 * en memoria porque el contrato no cambia mientras corre. El puerto (el
 * objeto con el que se hace la llamada) se crea en cada pedido: no se
 * garantiza que se pueda compartir entre hilos.
 *
 * Tiempos de espera: 2 segundos para conectar y 5 para leer la respuesta (ver
 * {@link FabricaDeArca}). Sin ellos, un ARCA colgado dejaría al consumidor del
 * tópico esperando para siempre, sin reintentos ni DLQ.
 *
 * Seguridad del XML (SEC-I01): la respuesta la lee el cliente SOAP de WildFly
 * (JBossWS con Apache CXF). CXF arma su lector StAX con la DTD
 * ({@code javax.xml.stream.supportDTD}) y las entidades externas
 * ({@code javax.xml.stream.isSupportingExternalEntities}) deshabilitadas, y
 * solo las habilita si se define la propiedad de sistema
 * {@code org.apache.cxf.stax.allowInsecureParser}. El cliente no tiene otra
 * opción que configurar: por eso {@link FabricaDeArca} se niega a crear este
 * adaptador si esa propiedad está activada. Además, SOAP prohíbe la DTD
 * dentro de un mensaje.
 *
 * No es un componente con estado de conversación: lo que guarda (la URL, los
 * tiempos y el {@code Service}) es configuración, la misma para todos los
 * pedidos. Hay una sola instancia por servicio, la que produce la fábrica.
 */
public class ArcaSoap implements Arca {

    private static final Logger LOG = Logger.getLogger(ArcaSoap.class.getName());

    static final QName SERVICIO = new QName(PuertoArca.ESPACIO_DE_NOMBRES, "ArcaSimulado");
    static final QName PUERTO = new QName(PuertoArca.ESPACIO_DE_NOMBRES, "ArcaSimuladoPort");

    /** Los nombres de los tiempos de espera en CXF, el cliente de WildFly. Aceptan un número en milisegundos. */
    static final String CXF_CONEXION = "jakarta.xml.ws.client.connectionTimeout";
    static final String CXF_LECTURA = "jakarta.xml.ws.client.receiveTimeout";
    /** Los mismos en Metro, el cliente con el que corren los tests. Piden un {@code Integer}. */
    static final String METRO_CONEXION = "com.sun.xml.ws.connect.timeout";
    static final String METRO_LECTURA = "com.sun.xml.ws.request.timeout";

    static final String APROBADO = "A";
    static final String RECHAZADO = "R";

    private static final DateTimeFormatter AAAAMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final URL wsdl;
    private final String direccion;
    private final int milisegundosConexion;
    private final int milisegundosLectura;

    /** Se crea con el primer pedido que llega a descargar el WSDL. */
    private Service servicio;

    /**
     * @param urlDelWsdl {@code http://localhost:8080/legado/arca?wsdl}; las llamadas van a la misma
     *                   dirección sin {@code ?wsdl}
     */
    ArcaSoap(String urlDelWsdl, Duration conexion, Duration lectura) {
        try {
            this.wsdl = URI.create(urlDelWsdl).toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new IllegalStateException("La dirección del WSDL de ARCA no es una URL: " + urlDelWsdl, e);
        }
        int consulta = urlDelWsdl.indexOf('?');
        this.direccion = consulta < 0 ? urlDelWsdl : urlDelWsdl.substring(0, consulta);
        this.milisegundosConexion = Math.toIntExact(conexion.toMillis());
        this.milisegundosLectura = Math.toIntExact(lectura.toMillis());
    }

    @Override
    public ComprobanteAutorizado autorizar(SolicitudDeCae solicitud) {
        RespuestaCaeXml respuesta;
        try {
            respuesta = puerto().solicitarCae(solicitud.tipo(), solicitud.puntoDeVenta(), solicitud.importe(),
                    solicitud.fecha().format(AAAAMMDD), solicitud.ventaId());
        } catch (WebServiceException e) {
            // Tiempo vencido, conexión rechazada, WSDL que no se pudo bajar o un SOAP Fault
            // (SOAPFaultException también es una WebServiceException): en todos los casos no se
            // sabe si ARCA autorizó. Pedirlo de nuevo es seguro (ver ServicioDeFacturacion).
            LOG.warning("ARCA no respondió el pedido de CAE de la venta " + Textos.enUnaLinea(solicitud.ventaId())
                    + ": " + Textos.enUnaLinea(String.valueOf(e.getMessage())));
            throw new ErroresDeNegocio.SinRespuesta("ARCA no respondió el pedido de CAE.");
        }
        return traducir(respuesta);
    }

    /** Del contrato de ARCA a los nombres de Palco. Una respuesta que no se entiende se trata como sin respuesta. */
    static ComprobanteAutorizado traducir(RespuestaCaeXml respuesta) {
        if (respuesta == null || respuesta.resultado == null) {
            throw new ErroresDeNegocio.SinRespuesta("ARCA respondió sin resultado.");
        }
        if (RECHAZADO.equals(respuesta.resultado)) {
            throw new ErroresDeNegocio.ReglaIncumplida(TiposDeProblema.COMPROBANTE_RECHAZADO,
                    "ARCA rechazó el comprobante: " + Textos.enUnaLinea(String.valueOf(respuesta.observacion)));
        }
        if (!APROBADO.equals(respuesta.resultado) || respuesta.numeroComprobante <= 0 || respuesta.cae == null
                || !respuesta.cae.matches("\\d{14}")) {
            throw new ErroresDeNegocio.SinRespuesta("ARCA respondió algo que no se entiende.");
        }
        LocalDate vencimiento;
        try {
            vencimiento = LocalDate.parse(String.valueOf(respuesta.vencimientoCae), AAAAMMDD);
        } catch (DateTimeParseException e) {
            throw new ErroresDeNegocio.SinRespuesta("ARCA respondió un vencimiento de CAE que no se entiende.");
        }
        return new ComprobanteAutorizado(respuesta.numeroComprobante, respuesta.cae, vencimiento);
    }

    /** Un puerto nuevo con la dirección y los tiempos de espera. */
    private PuertoArca puerto() {
        PuertoArca puerto = servicio().getPort(PUERTO, PuertoArca.class);
        Map<String, Object> pedido = ((BindingProvider) puerto).getRequestContext();
        // La dirección del WSDL manda sobre la que trae el WSDL adentro (soap:address), que el
        // servidor arma con el nombre con que se lo llamó.
        pedido.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, direccion);
        pedido.put(CXF_CONEXION, milisegundosConexion);
        pedido.put(CXF_LECTURA, milisegundosLectura);
        pedido.put(METRO_CONEXION, milisegundosConexion);
        pedido.put(METRO_LECTURA, milisegundosLectura);
        return puerto;
    }

    /** Si falla la descarga del WSDL no queda nada guardado: el próximo pedido lo intenta otra vez. */
    private synchronized Service servicio() {
        if (servicio == null) {
            servicio = Service.create(wsdl, SERVICIO);
        }
        return servicio;
    }

    String direccion() {
        return direccion;
    }

    int milisegundosConexion() {
        return milisegundosConexion;
    }

    int milisegundosLectura() {
        return milisegundosLectura;
    }
}
