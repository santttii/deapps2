package com.palco.facturacion.negocio.externos;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import com.palco.common.seguridad.TokenDeServicio;

/**
 * Factory: arma el {@link Arca} que usa el negocio a partir de la
 * configuración. Es el único lugar que conoce {@link ArcaSoap}; el negocio
 * pide un {@code Arca} con {@code @Inject}.
 *
 * Configuración (cada propiedad se puede definir con su variable de entorno,
 * en mayúsculas y con guiones bajos):
 * <ul>
 * <li>{@code palco.facturacion.arca.wsdl} ({@code PALCO_FACTURACION_ARCA_WSDL}):
 * la URL del WSDL. Por defecto, {@code ${PALCO_URL_INTERNA}/legado/arca?wsdl},
 * con {@code http://localhost:8080} si falta {@code PALCO_URL_INTERNA}.</li>
 * <li>{@code palco.facturacion.arca.espera-conexion-ms}: 2000 por defecto.</li>
 * <li>{@code palco.facturacion.arca.espera-lectura-ms}: 5000 por defecto.
 * El simulado responde en milisegundos; 5 segundos dejan margen sin que las 5
 * entregas del broker tarden más de medio minuto en llegar a la DLQ.</li>
 * </ul>
 *
 * {@code @ApplicationScoped}, acá y en lo que produce: un solo cliente para
 * todo el servicio, que se crea una vez. Es configuración, no la conversación
 * de un usuario.
 */
@ApplicationScoped
public class FabricaDeArca {

    static final String PROPIEDAD_WSDL = "palco.facturacion.arca.wsdl";
    static final String PROPIEDAD_ESPERA_CONEXION = "palco.facturacion.arca.espera-conexion-ms";
    static final String PROPIEDAD_ESPERA_LECTURA = "palco.facturacion.arca.espera-lectura-ms";

    static final String WSDL_POR_DEFECTO = TokenDeServicio.URL_INTERNA + "/legado/arca?wsdl";
    static final long ESPERA_CONEXION_POR_DEFECTO = 2_000;
    static final long ESPERA_LECTURA_POR_DEFECTO = 5_000;

    /** La propiedad con la que CXF habilitaría la DTD y las entidades externas (ver {@link ArcaSoap}). */
    static final String PARSER_INSEGURO = "org.apache.cxf.stax.allowInsecureParser";

    @Produces
    @ApplicationScoped
    Arca arca() {
        // getOptionalValue y no @ConfigProperty: con la anotación, una propiedad sin valor impide el despliegue.
        Config configuracion = ConfigProvider.getConfig();
        return crear(
                configuracion.getOptionalValue(PROPIEDAD_WSDL, String.class).orElse(WSDL_POR_DEFECTO),
                configuracion.getOptionalValue(PROPIEDAD_ESPERA_CONEXION, Long.class).orElse(ESPERA_CONEXION_POR_DEFECTO),
                configuracion.getOptionalValue(PROPIEDAD_ESPERA_LECTURA, Long.class).orElse(ESPERA_LECTURA_POR_DEFECTO));
    }

    /** Aparte del método de arriba para poder probarlo sin servidor. */
    static Arca crear(String urlDelWsdl, long milisegundosConexion, long milisegundosLectura) {
        if (Boolean.parseBoolean(System.getProperty(PARSER_INSEGURO))) {
            // SEC-I01: con esta propiedad, el XML que llega por SOAP podría traer una DTD con
            // entidades externas (XXE). Se prefiere que Facturación no arranque.
            throw new IllegalStateException("La propiedad de sistema " + PARSER_INSEGURO
                    + " habilita la DTD y las entidades externas en el XML de SOAP: sacala para facturar.");
        }
        if (milisegundosConexion <= 0 || milisegundosLectura <= 0) {
            // 0 en CXF y en Metro es "esperar para siempre": justo lo que los tiempos evitan.
            throw new IllegalStateException("Los tiempos de espera con ARCA tienen que ser mayores que 0 ("
                    + PROPIEDAD_ESPERA_CONEXION + ", " + PROPIEDAD_ESPERA_LECTURA + ").");
        }
        return new ArcaSoap(urlDelWsdl, Duration.ofMillis(milisegundosConexion), Duration.ofMillis(milisegundosLectura));
    }
}
