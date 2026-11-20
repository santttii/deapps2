package com.palco.usuarios.negocio;

import java.util.List;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.DependsOn;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.ConfigProvider;

import com.palco.usuarios.datos.UsuarioRepository;
import com.palco.usuarios.dominio.Usuario;
import com.palco.usuarios.seguridad.Contrasenas;

/**
 * Las seis cuentas de prueba (@palco.test) las cargan V2, V3 y V6 en toda base,
 * también en producción, y su contraseña es pública: está en el README. Esas
 * migraciones no se pueden sacar ni cambiar, porque Flyway valida las que ya
 * corrieron. Lo que cambia según el entorno es si las cuentas sirven, y eso lo
 * decide este bean al arrancar con la propiedad {@code palco.usuarios.cuentas-de-prueba}
 * (variable de entorno {@code PALCO_USUARIOS_CUENTAS_DE_PRUEBA}):
 * <ul>
 * <li>{@code true}, el valor por defecto: quedan como están. La demo, el
 * compose, el CI y las pruebas entran con ellas.</li>
 * <li>{@code false}, para producción: su hash pasa a {@link Contrasenas#INUTILIZABLE}
 * y ninguna contraseña las abre. La fila queda, porque los eventos de prueba
 * (V6 de Eventos) y la puerta asignada (V2 de Validación) la nombran.</li>
 * </ul>
 * Volver a {@code true} no les devuelve la contraseña: la propiedad solo desactiva.
 *
 * Singleton y {@code @Startup}: es una tarea del arranque, una vez por
 * despliegue, como {@link MigracionesUsuarios}, del que depende porque necesita
 * la tabla. No atiende pedidos ni guarda nada entre llamadas. Corre en una sola
 * transacción: o quedan desactivadas todas o ninguna, y si falla el servicio no
 * despliega, que es mejor que arrancar con las cuentas abiertas. Correrlo de
 * nuevo no cambia nada: una cuenta que ya tiene ese hash no se toca.
 */
@Startup
@Singleton
@DependsOn("MigracionesUsuarios")
public class DesactivacionDeCuentasDePrueba {

    private static final Logger LOG = Logger.getLogger(DesactivacionDeCuentasDePrueba.class.getName());

    static final String PROPIEDAD = "palco.usuarios.cuentas-de-prueba";
    /** Un dominio reservado para pruebas (RFC 2606): ninguna persona tiene un email ahí. */
    static final String DOMINIO = "palco.test";

    @Inject
    UsuarioRepository usuarios;

    /** REQUIRED en el arranque de un Singleton: el contenedor abre una transacción nueva para este método. */
    @PostConstruct
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    void alArrancar() {
        // getOptionalValue y no @ConfigProperty: con la anotación, una propiedad sin valor impide el despliegue.
        boolean activas = ConfigProvider.getConfig().getOptionalValue(PROPIEDAD, Boolean.class).orElse(true);
        aplicar(activas);
    }

    /**
     * Aparte de {@link #alArrancar} para poder probarlo sin servidor. Devuelve
     * cuántas cuentas desactivó esta vez. Las cuentas vienen administradas
     * (ver {@link UsuarioRepository}): el UPDATE sale al confirmar la transacción.
     */
    int aplicar(boolean activas) {
        if (activas) {
            LOG.warning("Cuentas de prueba (@" + DOMINIO + ") activas, con la contraseña del README. "
                    + "En producción hay que definir PALCO_USUARIOS_CUENTAS_DE_PRUEBA=false.");
            return 0;
        }
        List<Usuario> cuentas = usuarios.buscarPorDominioDeEmail(DOMINIO);
        int desactivadas = 0;
        for (Usuario cuenta : cuentas) {
            if (!Contrasenas.INUTILIZABLE.equals(cuenta.getPasswordHash())) {
                cuenta.cambiarPassword(Contrasenas.INUTILIZABLE);
                desactivadas++;
            }
        }
        LOG.info("Cuentas de prueba (@" + DOMINIO + ") desactivadas: " + desactivadas + " en este arranque, "
                + (cuentas.size() - desactivadas) + " ya lo estaban.");
        return desactivadas;
    }
}
