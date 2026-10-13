package com.palco.ventas.negocio.externos;

import java.util.Locale;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Factory: decide quién cobra a partir de la configuración. Es el único lugar
 * que conoce las dos implementaciones; la compra pide un {@code Cobros} con
 * {@code @Inject} y recibe el que salga de acá. Es el mismo mecanismo con el
 * que Pagos elige su pasarela ({@code FabricaDePasarela}).
 *
 * Lo elige la propiedad {@code palco.ventas.cobros}, que se define con la
 * variable de entorno {@code PALCO_VENTAS_COBROS}:
 *
 * <ul>
 * <li>{@code pagos}: la llamada REST al servicio de Pagos ({@link ClienteDePagos}).
 * Es el valor por defecto;</li>
 * <li>{@code fake}: el simulador local ({@link CobrosFake}), para trabajar en
 * Ventas cuando Pagos no está desplegado.</li>
 * </ul>
 *
 * El defecto es Pagos para que el simulador no quede puesto por olvido: solo
 * se cobra de mentira si alguien escribió {@code fake}. Por lo mismo, un valor
 * que no se conoce no cae al simulador: se cobra contra Pagos y queda un
 * WARNING con los valores posibles.
 *
 * {@code @ApplicationScoped}, acá y en lo que produce: se elige una sola vez,
 * en el primer cobro, y vale para todas las compras hasta que se redespliega
 * el servicio.
 */
@ApplicationScoped
public class FabricaDeCobros {

    private static final Logger LOG = Logger.getLogger(FabricaDeCobros.class.getName());

    static final String PROPIEDAD = "palco.ventas.cobros";

    /**
     * Lo que CDI entrega donde alguien pide {@code @Inject Cobros}. Los dos
     * candidatos llegan como parámetros: los crea el contenedor, y recién
     * cuando se usan, así que el que no se elige nunca abre su cliente HTTP.
     */
    @Produces
    @ApplicationScoped
    Cobros cobros(ClienteDePagos pagos, CobrosFake simulador) {
        // getOptionalValue y no @ConfigProperty: con la anotación, una propiedad sin valor impide el despliegue.
        String elegido = ConfigProvider.getConfig()
                .getOptionalValue(PROPIEDAD, String.class)
                .orElse(ClienteDePagos.NOMBRE);
        return elegir(elegido, pagos, simulador);
    }

    /** Aparte del método de arriba para poder probarlo sin servidor. */
    static Cobros elegir(String elegido, Cobros pagos, Cobros simulador) {
        String nombre = elegido.strip().toLowerCase(Locale.ROOT);
        if (nombre.equals(CobrosFake.NOMBRE)) {
            LOG.warning("Cobros: " + PROPIEDAD + " vale \"" + CobrosFake.NOMBRE + "\". Ventas cobra con el simulador"
                    + " local y NO llama al servicio de Pagos: ningún pago queda guardado.");
            return simulador;
        }
        if (!nombre.equals(ClienteDePagos.NOMBRE)) {
            LOG.warning("Cobros: " + PROPIEDAD + " vale \"" + elegido + "\" y ese valor no existe. Los posibles son \""
                    + ClienteDePagos.NOMBRE + "\" y \"" + CobrosFake.NOMBRE + "\". Se cobra contra el servicio de Pagos.");
        }
        LOG.info("Cobros: Ventas cobra llamando al servicio de Pagos (POST " + ClienteDePagos.RUTA_DE_PAGOS + ")."
                + " Para usar el simulador local: " + PROPIEDAD + "=" + CobrosFake.NOMBRE + ".");
        return pagos;
    }
}
