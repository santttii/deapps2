package com.palco.ventas.negocio.externos;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.Textos;
import com.palco.common.seguridad.TokenDeServicio;
import com.palco.ventas.negocio.TiposDeProblema;

/**
 * Lo que Ventas le pide a ServicioDeEventos: reservar lugares de un sector y
 * devolverlos. Es una llamada REST a otro WAR, con el token de rol
 * {@code servicio}: Ventas no toca las tablas de Eventos ni usa sus clases.
 *
 * Entre los dos servicios no hay transacción: cada llamada queda confirmada
 * en Eventos apenas responde, aunque después la compra falle. Por eso quien
 * reserva es responsable de devolver ({@link #liberarCupo}). La regla ante la
 * duda: puede quedar un lugar sin vender, nunca uno vendido dos veces.
 *
 * Es un bean {@code @ApplicationScoped}: una sola instancia para todo el
 * servicio, que comparte un único cliente HTTP y no guarda ningún dato de las
 * compras. No es la conversación de un usuario (eso es el stateful) y no
 * necesita transacciones, así que no hace falta un Session Bean.
 */
@ApplicationScoped
public class ClienteDeEventos {

    private static final Logger LOG = Logger.getLogger(ClienteDeEventos.class.getName());

    /** La operación de Eventos: cantidad positiva reserva, negativa libera. Los ids van como plantilla. */
    static final String RUTA_DEL_CUPO = "/api/eventos/{eventoId}/sectores/{sectorId}/cupo";

    private static final Jsonb JSONB = JsonbBuilder.create();

    /** El sector como lo devuelve Eventos después de cambiar el cupo. De acá salen el nombre y el precio de la venta. */
    public record Sector(String id, String nombre, long precio, int cupo, int vendidas) {
    }

    /** El {@code type} del 409 con el que Eventos dice que el evento ya empezó y no reserva más. */
    static final String TIPO_EVENTO_FINALIZADO = "/problemas/evento-finalizado";

    /** De un error de Eventos (Problem Details) solo se lee el {@code type}. */
    public record Problema(String type) {
    }

    /** Lo que se mira de una respuesta HTTP: el código de estado y el cuerpo como texto. */
    record Respuesta(int estado, String cuerpo) {
    }

    @Inject
    TokenDeServicio tokenDeServicio;

    private Client cliente;

    /**
     * Reserva {@code cantidad} lugares del sector y devuelve el sector, con su
     * nombre y su precio. Lanza 422 {@code sector-inexistente} si Eventos no
     * conoce ese sector, 409 {@code evento-finalizado} si el evento ya empezó,
     * 409 {@code cupo-agotado} si no quedan lugares y 504
     * {@code sin-respuesta} si Eventos no contesta o contesta otra cosa. En
     * ese último caso no se sabe si la reserva se aplicó.
     */
    public Sector reservarCupo(String eventoId, String sectorId, int cantidad) {
        return leerSector(cambiarCupo(eventoId, sectorId, cantidad));
    }

    /**
     * Devuelve {@code cantidad} lugares del sector. No lanza: quien devuelve
     * cupo ya cerró su parte (canceló o venció la venta, o está deshaciendo
     * una compra que falló) y no puede hacer nada más si Eventos no responde.
     * En ese caso deja un WARNING con los datos para devolverlo a mano y
     * devuelve {@code false}. El lugar queda sin vender: es el lado seguro.
     *
     * @param ventaId solo para que el aviso diga de qué venta era el cupo
     */
    public boolean liberarCupo(String ventaId, String eventoId, String sectorId, int cantidad) {
        try {
            cambiarCupo(eventoId, sectorId, -cantidad);
            return true;
        } catch (RuntimeException e) {
            // El evento y el sector vinieron en el pedido del comprador: al log van en un solo renglón.
            LOG.warning("No se pudo devolver el cupo de la venta " + ventaId + ": evento "
                    + Textos.enUnaLinea(eventoId) + ", sector " + Textos.enUnaLinea(sectorId) + ", cantidad "
                    + cantidad + ". Motivo: " + Textos.enUnaLinea(e.getMessage()));
            return false;
        }
    }

