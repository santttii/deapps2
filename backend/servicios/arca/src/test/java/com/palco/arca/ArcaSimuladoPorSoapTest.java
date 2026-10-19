package com.palco.arca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import jakarta.xml.ws.Endpoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * El simulado publicado como servicio SOAP de verdad, sin WildFly: con
 * {@code Endpoint.publish}, que usa el servidor HTTP del JDK. Se le manda el
 * mismo XML que manda el cliente de Facturación (el test de ese cliente usa
 * este mismo pedido), escrito a mano, como lo mandaría {@code curl}.
 *
 * En WildFly el WSDL lo genera JBossWS (Apache CXF) y no Metro, así que esto
 * comprueba las anotaciones y el formato del mensaje, no el servidor.
 */
class ArcaSimuladoPorSoapTest {

    /** El pedido que manda Facturación: documento literal, con los parámetros sin espacio de nombres. */
    static final String PEDIDO = """
            <S:Envelope xmlns:S="http://schemas.xmlsoap.org/soap/envelope/">
              <S:Body>
                <a:FECAESolicitar xmlns:a="urn:palco:legado:arca:wsfe">
                  <CbteTipo>6</CbteTipo>
                  <PtoVta>1</PtoVta>
                  <ImpTotal>60500</ImpTotal>
                  <CbteFch>20261016</CbteFch>
                  <IdVenta>V-3f9a1c2b</IdVenta>
                </a:FECAESolicitar>
              </S:Body>
            </S:Envelope>""";

    private final HttpClient http = HttpClient.newHttpClient();
    private Endpoint publicado;
    private String direccion;

    @BeforeEach
    void publicar() throws Exception {
        publicar(new ArcaSimulado(new Talonario()));
    }

    private void publicar(ArcaSimulado arca) throws Exception {
        int puerto;
        try (ServerSocket libre = new ServerSocket(0)) {
            puerto = libre.getLocalPort();
        }
        direccion = "http://localhost:" + puerto + "/legado/arca";
        publicado = Endpoint.publish(direccion, arca);
    }

    @AfterEach
    void bajar() {
        publicado.stop();
    }

    @Test
    void publicaElWsdlConLaOperacionYElServicio() throws Exception {
        HttpResponse<String> wsdl = http.send(HttpRequest.newBuilder(URI.create(direccion + "?wsdl")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, wsdl.statusCode());
        String texto = wsdl.body();
        assertTrue(texto.contains("targetNamespace=\"urn:palco:legado:arca:wsfe\""), texto);
        assertTrue(texto.contains("name=\"FECAESolicitar\""), texto);
        assertTrue(texto.contains("name=\"ArcaSimulado\""), texto);
        assertTrue(texto.contains("name=\"ArcaSimuladoPort\""), texto);
    }

    @Test
    void respondeElPedidoConElNumeroYElCae() throws Exception {
        HttpResponse<String> respuesta = soap(PEDIDO);

        assertEquals(200, respuesta.statusCode(), respuesta.body());
        String xml = respuesta.body();
        assertTrue(xml.contains("FECAESolicitarResponse"), xml);
        assertTrue(xml.contains("<Resultado>A</Resultado>"), xml);
        assertTrue(xml.contains("<CbteNro>1</CbteNro>"), xml);
        assertTrue(xml.matches("(?s).*<CAE>\\d{14}</CAE>.*"), xml);
        assertTrue(xml.contains("<CAEFchVto>20261026</CAEFchVto>"), xml);
    }

    @Test
    void enElModoSinRespuestaContestaConUnFault() throws Exception {
        System.setProperty(ArcaSimulado.PROPIEDAD_SIN_RESPUESTA, "true");
        try {
            // La espera de 30 segundos es la del servidor; acá se publica otra instancia, sin espera.
            publicado.stop();
            ArcaSimulado sinEspera = new ArcaSimulado(new Talonario());
            sinEspera.esperaSinRespuesta = Duration.ZERO;
            publicar(sinEspera);

            HttpResponse<String> respuesta = soap(PEDIDO);

            assertEquals(500, respuesta.statusCode());
            assertTrue(respuesta.body().contains("Fault"), respuesta.body());
        } finally {
            System.clearProperty(ArcaSimulado.PROPIEDAD_SIN_RESPUESTA);
        }
    }

    private HttpResponse<String> soap(String sobre) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(direccion))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"\"")
                .POST(HttpRequest.BodyPublishers.ofString(sobre))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
