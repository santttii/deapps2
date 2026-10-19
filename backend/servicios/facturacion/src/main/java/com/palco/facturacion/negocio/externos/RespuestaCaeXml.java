package com.palco.facturacion.negocio.externos;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * La respuesta de {@code FECAESolicitar} tal como viene en el XML. Es del
 * contrato ajeno: no sale de {@link ArcaSoap}, que la traduce a
 * {@link Arca.ComprobanteAutorizado}.
 *
 * Clase con campos y constructor vacío porque así la arma JAXB.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "RespuestaCae", namespace = PuertoArca.ESPACIO_DE_NOMBRES,
        propOrder = { "resultado", "numeroComprobante", "cae", "vencimientoCae", "observacion" })
public class RespuestaCaeXml {

    /** "A" aprobado, "R" rechazado, como en el WSFEv1. */
    @XmlElement(name = "Resultado")
    String resultado;

    @XmlElement(name = "CbteNro")
    long numeroComprobante;

    @XmlElement(name = "CAE")
    String cae;

    /** {@code aaaammdd}. */
    @XmlElement(name = "CAEFchVto")
    String vencimientoCae;

    @XmlElement(name = "Obs")
    String observacion;

    public RespuestaCaeXml() {
        // JAXB
    }

    /** Para armar respuestas en los tests. */
    public RespuestaCaeXml(String resultado, long numeroComprobante, String cae, String vencimientoCae,
            String observacion) {
        this.resultado = resultado;
        this.numeroComprobante = numeroComprobante;
        this.cae = cae;
        this.vencimientoCae = vencimientoCae;
        this.observacion = observacion;
    }
}