    /**
     * Le pide a Eventos que sume (o reste, si es negativa) {@code cantidad} a
     * las vendidas del sector y devuelve el cuerpo de la respuesta. Se decide
     * por el código de estado, nunca por el texto del error.
     */
    private String cambiarCupo(String eventoId, String sectorId, int cantidad) {
        String cuerpo = JSONB.toJson(Map.of("cantidad", cantidad));

        Respuesta respuesta = enviar(eventoId, sectorId, cuerpo, tokenDeServicio.obtener());
        if (respuesta.estado() == 401) {
            // El token guardado dejó de servir: pasa cuando se redespliega Usuarios, porque cambia la
            // clave con la que firma. Se pide uno nuevo y se reintenta una sola vez.
            tokenDeServicio.invalidar();
            respuesta = enviar(eventoId, sectorId, cuerpo, tokenDeServicio.obtener());
        }

        return switch (respuesta.estado()) {
            case 200 -> respuesta.cuerpo();
            case 404 -> throw new ErroresDeNegocio.ReglaIncumplida(TiposDeProblema.SECTOR_INEXISTENTE,
                    "El evento no tiene ese sector.");
            case 409 -> throw TIPO_EVENTO_FINALIZADO.equals(tipoDeProblema(respuesta.cuerpo()))
                    ? new ErroresDeNegocio.Conflicto(TiposDeProblema.EVENTO_FINALIZADO, "Ese evento ya empezó o terminó")
                    : new ErroresDeNegocio.Conflicto(TiposDeProblema.CUPO_AGOTADO, cantidad > 0
                    ? "No quedan suficientes entradas en ese sector."
                    : "Eventos no aceptó la devolución: el sector no tiene tantas entradas vendidas.");
            default -> {
                // Con el evento, el sector y la cantidad, el cupo se puede revisar a mano en Eventos.
                LOG.warning("Eventos respondió " + respuesta.estado() + " al cambiar el cupo: evento "
                        + Textos.enUnaLinea(eventoId) + ", sector " + Textos.enUnaLinea(sectorId) + ", cantidad "
                        + cantidad + ". No se sabe si lo aplicó.");
                throw new ErroresDeNegocio.SinRespuesta("El servicio de eventos no responde. Probá de nuevo.");
            }
        };
    }

    /** Un solo cliente HTTP para toda la vida del bean: 2 segundos para conectar y 5 para leer. */
    @PostConstruct
    void abrirCliente() {
        cliente = ClientBuilder.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    @PreDestroy
    void cerrarCliente() {
        cliente.close();
    }

    /**
     * Hace la llamada HTTP y devuelve el código y el cuerpo. No es privado para
     * que el test lo reemplace y no necesite un servidor.
     *
     * Los ids llegan en el pedido del comprador. Van como valores de una
     * plantilla y no pegados al texto de la ruta: así JAX-RS los codifica y
     * un id con una barra no puede apuntar la llamada a otra operación.
     *
     * Dos compras a la vez comparten esta instancia y su cliente HTTP. No hace
     * falta {@code synchronized}: el cliente que arma el servidor trae un pool
     * de conexiones y atiende varias llamadas a la vez.
     */
    Respuesta enviar(String eventoId, String sectorId, String cuerpo, String token) {
        try (Response respuesta = cliente.target(TokenDeServicio.URL_INTERNA)
                .path(RUTA_DEL_CUPO)
                .resolveTemplate("eventoId", eventoId)
                .resolveTemplate("sectorId", sectorId)
                .request(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .post(Entity.entity(cuerpo, MediaType.APPLICATION_JSON))) {
            // El cuerpo interesa en un 200 (el sector) y en un 409 (el type dice si es cupo o evento
            // finalizado). De los demás errores se mira el código y nada más.
            int estado = respuesta.getStatus();
            return new Respuesta(estado, estado == 200 || estado == 409 ? respuesta.readEntity(String.class) : null);
        } catch (ProcessingException e) {
            // Sin conexión o se cumplió el tiempo de espera: no se sabe si Eventos llegó a aplicar el cambio.
            // El aviso lleva el evento, el sector y el cuerpo enviado (la cantidad: positiva es una reserva,
            // negativa una devolución), para poder revisar ese cupo a mano en Eventos.
            LOG.warning("Eventos no respondió al cambiar el cupo: evento " + Textos.enUnaLinea(eventoId)
                    + ", sector " + Textos.enUnaLinea(sectorId) + ", pedido " + cuerpo
                    + ". No se sabe si lo aplicó. Motivo: " + Textos.enUnaLinea(e.getMessage()));
            throw new ErroresDeNegocio.SinRespuesta("El servicio de eventos no responde. Probá de nuevo.");
        }
    }

    /** El {@code type} de un error de Eventos, o null si el cuerpo no lo trae. */
    private static String tipoDeProblema(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Problema problema = JSONB.fromJson(json, Problema.class);
            return problema == null ? null : problema.type();
        } catch (JsonbException e) {
            return null;
        }
    }

    /** Convierte el cuerpo de un 200. Si no trae un sector con nombre y precio, no sirve para armar la venta. */
    static Sector leerSector(String json) {
        Sector sector;
        try {
            sector = JSONB.fromJson(json, Sector.class);
        } catch (JsonbException e) {
            sector = null;
        }
        if (sector == null || sector.nombre() == null || sector.nombre().isBlank() || sector.precio() <= 0) {
            throw new ErroresDeNegocio.SinRespuesta("Eventos respondió algo que no es un sector.");
        }
        return sector;
    }
}
