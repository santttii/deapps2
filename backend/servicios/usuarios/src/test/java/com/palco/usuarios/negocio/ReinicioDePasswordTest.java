package com.palco.usuarios.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.email.Correos;
import com.palco.common.email.Email;
import com.palco.usuarios.api.Dtos.ReinicioDto;
import com.palco.usuarios.datos.CodigoDeReinicioRepository;
import com.palco.usuarios.datos.IntentoLoginRepository;
import com.palco.usuarios.datos.UsuarioRepository;
import com.palco.usuarios.dominio.CodigoDeReinicio;
import com.palco.usuarios.dominio.Rol;
import com.palco.usuarios.dominio.Usuario;
import com.palco.usuarios.seguridad.Contrasenas;

/**
 * Recuperar la contraseña: pedir el código por email y usarlo para poner una
 * nueva. Sin servidor ni base; el código se lee del email que se "mandó" y la
 * hora sale de un reloj que el test adelanta.
 *
 * Lo que se cuida acá, además del camino feliz: que la respuesta no revele qué
 * emails están registrados, que un código incorrecto cuente como intento y que
 * quien quedó bloqueado por los intentos de login pueda entrar con la nueva.
 */
class ReinicioDePasswordTest {

    private static final String EMAIL = "ana@palco.test";
    private static final String CLAVE_VIEJA = "palco1234";
    private static final String CLAVE_NUEVA = "palco5678";
    private static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Pattern SEIS_DIGITOS = Pattern.compile("\\b(\\d{6})\\b");

    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final IntentoLoginRepository intentos = mock(IntentoLoginRepository.class);
    private final ReiniciosEnMemoria reinicios = new ReiniciosEnMemoria();
    private final CorreosEnMemoria correos = new CorreosEnMemoria();
    private final ServicioDeUsuarios servicio = new ServicioDeUsuarios();
    private final Usuario ana = new Usuario("user-3f9a1c2b", "Ana", "Gómez", "30123456", EMAIL, null,
            LocalDate.of(1995, 5, 17), Contrasenas.hashear(CLAVE_VIEJA), Rol.USER);
    private Instant ahora = Instant.parse("2026-10-30T12:00:00Z");

    ReinicioDePasswordTest() {
        ana.marcarEmailVerificado();
        servicio.usuarios = usuarios;
        servicio.reinicios = reinicios;
        servicio.intentos = intentos;
        servicio.correos = correos;
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
        when(usuarios.buscarPorEmail(anyString()))
                .thenAnswer(i -> EMAIL.equals(i.getArgument(0)) ? Optional.of(ana) : Optional.empty());
    }

    // --- Pedir el código ---

    @Test
    void pedirloConUnaCuentaVerificadaGuardaElCodigoYMandaElEmail() {
        servicio.pedirReinicioDePassword("  Ana@Palco.TEST ");

        assertEquals(1, correos.enviados.size());
        Email email = correos.enviados.get(0);
        assertEquals(EMAIL, email.para());
        assertEquals("Volvé a poner tu contraseña de Palco", email.asunto());
        assertTrue(email.cuerpo().contains("Ana"), email.cuerpo());
        assertTrue(SEIS_DIGITOS.matcher(email.cuerpo()).find(), email.cuerpo());
        assertEquals(1, reinicios.guardados.size());
        assertEquals(ana.getId(), reinicios.guardados.get(0).getUsuarioId());
    }

    @Test
    void elCodigoNoViajaEnClaroALaBaseSinoSuHash() {
        servicio.pedirReinicioDePassword(EMAIL);

        String codigo = codigoDelEmail();
        String guardado = reinicios.guardados.get(0).getCodigoHash();
        assertNotEquals(codigo, guardado);
        assertEquals(64, guardado.length(), guardado);
    }

