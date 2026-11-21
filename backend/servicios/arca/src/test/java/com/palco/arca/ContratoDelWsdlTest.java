package com.palco.arca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.io.StringReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import jakarta.xml.ws.Endpoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Prueba de contrato del ARCA simulado: el WSDL que publica el servicio es el
 * de la copia de referencia, {@code docs/api/arca.wsdl}, y lo que viaja en los
 * mensajes cumple el esquema de esa copia.
 *
 * La copia es la que publicó WildFly en {@code /legado/arca?wsdl}; acá el
 * servicio se publica con {@code Endpoint.publish} (Metro), sin servidor. Los
 * dos WSDL salen de las mismas anotaciones pero no tienen el mismo texto, así
 * que se compara el contrato y no el archivo (ver {@link ResumenDeWsdl}). Si
 * alguien cambia un nombre, un tipo, el orden o la cardinalidad de un elemento,
 * la operación o el servicio, este test falla hasta que la copia se actualice:
 * Facturación conoce al simulado solo por ese contrato.
 *
 * El esquema contra el que se validan los mensajes es el de la copia, no el que
 * genera el servicio: lo que se prueba es que el simulado cumple lo publicado.
 */
class ContratoDelWsdlTest {

    private static final Path COPIA = Path.of("docs", "api", "arca.wsdl");
    private static final String SOBRE = "http://schemas.xmlsoap.org/soap/envelope/";

    private final HttpClient http = HttpClient.newHttpClient();
    private Endpoint publicado;
    private String direccion;

    @BeforeEach
    void publicar() throws Exception {
        int puerto;
        try (ServerSocket libre = new ServerSocket(0)) {
            puerto = libre.getLocalPort();
        }
        direccion = "http://localhost:" + puerto + "/legado/arca";
        publicado = Endpoint.publish(direccion, new ArcaSimulado(new Talonario()));
    }

    @AfterEach
    void bajar() {
        publicado.stop();
    }

    @Test
    void elWsdlQuePublicaElServicioEsElDeLaCopiaDeReferencia() throws Exception {
        URI wsdl = URI.create(direccion + "?wsdl");
        List<String> publicadoAhora = ResumenDeWsdl.de(pedir(wsdl), wsdl, this::pedir);
        List<String> copia = resumenDeLaCopia(leerCopia());

        assertEquals(String.join("\n", copia), String.join("\n", publicadoAhora),
                "El WSDL que genera ArcaSimulado ya no es el de docs/api/arca.wsdl. Si el cambio es a propósito,"
                        + " actualizá la copia con el que publica WildFly en /legado/arca?wsdl.");
    }

    /** Que la comparación de arriba no pase por no haber leído nada: el resumen tiene el contrato entero. */
    @Test
    void elResumenDeLaCopiaTieneLaOperacionLosTiposYElServicio() throws Exception {
        List<String> copia = resumenDeLaCopia(leerCopia());

        assertTrue(copia.contains("operación ServicioFacturaElectronica.FECAESolicitar:"
                + " entrada tns:FECAESolicitar, salida tns:FECAESolicitarResponse"), String.join("\n", copia));
        assertTrue(copia.contains("tipo FECAESolicitar: CbteTipo xs:int [1..1], PtoVta xs:int [1..1],"
                + " ImpTotal xs:long [1..1], CbteFch xs:string [0..1], IdVenta xs:string [0..1]"), String.join("\n", copia));
        assertTrue(copia.contains("tipo RespuestaCae: Resultado xs:string [1..1], CbteNro xs:long [1..1],"
                + " CAE xs:string [0..1], CAEFchVto xs:string [0..1], Obs xs:string [0..1]"), String.join("\n", copia));
        assertTrue(copia.contains("binding del puerto ArcaSimuladoPort, operación FECAESolicitar:"
                + " soapAction \"\", estilo document, entrada literal, salida literal"), String.join("\n", copia));
        assertTrue(copia.contains("servicio ArcaSimulado, puerto ArcaSimuladoPort, ruta /legado/arca"),
                String.join("\n", copia));
    }

    /** Y que un cambio en el contrato se note: otro nombre de elemento, u otro orden en la secuencia. */
    @Test
    void unCambioEnElNombreOEnElOrdenDeUnElementoHaceQueNoCoincida() throws Exception {
        List<String> original = resumenDeLaCopia(leerCopia());

        Document renombrado = leerCopia();
        elementoDelEsquema(renombrado, "CbteNro").setAttribute("name", "NroComprobante");
        assertNotEquals(original, resumenDeLaCopia(renombrado));

        Document reordenado = leerCopia();
        Element cae = elementoDelEsquema(reordenado, "CAE");
        Element vencimiento = elementoDelEsquema(reordenado, "CAEFchVto");
        cae.getParentNode().insertBefore(vencimiento, cae);
        assertNotEquals(original, resumenDeLaCopia(reordenado));
    }

    @Test
    void elPedidoQueMandaFacturacionCumpleElEsquema() throws Exception {
        Element pedido = cuerpo(leerXml(ArcaSimuladoPorSoapTest.PEDIDO));

        assertEquals("FECAESolicitar", pedido.getLocalName());
        esquemaDeLaCopia().newValidator().validate(new DOMSource(pedido));
    }

    @Test
    void unaRespuestaAprobadaCumpleElEsquema() throws Exception {
        Element respuesta = cuerpo(soap(ArcaSimuladoPorSoapTest.PEDIDO));

        assertEquals("FECAESolicitarResponse", respuesta.getLocalName());
        assertEquals(RespuestaCae.APROBADO, texto(respuesta, "Resultado"));
        esquemaDeLaCopia().newValidator().validate(new DOMSource(respuesta));
    }

