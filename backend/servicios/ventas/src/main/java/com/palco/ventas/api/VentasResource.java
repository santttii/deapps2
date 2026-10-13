package com.palco.ventas.api;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.eclipse.microprofile.jwt.JsonWebToken;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.seguridad.Sesion;
import com.palco.ventas.api.Dtos.NuevaVentaDto;
import com.palco.ventas.api.Dtos.NuevoPagoDto;
import com.palco.ventas.api.Dtos.VentaDto;
import com.palco.ventas.negocio.ComprasEnCurso;

/**
 * API REST de las compras, en {@code /api/v1/ventas}: abrir una compra,
 * consultarla, pagarla, cancelarla y listar las propias.
 *
 * Es una capa delgada. Revisa la forma del pedido (el cuerpo y la cabecera
 * {@code Idempotency-Key}), saca del token quién es el comprador, delega en
 * {@link ComprasEnCurso} y le agrega a la respuesta lo que es propio de HTTP:
 * el código, la cabecera {@code Location} y los {@code _links}. Las reglas de
 * la compra no están acá.
 *
 * Todas las operaciones son solo para compradores ({@code @RolesAllowed("user")}).
 * Una venta que no existe y una venta de otro usuario responden lo mismo, 404,
 * para no revelar que la venta existe: eso lo resuelve {@link ComprasEnCurso}.
 *
 * {@code @RequestScoped}: el servidor crea un objeto de esta clase por cada
 * pedido y lo descarta al responder, así que no guarda nada entre un pedido y
 * el siguiente. Por eso no puede tener la compra en curso: la conversación
 * vive en el componente stateful y este recurso llega a ella por el id de la
 * venta, a través de {@link ComprasEnCurso}.
 */
