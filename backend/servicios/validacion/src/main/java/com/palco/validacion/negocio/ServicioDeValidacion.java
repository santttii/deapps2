package com.palco.validacion.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.common.ErroresDeNegocio;
import com.palco.validacion.api.Dtos.EntradaEscaneadaDto;
import com.palco.validacion.api.Dtos.EscaneoDto;
import com.palco.validacion.api.Dtos.PuertaAsignadaDto;
import com.palco.validacion.api.Dtos.ResultadoDeEscaneoDto;
import com.palco.validacion.api.Dtos.TitularDto;
import com.palco.validacion.api.Dtos.UsoAnteriorDto;
import com.palco.validacion.datos.EscaneoRepository;
import com.palco.validacion.datos.PuertaAsignadaRepository;
import com.palco.validacion.dominio.Escaneo;
import com.palco.validacion.dominio.PuertaAsignada;
import com.palco.validacion.dominio.ResultadoEscaneo;
import com.palco.validacion.negocio.externos.ClienteDeEventos;
import com.palco.validacion.negocio.externos.ClienteDeVentas;

/**
 * Stateless: cada escaneo es independiente y no recuerda nada del anterior.
 * Lo que tiene que durar (que la entrada se usó) queda en la base de Ventas,
 * y el registro de la puerta en la de este servicio, así que cualquier
 * instancia del pool atiende cualquier escaneo de cualquier puerta. Varias
 * puertas escaneando a la vez son varias instancias, sin nada que compartir.
 *
 * El uso único no se decide acá sino en Ventas, con un UPDATE condicional
 * sobre la entrada: es el único lugar donde dos escaneos simultáneos del
 * mismo código se ordenan sin carrera.
 *
 * Sin transacción propia ({@code NOT_SUPPORTED}), igual que ServicioDePagos:
 * entre leer el registro y guardar el escaneo se espera a Ventas, que es otro
 * servicio y puede tardar. Con una transacción abierta, esa espera retendría
 * una de las conexiones a la base que comparten los 8 servicios. Cada método
 * del repositorio abre y cierra su propia transacción, corta. Por la misma
 * razón el escaneo {@code SIN_RESPUESTA} queda guardado aunque después se
 * lance el error: no hay una transacción que lo deshaga.
 */