    @Test
    void unaRespuestaRechazadaCumpleElEsquema() throws Exception {
        // Tipo de comprobante 2: no existe, y ARCA contesta "R" con una observación y sin CAE.
        Element respuesta = cuerpo(soap(ArcaSimuladoPorSoapTest.PEDIDO.replace("<CbteTipo>6<", "<CbteTipo>2<")));

        assertEquals(RespuestaCae.RECHAZADO, texto(respuesta, "Resultado"));
        esquemaDeLaCopia().newValidator().validate(new DOMSource(respuesta));
    }

    /** La validación no acepta cualquier cosa: sin el resultado, o con un número que no es un número, falla. */
    @Test
    void elEsquemaRechazaUnaRespuestaQueNoLoCumple() throws Exception {
        Schema esquema = esquemaDeLaCopia();

        Element sinResultado = cuerpo(soap(ArcaSimuladoPorSoapTest.PEDIDO));
        Element resultado = (Element) sinResultado.getElementsByTagNameNS("", "Resultado").item(0);
        resultado.getParentNode().removeChild(resultado);
        assertThrows(SAXException.class, () -> esquema.newValidator().validate(new DOMSource(sinResultado)));

        Element numeroInvalido = cuerpo(soap(ArcaSimuladoPorSoapTest.PEDIDO));
        numeroInvalido.getElementsByTagNameNS("", "CbteNro").item(0).setTextContent("uno");
        assertThrows(SAXException.class, () -> esquema.newValidator().validate(new DOMSource(numeroInvalido)));
    }

    // --- La copia de referencia --------------------------------------------------------------------

    /**
     * {@code docs/api/arca.wsdl}, buscado desde la carpeta en la que corre el
     * test hacia arriba: Maven corre los tests en {@code backend/servicios/arca}.
     */
    private static Path copia() {
        for (Path carpeta = Path.of("").toAbsolutePath(); carpeta != null; carpeta = carpeta.getParent()) {
            Path candidata = carpeta.resolve(COPIA);
            if (Files.isRegularFile(candidata)) {
                return candidata;
            }
        }
        return fail("No está docs/api/arca.wsdl en " + Path.of("").toAbsolutePath() + " ni en sus carpetas de arriba.");
    }

    private static Document leerCopia() throws Exception {
        try (InputStream entrada = Files.newInputStream(copia())) {
            return constructor().parse(entrada);
        }
    }

    /** La copia trae el esquema adentro del WSDL: no tiene nada que importar. */
    private static List<String> resumenDeLaCopia(Document wsdl) throws Exception {
        return ResumenDeWsdl.de(wsdl, copia().toUri(),
                ubicacion -> fail("La copia de referencia no tendría que importar esquemas: " + ubicacion));
    }

    /** El esquema de {@code wsdl:types} de la copia, listo para validar mensajes. */
    private static Schema esquemaDeLaCopia() throws Exception {
        Element tipos = ResumenDeWsdl.hijos(leerCopia().getDocumentElement(), ResumenDeWsdl.WSDL, "types").get(0);
        Element esquema = ResumenDeWsdl.hijos(tipos, ResumenDeWsdl.XSD, "schema").get(0);
        SchemaFactory fabrica = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        // El esquema no importa nada: que tampoco pueda ir a buscar archivos o URLs de afuera.
        fabrica.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        fabrica.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return fabrica.newSchema(new DOMSource(esquema));
    }

    private static Element elementoDelEsquema(Document wsdl, String nombre) {
        var elementos = wsdl.getElementsByTagNameNS(ResumenDeWsdl.XSD, "element");
        for (int i = 0; i < elementos.getLength(); i++) {
            Element elemento = (Element) elementos.item(i);
            if (elemento.getAttribute("name").equals(nombre)) {
                return elemento;
            }
        }
        return fail("La copia no tiene el elemento " + nombre);
    }

    // --- El servicio publicado ---------------------------------------------------------------------

    private Document pedir(URI uri) throws Exception {
        HttpResponse<String> respuesta = http.send(HttpRequest.newBuilder(uri).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respuesta.statusCode(), uri + "\n" + respuesta.body());
        return leerXml(respuesta.body());
    }

    private Document soap(String sobre) throws Exception {
        HttpResponse<String> respuesta = http.send(HttpRequest.newBuilder(URI.create(direccion))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"\"")
                .POST(HttpRequest.BodyPublishers.ofString(sobre))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respuesta.statusCode(), respuesta.body());
        return leerXml(respuesta.body());
    }

    /** El único elemento adentro de {@code soap:Body}: el pedido o la respuesta. */
    private static Element cuerpo(Document sobre) {
        Element cuerpo = ResumenDeWsdl.hijos(sobre.getDocumentElement(), SOBRE, "Body").get(0);
        List<Element> contenido = ResumenDeWsdl.hijos(cuerpo, ArcaSimulado.ESPACIO_DE_NOMBRES, null);
        assertEquals(1, contenido.size(), "El cuerpo del sobre tendría que tener un solo elemento del servicio.");
        return contenido.get(0);
    }

    private static String texto(Element padre, String nombreLocal) {
        return padre.getElementsByTagNameNS("", nombreLocal).item(0).getTextContent();
    }

    // --- XML ---------------------------------------------------------------------------------------

    private static Document leerXml(String xml) throws Exception {
        return constructor().parse(new InputSource(new StringReader(xml)));
    }

    /** Con espacios de nombres y sin DTD ni entidades externas, como lee CXF los pedidos del servidor. */
    private static DocumentBuilder constructor() throws Exception {
        DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
        fabrica.setNamespaceAware(true);
        fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        fabrica.setXIncludeAware(false);
        fabrica.setExpandEntityReferences(false);
        return fabrica.newDocumentBuilder();
    }
}
