package com.palco.usuarios.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.usuarios.api.Dtos.VerificacionDeIdentidadDto;
import com.palco.usuarios.dominio.Rol;
import com.palco.usuarios.dominio.Usuario;

/**
 * La verificación de identidad, sin servidor ni base: el {@code EntityManager}
 * es un doble. Se comprueba qué cambia en la cuenta y que la respuesta declare
 * que es una simulación. Quién puede llamarla está en {@code UsuariosResourceTest}.
 */
class VerificacionDeIdentidadTest {

    private static final String USUARIO = "user-3f9a1c2b";

    private final EntityManager em = mock(EntityManager.class);
    private final ServicioDeUsuarios servicio = new ServicioDeUsuarios();

    VerificacionDeIdentidadTest() {
        servicio.em = em;
    }

    /** Una cuenta como queda después de registrarse: todavía sin la identidad verificada. */
    private static Usuario cuentaNueva() {
        return new Usuario(USUARIO, "Ana", "Gómez", "30123456", "ana@palco.test", null,
                LocalDate.of(1995, 5, 17), "hash-de-prueba", Rol.USER);
    }

    @Test
    void unaCuentaRecienRegistradaQuedaConLaIdentidadVerificada() {
        Usuario usuario = cuentaNueva();
        assertFalse(usuario.isIdentidadVerificada());
        when(em.find(Usuario.class, USUARIO)).thenReturn(usuario);

        VerificacionDeIdentidadDto respuesta = servicio.verificarIdentidad(USUARIO);

        // Cambia la entidad que administra el servidor: el cambio se guarda al cerrar la transacción.
        assertTrue(usuario.isIdentidadVerificada());
        // La respuesta trae la cuenta como quedó: es lo que después lee Ventas en GET /api/usuarios/yo.
        assertEquals(USUARIO, respuesta.usuario().id());
        assertTrue(respuesta.usuario().identidadVerificada());
    }

    @Test
    void laRespuestaDeclaraQueLaVerificacionEsSimulada() {
        when(em.find(Usuario.class, USUARIO)).thenReturn(cuentaNueva());

        VerificacionDeIdentidadDto respuesta = servicio.verificarIdentidad(USUARIO);

        assertTrue(respuesta.simulada());
        assertTrue(respuesta.detalle().contains("simulada"), respuesta.detalle());
        assertTrue(respuesta.detalle().contains("sin revisar nada"), respuesta.detalle());
    }

    @Test
    void repetirlaNoFallaNiCambiaNada() {
        Usuario usuario = cuentaNueva();
        when(em.find(Usuario.class, USUARIO)).thenReturn(usuario);

        servicio.verificarIdentidad(USUARIO);
        VerificacionDeIdentidadDto segunda = servicio.verificarIdentidad(USUARIO);

        assertTrue(usuario.isIdentidadVerificada());
        assertTrue(segunda.usuario().identidadVerificada());
        assertTrue(segunda.simulada());
    }

    @Test
    void siElUsuarioDelTokenYaNoExisteResponde404() {
        when(em.find(Usuario.class, USUARIO)).thenReturn(null);

        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> servicio.verificarIdentidad(USUARIO));
    }
}
