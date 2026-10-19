package com.palco.facturacion.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;

import jakarta.xml.ws.Endpoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.facturacion.negocio.TiposDeProblema;
import com.palco.facturacion.negocio.externos.Arca.ComprobanteAutorizado;
import com.palco.facturacion.negocio.externos.Arca.SolicitudDeCae;

/**
 * El cliente SOAP de verdad ({@link ArcaSoap}) contra un servicio SOAP de
 * prueba publicado con {@code Endpoint.publish}: baja el WSDL, arma el puerto
 * con {@code Service.create} y llama por HTTP. En los tests el cliente es
 * Metro; en WildFly es CXF, con los mismos tiempos de espera (ver
 * {@link ArcaSoap}).
 */
class ArcaSoapTest {

    /** El mismo pedido, escrito a mano, que el test del ARCA simulado le manda al simulado. */
    private static final String PEDIDO = """
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

    private static final SolicitudDeCae SOLICITUD = new SolicitudDeCae(6, 1, 60_500, LocalDate.of(2026, 10, 16),
            "V-3f9a1c2b");

    private final ArcaDePrueba arcaDePrueba = new ArcaDePrueba();
    private Endpoint publicado;
    private String direccion;

    @BeforeEach
    void publicar() throws Exception {
        direccion = "http://localhost:" + puertoLibre() + "/legado/arca";
        publicado = Endpoint.publish(direccion, arcaDePrueba);
    }

    @AfterEach
    void bajar() {
        if (publicado != null) {
            publicado.stop();
        }
    }

    private ArcaSoap cliente(Duration lectura) {
        return new ArcaSoap(direccion + "?wsdl", Duration.ofSeconds(2), lectura);
    }

    @Test
    void pideElCaeConLosDatosDeLaSolicitudYTraduceLaRespuesta() {
        ComprobanteAutorizado comprobante = cliente(Duration.ofSeconds(5)).autorizar(SOLICITUD);

        assertEquals(new ComprobanteAutorizado(1, "75123456789012", LocalDate.of(2026, 10, 26)), comprobante);
        // Del otro lado llegaron los datos con los formatos del contrato: la fecha en aaaammdd.
        assertEquals(new ArcaDePrueba.Pedido(6, 1, 60_500, "20261016", "V-3f9a1c2b"), arcaDePrueba.pedidos.get(0));
    }

    @Test
    void elContratoDelClienteAceptaElMismoXmlQueElArcaSimulado() throws Exception {
        HttpResponse<String> respuesta = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(direccion))
                        .header("Content-Type", "text/xml; charset=utf-8")
                        .header("SOAPAction", "\"\"")
                        .POST(HttpRequest.BodyPublishers.ofString(PEDIDO))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, respuesta.statusCode(), respuesta.body());
        assertEquals(new ArcaDePrueba.Pedido(6, 1, 60_500, "20261016", "V-3f9a1c2b"), arcaDePrueba.pedidos.get(0));
        // Y responde con los mismos elementos que el simulado.
        assertTrue(respuesta.body().contains("<Resultado>A</Resultado>"), respuesta.body());
        assertTrue(respuesta.body().contains("<CbteNro>1</CbteNro>"), respuesta.body());
        assertTrue(respuesta.body().contains("<CAEFchVto>20261026</CAEFchVto>"), respuesta.body());
    }

    @Test
    void unRechazoDeArcaEsUnaReglaIncumplidaConLaObservacion() {
        arcaDePrueba.respuesta = new RespuestaCaeXml("R", 0, null, null, "El importe total tiene que ser mayor que 0.");

        ErroresDeNegocio.ReglaIncumplida error = assertThrows(ErroresDeNegocio.ReglaIncumplida.class,
                () -> cliente(Duration.ofSeconds(5)).autorizar(SOLICITUD));

        assertEquals(TiposDeProblema.COMPROBANTE_RECHAZADO, error.tipo());
        assertTrue(error.getMessage().contains("El importe total tiene que ser mayor que 0."), error.getMessage());
    }

    @Test
    void siArcaTardaMasQueElTiempoDeLecturaFallaSinEsperarlo() {
        arcaDePrueba.demora = Duration.ofSeconds(3);
        ArcaSoap cliente = cliente(Duration.ofMillis(400));

        long inicio = System.nanoTime();
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> cliente.autorizar(SOLICITUD));
        long milisegundos = Duration.ofNanos(System.nanoTime() - inicio).toMillis();

        assertTrue(milisegundos < 2_500, "esperó " + milisegundos + " ms");
    }

    @Test
    void unSoapFaultEsSinRespuesta() {
        arcaDePrueba.falla = true;

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> cliente(Duration.ofSeconds(5)).autorizar(SOLICITUD));
    }

    @Test
    void conArcaCaidoDespliegaIgualFallaAlPedirYSeRecuperaCuandoVuelve() throws Exception {
        publicado.stop();
        publicado = null;
        // Crear el cliente no baja el WSDL: Facturación despliega aunque ARCA no esté.
        ArcaSoap cliente = cliente(Duration.ofSeconds(5));

        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> cliente.autorizar(SOLICITUD));

        // ARCA vuelve en la misma dirección: el mismo cliente vuelve a intentar bajar el WSDL y anda.
        publicado = Endpoint.publish(direccion, arcaDePrueba);
        assertEquals(1, cliente.autorizar(SOLICITUD).numero());
    }

    @Test
    void unaRespuestaAprobadaQueNoSeEntiendeEsSinRespuesta() {
        // CAE corto, resultado desconocido, vencimiento que no es aaaammdd y número 0.
        for (RespuestaCaeXml rara : new RespuestaCaeXml[] {
                new RespuestaCaeXml("A", 1, "123", "20261026", null),
                new RespuestaCaeXml("P", 1, "75123456789012", "20261026", null),
                new RespuestaCaeXml("A", 1, "75123456789012", "2026-10-26", null),
                new RespuestaCaeXml("A", 0, "75123456789012", "20261026", null),
                new RespuestaCaeXml(null, 1, "75123456789012", "20261026", null) }) {
            assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> ArcaSoap.traducir(rara));
        }
        assertThrows(ErroresDeNegocio.SinRespuesta.class, () -> ArcaSoap.traducir(null));
    }

    private static int puertoLibre() throws Exception {
        try (ServerSocket libre = new ServerSocket(0)) {
            return libre.getLocalPort();
        }
    }
}
