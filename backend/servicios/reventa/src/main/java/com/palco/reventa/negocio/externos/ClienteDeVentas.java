package com.palco.reventa.negocio.externos;

import java.util.Map;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.JsonbException;

import com.palco.common.ErroresDeNegocio;
import com.palco.reventa.negocio.TiposDeProblema;
import com.palco.reventa.negocio.externos.ClienteRest.Respuesta;

/**
 * Lo que Reventa le pide a ServicioDeVentas, dueña de las entradas: leer una
 * entrada del vendedor, publicarla, retirarla y transferirla al comprador.
 * Son las operaciones internas de {@code /api/v1/operaciones-de-reventa}, con
 * el token de servicio: Reventa no toca la tabla de entradas ni usa clases de
 * Ventas.
 *
 * Es un Adapter: traduce los códigos HTTP de Ventas a los errores de negocio
 * de Reventa. Un 404 (entrada ajena o inexistente) sale como 404; un 409 sale
 * con el tipo que corresponde a la operación; cualquier otra cosa es
 * {@code SinRespuesta}.
 *
 * {@code @ApplicationScoped}: una instancia sin estado para todo el servicio.
 */
@ApplicationScoped
public class ClienteDeVentas {

    private static final Logger LOG = Logger.getLogger(ClienteDeVentas.class.getName());

    static final String RAIZ = "/api/v1/operaciones-de-reventa";

    private static final String VENTAS_NO_RESPONDE = "El servicio de ventas no responde. Probá de nuevo.";

    /** La entrada como la ve la reventa. */
    public record Entrada(String entradaId, String ventaId, String eventoId, String sectorId, String sector,
            long precioPagado, String estado) {
    }

    public record Titular(String nombre, String dni) {
    }

    /** La entrada nueva del comprador, como la devuelve Ventas. */
    public record EntradaNueva(String id, String eventoId, String sectorId, String sector, Titular titular,
            long precioPagado, String estado, String codigoQr) {
    }

    public record Transferencia(String ventaId, EntradaNueva entrada) {
    }

    public record PedidoDeVendedor(String entradaId, String vendedorId) {
    }

    public record PedidoDeTransferencia(String publicacionId, String entradaId, String vendedorId, String compradorId,
            Titular titular, long precio, String pagoId) {
    }

    @Inject
    ClienteRest rest;

    /** La entrada, si es de ese vendedor. 404 si no. */
    public Entrada consultar(String entradaId, String vendedorId) {
        Respuesta respuesta = rest.llamar("GET", RAIZ + "/entradas/" + entradaId,
                Map.of("vendedorId", vendedorId), Map.of(), null);
        return leer(respuesta, 200, Entrada.class, "consultar la entrada " + entradaId,
                TiposDeProblema.ENTRADA_NO_DISPONIBLE);
    }

    /** VALIDA → PUBLICADA. 404 si no es del vendedor; 409 {@code entrada-no-disponible} si no está VALIDA. */
    public Entrada publicar(String entradaId, String vendedorId) {
        Respuesta respuesta = rest.llamar("POST", RAIZ + "/publicaciones", Map.of(), Map.of(),
                ClienteRest.JSONB.toJson(new PedidoDeVendedor(entradaId, vendedorId)));
        return leer(respuesta, 200, Entrada.class, "publicar la entrada " + entradaId,
                TiposDeProblema.ENTRADA_NO_DISPONIBLE);
    }

    /** PUBLICADA → VALIDA. Repetirlo no cambia nada. */
    public Entrada retirar(String entradaId, String vendedorId) {
        Respuesta respuesta = rest.llamar("POST", RAIZ + "/retiros", Map.of(), Map.of(),
                ClienteRest.JSONB.toJson(new PedidoDeVendedor(entradaId, vendedorId)));
        return leer(respuesta, 200, Entrada.class, "retirar la entrada " + entradaId,
                TiposDeProblema.PUBLICACION_NO_ACTIVA);
    }

    /**
     * La original queda VENDIDA y nace la venta del comprador con su entrada
     * nueva. Ventas lo hace en una transacción y es idempotente por
     * publicación y comprador: repetirlo devuelve la misma venta.
     */
    public Transferencia transferir(String publicacionId, String entradaId, String vendedorId, String compradorId,
            Titular titular, long precio, String pagoId) {
        String cuerpo = ClienteRest.JSONB.toJson(new PedidoDeTransferencia(publicacionId, entradaId, vendedorId,
                compradorId, titular, precio, pagoId));
        Respuesta respuesta = rest.llamar("POST", RAIZ + "/transferencias", Map.of(), Map.of(), cuerpo);
        return leer(respuesta, 201, Transferencia.class, "transferir la entrada " + entradaId,
                TiposDeProblema.PUBLICACION_NO_ACTIVA);
    }

    /**
     * Traduce la respuesta: el código esperado con un cuerpo legible es el
     * resultado; 404 es {@code no-encontrado}; 409, un conflicto del tipo que
     * se indica; el resto, {@code SinRespuesta}.
     */
    private static <T> T leer(Respuesta respuesta, int esperado, Class<T> tipo, String operacion,
            String tipoDelConflicto) {
        if (respuesta.estado() == esperado) {
            try {
                T leido = ClienteRest.JSONB.fromJson(respuesta.cuerpo(), tipo);
                if (leido != null) {
                    return leido;
                }
            } catch (JsonbException e) {
                // Se trata abajo, como cualquier respuesta que no se entiende.
            }
            LOG.warning("Ventas respondió algo ilegible al " + operacion + ".");
            throw new ErroresDeNegocio.SinRespuesta(VENTAS_NO_RESPONDE);
        }
        if (respuesta.estado() == 404 && "/problemas/no-encontrado".equals(respuesta.tipo())) {
            throw new ErroresDeNegocio.NoEncontrado("No existe esa entrada entre las tuyas.");
        }
        if (respuesta.estado() == 409) {
            throw new ErroresDeNegocio.Conflicto(tipoDelConflicto, TiposDeProblema.ENTRADA_NO_DISPONIBLE.equals(
                    tipoDelConflicto)
                    ? "La entrada no se puede revender: tiene que estar emitida y sin usar."
                    : "La entrada ya no está publicada en la reventa.");
        }
        LOG.warning("Ventas respondió " + respuesta.estado() + " al " + operacion + ".");
        throw new ErroresDeNegocio.SinRespuesta(VENTAS_NO_RESPONDE);
    }
}
