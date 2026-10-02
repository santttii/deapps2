package com.palco.usuarios.negocio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.palco.common.ErroresDeNegocio;
import com.palco.usuarios.api.Dtos.LoginDto;
import com.palco.usuarios.api.Dtos.RegistroDto;
import com.palco.usuarios.api.Dtos.RegistroPendienteDto;
import com.palco.usuarios.api.Dtos.SesionDto;
import com.palco.usuarios.api.Dtos.UsuarioDto;
import com.palco.usuarios.api.Dtos.VerificacionDto;
import com.palco.usuarios.dominio.CodigoVerificacion;
import com.palco.usuarios.dominio.Rol;
import com.palco.usuarios.dominio.Usuario;
import com.palco.usuarios.seguridad.ClavesJwt;
import com.palco.usuarios.seguridad.Contrasenas;
import com.palco.usuarios.seguridad.Tokens;

@Stateless
public class ServicioDeUsuarios implements IUsuarios {

    private static final Logger LOG = Logger.getLogger(ServicioDeUsuarios.class.getName());

    static final int EDAD_MINIMA = 16;
    static final Duration DURACION_SESION = Duration.ofHours(8);
    static final Duration VIGENCIA_CODIGO = Duration.ofMinutes(15);
    static final Duration ESPERA_REENVIO = Duration.ofSeconds(30);
    static final int MAX_INTENTOS_CODIGO = 5;

    private static final String ERROR_LOGIN = "Revisá el email y la contraseña.";
    private static final SecureRandom AZAR = new SecureRandom();

    /** Hash de una contraseña que nadie tiene, para igualar tiempos cuando el email no existe. */
    private static final String HASH_FALSO = Contrasenas.hashear(UUID.randomUUID().toString());

    @PersistenceContext(unitName = "usuarios")
    EntityManager em;

    @Inject
    ClavesJwt claves;

    @Override
    public RegistroPendienteDto registrar(RegistroDto r) {
        String email = normalizarEmail(r.email());
        String dni = r.dni().replaceAll("\\D", "");
        if (!dni.matches("\\d{7,8}")) {
            throw new ErroresDeNegocio.DatosInvalidos("dni", "El DNI debe tener 7 u 8 dígitos.");
        }
        if (Period.between(r.fechaNacimiento(), LocalDate.now()).getYears() < EDAD_MINIMA) {
            throw new ErroresDeNegocio.DatosInvalidos("fechaNacimiento", "Tenés que ser mayor de 16 años.");
        }

        // Si alguien se registró y nunca verificó el email, puede volver a registrarse con esos datos.
        buscarPorEmail(email).filter(u -> !u.isEmailVerificado()).ifPresent(em::remove);
        buscarPorDni(dni).filter(u -> !u.isEmailVerificado()).ifPresent(em::remove);
        em.flush();

        if (buscarPorEmail(email).isPresent()) {
            throw new ErroresDeNegocio.DatosInvalidos("email", "Ya hay una cuenta con ese email.");
        }
        if (buscarPorDni(dni).isPresent()) {
            throw new ErroresDeNegocio.DatosInvalidos("dni", "Ese DNI ya está registrado.");
        }

        Usuario usuario = new Usuario(
                "user-" + UUID.randomUUID().toString().substring(0, 8),
                r.nombre().strip(),
                r.apellido().strip(),
                dni,
                email,
                r.telefono() == null || r.telefono().isBlank() ? null : r.telefono().strip(),
                r.fechaNacimiento(),
                Contrasenas.hashear(r.password()),
                Rol.USER);
        em.persist(usuario);

        CodigoVerificacion codigo = new CodigoVerificacion(usuario.getId());
        enviarCodigoNuevo(usuario, codigo);
        em.persist(codigo);

        return new RegistroPendienteDto(email, "Te enviamos un código de 6 dígitos a " + email + ".");
    }

    @Override
    public SesionDto verificarEmail(VerificacionDto v) {
        Usuario usuario = buscarPorEmail(normalizarEmail(v.email()))
                .orElseThrow(() -> new ErroresDeNegocio.DatosInvalidos("codigo", "El código no es correcto."));
        if (usuario.isEmailVerificado()) {
            throw new ErroresDeNegocio.Conflicto("Ese email ya está verificado. Iniciá sesión.");
        }
        CodigoVerificacion codigo = em.find(CodigoVerificacion.class, usuario.getId());
        if (codigo == null || LocalDateTime.now().isAfter(codigo.getExpira())) {
            throw new ErroresDeNegocio.DatosInvalidos("codigo", "El código venció. Pedí uno nuevo.");
        }
        if (codigo.getIntentos() >= MAX_INTENTOS_CODIGO) {
            throw new ErroresDeNegocio.DatosInvalidos("codigo", "Demasiados intentos. Pedí un código nuevo.");
        }
        if (!MessageDigest.isEqual(
                codigo.getCodigoHash().getBytes(StandardCharsets.US_ASCII),
                sha256(v.codigo()).getBytes(StandardCharsets.US_ASCII))) {
            // El intento cuenta aunque la respuesta sea un error: no se deshace la transacción.
            codigo.sumarIntento();
            em.flush();
            throw new CodigoIncorrecto();
        }

        usuario.marcarEmailVerificado();
        em.remove(codigo);
        return sesion(usuario);
    }

