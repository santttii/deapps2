package com.palco.common.mensajeria;

import java.io.StringReader;
import java.time.Instant;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonReader;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;

/**
 * El mensaje {@code COMPRA_ANULADA}: lo publica Ventas por cada compra que
 * anuló porque se canceló su evento, en el tópico
 * {@code topico.eventos-cancelados}.
 *
 * Existe aparte de {@link EventoCanceladoMensaje} porque son dos cosas
 * distintas: la cancelación es un hecho del evento (uno por evento) y la
 * anulación es un hecho de cada compra (uno por comprador). Notificaciones
 * necesita el segundo, que es el que trae a quién avisarle y cuánto se le
 * devolvió; auditoría anota los dos.
 *
 * {@code reembolsado} dice si Pagos llegó a devolver el dinero. Puede ser
 * false: la entrada se anula igual, y el reembolso queda para la conciliación.
 * Así el aviso no promete una devolución que todavía no se hizo.
 *
 * @param ventaId     id de la venta anulada
 * @param compradorId id del usuario que había comprado
 * @param eventoId    id del evento cancelado
 * @param titulo      título del evento, para el aviso
 * @param motivo      lo que se le cuenta al comprador; puede ser null
 * @param monto       lo que había pagado, en pesos enteros
 * @param reembolsado si Pagos ya devolvió ese monto
 * @param timestamp   instante de la anulación
 */
public record CompraAnuladaMensaje(String ventaId, String compradorId, String eventoId, String titulo,
        String motivo, long monto, boolean reembolsado, Instant timestamp) {

    /** Largo de la columna de los ids en todas las tablas. */
    private static final int LARGO_MAXIMO_ID = 40;

    private static final Jsonb JSONB = JsonbBuilder.create();

    /** El mensaje como texto JSON. */
    public String aJson() {
        return JSONB.toJson(this);
    }

    /** Convierte el texto recibido y lo valida. Mismo cuidado que los otros mensajes con el JSON cortado. */
    public static CompraAnuladaMensaje desdeJson(String json) {
        if (json == null || json.isBlank()) {
            throw new MensajeInvalido("El mensaje está vacío.");
        }
        try (JsonReader lector = Json.createReader(new StringReader(json))) {
            lector.readValue();
        } catch (JsonException e) {
            throw new MensajeInvalido("El mensaje no es un JSON de COMPRA_ANULADA.", e);
        }
        CompraAnuladaMensaje mensaje;
        try {
            mensaje = JSONB.fromJson(json, CompraAnuladaMensaje.class);
        } catch (JsonbException e) {
            throw new MensajeInvalido("El mensaje no es un JSON de COMPRA_ANULADA.", e);
        }
        if (mensaje == null) {
            throw new MensajeInvalido("El mensaje es null.");
        }
        mensaje.validar();
        return mensaje;
    }

    /** Comprueba que el mensaje esté completo; si no, lanza {@link MensajeInvalido} diciendo qué falta. */
    public void validar() {
        exigirId("ventaId", ventaId);
        exigirId("compradorId", compradorId);
        exigirId("eventoId", eventoId);
        if (titulo == null || titulo.isBlank()) {
            throw new MensajeInvalido("Falta el título del evento.");
        }
        if (monto <= 0) {
            throw new MensajeInvalido("monto tiene que ser mayor que 0.");
        }
        if (timestamp == null) {
            throw new MensajeInvalido("Falta timestamp.");
        }
    }

    private static void exigirId(String campo, String valor) {
        if (valor == null || valor.isBlank() || valor.length() > LARGO_MAXIMO_ID) {
            throw new MensajeInvalido(campo + " tiene que tener entre 1 y " + LARGO_MAXIMO_ID + " caracteres.");
        }
    }
}
