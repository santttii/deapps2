package com.palco.usuarios.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import jakarta.ejb.ApplicationException;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.usuarios.api.Dtos.LoginDto;
import com.palco.usuarios.api.Dtos.SesionDto;
import com.palco.usuarios.datos.IntentoLoginRepository;
import com.palco.usuarios.datos.UsuarioRepository;
import com.palco.usuarios.dominio.Rol;
import com.palco.usuarios.dominio.Usuario;
import com.palco.usuarios.seguridad.ClavesJwt;
import com.palco.usuarios.seguridad.Contrasenas;

/**
 * SEC-W04: 5 inicios de sesión fallidos en 15 minutos para un email bloquean ese email.
 * Sin servidor ni base: los intentos se guardan en una lista y la hora sale de un reloj
 * que el test adelanta.
 */
class LimiteDeIntentosDeLoginTest {

    private static final String EMAIL = "titular@palco.test";
    private static final String CLAVE = "palco1234";
    private static final String CLAVE_MALA = "otra-clave";
    private static final String HASH = Contrasenas.hashear(CLAVE);
    private static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");

    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final ClavesJwt claves = mock(ClavesJwt.class);
    private final IntentosEnMemoria intentos = new IntentosEnMemoria();
    private final ServicioDeUsuarios servicio = new ServicioDeUsuarios();
    private final List<Usuario> cuentas = new ArrayList<>();
    private Instant ahora = Instant.parse("2026-10-23T12:00:00Z");

    LimiteDeIntentosDeLoginTest() throws Exception {
        servicio.usuarios = usuarios;
        servicio.claves = claves;
        servicio.intentos = intentos;
        servicio.reloj = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZONA;
            }

            @Override
            public Clock withZone(ZoneId zona) {
                return this;
            }

