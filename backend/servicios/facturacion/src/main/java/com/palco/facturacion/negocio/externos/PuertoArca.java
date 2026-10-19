package com.palco.facturacion.negocio.externos;

import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebResult;
import jakarta.jws.WebService;

/**
 * El contrato SOAP de ARCA visto desde Facturación: una interfaz Java anotada
 * que repite los nombres del WSDL (espacio de nombres, tipo de puerto,
 * operación y parámetros). Con ella, {@code Service.getPort} arma el cliente
 * en tiempo de ejecución; no se genera código con {@code wsimport} en el build.
 *
 * No usa ninguna clase del ARCA simulado: los dos lados solo comparten el
 * contrato. Si el WSDL cambiara, esta interfaz es lo único que hay que tocar.
 */
@WebService(name = "ServicioFacturaElectronica", targetNamespace = PuertoArca.ESPACIO_DE_NOMBRES)
public interface PuertoArca {

    /** El del WSDL del ARCA simulado. */
    String ESPACIO_DE_NOMBRES = "urn:palco:legado:arca:wsfe";

    @WebMethod(operationName = "FECAESolicitar")
    @WebResult(name = "FECAESolicitarResult")
    RespuestaCaeXml solicitarCae(
            @WebParam(name = "CbteTipo") int tipo,
            @WebParam(name = "PtoVta") int puntoDeVenta,
            @WebParam(name = "ImpTotal") long importeTotal,
            @WebParam(name = "CbteFch") String fecha,
            @WebParam(name = "IdVenta") String ventaId);
}
