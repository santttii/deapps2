package com.palco.pagos.api;

import java.net.URI;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.palco.pagos.api.Dtos.NuevoPagoDto;
import com.palco.pagos.api.Dtos.PagoDto;
import com.palco.pagos.negocio.IPagos;

/**
 * API REST de ServicioDePagos, en {@code /api/v1/pagos}. Es interna: todas
 * las operaciones piden el rol {@code servicio}, que solo tiene un token de
 * servicio. El comprador paga contra ServicioDeVentas, y Ventas llama acá.
 *
 * Un rechazo de la pasarela responde 201 con {@code "estado": "RECHAZADO"}:
 * para Pagos el pago se procesó y ese es su resultado. El 402 se lo da Ventas
 * al comprador.
 *
 * {@code @RequestScoped}: una instancia por pedido HTTP. No guarda nada; valida
 * el formato de lo que llega y delega en {@link IPagos}.
 */
@RequestScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PagosResource {

    /** El largo de la columna {@code clave_idempotencia}. Un UUID, que es lo que manda Ventas, ocupa 36. */
    private static final int LARGO_MAXIMO_DE_LA_CLAVE = 80;

    @Inject
    IPagos pagos;

    /**
     * {@code POST /api/v1/pagos} con la cabecera {@code Idempotency-Key} y
     * {@code {"ventaId": "V-3f9a1c2b", "monto": 60500, "medioDePago": "tarjeta", "token": "tok_test"}}.
     * Repetirlo con la misma clave devuelve el mismo pago, también con 201.
     */
    @POST
    @RolesAllowed("servicio")
    public Response procesar(
            @HeaderParam("Idempotency-Key")
            @NotBlank(message = "Falta la cabecera Idempotency-Key.")
            @Size(max = LARGO_MAXIMO_DE_LA_CLAVE,
                    message = "La cabecera Idempotency-Key tiene hasta " + LARGO_MAXIMO_DE_LA_CLAVE + " caracteres.")
            String claveIdempotencia,
            @NotNull(message = "Falta el cuerpo de la solicitud.") @Valid NuevoPagoDto pedido) {
        PagoDto pago = pagos.procesarPago(pedido, claveIdempotencia);
        return Response.created(URI.create(pago.pagoId())).entity(pago).build();
    }

    /**
     * {@code GET /api/v1/pagos?claveIdempotencia=V-3f9a1c2b:5d3c1f0e-8a4b-4c7d-9e2f-1a2b3c4d5e6f}:
     * el pago que se guardó con esa clave, o 404 {@code no-encontrado} si no hay
     * ninguno. La usa la conciliación de Ventas para saber cómo terminó un cobro
     * que quedó sin resultado. Devuelve un pago y no una lista porque la clave es
     * única en la tabla.
     *
     * La clave va como parámetro de la consulta y no en la ruta: es un filtro
     * sobre la colección de pagos, y así no se confunde con
     * {@code /api/v1/pagos/{pagoId}}, que busca por el id del pago.
     */
    @GET
    @RolesAllowed("servicio")
    public PagoDto buscarPorClave(
            @QueryParam("claveIdempotencia")
            @NotBlank(message = "Falta el parámetro claveIdempotencia.")
            @Size(max = LARGO_MAXIMO_DE_LA_CLAVE,
                    message = "El parámetro claveIdempotencia tiene hasta " + LARGO_MAXIMO_DE_LA_CLAVE + " caracteres.")
            String claveIdempotencia) {
        return pagos.buscarPorClave(claveIdempotencia);
    }

    /** {@code GET /api/v1/pagos/P-3f9a1c2b} */
    @GET
    @RolesAllowed("servicio")
    @Path("{pagoId}")
    public PagoDto obtener(@PathParam("pagoId") String pagoId) {
        return pagos.obtener(pagoId);
    }
}