            @Override
            public Instant instant() {
                return ahora;
            }
        };
        KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
        generador.initialize(2048);
        when(claves.privada()).thenReturn((RSAPrivateKey) generador.generateKeyPair().getPrivate());
        when(claves.kid()).thenReturn("clave-de-prueba");

        Usuario titular = new Usuario("user-3f9a1c2b", "Ana", "Gómez", "30123456", EMAIL, null,
                LocalDate.of(1995, 5, 17), HASH, Rol.USER);
        titular.marcarEmailVerificado();
        cuentas.add(titular);

        // La búsqueda por email devuelve la cuenta solo si el email pedido es el suyo.
        when(usuarios.buscarPorEmail(anyString())).thenAnswer(i -> {
            String pedido = i.getArgument(0);
            return cuentas.stream().filter(u -> u.getEmail().equals(pedido)).findFirst();
        });
    }

    @Test
    void cincoFallidosYElSextoConLaClaveBuenaRecibe429() {
        for (int i = 0; i < 5; i++) {
            assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> login(EMAIL, CLAVE_MALA));
            avanzar(Duration.ofMinutes(1));
        }
        // El primer fallido fue hace 5 minutos: faltan 10 para que salga de la ventana.
        ErroresDeNegocio.DemasiadosIntentos error =
                assertThrows(ErroresDeNegocio.DemasiadosIntentos.class, () -> login(EMAIL, CLAVE));
        assertEquals("demasiados-intentos", error.tipo());
        assertEquals(Duration.ofMinutes(10).toSeconds(), error.segundosDeEspera());
    }

    @Test
    void laVentanaEsDeQuinceMinutos() {
        for (int i = 0; i < 5; i++) {
            assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> login(EMAIL, CLAVE_MALA));
        }
        avanzar(Duration.ofMinutes(15).minusSeconds(1));
        ErroresDeNegocio.DemasiadosIntentos error =
                assertThrows(ErroresDeNegocio.DemasiadosIntentos.class, () -> login(EMAIL, CLAVE));
        assertEquals(1, error.segundosDeEspera());

        avanzar(Duration.ofSeconds(1));
        assertNotNull(login(EMAIL, CLAVE).token(), "a los 15 minutos el bloqueo se libera");
    }

    @Test
    void cuatroFallidosNoBloqueanYUnLoginCorrectoLosBorra() {
        for (int i = 0; i < 4; i++) {
            assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> login(EMAIL, CLAVE_MALA));
        }
        assertNotNull(login(EMAIL, CLAVE).token());
        assertTrue(intentos.guardados.isEmpty());

        // Después del login correcto vuelven a hacer falta 5 fallidos.
        for (int i = 0; i < 4; i++) {
            assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> login(EMAIL, CLAVE_MALA));
        }
        assertNotNull(login(EMAIL, CLAVE).token());
    }

    @Test
    void unEmailSinCuentaCuentaIgualYRecibeLaMismaRespuesta() {
        String inexistente = "nadie@palco.test";
        for (int i = 0; i < 5; i++) {
            ErroresDeNegocio.NoAutenticado error =
                    assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> login(inexistente, CLAVE));
            assertEquals("Revisá el email y la contraseña.", error.getMessage());
        }
        ErroresDeNegocio.DemasiadosIntentos sinCuenta =
                assertThrows(ErroresDeNegocio.DemasiadosIntentos.class, () -> login(inexistente, CLAVE));

        // Una cuenta que existe recibe el mismo texto: el 429 no revela si el email está registrado.
        for (int i = 0; i < 5; i++) {
            assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> login(EMAIL, CLAVE_MALA));
        }
        ErroresDeNegocio.DemasiadosIntentos conCuenta =
                assertThrows(ErroresDeNegocio.DemasiadosIntentos.class, () -> login(EMAIL, CLAVE));
        assertEquals(conCuenta.getMessage(), sinCuenta.getMessage());
        assertEquals(conCuenta.segundosDeEspera(), sinCuenta.segundosDeEspera());
    }

    @Test
    void elEmailSeCuentaSinImportarMayusculasNiEspacios() {
        for (int i = 0; i < 5; i++) {
            assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> login("  Titular@Palco.TEST ", CLAVE_MALA));
        }
        assertThrows(ErroresDeNegocio.DemasiadosIntentos.class, () -> login(EMAIL, CLAVE));
    }

    @Test
    void losIntentosDeUnEmailNoBloqueanAOtro() {
        for (int i = 0; i < 5; i++) {
            assertThrows(ErroresDeNegocio.NoAutenticado.class, () -> login("nadie@palco.test", CLAVE_MALA));
        }
        assertNotNull(login(EMAIL, CLAVE).token());
    }

    @Test
    void elFallidoNoDeshaceLaTransaccionParaQueElIntentoQuedeGuardado() {
        ApplicationException anotacion =
                ServicioDeUsuarios.CredencialesIncorrectas.class.getDeclaredAnnotation(ApplicationException.class);
        assertNotNull(anotacion);
        assertFalse(anotacion.rollback());
    }

    @Test
    void elHashFalsoCuestaLoMismoQueElDeUnaCuentaNueva() {
        String prefijo = "pbkdf2-sha256$" + Contrasenas.ITERACIONES + "$";
        assertTrue(ServicioDeUsuarios.HASH_FALSO.startsWith(prefijo), ServicioDeUsuarios.HASH_FALSO);
        assertTrue(Contrasenas.hashear("x").startsWith(prefijo));
    }

    private SesionDto login(String email, String clave) {
        return servicio.iniciarSesion(new LoginDto(email, clave));
    }

    private void avanzar(Duration tiempo) {
        ahora = ahora.plus(tiempo);
    }

    /** Hace lo mismo que la tabla intento_login, en una lista. */
    static class IntentosEnMemoria implements IntentoLoginRepository {
        record Guardado(String emailHash, LocalDateTime momento) {
        }

        final List<Guardado> guardados = new ArrayList<>();

        @Override
        public List<LocalDateTime> recientes(String emailHash, LocalDateTime desde, int maximo) {
            return guardados.stream()
                    .filter(g -> g.emailHash().equals(emailHash) && g.momento().isAfter(desde))
                    .map(Guardado::momento)
                    .sorted(Comparator.reverseOrder())
                    .limit(maximo)
                    .toList();
        }

        @Override
        public void registrar(String emailHash, LocalDateTime momento, LocalDateTime desde) {
            guardados.removeIf(g -> g.emailHash().equals(emailHash) && !g.momento().isAfter(desde));
            guardados.add(new Guardado(emailHash, momento));
        }

        @Override
        public void olvidar(String emailHash) {
            guardados.removeIf(g -> g.emailHash().equals(emailHash));
        }
    }
}
