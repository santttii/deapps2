package com.palco.usuarios.negocio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.junit.jupiter.api.Test;

import com.palco.usuarios.api.Dtos.SesionDto;
import com.palco.usuarios.api.Dtos.VerificacionDto;
import com.palco.usuarios.dominio.CodigoVerificacion;
import com.palco.usuarios.dominio.Rol;
import com.palco.usuarios.dominio.Usuario;
import com.palco.usuarios.seguridad.ClavesJwt;

/**
 * Completar el registro (verificar el email con el código) deja la cuenta con
 * la identidad verificada, que es lo que Ventas exige para comprar. Sin
 * servidor ni base: el {@code EntityManager} y las claves son dobles.
 */
class RegistroCompletoTest {

    private static final String USUARIO = "user-3f9a1c2b";
    private static final String EMAIL = "ana@palco.test";
    private static final String CODIGO = "123456";

    private final EntityManager em = mock(EntityManager.class);
    private final ClavesJwt claves = mock(ClavesJwt.class);
    private final ServicioDeUsuarios servicio = new ServicioDeUsuarios();

    RegistroCompletoTest() throws Exception {
        servicio.em = em;
        servicio.claves = claves;
        KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
        generador.initialize(2048);
        when(claves.privada()).thenReturn((RSAPrivateKey) generador.generateKeyPair().getPrivate());
        when(claves.kid()).thenReturn("clave-de-prueba");
    }

    @Test
    @SuppressWarnings("unchecked")
    void unaCuentaQueCompletaElRegistroSaleConLaIdentidadVerificada() throws Exception {
        Usuario usuario = new Usuario(USUARIO, "Ana", "Gómez", "30123456", EMAIL, null,
                LocalDate.of(1995, 5, 17), "hash-de-prueba", Rol.USER);
        assertFalse(usuario.isIdentidadVerificada());

        TypedQuery<Usuario> consulta = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(Usuario.class))).thenReturn(consulta);
        when(consulta.setParameter(anyString(), anyString())).thenReturn(consulta);
        when(consulta.getResultStream()).thenAnswer(i -> Stream.of(usuario));

        CodigoVerificacion codigo = new CodigoVerificacion(USUARIO);
        LocalDateTime ahora = LocalDateTime.now();
        codigo.renovar(sha256(CODIGO), ahora, ahora.plusMinutes(15));
        when(em.find(CodigoVerificacion.class, USUARIO)).thenReturn(codigo);

        SesionDto sesion = servicio.verificarEmail(new VerificacionDto(EMAIL, CODIGO));

        assertTrue(usuario.isEmailVerificado());
        assertTrue(usuario.isIdentidadVerificada());
        // La sesión trae la cuenta como quedó: es lo que después lee Ventas en GET /api/usuarios/yo.
        assertTrue(sesion.usuario().identidadVerificada());
    }

    private static String sha256(String texto) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
    }
}
