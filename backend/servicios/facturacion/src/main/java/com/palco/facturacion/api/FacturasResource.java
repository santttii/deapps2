package com.palco.facturacion.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

import com.palco.common.seguridad.Sesion;
import com.palco.facturacion.api.Dtos.FacturaDto;
import com.palco.facturacion.negocio.IFacturacion;

/**
 * API REST de ServicioDeFacturación, en {@code /api/v1/facturas}. Solo se
 * consulta: las facturas nacen de los mensajes del tópico, no de un pedido
 * HTTP.
 *
 * {@code @RequestScoped}: una instancia por pedido HTTP. No guarda nada; elige
 * a nombre de quién se consulta y delega en {@link IFacturacion}.
 */
@RequestScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class FacturasResource {

    static final String ROL_SERVICIO = "servicio";

    @Inject
    IFacturacion facturacion;

    /**
     * {@code GET /api/v1/facturas/V-3f9a1c2b}. El comprador ve solo la factura
     * de su compra (el "sub" del token tiene que ser el comprador de la venta);
     * otro servicio, cualquiera. 404 si la venta no tiene factura todavía, si
     * no existe o si es de otro comprador: las tres responden igual.
     */
    @GET
    @RolesAllowed({ "user", ROL_SERVICIO })
    @Path("{ventaId}")
    public FacturaDto obtener(@PathParam("ventaId") String ventaId, @Context SecurityContext seguridad) {
        if (seguridad.isUserInRole(ROL_SERVICIO)) {
            return facturacion.consultarFactura(ventaId);
        }
        return facturacion.consultarFacturaDelComprador(Sesion.usuarioId(seguridad), ventaId);
    }
}
