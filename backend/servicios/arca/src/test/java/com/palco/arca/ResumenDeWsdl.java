package com.palco.arca;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Lo que un WSDL le promete a quien lo usa, en líneas de texto que se pueden
 * comparar: los tipos del esquema (cada elemento con su tipo, en su orden y con
 * cuántas veces puede venir), los mensajes, la operación, cómo viaja (estilo,
 * transporte, {@code soapAction}, uso del cuerpo), el servicio, el puerto y la
 * ruta de la dirección.
 *
 * Deja afuera lo que cambia según quién genera el WSDL y no viaja en ningún
 * mensaje: el formato del texto y los prefijos; el nombre interno del binding
 * (CXF, el de WildFly, lo llama {@code ArcaSimuladoSoapBinding} y Metro, el de
 * {@code Endpoint.publish}, {@code ArcaSimuladoPortBinding}); si el esquema va
 * adentro del WSDL o importado; los valores por defecto que uno escribe y el
 * otro no ({@code elementFormDefault}, el estilo de cada operación); las
 * acciones de WS-Addressing que agrega Metro; y el host y el puerto de la
 * dirección, que dependen de dónde corre.
 *
 * Las líneas salen ordenadas: dos generadores pueden listar los mensajes en
 * otro orden. El orden de los elementos de un tipo sí importa (es una
 * {@code xs:sequence}), y por eso va adentro de una sola línea.
 */
final class ResumenDeWsdl {

    static final String WSDL = "http://schemas.xmlsoap.org/wsdl/";
    static final String SOAP = "http://schemas.xmlsoap.org/wsdl/soap/";
    static final String XSD = "http://www.w3.org/2001/XMLSchema";

    /** Trae un esquema importado ({@code xs:import} con {@code schemaLocation}). */
    @FunctionalInterface
    interface Cargador {
        Document cargar(URI direccion) throws Exception;
    }

    private ResumenDeWsdl() {
    }

    /**
     * El resumen del WSDL. {@code direccion} es de donde se leyó, para
     * resolver los {@code schemaLocation} relativos.
     */
    static List<String> de(Document wsdl, URI direccion, Cargador cargador) throws Exception {
        Element definiciones = wsdl.getDocumentElement();
        String espacio = definiciones.getAttribute("targetNamespace");
        List<String> lineas = new ArrayList<>();
        lineas.add("espacio de nombres " + espacio);

        for (Element esquema : esquemas(definiciones, direccion, cargador)) {
            lineas.addAll(tipos(esquema, espacio));
        }
        for (Element mensaje : hijos(definiciones, WSDL, "message")) {
            for (Element parte : hijos(mensaje, WSDL, "part")) {
                lineas.add("mensaje " + mensaje.getAttribute("name") + ": parte " + parte.getAttribute("name")
                        + ", elemento " + nombre(parte, parte.getAttribute("element"), espacio));
            }
        }
        for (Element tipoDePuerto : hijos(definiciones, WSDL, "portType")) {
            for (Element operacion : hijos(tipoDePuerto, WSDL, "operation")) {
                lineas.add("operación " + tipoDePuerto.getAttribute("name") + "." + operacion.getAttribute("name")
                        + ": entrada " + mensajeDe(operacion, "input", espacio)
                        + ", salida " + mensajeDe(operacion, "output", espacio));
            }
        }
        for (Element servicio : hijos(definiciones, WSDL, "service")) {
            for (Element puerto : hijos(servicio, WSDL, "port")) {
                String nombrePuerto = puerto.getAttribute("name");
                String ruta = URI.create(unico(puerto, SOAP, "address").getAttribute("location")).getPath();
                lineas.add("servicio " + servicio.getAttribute("name") + ", puerto " + nombrePuerto + ", ruta " + ruta);
                lineas.addAll(binding(definiciones, puerto, espacio));
            }
        }
        lineas.sort(null);
        return lineas;
    }

    /**
     * Los esquemas con tipos: los que están adentro de {@code wsdl:types} y los
     * que esos importan. Uno que solo importa a otro (el envoltorio de Metro)
     * no suma nada al contrato y no se lista.
     */
    private static List<Element> esquemas(Element definiciones, URI direccion, Cargador cargador) throws Exception {
        List<Element> esquemas = new ArrayList<>();
        for (Element tipos : hijos(definiciones, WSDL, "types")) {
            for (Element esquema : hijos(tipos, XSD, "schema")) {
                if (tieneTipos(esquema)) {
                    esquemas.add(esquema);
                }
                for (Element importacion : hijos(esquema, XSD, "import")) {
                    String ubicacion = importacion.getAttribute("schemaLocation");
                    if (!ubicacion.isEmpty()) {
                        esquemas.add(cargador.cargar(direccion.resolve(ubicacion)).getDocumentElement());
                    }
                }
            }
        }
        return esquemas;
    }

    private static boolean tieneTipos(Element esquema) {
        return !hijos(esquema, XSD, "element").isEmpty() || !hijos(esquema, XSD, "complexType").isEmpty()
                || !hijos(esquema, XSD, "simpleType").isEmpty();
    }

    private static List<String> tipos(Element esquema, String espacio) {
        List<String> lineas = new ArrayList<>();
        // Sin el atributo, los elementos locales van sin espacio de nombres: es lo mismo que "unqualified".
        String forma = esquema.getAttribute("elementFormDefault");
        lineas.add("esquema " + esquema.getAttribute("targetNamespace") + ": elementos locales "
                + (forma.isEmpty() ? "unqualified" : forma));
        for (Element elemento : hijos(esquema, XSD, "element")) {
            lineas.add("elemento " + elemento.getAttribute("name") + ": tipo "
                    + nombre(elemento, elemento.getAttribute("type"), espacio));
        }
        for (Element tipo : hijos(esquema, XSD, "complexType")) {
            lineas.add("tipo " + tipo.getAttribute("name") + ": " + contenido(tipo, espacio));
        }
        for (Element tipo : hijos(esquema, XSD, "simpleType")) {
            lineas.add("tipo simple " + tipo.getAttribute("name"));
        }
        return lineas;
    }