@RequestScoped
@Path(Enlaces.VENTAS)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VentasResource {

    /** La cabecera con la clave que manda el cliente para que repetir un pedido no repita su efecto. */
    static final String CLAVE_DE_IDEMPOTENCIA = "Idempotency-Key";

    /** La forma de un UUID: cinco grupos de dígitos hexadecimales, de 8, 4, 4, 4 y 12. */
    private static final Pattern FORMA_DE_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final String FALTA_CUERPO = "Falta el cuerpo de la solicitud.";

    @Inject
    ComprasEnCurso compras;

    /**
     * {@code POST /api/v1/ventas}: abre una compra y reserva el cupo. Responde
     * 201 con la venta en {@code EN_CHECKOUT} y su dirección en {@code Location}.
     * Repetir el pedido con la misma {@code Idempotency-Key} devuelve la misma
     * venta, sin reservar otra vez.
     */
    @POST
    @RolesAllowed("user")
    public Response iniciar(@HeaderParam(CLAVE_DE_IDEMPOTENCIA) String claveIdempotencia,
            @NotNull(message = FALTA_CUERPO) @Valid NuevaVentaDto nueva,
            @Context SecurityContext seguridad, @Context UriInfo pedido) {
        String clave = claveValida(claveIdempotencia);
        VentaDto venta = compras.iniciar(Sesion.usuarioId(seguridad), tokenDelComprador(seguridad), nueva, clave);

        Enlaces enlaces = new Enlaces(pedido.getBaseUri());
        return Response.created(enlaces.aLaVenta(venta.id())).entity(enlaces.completar(venta)).build();
    }

    /**
     * {@code GET /api/v1/ventas}: las compras de quien hace el pedido, de la
     * más nueva a la más vieja, cada una con sus entradas. De quién son lo
     * dice el token, no un parámetro: nadie puede pedir las de otro usuario.
     * Es lo que usa "Mis entradas", que así no depende de lo que recuerde el
     * navegador. Sin compras responde 200 con una lista vacía.
     */
    @GET
    @RolesAllowed("user")
    public List<VentaDto> listar(@Context SecurityContext seguridad, @Context UriInfo pedido) {
        Enlaces enlaces = new Enlaces(pedido.getBaseUri());
        return compras.listar(Sesion.usuarioId(seguridad)).stream()
                .map(enlaces::completar)
                .toList();
    }

    /** {@code GET /api/v1/ventas/V-3f9a1c2b}: la venta como está ahora, solo para quien la compró. */
    @GET
    @Path("{ventaId}")
    @RolesAllowed("user")
    public VentaDto obtener(@PathParam("ventaId") String ventaId,
            @Context SecurityContext seguridad, @Context UriInfo pedido) {
        VentaDto venta = compras.obtener(Sesion.usuarioId(seguridad), ventaId);
        return new Enlaces(pedido.getBaseUri()).completar(venta);
    }

    /**
     * {@code POST /api/v1/ventas/V-3f9a1c2b/pagos}: paga una compra abierta.
     * Responde 201 con la venta en {@code CONFIRMADA} y sus entradas, y la
     * dirección del pago en {@code Location}. Las entradas salen todavía sin
     * código: la emisión es asincrónica. Repetir el pedido con la misma
     * {@code Idempotency-Key} devuelve la misma venta, sin cobrar otra vez.
     * Un cobro rechazado responde 402 y la compra sigue abierta.
     */
    @POST
    @Path("{ventaId}/" + Enlaces.PAGOS)
    @RolesAllowed("user")
    public Response pagar(@PathParam("ventaId") String ventaId,
            @HeaderParam(CLAVE_DE_IDEMPOTENCIA) String claveIdempotencia,
            @NotNull(message = FALTA_CUERPO) @Valid NuevoPagoDto pago,
            @Context SecurityContext seguridad, @Context UriInfo pedido) {
        String clave = claveValida(claveIdempotencia);
        VentaDto venta = compras.pagar(Sesion.usuarioId(seguridad), ventaId, pago, clave);

        Enlaces enlaces = new Enlaces(pedido.getBaseUri());
        return Response.created(enlaces.alPago(venta.id(), venta.pagoId())).entity(enlaces.completar(venta)).build();
    }

    /**
     * {@code DELETE /api/v1/ventas/V-3f9a1c2b}: cancela una compra sin pagar y
     * devuelve su cupo. Responde 204, y repetirlo también.
     */
    @DELETE
    @Path("{ventaId}")
    @RolesAllowed("user")
    public Response cancelar(@PathParam("ventaId") String ventaId, @Context SecurityContext seguridad) {
        compras.cancelar(Sesion.usuarioId(seguridad), ventaId);
        return Response.noContent().build();
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * La clave de idempotencia del pedido, ya revisada. Es obligatoria y tiene
     * que ser un UUID: así es difícil que dos compras distintas usen la misma
     * por accidente, y entra en su columna. Se pasa a minúsculas porque un
     * UUID vale igual escrito en mayúsculas, y tiene que contar como la misma
     * clave. La usan los dos POST: abrir la compra y pagarla.
     *
     * Se valida acá, a mano, y no con una anotación sobre el parámetro, para
     * que el error nombre la cabecera tal como la escribe el cliente.
     */
    static String claveValida(String cabecera) {
        if (cabecera == null || !FORMA_DE_UUID.matcher(cabecera.strip()).matches()) {
            throw new ErroresDeNegocio.DatosInvalidos(CLAVE_DE_IDEMPOTENCIA,
                    "Mandá la cabecera " + CLAVE_DE_IDEMPOTENCIA + " con un UUID nuevo por cada compra y por cada pago.");
        }
        return cabecera.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * El token con el que llegó el pedido, sin la palabra "Bearer". Ventas lo
     * necesita para preguntarle a Usuarios por el comprador en su nombre. Sale
     * del mismo lugar que el id del usuario: el token que el servidor ya validó.
     */
    private static String tokenDelComprador(SecurityContext seguridad) {
        if (seguridad.getUserPrincipal() instanceof JsonWebToken token) {
            return token.getRawToken();
        }
        throw new ErroresDeNegocio.NoAutenticado("Tenés que iniciar sesión.");
    }
}
