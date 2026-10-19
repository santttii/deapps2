package com.palco.arca;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.logging.Logger;

import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebResult;
import jakarta.jws.WebService;

/**
 * ARCA simulado: representa el servicio SOAP de factura electrónica de ARCA
 * (ex AFIP, WSFEv1), el sistema legado al que Palco le pide el CAE de cada
 * factura. <b>Es una simulación</b>: no se conecta con ARCA y los números y
 * CAE que da no tienen validez fiscal. Lo real es el canal: SOAP sobre HTTP,
 * con el WSDL que publica WildFly a partir de las anotaciones de esta clase
 * ({@code /legado/arca?wsdl}).
 *
 * Una sola operación, {@code FECAESolicitar}, inspirada en la del WSFEv1 y
 * simplificada: un comprobante por pedido, sin autenticación (el real pide un
 * token y una firma que da otro servicio de ARCA, el WSAA) y con el id de la
 * venta de Palco para reconocer un pedido repetido (ver {@link Talonario}).
 *
 * Vive en su propio WAR y no comparte clases con Palco: Facturación lo conoce
 * solo por el WSDL, como conocería al sistema real.
 *
 * Un componente sin estado propio: lo que tiene que durar entre pedidos (la
 * numeración) está en el {@link Talonario} del despliegue, que es uno solo y
 * se protege a sí mismo de los pedidos simultáneos. Por eso no importa cuántas
 * instancias de esta clase cree el servidor.
 *
 * Seguridad del XML (SEC-I01): el XML lo lee JBossWS (Apache CXF) antes de
 * llegar a esta clase. CXF arma su lector StAX con la DTD y las entidades
 * externas deshabilitadas, salvo que se defina la propiedad de sistema
 * {@code org.apache.cxf.stax.allowInsecureParser}, que Palco no define.
 */
@WebService(name = "ServicioFacturaElectronica", serviceName = "ArcaSimulado", portName = "ArcaSimuladoPort",
        targetNamespace = ArcaSimulado.ESPACIO_DE_NOMBRES)
public class ArcaSimulado {

    private static final Logger LOG = Logger.getLogger(ArcaSimulado.class.getName());

    /** El espacio de nombres del contrato. Es propio: no es el del WSFEv1 real. */
    public static final String ESPACIO_DE_NOMBRES = "urn:palco:legado:arca:wsfe";

    /**
     * Simulación de un ARCA que no contesta. Con la propiedad de sistema en
     * {@code true}, cada pedido espera {@link #esperaSinRespuesta} y termina en
     * error, sin numerar nada. Se lee en cada pedido, así que se prende y se
     * apaga con el servidor en marcha:
     * {@code /system-property=palco.arca.simular-sin-respuesta:add(value=true)} y
     * {@code :remove} en {@code jboss-cli}. Sirve para ver los reintentos del
     * broker y la DLQ del lado de Facturación.
     */
    static final String PROPIEDAD_SIN_RESPUESTA = "palco.arca.simular-sin-respuesta";

    /** Factura A, B y C, con los códigos del WSFEv1. Palco emite B (6). */
    static final Set<Integer> TIPOS_DE_COMPROBANTE = Set.of(1, 6, 11);

    /** Rango de puntos de venta que acepta el WSFEv1. */
    static final int PUNTO_DE_VENTA_MAXIMO = 99_998;

    static final int LARGO_MAXIMO_ID_VENTA = 40;

    /** Uno por despliegue: es la numeración que comparten todos los pedidos. */
    private static final Talonario TALONARIO_DEL_DESPLIEGUE = new Talonario();

    private final Talonario talonario;

    /**
     * Cuánto tarda en fallar en el modo sin respuesta. Es más que el tiempo de
     * espera de lectura del cliente de Facturación (5 segundos): del otro lado
     * se ve como un servicio colgado. Los tests la acortan.
     */
    Duration esperaSinRespuesta = Duration.ofSeconds(30);