    /**
     * Los elementos de la secuencia, en orden: nombre, tipo y cuántas veces
     * puede venir. Cualquier otro contenido (atributos, una elección) aparece
     * por su nombre, así un cambio de ese estilo también se nota.
     */
    private static String contenido(Element tipo, String espacio) {
        List<String> partes = new ArrayList<>();
        for (Element hijo : hijos(tipo, XSD, null)) {
            if (!hijo.getLocalName().equals("sequence")) {
                partes.add("<" + hijo.getLocalName() + ">");
                continue;
            }
            for (Element elemento : hijos(hijo, XSD, null)) {
                if (!elemento.getLocalName().equals("element")) {
                    partes.add("<" + elemento.getLocalName() + ">");
                    continue;
                }
                partes.add(elemento.getAttribute("name") + " " + nombre(elemento, elemento.getAttribute("type"), espacio)
                        + " [" + atributo(elemento, "minOccurs", "1") + ".." + atributo(elemento, "maxOccurs", "1") + "]"
                        + (Boolean.parseBoolean(elemento.getAttribute("nillable")) ? " nillable" : ""));
            }
        }
        return String.join(", ", partes);
    }

    /** El binding del puerto, leído desde el puerto: su nombre interno no cuenta. */
    private static List<String> binding(Element definiciones, Element puerto, String espacio) {
        String nombrePuerto = puerto.getAttribute("name");
        String buscado = local(puerto.getAttribute("binding"));
        Element binding = hijos(definiciones, WSDL, "binding").stream()
                .filter(b -> b.getAttribute("name").equals(buscado))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("El puerto " + nombrePuerto
                        + " nombra un binding que no está: " + buscado));
        Element soap = unico(binding, SOAP, "binding");
        String estilo = atributo(soap, "style", "document");

        List<String> lineas = new ArrayList<>();
        lineas.add("binding del puerto " + nombrePuerto + ": portType " + nombre(binding, binding.getAttribute("type"), espacio)
                + ", estilo " + estilo + ", transporte " + soap.getAttribute("transport"));
        for (Element operacion : hijos(binding, WSDL, "operation")) {
            Element soapOperacion = unico(operacion, SOAP, "operation");
            lineas.add("binding del puerto " + nombrePuerto + ", operación " + operacion.getAttribute("name")
                    + ": soapAction \"" + soapOperacion.getAttribute("soapAction") + "\""
                    + ", estilo " + atributo(soapOperacion, "style", estilo)
                    + ", entrada " + usoDelCuerpo(operacion, "input")
                    + ", salida " + usoDelCuerpo(operacion, "output"));
        }
        return lineas;
    }

    private static String usoDelCuerpo(Element operacion, String sentido) {
        return unico(unico(operacion, WSDL, sentido), SOAP, "body").getAttribute("use");
    }

    private static String mensajeDe(Element operacion, String sentido, String espacio) {
        Element mensaje = unico(operacion, WSDL, sentido);
        return nombre(mensaje, mensaje.getAttribute("message"), espacio);
    }

    /**
     * Un nombre calificado ({@code tns:RespuestaCae}, {@code xs:long}) con un
     * prefijo fijo según su espacio de nombres: {@code xs} para XML Schema y
     * {@code tns} para el del servicio. Así no importa qué prefijos eligió el
     * generador.
     */
    private static String nombre(Element donde, String calificado, String espacio) {
        int dosPuntos = calificado.indexOf(':');
        String prefijo = dosPuntos < 0 ? null : calificado.substring(0, dosPuntos);
        String uri = donde.lookupNamespaceURI(prefijo);
        String local = calificado.substring(dosPuntos + 1);
        if (XSD.equals(uri)) {
            return "xs:" + local;
        }
        if (espacio.equals(uri)) {
            return "tns:" + local;
        }
        return "{" + uri + "}" + local;
    }

    private static String local(String calificado) {
        return calificado.substring(calificado.indexOf(':') + 1);
    }

    private static String atributo(Element elemento, String nombre, String porDefecto) {
        String valor = elemento.getAttribute(nombre);
        return valor.isEmpty() ? porDefecto : valor;
    }

    /** Los hijos directos con ese espacio de nombres y, si no es null, ese nombre local. */
    static List<Element> hijos(Element padre, String espacio, String nombreLocal) {
        List<Element> hijos = new ArrayList<>();
        for (Node nodo = padre.getFirstChild(); nodo != null; nodo = nodo.getNextSibling()) {
            if (nodo instanceof Element elemento && espacio.equals(elemento.getNamespaceURI())
                    && (nombreLocal == null || nombreLocal.equals(elemento.getLocalName()))) {
                hijos.add(elemento);
            }
        }
        return hijos;
    }

    private static Element unico(Element padre, String espacio, String nombreLocal) {
        List<Element> encontrados = hijos(padre, espacio, nombreLocal);
        if (encontrados.size() != 1) {
            throw new IllegalArgumentException("Se esperaba un " + nombreLocal + " adentro de " + padre.getLocalName()
                    + " " + padre.getAttribute("name") + " y hay " + encontrados.size() + ".");
        }
        return encontrados.get(0);
    }
}
