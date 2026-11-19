package com.palco.asistente.negocio;

import java.util.List;
import java.util.Optional;

import com.palco.common.ReglasDeNegocio;

/**
 * El fallback: respuestas fijas, sin IA, buscadas por palabras clave. Se usan cuando el modelo
 * no responde en 5 segundos, falla o el asistente está apagado.
 */
public final class RespuestasFrecuentes {

    private record Frecuente(List<String> palabras, String respuesta) { }

    private static final List<Frecuente> FRECUENTES = List.of(
            new Frecuente(List.of("revend", "reventa", "publicar"),
                    "La reventa oficial permite publicar una entrada entre el "
                            + ReglasDeNegocio.MINIMO_REVENTA_PORCENTAJE + " % del precio original de la entrada y ese precio más un "
                            + ReglasDeNegocio.TOPE_REVENTA_PORCENTAJE + " %, hasta "
                            + ReglasDeNegocio.HORAS_LIMITE_REVENTA + " horas antes del evento."),
            new Frecuente(List.of("cuantas entradas", "maximo de entradas", "por compra"),
                    "Podés comprar hasta " + ReglasDeNegocio.MAX_ENTRADAS_POR_ORDEN + " entradas por compra y "
                            + ReglasDeNegocio.MAX_ENTRADAS_POR_EVENTO + " por evento; cada DNI, una sola entrada por evento."),
            new Frecuente(List.of("factura"),
                    "La factura está en Mis entradas, con el botón Ver factura: tiene su número y el CAE de ARCA."),
            new Frecuente(List.of("reserva", "minutos", "tiempo para pagar"),
                    "Los lugares quedan reservados " + ReglasDeNegocio.MINUTOS_RESERVA
                            + " minutos mientras pagás."),
            new Frecuente(List.of("cargo", "comision"),
                    "El cargo de servicio es el " + ReglasDeNegocio.TASA_SERVICIO_PORCENTAJE + " % del subtotal."),
            new Frecuente(List.of("puerta", "ingres", "llevar"),
                    "En la puerta mostrás el QR de tu entrada y el DNI del titular."),
            new Frecuente(List.of("cancel", "reembols", "devolucion"),
                    "Una compra se cancela solo antes de pagarla. Si se cancela un evento, la compra se anula y "
                            + "se devuelve el dinero. Te avisamos por email."));

    private RespuestasFrecuentes() {
    }

    /** La respuesta frecuente que mejor coincide con la pregunta, si hay alguna. */
    public static Optional<String> buscar(String pregunta) {
        String normalizada = Texto.normalizar(pregunta);
        return FRECUENTES.stream()
                .filter(f -> f.palabras().stream().anyMatch(normalizada::contains))
                .map(Frecuente::respuesta)
                .findFirst();
    }
}
