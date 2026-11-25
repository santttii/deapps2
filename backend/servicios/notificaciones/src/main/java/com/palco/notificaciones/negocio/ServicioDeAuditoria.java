package com.palco.notificaciones.negocio;

import java.io.StringReader;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.logging.Logger;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import com.palco.common.Textos;
import com.palco.common.mensajeria.CompraAnuladaMensaje;
import com.palco.common.mensajeria.DestinosJms;
import com.palco.common.mensajeria.PagoConfirmadoMensaje;
import com.palco.notificaciones.api.Dtos.RegistroDeAuditoriaDto;
import com.palco.notificaciones.datos.RegistroDeAuditoriaRepository;
import com.palco.notificaciones.dominio.RegistroDeAuditoria;

/**
 * Stateless: registrar un mensaje no depende del anterior, y lo que tiene que
 * durar queda en la base, así que el contenedor reutiliza un pool de
 * instancias. El reloj no es estado de una conversación: es el mismo para
 * todas las instancias y solo se reemplaza en los tests.
 *
 * Decide qué se guarda y cuándo no (la reentrega del broker); el acceso a la
 * base está en {@link RegistroDeAuditoriaRepository}, que se suma a la
 * transacción del MDB.
 *
 * Del cuerpo de una compra se guarda todo menos el DNI de los titulares
 * (DEF-37): desde el 24/11 el mensaje ya no lo trae, pero uno publicado antes
 * puede llegar después (estaba en la suscripción durable o vuelve de la DLQ).
 * La auditoría no lo usa y la entrada, con su titular, está en Ventas. Las
 * filas guardadas antes las limpia la migración V3.
 */
@Stateless
public class ServicioDeAuditoria implements IAuditoria {

    private static final Logger LOG = Logger.getLogger(ServicioDeAuditoria.class.getName());

    static final int MAXIMO_A_LISTAR = 50;

    /** Los campos del cuerpo de PAGO_CONFIRMADO que hacen falta para sacar el DNI. */
    private static final String ENTRADAS = "entradas";
    private static final String TITULAR = "titular";
    private static final String DNI = "dni";

    @Inject
    RegistroDeAuditoriaRepository registros;

    /** De acá sale el "ahora". El servidor corre en hora argentina y la columna guarda hora universal (UTC). */
    @SuppressWarnings("java:S8983") // Se fija al crear el bean, no cambia entre llamadas y los tests lo reemplazan.
    Clock reloj = Clock.systemUTC();

    @Override
    public void registrar(String mensajeId, String tipoEvento, PagoConfirmadoMensaje mensaje, String cuerpo) {
        // El id del mensaje es la clave primaria y no cambia cuando el broker reentrega:
        // si la fila ya está, este mensaje ya se registró y no hay nada que hacer.
        if (registros.existe(mensajeId)) {
            LOG.info("El mensaje " + Textos.enUnaLinea(mensajeId)
                    + " ya estaba registrado (reentrega del broker): no se repite.");
            return;
        }
        // guardar manda el INSERT en el momento: si la base lo rechaza, la falla sale de acá y
        // quien llama no llega a escribir en el log que procesó un mensaje cuya fila no existe.
        registros.guardar(new RegistroDeAuditoria(
                mensajeId,
                // Lo que dice el mensaje: PAGO_CONFIRMADO o REVENTA_CONFIRMADA (el MDB filtra los demás).
                tipoEvento,
                mensaje.ventaId(),
                mensaje.compradorId(),
                mensaje.eventoId(),
                mensaje.montoTotal(),
                sinElDniDeLosTitulares(cuerpo),
                ahoraEnUtc()));
    }

    /**
     * El cuerpo tal como llegó, menos el campo {@code dni} del titular de cada
     * entrada. Si no trae ninguno (un mensaje publicado desde el 24/11), vuelve
     * el mismo texto. Si trae alguno, se escribe de nuevo con el resto de los
     * campos en el mismo orden: un mensaje viejo queda guardado igual que uno
     * nuevo. El texto ya pasó por {@link PagoConfirmadoMensaje#desdeJson}, así
     * que es un JSON con la forma del contrato.
     */
    static String sinElDniDeLosTitulares(String cuerpo) {
        JsonObject leido;
        try (JsonReader lector = Json.createReader(new StringReader(cuerpo))) {
            leido = lector.readObject();
        }
        JsonArray entradas = leido.getJsonArray(ENTRADAS);
        if (entradas == null || entradas.stream().noneMatch(ServicioDeAuditoria::tieneDni)) {
            return cuerpo;
        }
        JsonArrayBuilder limpias = Json.createArrayBuilder();
        for (JsonValue entrada : entradas) {
            limpias.add(tieneDni(entrada) ? sinDni(entrada.asJsonObject()) : entrada);
        }
        return Json.createObjectBuilder(leido).add(ENTRADAS, limpias).build().toString();
    }

    private static boolean tieneDni(JsonValue entrada) {
        return entrada instanceof JsonObject objeto
                && objeto.get(TITULAR) instanceof JsonObject titular
                && titular.containsKey(DNI);
    }

    private static JsonObject sinDni(JsonObject entrada) {
        return Json.createObjectBuilder(entrada)
                .add(TITULAR, Json.createObjectBuilder(entrada.getJsonObject(TITULAR)).remove(DNI))
                .build();
    }

    @Override
    public void registrarAnulacion(String mensajeId, CompraAnuladaMensaje mensaje, String cuerpo) {
        if (registros.existe(mensajeId)) {
            LOG.info("El mensaje " + Textos.enUnaLinea(mensajeId)
                    + " ya estaba registrado (reentrega del broker): no se repite.");
            return;
        }
        registros.guardar(new RegistroDeAuditoria(
                mensajeId,
                DestinosJms.COMPRA_ANULADA,
                mensaje.ventaId(),
                mensaje.compradorId(),
                mensaje.eventoId(),
                mensaje.monto(),
                cuerpo,
                ahoraEnUtc()));
    }

    /** La hora del reloj pasada a UTC a mano, para no depender de la zona con que se lo haya creado. */
    private LocalDateTime ahoraEnUtc() {
        return LocalDateTime.ofInstant(reloj.instant().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);
    }

    @Override
    public List<RegistroDeAuditoriaDto> listar() {
        return registros.ultimos(MAXIMO_A_LISTAR)
                .stream()
                .map(ServicioDeAuditoria::aDto)
                .toList();
    }

    static RegistroDeAuditoriaDto aDto(RegistroDeAuditoria r) {
        return new RegistroDeAuditoriaDto(
                r.getMensajeId(),
                r.getTipoEvento(),
                r.getVentaId(),
                r.getCompradorId(),
                r.getEventoId(),
                r.getMontoTotal(),
                // La columna guarda hora UTC sin zona; hacia afuera sale como instante, con "Z".
                r.getRecibidoEn().toInstant(ZoneOffset.UTC));
    }
}
