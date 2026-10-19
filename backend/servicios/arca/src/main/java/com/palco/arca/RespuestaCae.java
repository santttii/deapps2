package com.palco.arca;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

/**
 * Lo que devuelve {@code FECAESolicitar}: el resultado y, si se aprobó, el
 * número de comprobante y el CAE con su vencimiento. Los nombres de los
 * elementos XML son los del WSFEv1 real ({@code Resultado}, {@code CbteNro},
 * {@code CAE}, {@code CAEFchVto}, {@code Obs}); la estructura está
 * simplificada: el real devuelve una cabecera y un detalle por comprobante.
 *
 * Es una clase con getters y setters, y no un {@code record}, porque JAXB
 * necesita un constructor sin parámetros para armarla desde el XML.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "RespuestaCae", namespace = ArcaSimulado.ESPACIO_DE_NOMBRES,
        propOrder = { "resultado", "numeroComprobante", "cae", "vencimientoCae", "observacion" })
public class RespuestaCae {

    /** Aprobado: hay número y CAE. */
    public static final String APROBADO = "A";
    /** Rechazado: no se numeró; {@code observacion} dice por qué. */
    public static final String RECHAZADO = "R";

    @XmlElement(name = "Resultado", required = true)
    private String resultado;

    /** 0 si se rechazó. */
    @XmlElement(name = "CbteNro")
    private long numeroComprobante;

    /** 14 dígitos; null si se rechazó. */
    @XmlElement(name = "CAE")
    private String cae;

    /** {@code aaaammdd}, como en el WSFEv1; null si se rechazó. */
    @XmlElement(name = "CAEFchVto")
    private String vencimientoCae;

    /** Por qué se rechazó; null si se aprobó. */
    @XmlElement(name = "Obs")
    private String observacion;

    public RespuestaCae() {
        // JAXB
    }

    static RespuestaCae aprobada(Talonario.Comprobante comprobante) {
        RespuestaCae respuesta = new RespuestaCae();
        respuesta.resultado = APROBADO;
        respuesta.numeroComprobante = comprobante.numero();
        respuesta.cae = comprobante.cae();
        respuesta.vencimientoCae = comprobante.vencimientoCae();
        return respuesta;
    }

    static RespuestaCae rechazada(String observacion) {
        RespuestaCae respuesta = new RespuestaCae();
        respuesta.resultado = RECHAZADO;
        respuesta.observacion = observacion;
        return respuesta;
    }

    public String getResultado() { return resultado; }
    public void setResultado(String resultado) { this.resultado = resultado; }
    public long getNumeroComprobante() { return numeroComprobante; }
    public void setNumeroComprobante(long numeroComprobante) { this.numeroComprobante = numeroComprobante; }
    public String getCae() { return cae; }
    public void setCae(String cae) { this.cae = cae; }
    public String getVencimientoCae() { return vencimientoCae; }
    public void setVencimientoCae(String vencimientoCae) { this.vencimientoCae = vencimientoCae; }
    public String getObservacion() { return observacion; }
    public void setObservacion(String observacion) { this.observacion = observacion; }
}
