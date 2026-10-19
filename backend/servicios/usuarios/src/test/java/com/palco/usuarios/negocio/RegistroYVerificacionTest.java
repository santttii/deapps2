package com.palco.usuarios.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.palco.common.ErroresDeNegocio;
import com.palco.usuarios.api.Dtos.RegistroDto;
import com.palco.usuarios.api.Dtos.VerificacionDto;
import com.palco.usuarios.datos.CodigoVerificacionRepository;
import com.palco.usuarios.datos.UsuarioRepository;
import com.palco.usuarios.dominio.CodigoVerificacion;
import com.palco.usuarios.dominio.Rol;
import com.palco.usuarios.dominio.Usuario;

/**
 * El registro y la verificación del email, con los repositorios como dobles:
 * qué se guarda, qué se borra, en qué orden y con qué error se corta cada caso.
 * Sin servidor ni base.
 */
class RegistroYVerificacionTest {

    private static final String EMAIL = "ana@palco.test";
    private static final String DNI = "30123456";
    private static final String CODIGO = "123456";

    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final CodigoVerificacionRepository codigos = mock(CodigoVerificacionRepository.class);
    private final ServicioDeUsuarios servicio = new ServicioDeUsuarios();

    RegistroYVerificacionTest() {
        servicio.usuarios = usuarios;
        servicio.codigos = codigos;
        when(usuarios.buscarPorEmail(anyString())).thenReturn(Optional.empty());
        when(usuarios.buscarPorDni(anyString())).thenReturn(Optional.empty());
        when(codigos.buscarPorUsuario(anyString())).thenReturn(Optional.empty());
    }

    // --- Registrarse ---

    @Test
    void registrarseGuardaLaCuentaYSuCodigoConElEmailNormalizadoYElDniSoloConDigitos() {
        servicio.registrar(registro("  Ana@Palco.TEST ", "30.123.456"));

        ArgumentCaptor<Usuario> guardado = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarios).guardar(guardado.capture());
        Usuario usuario = guardado.getValue();
        assertEquals(EMAIL, usuario.getEmail());
        assertEquals(DNI, usuario.getDni());
        assertEquals(Rol.USER, usuario.getRol());
        assertTrue(usuario.getId().startsWith("user-"), usuario.getId());

