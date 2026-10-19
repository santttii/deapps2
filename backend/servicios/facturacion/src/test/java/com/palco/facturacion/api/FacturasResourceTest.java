package com.palco.facturacion.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.util.Arrays;

import org.eclipse.microprofile.jwt.JsonWebToken;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.SecurityContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.facturacion.Ejemplos;
import com.palco.facturacion.api.Dtos.FacturaDto;
import com.palco.facturacion.datos.FacturaRepositoryEnMemoria;
import com.palco.facturacion.negocio.ServicioDeFacturacionDePrueba;
import com.palco.facturacion.negocio.externos.ArcaEnMemoria;

/**
 * Lo que decide el recurso: a nombre de quién se consulta y qué rol pide.
 * Detrás está el negocio de verdad, con una factura guardada en memoria, así
 * se ve qué responde a cada uno. El 404 lo escribe {@code ManejadoresDeErrores}
 * a partir de {@code NoEncontrado}; el 401 y el 403 los da el servidor.
 */
class FacturasResourceTest {

    private final FacturaRepositoryEnMemoria repositorio = new FacturaRepositoryEnMemoria();
    private final FacturasResource recurso = new FacturasResource();

    @BeforeEach
    void unaFacturaGuardada() {
        repositorio.guardar(Ejemplos.factura(Ejemplos.VENTA, Ejemplos.COMPRADOR));
        recurso.facturacion = ServicioDeFacturacionDePrueba.con(repositorio, new ArcaEnMemoria(), Clock.systemUTC());
    }

    @Test
    void elCompradorVeLaFacturaDeSuCompra() {
        FacturaDto factura = recurso.obtener(Ejemplos.VENTA, sesionDeUsuario(Ejemplos.COMPRADOR));

        assertEquals(Ejemplos.VENTA, factura.ventaId());
        assertEquals("00001-00000007", factura.comprobante());
        assertEquals("75123456789012", factura.cae());
        assertEquals("2026-10-26", factura.vencimientoCae());
        assertEquals(60500, factura.importe());
    }

    @Test
    void otroUsuarioNoVeUnaFacturaAjenaYRecibeElMismo404QueSiNoExistiera() {
        ErroresDeNegocio.NoEncontrado ajena = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> recurso.obtener(Ejemplos.VENTA, sesionDeUsuario("user-otro")));
        ErroresDeNegocio.NoEncontrado inexistente = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> recurso.obtener("V-no-existe", sesionDeUsuario("user-otro")));

        assertEquals(inexistente.getMessage(), ajena.getMessage());
    }

    @Test
    void unaVentaSinFacturaTodaviaEs404() {
        assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> recurso.obtener("V-recien-pagada", sesionDeUsuario(Ejemplos.COMPRADOR)));
    }

    @Test
    void otroServicioVeCualquierFacturaSinSerElComprador() {
        FacturaDto factura = recurso.obtener(Ejemplos.VENTA, sesionDeServicio());

        assertEquals(Ejemplos.COMPRADOR, factura.compradorId());
        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> recurso.obtener("V-no-existe", sesionDeServicio()));
    }

    @Test
    void sinUnTokenConSujetoNoSeConsultaNada() {
        SecurityContext sinSesion = mock(SecurityContext.class);

        assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> recurso.obtener(Ejemplos.VENTA, sinSesion));
    }

    @Test
    void laConsultaEsParaCompradoresYServiciosYEsLaUnicaOperacion() throws NoSuchMethodException {
        int operaciones = 0;
        for (Method metodo : FacturasResource.class.getDeclaredMethods()) {
            if (Arrays.stream(metodo.getAnnotations())
                    .anyMatch(a -> a.annotationType().isAnnotationPresent(HttpMethod.class))) {
                operaciones++;
            }
        }
        assertEquals(1, operaciones);
        Method obtener = FacturasResource.class.getMethod("obtener", String.class, SecurityContext.class);
        RolesAllowed roles = obtener.getAnnotation(RolesAllowed.class);
        assertNotNull(roles);
        assertArrayEquals(new String[] { "user", "servicio" }, roles.value());
        assertEquals("{ventaId}", obtener.getAnnotation(Path.class).value());
    }

    /** El pedido de un comprador: el servidor deja su token ya validado en el contexto de seguridad. */
    private static SecurityContext sesionDeUsuario(String usuarioId) {
        JsonWebToken token = mock(JsonWebToken.class);
        when(token.getSubject()).thenReturn(usuarioId);
        SecurityContext seguridad = mock(SecurityContext.class);
        when(seguridad.getUserPrincipal()).thenReturn(token);
        when(seguridad.isUserInRole("user")).thenReturn(true);
        return seguridad;
    }

    /** El token de servicio no es de ningún comprador: su "sub" es "servicio". */
    private static SecurityContext sesionDeServicio() {
        SecurityContext seguridad = sesionDeUsuario("servicio");
        when(seguridad.isUserInRole("user")).thenReturn(false);
        when(seguridad.isUserInRole("servicio")).thenReturn(true);
        return seguridad;
    }
}
