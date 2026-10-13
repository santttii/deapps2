package com.palco.pagos.pasarela;

import java.util.Locale;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Factory: decide qué pasarela se crea a partir de la configuración. Es el
 * único lugar que conoce las clases concretas; el negocio pide un
 * {@code PasarelaDePagos} con {@code @Inject} y recibe la que salga de acá.
 *
 * La elige la propiedad {@code palco.pagos.proveedor}, que se define con la
 * variable de entorno {@code PALCO_PAGOS_PROVEEDOR}. Si falta, vale
 * {@code fake}. Un valor que no se conoce no cae en silencio al simulador:
 * lanza un error y el servicio no despliega.
 *
 * {@code @ApplicationScoped}, acá y en lo que produce: hay una sola pasarela
 * para todo el servicio, la misma para todos los pedidos, y se crea una vez.
 * No es la conversación de un usuario, así que no hace falta un componente
 * con estado.
 */
@ApplicationScoped
public class FabricaDePasarela {

    static final String PROPIEDAD = "palco.pagos.proveedor";

    /** Lo que CDI entrega donde alguien pide {@code @Inject PasarelaDePagos}. */
    @Produces
    @ApplicationScoped
    PasarelaDePagos pasarela() {
        // getOptionalValue y no @ConfigProperty: con la anotación, una propiedad sin valor impide el despliegue.
        String proveedor = ConfigProvider.getConfig()
                .getOptionalValue(PROPIEDAD, String.class)
                .orElse(PagosFake.NOMBRE);
        return crear(proveedor);
    }

    /** Aparte del método de arriba para poder probarlo sin servidor. */
    static PasarelaDePagos crear(String proveedor) {
        String nombre = proveedor.strip().toLowerCase(Locale.ROOT);
        if (nombre.equals(PagosFake.NOMBRE)) {
            return new PagosFake();
        }
        // Acá se suma Mercado Pago cuando exista su adaptador.
        throw new IllegalStateException("La propiedad " + PROPIEDAD + " vale \"" + proveedor
                + "\" y no hay ninguna pasarela con ese nombre. El único valor posible hoy es \""
                + PagosFake.NOMBRE + "\".");
    }
}
