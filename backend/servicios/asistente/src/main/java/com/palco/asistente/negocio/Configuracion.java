package com.palco.asistente.negocio;

import java.util.Optional;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Lee la configuración del asistente (palco.asistente.*). En el servidor la da MicroProfile
 * Config; sin una implementación (por ejemplo, en un test unitario) devuelve el valor por defecto.
 */
public final class Configuracion {

    private Configuracion() {
    }

    public static <T> T leer(String propiedad, Class<T> tipo, T porDefecto) {
        try {
            Optional<T> valor = ConfigProvider.getConfig().getOptionalValue(propiedad, tipo);
            return valor.orElse(porDefecto);
        } catch (RuntimeException sinConfiguracion) {
            return porDefecto;
        }
    }
}
