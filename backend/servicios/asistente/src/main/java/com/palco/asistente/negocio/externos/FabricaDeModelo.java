package com.palco.asistente.negocio.externos;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import com.palco.asistente.negocio.Configuracion;

/**
 * Elige el modelo por configuración (patrón Factory), con {@code palco.asistente.modelo}
 * (variable de entorno {@code PALCO_ASISTENTE_MODELO}). Hay dos: {@code simulado}, el valor por
 * defecto, y {@code openai}, un modelo real por la API de chat de OpenAI
 * ({@link ModeloCompatibleOpenAI}). Otro proveedor se suma como otra implementación de
 * {@link ModeloDeLenguaje} y otro caso acá, sin tocar el resto del servicio.
 */
@ApplicationScoped
public class FabricaDeModelo {

    static final String PROPIEDAD = "palco.asistente.modelo";

    @Inject
    ModeloSimulado simulado;

    @Inject
    ModeloCompatibleOpenAI compatibleOpenAI;

    @Produces
    @ApplicationScoped
    ModeloDeLenguaje modelo() {
        String elegido = Configuracion.leer(PROPIEDAD, String.class, "simulado");
        return switch (elegido) {
            case "simulado" -> simulado;
            case "openai" -> compatibleOpenAI;
            default -> throw new IllegalStateException(
                    "Modelo desconocido en " + PROPIEDAD + ": " + elegido + ". Los disponibles son 'simulado' y 'openai'.");
        };
    }
}