    /** El que usa el servidor. */
    public ArcaSimulado() {
        this(TALONARIO_DEL_DESPLIEGUE);
    }

    /** Para los tests: cada uno con su numeración. */
    ArcaSimulado(Talonario talonario) {
        this.talonario = talonario;
    }

    /**
     * Pide el CAE de un comprobante. Si los datos no son válidos responde
     * {@code Resultado} "R" con una observación y no numera; si son válidos,
     * "A" con el número, el CAE y su vencimiento.
     *
     * @param tipo         código del comprobante (6: factura B)
     * @param puntoDeVenta entre 1 y 99998
     * @param importeTotal en pesos enteros
     * @param fecha        fecha del comprobante, {@code aaaammdd}
     * @param ventaId      id de la venta en Palco
     */
    @WebMethod(operationName = "FECAESolicitar")
    @WebResult(name = "FECAESolicitarResult")
    public RespuestaCae solicitarCae(
            @WebParam(name = "CbteTipo") int tipo,
            @WebParam(name = "PtoVta") int puntoDeVenta,
            @WebParam(name = "ImpTotal") long importeTotal,
            @WebParam(name = "CbteFch") String fecha,
            @WebParam(name = "IdVenta") String ventaId) {
        if (Boolean.parseBoolean(System.getProperty(PROPIEDAD_SIN_RESPUESTA))) {
            noResponder();
        }
        if (!TIPOS_DE_COMPROBANTE.contains(tipo)) {
            return rechazo("El tipo de comprobante " + tipo + " no existe (1, 6 u 11).");
        }
        if (puntoDeVenta < 1 || puntoDeVenta > PUNTO_DE_VENTA_MAXIMO) {
            return rechazo("El punto de venta tiene que estar entre 1 y " + PUNTO_DE_VENTA_MAXIMO + ".");
        }
        if (importeTotal <= 0) {
            return rechazo("El importe total tiene que ser mayor que 0.");
        }
        if (ventaId == null || ventaId.isBlank() || ventaId.length() > LARGO_MAXIMO_ID_VENTA) {
            return rechazo("Falta el id de la venta o tiene más de " + LARGO_MAXIMO_ID_VENTA + " caracteres.");
        }
        LocalDate fechaDelComprobante = leerFecha(fecha);
        if (fechaDelComprobante == null) {
            return rechazo("La fecha del comprobante tiene que ser aaaammdd.");
        }
        Talonario.Comprobante comprobante = talonario.autorizar(tipo, puntoDeVenta, fechaDelComprobante, ventaId);
        LOG.info("ARCA simulado: comprobante " + tipo + " " + puntoDeVenta + "-" + comprobante.numero()
                + " autorizado para la venta " + enUnaLinea(ventaId) + ", importe " + importeTotal + ".");
        return RespuestaCae.aprobada(comprobante);
    }

    /** Espera y falla: el cliente ve un servicio que no contesta. Lanzar acá le llega como un SOAP Fault. */
    private void noResponder() {
        LOG.warning("ARCA simulado: modo sin respuesta (" + PROPIEDAD_SIN_RESPUESTA + "=true); el pedido no se atiende.");
        try {
            Thread.sleep(esperaSinRespuesta);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new IllegalStateException("ARCA simulado sin respuesta.");
    }

    private static RespuestaCae rechazo(String motivo) {
        LOG.info("ARCA simulado: comprobante rechazado. " + motivo);
        return RespuestaCae.rechazada(motivo);
    }

    /** La fecha {@code aaaammdd}, o null si no lo es (también si el día no existe, como el 20261332). */
    private static LocalDate leerFecha(String fecha) {
        if (fecha == null || !fecha.matches("\\d{8}")) {
            return null;
        }
        try {
            return LocalDate.parse(fecha, Talonario.FORMATO_FECHA);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** El id viene de afuera: al log va sin saltos de línea, para que no pueda fingir otra línea. */
    private static String enUnaLinea(String texto) {
        return texto.replaceAll("[\\r\\n]+", " ");
    }
}