        ArgumentCaptor<CodigoVerificacion> codigo = ArgumentCaptor.forClass(CodigoVerificacion.class);
        verify(codigos).guardar(codigo.capture());
        assertEquals(usuario.getId(), codigo.getValue().getUsuarioId());
    }

    @Test
    void unaCuentaSinVerificarConEseEmailSeBorraAntesDeGuardarLaNueva() {
        Usuario abandonada = cuenta(EMAIL, DNI);
        // Antes de borrarla la búsqueda la encuentra; después, ya no.
        when(usuarios.buscarPorEmail(EMAIL)).thenReturn(Optional.of(abandonada), Optional.empty());

        servicio.registrar(registro(EMAIL, DNI));

        InOrder enOrden = inOrder(usuarios);
        enOrden.verify(usuarios).borrar(abandonada);
        enOrden.verify(usuarios).guardar(any(Usuario.class));
    }

    @Test
    void unEmailYaVerificadoEsUnDatoInvalidoDelCampoEmail() {
        Usuario verificada = cuenta(EMAIL, "29999999");
        verificada.marcarEmailVerificado();
        when(usuarios.buscarPorEmail(EMAIL)).thenReturn(Optional.of(verificada));

        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.registrar(registro(EMAIL, DNI)));

        assertEquals(Map.of("email", "Ya hay una cuenta con ese email."), error.campos());
        verify(usuarios, never()).borrar(any());
        verify(usuarios, never()).guardar(any());
    }

    @Test
    void unDniYaVerificadoEsUnDatoInvalidoDelCampoDni() {
        Usuario verificada = cuenta("otra@palco.test", DNI);
        verificada.marcarEmailVerificado();
        when(usuarios.buscarPorDni(DNI)).thenReturn(Optional.of(verificada));

        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.registrar(registro(EMAIL, DNI)));

        assertEquals(Map.of("dni", "Ese DNI ya está registrado."), error.campos());
        verify(usuarios, never()).guardar(any());
    }

    @Test
    void unDniDeSeisDigitosSeRechazaSinTocarLaBase() {
        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.registrar(registro(EMAIL, "123.456")));

        assertEquals(Map.of("dni", "El DNI debe tener 7 u 8 dígitos."), error.campos());
        verify(usuarios, never()).buscarPorEmail(anyString());
    }

    @Test
    void unMenorDeDieciseisSeRechaza() {
        RegistroDto menor = new RegistroDto("Ana", "Gómez", DNI, LocalDate.now().minusYears(15), EMAIL, null,
                "palco1234");

        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.registrar(menor));

        assertTrue(error.campos().containsKey("fechaNacimiento"));
        verify(usuarios, never()).guardar(any());
    }

    // --- Verificar el email ---

    @Test
    void unCodigoIncorrectoSumaElIntentoYLoGuardaEnElMomento() throws Exception {
        CodigoVerificacion codigo = codigoVigente();

        assertThrows(ServicioDeUsuarios.CodigoIncorrecto.class,
                () -> servicio.verificarEmail(new VerificacionDto(EMAIL, "000000")));

        assertEquals(1, codigo.getIntentos());
        // CodigoIncorrecto no deshace la transacción: el intento tiene que haber salido a la base.
        verify(codigos).guardarCambios(codigo);
        verify(codigos, never()).borrar(any());
    }

    @Test
    void conCincoIntentosFallidosElCodigoYaNoSirve() throws Exception {
        CodigoVerificacion codigo = codigoVigente();
        for (int i = 0; i < ServicioDeUsuarios.MAX_INTENTOS_CODIGO; i++) {
            codigo.sumarIntento();
        }

        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.verificarEmail(new VerificacionDto(EMAIL, CODIGO)));

        assertEquals("Demasiados intentos. Pedí un código nuevo.", error.campos().get("codigo"));
    }

    @Test
    void unCodigoVencidoSeRechaza() throws Exception {
        Usuario usuario = cuentaSinVerificar();
        CodigoVerificacion codigo = new CodigoVerificacion(usuario.getId());
        LocalDateTime antes = LocalDateTime.now().minusMinutes(20);
        codigo.renovar(sha256(CODIGO), antes, antes.plusMinutes(15));
        when(codigos.buscarPorUsuario(usuario.getId())).thenReturn(Optional.of(codigo));

        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.verificarEmail(new VerificacionDto(EMAIL, CODIGO)));

        assertEquals("El código venció. Pedí uno nuevo.", error.campos().get("codigo"));
    }

    @Test
    void unEmailQueNoExisteRespondeComoUnCodigoIncorrecto() {
        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.verificarEmail(new VerificacionDto("nadie@palco.test", CODIGO)));

        assertEquals("El código no es correcto.", error.campos().get("codigo"));
    }

    @Test
    void unEmailYaVerificadoEsUnConflicto() {
        Usuario usuario = cuentaSinVerificar();
        usuario.marcarEmailVerificado();

        assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.verificarEmail(new VerificacionDto(EMAIL, CODIGO)));
    }

    // --- Reenviar el código ---

    @Test
    void reenviarAUnaCuentaSinCodigoLeCreaUno() {
        Usuario usuario = cuentaSinVerificar();

        servicio.reenviarCodigo(EMAIL);

        ArgumentCaptor<CodigoVerificacion> guardado = ArgumentCaptor.forClass(CodigoVerificacion.class);
        verify(codigos).guardar(guardado.capture());
        assertEquals(usuario.getId(), guardado.getValue().getUsuarioId());
    }

    @Test
    void reenviarAntesDeTreintaSegundosEsUnConflicto() throws Exception {
        codigoVigente();

        assertThrows(ErroresDeNegocio.Conflicto.class, () -> servicio.reenviarCodigo(EMAIL));
    }

    @Test
    void reenviarDespuesDeTreintaSegundosRenuevaElMismoCodigo() throws Exception {
        Usuario usuario = cuentaSinVerificar();
        CodigoVerificacion codigo = new CodigoVerificacion(usuario.getId());
        LocalDateTime antes = LocalDateTime.now().minusMinutes(1);
        codigo.renovar(sha256(CODIGO), antes, antes.plusMinutes(15));
        codigo.sumarIntento();
        when(codigos.buscarPorUsuario(usuario.getId())).thenReturn(Optional.of(codigo));

        servicio.reenviarCodigo(EMAIL);

        // El código está administrado: renovarlo se guarda al confirmar, sin un INSERT nuevo.
        assertEquals(0, codigo.getIntentos());
        assertTrue(codigo.getEnviado().isAfter(antes));
        verify(codigos, never()).guardar(any());
    }

    @Test
    void reenviarAUnEmailQueNoExisteNoHaceNadaYNoLoRevela() {
        servicio.reenviarCodigo("nadie@palco.test");

        verify(codigos, never()).guardar(any());
    }

    // --- Leer una cuenta ---

    @Test
    void unaCuentaQueNoExisteEsNoEncontrado() {
        when(usuarios.buscarPorId("user-x")).thenReturn(Optional.empty());

        ErroresDeNegocio.NoEncontrado error = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> servicio.obtener("user-x"));

        assertEquals("No existe el usuario.", error.getMessage());
    }

    @Test
    void unaCuentaQueExisteSaleComoDto() {
        Usuario usuario = cuenta(EMAIL, DNI);
        when(usuarios.buscarPorId(usuario.getId())).thenReturn(Optional.of(usuario));

        assertEquals(ServicioDeUsuarios.aDto(usuario), servicio.obtener(usuario.getId()));
    }

    // ---------------------------------------------------------------------------------------------

    private Usuario cuentaSinVerificar() {
        Usuario usuario = cuenta(EMAIL, DNI);
        when(usuarios.buscarPorEmail(EMAIL)).thenReturn(Optional.of(usuario));
        return usuario;
    }

    /** Un código recién enviado a una cuenta sin verificar. */
    private CodigoVerificacion codigoVigente() throws Exception {
        Usuario usuario = cuentaSinVerificar();
        CodigoVerificacion codigo = new CodigoVerificacion(usuario.getId());
        LocalDateTime ahora = LocalDateTime.now();
        codigo.renovar(sha256(CODIGO), ahora, ahora.plusMinutes(15));
        when(codigos.buscarPorUsuario(usuario.getId())).thenReturn(Optional.of(codigo));
        return codigo;
    }

    private static Usuario cuenta(String email, String dni) {
        return new Usuario("user-3f9a1c2b", "Ana", "Gómez", dni, email, null, LocalDate.of(1995, 5, 17),
                "hash-de-prueba", Rol.USER);
    }

    private static RegistroDto registro(String email, String dni) {
        return new RegistroDto("Ana", "Gómez", dni, LocalDate.of(1995, 5, 17), email, null, "palco1234");
    }

    private static String sha256(String texto) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
    }
}
