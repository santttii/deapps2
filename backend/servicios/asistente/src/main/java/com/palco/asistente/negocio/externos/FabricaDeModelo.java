package com.palco.asistente.negocio.externos;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import com.palco.asistente.negocio.Configuracion;

/**
 * Elige el modelo por configuración (patrón Factory), con {@code palco.asistente.modelo}. Hoy
 * solo existe {@code simulado}, que es el valor por defecto. Un proveedor real se suma como otra
 * implementación de {@link ModeloDeLenguaje} y otro caso acá, sin tocar el resto del servicio.
 */
@ApplicationScoped
public class FabricaDeModelo {

    static final String PROPIEDAD = "palco.asistente.modelo";

    @Inject
    ModeloSimulado simulado;

    @Produces
    @ApplicationScoped
    ModeloDeLenguaje modelo() {
        String elegido = Configuracion.leer(PROPIEDAD, String.class, "simulado");
        return switch (elegido) {
            case "simulado" -> simulado;
            default -> throw new IllegalStateException(
                    "Modelo desconocido en " + PROPIEDAD + ": " + elegido + ". El único disponible es 'simulado'.");
        };
    }
}