    @Test
    void unEmailQueNoExisteNoMandaNadaYTampocoEsUnError() {
        // Si contestara distinto se podrían averiguar las cuentas registradas.
        servicio.pedirReinicioDePassword("nadie@palco.test");

        assertTrue(correos.enviados.isEmpty());
        assertTrue(reinicios.guardados.isEmpty());
    }

    @Test
    void unaCuentaSinVerificarElEmailNoRecibeCodigoDeReinicio() {
        // Tiene que terminar de verificarse, no cambiar la contraseña.
        Usuario sinVerificar = new Usuario("user-sin", "Juan", "Pérez", "30999888", "juan@palco.test", null,
                LocalDate.of(1990, 1, 1), Contrasenas.hashear(CLAVE_VIEJA), Rol.USER);
        when(usuarios.buscarPorEmail("juan@palco.test")).thenReturn(Optional.of(sinVerificar));

        servicio.pedirReinicioDePassword("juan@palco.test");

        assertTrue(correos.enviados.isEmpty());
    }

    @Test
    void pedirloDeNuevoEnSeguidaNoMandaOtroPeroTampocoEsUnError() {
        // El reenvío del código de verificación sí responde 409, pero acá eso sería la única
        // respuesta distinta según si el email existe: se deja el código que ya salió.
        servicio.pedirReinicioDePassword(EMAIL);
        avanzar(Duration.ofSeconds(10));

        servicio.pedirReinicioDePassword(EMAIL);

        assertEquals(1, correos.enviados.size());
    }

    @Test
    void pasadaLaEsperaPedirloDeNuevoMandaOtroCodigo() {
        servicio.pedirReinicioDePassword(EMAIL);
        String primero = codigoDelEmail();
        avanzar(Duration.ofSeconds(31));

        servicio.pedirReinicioDePassword(EMAIL);

        assertEquals(2, correos.enviados.size());
        // El segundo reemplaza al primero: el viejo deja de servir.
        assertNotEquals(primero, codigoDelEmail());
    }

    // --- Usar el código ---

    @Test
    void conElCodigoCorrectoCambiaLaContrasenaYBorraElCodigo() {
        servicio.pedirReinicioDePassword(EMAIL);
        String codigo = codigoDelEmail();

        servicio.reiniciarPassword(new ReinicioDto(EMAIL, codigo, CLAVE_NUEVA));

        assertTrue(Contrasenas.verificar(CLAVE_NUEVA, ana.getPasswordHash()));
        assertFalse(Contrasenas.verificar(CLAVE_VIEJA, ana.getPasswordHash()));
        assertTrue(reinicios.guardados.isEmpty(), "el código usado tiene que borrarse");
    }

    @Test
    void cambiarLaContrasenaOlvidaLosIntentosDeLoginFallidos() {
        // Quien la olvidó suele haber quedado bloqueado: con la nueva entra sin esperar 15 minutos.
        servicio.pedirReinicioDePassword(EMAIL);

        servicio.reiniciarPassword(new ReinicioDto(EMAIL, codigoDelEmail(), CLAVE_NUEVA));

        org.mockito.Mockito.verify(intentos).olvidar(anyString());
    }

    @Test
    void unCodigoIncorrectoSumaUnIntentoYNoCambiaNada() {
        servicio.pedirReinicioDePassword(EMAIL);

        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.reiniciarPassword(new ReinicioDto(EMAIL, "000000", CLAVE_NUEVA)));