    @Override
    public void reenviarCodigo(String email) {
        Optional<Usuario> usuario = buscarPorEmail(normalizarEmail(email)).filter(u -> !u.isEmailVerificado());
        if (usuario.isEmpty()) {
            return; // No se revela si el email existe.
        }
        CodigoVerificacion codigo = em.find(CodigoVerificacion.class, usuario.get().getId());
        if (codigo == null) {
            codigo = new CodigoVerificacion(usuario.get().getId());
            enviarCodigoNuevo(usuario.get(), codigo);
            em.persist(codigo);
            return;
        }
        if (codigo.getEnviado().plus(ESPERA_REENVIO).isAfter(LocalDateTime.now())) {
            throw new ErroresDeNegocio.Conflicto("Esperá unos segundos antes de pedir otro código.");
        }
        enviarCodigoNuevo(usuario.get(), codigo);
    }

    @Override
    public SesionDto iniciarSesion(LoginDto login) {
        Optional<Usuario> usuario = buscarPorEmail(normalizarEmail(login.email()));
        // Se calcula el hash aunque el email no exista, para no revelar por el tiempo de respuesta qué emails existen.
        String hash = usuario.map(Usuario::getPasswordHash).orElse(HASH_FALSO);
        if (!Contrasenas.verificar(login.password(), hash) || usuario.isEmpty()) {
            throw new ErroresDeNegocio.NoAutenticado(ERROR_LOGIN);
        }
        if (!usuario.get().isEmailVerificado()) {
            throw new ErroresDeNegocio.Conflicto("Verificá tu email antes de iniciar sesión.");
        }
        return sesion(usuario.get());
    }

    @Override
    public UsuarioDto obtener(String usuarioId) {
        Usuario usuario = em.find(Usuario.class, usuarioId);
        if (usuario == null) {
            throw new ErroresDeNegocio.NoEncontrado("No existe el usuario.");
        }
        return aDto(usuario);
    }

    // ---------------------------------------------------------------------------------------------

    private SesionDto sesion(Usuario usuario) {
        Instant ahora = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "palco");
        claims.put("sub", usuario.getId());
        claims.put("upn", usuario.getEmail());
        claims.put("groups", List.of(usuario.getRol().nombre()));
        claims.put("iat", ahora.getEpochSecond());
        claims.put("exp", ahora.plus(DURACION_SESION).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("nombre", usuario.getNombre());
        claims.put("apellido", usuario.getApellido());
        claims.put("dni", usuario.getDni());

        String token = Tokens.firmar(claims, claves.privada(), claves.kid());
        return new SesionDto(token, DURACION_SESION.toSeconds(), aDto(usuario));
    }

    private void enviarCodigoNuevo(Usuario usuario, CodigoVerificacion codigo) {
        String numero = "%06d".formatted(AZAR.nextInt(1_000_000));
        LocalDateTime ahora = LocalDateTime.now();
        codigo.renovar(sha256(numero), ahora, ahora.plus(VIGENCIA_CODIGO));
        // TODO(ServicioDeNotificaciones): mandarlo por email. Mientras tanto queda en el log del servidor.
        LOG.info("Código de verificación para " + usuario.getEmail() + ": " + numero);
    }

    private Optional<Usuario> buscarPorEmail(String email) {
        return em.createQuery("select u from Usuario u where u.email = :email", Usuario.class)
                .setParameter("email", email)
                .getResultStream()
                .findFirst();
    }

    private Optional<Usuario> buscarPorDni(String dni) {
        return em.createQuery("select u from Usuario u where u.dni = :dni", Usuario.class)
                .setParameter("dni", dni)
                .getResultStream()
                .findFirst();
    }

    private static String normalizarEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String texto) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static UsuarioDto aDto(Usuario u) {
        return new UsuarioDto(u.getId(), u.getNombre(), u.getApellido(), u.getDni(), u.getEmail(),
                u.getRol().nombre(), u.isIdentidadVerificada());
    }

    /**
     * Código incorrecto → 400. A diferencia del resto de los errores, NO deshace
     * la transacción: el intento fallido tiene que quedar guardado.
     */
    @jakarta.ejb.ApplicationException(rollback = false)
    public static class CodigoIncorrecto extends ErroresDeNegocio.DatosInvalidos {
        public CodigoIncorrecto() {
            super("codigo", "El código no es correcto.");
        }
    }
}
