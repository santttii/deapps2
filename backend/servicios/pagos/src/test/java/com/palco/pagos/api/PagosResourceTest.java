package com.palco.pagos.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.pagos.api.Dtos.NuevoPagoDto;
import com.palco.pagos.api.Dtos.PagoDto;
import com.palco.pagos.api.Dtos.ReembolsoDto;
import com.palco.pagos.negocio.IPagos;

/**
 * Lo que el recurso decide por su cuenta, sin servidor: que cada operación
 * declare el rol {@code servicio} y que la consulta por clave delegue en el
 * negocio sin agregar nada. El 401 y el 403 los da el servidor y se
 * comprueban con {@code curl}.
 */
class PagosResourceTest {

    private static final String CLAVE_EN_PAGOS = "V-3f9a1c2b:5d3c1f0e-8a4b-4c7d-9e2f-1a2b3c4d5e6f";

    @Test
    void todasLasOperacionesSonSoloParaOtrosServicios() throws NoSuchMethodException {
        Method[] operaciones = {
                PagosResource.class.getMethod("procesar", String.class, NuevoPagoDto.class),
                PagosResource.class.getMethod("buscarPorClave", String.class),
                PagosResource.class.getMethod("obtener", String.class),
                PagosResource.class.getMethod("reembolsar", String.class, ReembolsoDto.class) };

        for (Method operacion : operaciones) {
            RolesAllowed roles = operacion.getAnnotation(RolesAllowed.class);
            assertNotNull(roles, operacion.getName() + " no declara su rol");
            assertArrayEquals(new String[] { "servicio" }, roles.value(), operacion.getName());
        }
    }

    @Test
    void laConsultaPorClaveEsUnGetSobreLaColeccionConLaClaveComoParametro() throws NoSuchMethodException {
        Method buscar = PagosResource.class.getMethod("buscarPorClave", String.class);

        assertNotNull(buscar.getAnnotation(GET.class));
        // Sin @Path propio: es GET /api/v1/pagos, y no choca con GET /api/v1/pagos/{pagoId}.
        assertNull(buscar.getAnnotation(Path.class));
        QueryParam parametro = (QueryParam) buscar.getParameterAnnotations()[0][0];
        assertEquals("claveIdempotencia", parametro.value());
    }

    @Test
    void laConsultaPorClaveDevuelveLoQueEncuentraElNegocio() {
        PagoDto pago = new PagoDto("P-598c58a0", "V-3f9a1c2b", 60500, "APROBADO", null, "fake-" + CLAVE_EN_PAGOS);
        PagosResource recurso = conNegocioQueTiene(pago);

        assertSame(pago, recurso.buscarPorClave(CLAVE_EN_PAGOS));
    }

    @Test
    void siNoHayPagoConEsaClaveSaleElNoEncontradoDelNegocio() {
        // ManejadoresDeErrores lo responde como 404 con "type": "/problemas/no-encontrado".
        PagosResource recurso = conNegocioQueTiene(null);

        ErroresDeNegocio.NoEncontrado error = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> recurso.buscarPorClave(CLAVE_EN_PAGOS));
        assertEquals("no-encontrado", error.tipo());
    }

    /** El recurso con un negocio de mentira que conoce un solo pago, guardado con {@link #CLAVE_EN_PAGOS}. */
    private static PagosResource conNegocioQueTiene(PagoDto pago) {
        PagosResource recurso = new PagosResource();
        recurso.pagos = new IPagos() {
            @Override
            public PagoDto procesarPago(NuevoPagoDto pedido, String claveIdempotencia) {
                throw new AssertionError("La consulta no tiene que cobrar");
            }

            @Override
            public PagoDto obtener(String pagoId) {
                throw new AssertionError("La consulta por clave no busca por id");
            }

            @Override
            public PagoDto buscarPorClave(String claveIdempotencia) {
                if (pago == null || !CLAVE_EN_PAGOS.equals(claveIdempotencia)) {
                    throw new ErroresDeNegocio.NoEncontrado("No hay ningún pago con esa clave de idempotencia.");
                }
                return pago;
            }

            @Override
            public PagoDto reembolsar(String pagoId, ReembolsoDto pedido) {
                throw new AssertionError("La consulta no tiene que devolver nada");
            }
        };
        return recurso;
    }
}