        assertEquals(1, reinicios.guardados.get(0).getIntentos());
        assertTrue(Contrasenas.verificar(CLAVE_VIEJA, ana.getPasswordHash()));
    }

    @Test
    void elIntentoFallidoQuedaGuardadoAunqueLaRespuestaSeaUnError() {
        // Si no se escribiera, el límite de 5 intentos no frenaría a quien sigue probando.
        servicio.pedirReinicioDePassword(EMAIL);

        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.reiniciarPassword(new ReinicioDto(EMAIL, "000000", CLAVE_NUEVA)));

        assertEquals(1, reinicios.cambiosGuardados);
        // Y no deshace la transacción: por eso la excepción está anotada con rollback = false.
        assertTrue(ServicioDeUsuarios.CodigoIncorrecto.class
                .isAnnotationPresent(jakarta.ejb.ApplicationException.class));
        assertFalse(ServicioDeUsuarios.CodigoIncorrecto.class
                .getAnnotation(jakarta.ejb.ApplicationException.class).rollback());
    }

    @Test
    void despuesDeCincoIntentosFallidosElCodigoNoSirveMasAunqueSeAcierte() {
        servicio.pedirReinicioDePassword(EMAIL);
        String codigo = codigoDelEmail();
        for (int n = 0; n < 5; n++) {
            assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                    () -> servicio.reiniciarPassword(new ReinicioDto(EMAIL, "000000", CLAVE_NUEVA)));
        }

        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.reiniciarPassword(new ReinicioDto(EMAIL, codigo, CLAVE_NUEVA)));

        assertTrue(error.getMessage().contains("Demasiados intentos"), error.getMessage());
        assertTrue(Contrasenas.verificar(CLAVE_VIEJA, ana.getPasswordHash()));
    }

    @Test
    void unCodigoVencidoNoSirve() {
        servicio.pedirReinicioDePassword(EMAIL);
        String codigo = codigoDelEmail();
        avanzar(Duration.ofMinutes(16));

        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.reiniciarPassword(new ReinicioDto(EMAIL, codigo, CLAVE_NUEVA)));

        assertTrue(error.getMessage().contains("venció"), error.getMessage());
    }

    @Test
    void sinHaberPedidoNingunCodigoNoSePuedeCambiarLaContrasena() {
        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.reiniciarPassword(new ReinicioDto(EMAIL, "123456", CLAVE_NUEVA)));

        assertTrue(error.getMessage().contains("venció"), error.getMessage());
    }

    @Test
    void conUnEmailQueNoExisteElErrorEsElMismoQueConUnCodigoIncorrecto() {
        // Tampoco acá se revela si la cuenta existe.
        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.reiniciarPassword(new ReinicioDto("nadie@palco.test", "123456", CLAVE_NUEVA)));

        assertTrue(error.campos().containsKey("codigo"), error.campos().toString());
    }

    // ---------------------------------------------------------------------------------------------

    private void avanzar(Duration cuanto) {
        ahora = ahora.plus(cuanto);
    }

    /** El código de 6 dígitos del último email que se mandó. */
    private String codigoDelEmail() {
        Matcher encontrado = SEIS_DIGITOS.matcher(correos.enviados.get(correos.enviados.size() - 1).cuerpo());
        assertTrue(encontrado.find(), "el email no trae un código de 6 dígitos");
        return encontrado.group(1);
    }

    /** Los emails que habría mandado el servicio. */
    private static final class CorreosEnMemoria implements Correos {
        private final List<Email> enviados = new ArrayList<>();

        @Override
        public void enviar(Email email) {
            enviados.add(email);
        }
    }

    /** El repositorio de códigos, en una lista: alcanza uno por cuenta. */
    private static final class ReiniciosEnMemoria implements CodigoDeReinicioRepository {
        private final List<CodigoDeReinicio> guardados = new ArrayList<>();
        private int cambiosGuardados;

        @Override
        public Optional<CodigoDeReinicio> buscarPorUsuario(String usuarioId) {
            return guardados.stream().filter(c -> c.getUsuarioId().equals(usuarioId)).findFirst();
        }

        @Override
        public void guardar(CodigoDeReinicio codigo) {
            guardados.add(codigo);
        }

        @Override
        public void guardarCambios(CodigoDeReinicio codigo) {
            cambiosGuardados++;
        }

        @Override
        public void borrar(CodigoDeReinicio codigo) {
            guardados.remove(codigo);
        }
    }
}