@Stateless
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class ServicioDeValidacion implements IValidacion {

    /**
     * Cuántos escaneos devuelve como máximo la consulta del registro. Es un
     * límite técnico, para que una consulta no traiga toda la tabla de un
     * evento grande; no es una regla del negocio.
     */
    static final int MAXIMO_POR_CONSULTA = 500;

    /** Cuántos caracteres del final del código muestra el registro. */
    static final int CARACTERES_VISIBLES_DEL_CODIGO = 6;

    /** El {@code type} del 403 cuando el staff no trabaja en la puerta de ese evento. */
    static final String PUERTA_NO_ASIGNADA = "puerta-no-asignada";

    private static final Logger LOG = Logger.getLogger(ServicioDeValidacion.class.getName());

    @Inject
    EscaneoRepository escaneos;

    @Inject
    ClienteDeVentas ventas;

    @Inject
    PuertaAsignadaRepository puertas;

    @Inject
    ClienteDeEventos eventos;

    /** De dónde sale el "ahora". No es estado: es fijo, y los tests lo reemplazan por un reloj detenido. */
    Clock reloj = Clock.systemUTC();

    @Override
    public ResultadoDeEscaneoDto escanear(String codigoQr, String eventoId, Solicitante staff) {
        // Un código tipeado a mano puede traer espacios de más; el que genera Ventas no tiene ninguno.
        String codigo = codigoQr.strip();
        String evento = eventoId.strip();
        String escaneadoPor = staff.id();
        // Antes que nada: solo escanea quien trabaja en esta puerta. No se llama a Ventas ni se registra.
        exigirPuerta(evento, staff);

        ClienteDeVentas.Uso uso;
        try {
            uso = ventas.usar(codigo, evento);
        } catch (ErroresDeNegocio.SinRespuesta sinRespuesta) {
            // Se registra el intento: si la entrada sí quedó usada, el próximo escaneo responde YA_USADA
            // y este renglón explica por qué.
            escaneos.guardar(new Escaneo(nuevoId(), codigo, evento, ResultadoEscaneo.SIN_RESPUESTA, null,
                    escaneadoPor, ahora()));
            throw sinRespuesta;
        }

        // El uso anterior se busca antes de guardar este escaneo, así no se encuentra a sí mismo.
        UsoAnteriorDto anterior = uso.resultado() == ResultadoEscaneo.YA_USADA ? usoAnterior(codigo) : null;

        ClienteDeVentas.Entrada entrada = uso.entrada();
        Escaneo escaneo = new Escaneo(nuevoId(), codigo, evento, uso.resultado(),
                entrada == null ? null : entrada.id(), escaneadoPor, ahora());
        escaneos.guardar(escaneo);

        EntradaEscaneadaDto datos = entrada == null ? null
                : new EntradaEscaneadaDto(entrada.id(), entrada.sectorId(), entrada.sector(),
                        new TitularDto(entrada.titular().nombre(), entrada.titular().dni()));
        return new ResultadoDeEscaneoDto(escaneo.getId(), uso.resultado().name(), uso.resultado().dejaEntrar(),
                mensaje(uso.resultado(), anterior), evento, datos, anterior, escaneadoPor,
                enUtc(escaneo.getEscaneado()));
    }

    @Override
    public List<EscaneoDto> listar(String eventoId, Solicitante quien) {
        String evento = eventoId.strip();
        if (quien.organizador()) {
            exigirDueno(evento, quien);
        } else {
            exigirPuerta(evento, quien);
        }
        return escaneos.listarPorEvento(evento, MAXIMO_POR_CONSULTA).stream()
                .map(ServicioDeValidacion::aDto)
                .toList();
    }

    @Override
    public EscaneoDto obtener(String escaneoId, Solicitante quien) {
        Escaneo escaneo = escaneos.buscarPorId(escaneoId)
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe ese escaneo."));
        // Uno de un evento ajeno responde igual que uno que no existe.
        boolean loPuedeVer = quien.organizador()
                ? esDueno(escaneo.getEventoId(), quien)
                : puertas.estaAsignada(escaneo.getEventoId(), quien.email());
        if (!loPuedeVer) {
            throw new ErroresDeNegocio.NoEncontrado("No existe ese escaneo.");
        }
        return aDto(escaneo);
    }

    @Override
    public List<PuertaAsignadaDto> misPuertas(Solicitante staff) {
        return puertas.deLaCuenta(staff.email()).stream().map(ServicioDeValidacion::aDto).toList();
    }

    @Override
    public List<PuertaAsignadaDto> equipoDePuerta(String eventoId, Solicitante organizador) {
        String evento = eventoId.strip();
        exigirDueno(evento, organizador);
        return puertas.delEvento(evento).stream().map(ServicioDeValidacion::aDto).toList();
    }

    @Override
    public PuertaAsignadaDto asignar(String eventoId, String staffEmail, Solicitante organizador) {
        String evento = eventoId.strip();
        String email = normalizarEmail(staffEmail);
        exigirDueno(evento, organizador);
        Optional<PuertaAsignada> existente = puertas.buscar(evento, email);
        if (existente.isPresent()) {
            return aDto(existente.get());
        }
        PuertaAsignada nueva = new PuertaAsignada(evento, email, organizador.id(), ahora());
        try {
            puertas.guardar(nueva);
        } catch (ErroresDeNegocio.Conflicto choque) {
            // La misma asignación llegó dos veces a la vez: vale la que se guardó.
            return puertas.buscar(evento, email).map(ServicioDeValidacion::aDto).orElseThrow(() -> choque);
        }
        LOG.info("Puerta del evento " + evento + ": " + organizador.id() + " asignó una cuenta de staff.");
        return aDto(nueva);
    }

    @Override
    public void quitar(String eventoId, String staffEmail, Solicitante organizador) {
        String evento = eventoId.strip();
        exigirDueno(evento, organizador);
        if (puertas.quitar(evento, normalizarEmail(staffEmail))) {
            LOG.info("Puerta del evento " + evento + ": " + organizador.id() + " quitó una cuenta de staff.");
        }
    }

    /** El staff tiene que estar asignado a la puerta del evento. */
    private void exigirPuerta(String eventoId, Solicitante staff) {
        if (!puertas.estaAsignada(eventoId, staff.email())) {
            throw new ErroresDeNegocio.SinPermiso(PUERTA_NO_ASIGNADA,
                    "No estás asignado a la puerta de este evento. Pedíselo al organizador.");
        }
    }

    /** El organizador tiene que ser el dueño del evento. Si no lo es, el evento "no existe" para él. */
    private void exigirDueno(String eventoId, Solicitante organizador) {
        if (!esDueno(eventoId, organizador)) {
            throw new ErroresDeNegocio.NoEncontrado("No existe ese evento entre los tuyos.");
        }
    }

    private boolean esDueno(String eventoId, Solicitante organizador) {
        if (!eventoId.matches("[A-Za-z0-9_-]{1,40}")) {
            // Va en la ruta del pedido a Eventos: un id con una barra no sale.
            return false;
        }
        try {
            return organizador.id().equals(eventos.organizadorDe(eventoId));
        } catch (ErroresDeNegocio.NoEncontrado e) {
            return false;
        }
    }

    static String normalizarEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Para una entrada ya usada: el escaneo de esta puerta que la usó o, si no
     * hay, el último que quedó sin respuesta de Ventas (que pudo haberla usado
     * sin que nadie se enterara). Con eso el personal sabe si es alguien que
     * ya entró o el mismo titular que vuelve a escanear después de una falla.
     */
    private UsoAnteriorDto usoAnterior(String codigo) {
        Optional<Escaneo> anterior = escaneos.ultimoDelCodigo(codigo, ResultadoEscaneo.VALIDA)
                .or(() -> escaneos.ultimoDelCodigo(codigo, ResultadoEscaneo.SIN_RESPUESTA));
        return anterior.map(e -> new UsoAnteriorDto(e.getId(), e.getResultado().name(), e.getEscaneadoPor(),
                enUtc(e.getEscaneado()))).orElse(null);
    }

    /** El texto que ve el personal de puerta. La pantalla decide por {@code resultado}, no por este texto. */
    static String mensaje(ResultadoEscaneo resultado, UsoAnteriorDto anterior) {
        return switch (resultado) {
            case VALIDA -> "Puede entrar. Controlá que el nombre y el DNI coincidan con el documento.";
            case YA_USADA -> anterior != null && ResultadoEscaneo.SIN_RESPUESTA.name().equals(anterior.resultado())
                    ? "La entrada ya figura usada, y un escaneo anterior de este código quedó sin respuesta: "
                            + "puede ser el mismo titular. Controlá el documento con un supervisor."
                    : "La entrada ya se usó. No puede entrar.";
            case INEXISTENTE -> "El código no corresponde a ninguna entrada. No puede entrar.";
            case OTRO_EVENTO -> "La entrada es de otro evento. No puede entrar por esta puerta.";
            case NO_HABILITADA -> "La entrada no está habilitada: está en la reventa o fue anulada. No puede entrar.";
            case SIN_RESPUESTA -> "No se pudo validar la entrada. Escaneá de nuevo.";
        };
    }

    /** Solo los últimos caracteres del código: ver {@code EscaneoDto}. */
    static String parcial(String codigo) {
        return codigo.length() <= CARACTERES_VISIBLES_DEL_CODIGO ? codigo
                : "…" + codigo.substring(codigo.length() - CARACTERES_VISIBLES_DEL_CODIGO);
    }

    static PuertaAsignadaDto aDto(PuertaAsignada p) {
        return new PuertaAsignadaDto(p.getEventoId(), p.getStaffEmail(), p.getAsignadaPor(), enUtc(p.getAsignada()));
    }

    static EscaneoDto aDto(Escaneo e) {
        return new EscaneoDto(e.getId(), e.getEventoId(), parcial(e.getCodigoQr()), e.getResultado().name(),
                e.getEntradaId(), e.getEscaneadoPor(), enUtc(e.getEscaneado()));
    }

    /** El instante del reloj, en UTC y sin fracción de segundo: no depende de la zona horaria del servidor. */
    private LocalDateTime ahora() {
        return LocalDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    static String enUtc(LocalDateTime fecha) {
        return fecha.toInstant(ZoneOffset.UTC).toString();
    }

    private static String nuevoId() {
        return "ESC-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
