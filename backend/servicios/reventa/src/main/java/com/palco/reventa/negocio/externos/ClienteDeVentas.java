package com.palco.reventa.negocio.externos;

import java.util.Map;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.JsonbException;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.ReglasDeNegocio;
import com.palco.reventa.negocio.TiposDeProblema;
import com.palco.reventa.negocio.externos.ClienteRest.Respuesta;

/**
 * Lo que Reventa le pide a ServicioDeVentas, dueña de las entradas: leer una
 * entrada del vendedor, publicarla, retirarla, controlar antes de cobrar que
 * el comprador pueda quedarse con ella y transferírsela.
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

    public record PedidoDeControl(String entradaId, String vendedorId, String compradorId, Titular titular) {
    }

    /**
     * Ventas no transfirió la entrada porque con ella el comprador pasaba una
     * regla entre compras: 409 {@code tope-por-evento} o
     * {@code dni-con-entrada}, con el tipo y el detalle de Ventas. Pasa cuando
     * otra compra del mismo comprador, o con el mismo DNI, se cruzó entre el
     * control y la transferencia (DEF-46). A diferencia de una entrada que ya
     * no está publicada, la entrada sigue en la reventa: la publicación puede
     * quedar libre para otra persona.
     */
    public static class CompraFueraDeLasReglas extends ErroresDeNegocio.Conflicto {
        public CompraFueraDeLasReglas(String tipo, String mensaje) {
            super(tipo, mensaje);
        }
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
     * Antes de cobrar, las reglas entre compras de Ventas: el tope de 6
     * entradas por persona y evento y un DNI por evento. Ventas responde 204
     * si el comprador puede quedarse con la entrada. Si no, responde 409
     * {@code tope-por-evento} o {@code dni-con-entrada}, que salen con el
     * mismo tipo y el mismo detalle: es el error de una compra del catálogo,
     * y el texto lo arma quien tiene la regla. No cambia nada en Ventas.
     */
    public void controlarCompra(String entradaId, String vendedorId, String compradorId, Titular titular) {
        Respuesta respuesta = rest.llamar("POST", RAIZ + "/controles-de-compra", Map.of(), Map.of(),
                ClienteRest.JSONB.toJson(new PedidoDeControl(entradaId, vendedorId, compradorId, titular)));
        if (respuesta.estado() == 204) {
            return;
        }
        CompraFueraDeLasReglas regla = reglaEntreCompras(respuesta, titular);
        if (regla != null) {
            throw regla;
        }
        // Que la entrada ya no sea del vendedor (404) es que la publicación quedó vieja: no se vende.
        if (respuesta.estado() == 404 && "/problemas/no-encontrado".equals(respuesta.tipo())) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.PUBLICACION_NO_ACTIVA,
                    "La publicación ya no está disponible.");
        }
        LOG.warning("Ventas respondió " + respuesta.estado() + " al controlar la compra de la entrada " + entradaId
                + ".");
        throw new ErroresDeNegocio.SinRespuesta(VENTAS_NO_RESPONDE);
    }

    /**
     * La original queda VENDIDA y nace la venta del comprador con su entrada
     * nueva. Ventas lo hace en una transacción y es idempotente por
     * publicación y comprador: repetirlo devuelve la misma venta. Antes vuelve
     * a mirar las reglas entre compras: si alguna no se cumple, lanza
     * {@link CompraFueraDeLasReglas}.
     */
    public Transferencia transferir(String publicacionId, String entradaId, String vendedorId, String compradorId,
            Titular titular, long precio, String pagoId) {
        String cuerpo = ClienteRest.JSONB.toJson(new PedidoDeTransferencia(publicacionId, entradaId, vendedorId,
                compradorId, titular, precio, pagoId));
        Respuesta respuesta = rest.llamar("POST", RAIZ + "/transferencias", Map.of(), Map.of(), cuerpo);
        CompraFueraDeLasReglas regla = reglaEntreCompras(respuesta, titular);
        if (regla != null) {
            throw regla;
        }
        return leer(respuesta, 201, Transferencia.class, "transferir la entrada " + entradaId,
                TiposDeProblema.PUBLICACION_NO_ACTIVA);
    }

    /**
     * El 409 de una regla entre compras, con el tipo y el detalle de Ventas,
     * que arma el texto porque tiene la regla; null si la respuesta es otra
     * cosa.
     */
    private static CompraFueraDeLasReglas reglaEntreCompras(Respuesta respuesta, Titular titular) {
        if (respuesta.estado() != 409) {
            return null;
        }
        String detalle = respuesta.detalle() == null || respuesta.detalle().isBlank() ? null : respuesta.detalle();
        if (("/problemas/" + TiposDeProblema.TOPE_POR_EVENTO).equals(respuesta.tipo())) {
            return new CompraFueraDeLasReglas(TiposDeProblema.TOPE_POR_EVENTO, detalle != null ? detalle
                    : "Cada persona puede tener hasta " + ReglasDeNegocio.MAX_ENTRADAS_POR_EVENTO
                            + " entradas por evento, sumando todas sus compras: no podés comprar más para este evento.");
        }
        if (("/problemas/" + TiposDeProblema.DNI_CON_ENTRADA).equals(respuesta.tipo())) {
            return new CompraFueraDeLasReglas(TiposDeProblema.DNI_CON_ENTRADA, detalle != null ? detalle
                    : "El DNI " + titular.dni() + " ya tiene una entrada para este evento. Cada persona puede tener"
                            + " una sola entrada por evento.");
        }
        return null;
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
