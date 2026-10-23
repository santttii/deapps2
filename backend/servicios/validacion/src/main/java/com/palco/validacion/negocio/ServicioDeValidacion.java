package com.palco.validacion.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.common.ErroresDeNegocio;
import com.palco.validacion.api.Dtos.EntradaEscaneadaDto;
import com.palco.validacion.api.Dtos.EscaneoDto;
import com.palco.validacion.api.Dtos.ResultadoDeEscaneoDto;
import com.palco.validacion.api.Dtos.TitularDto;
import com.palco.validacion.api.Dtos.UsoAnteriorDto;
import com.palco.validacion.datos.EscaneoRepository;
import com.palco.validacion.dominio.Escaneo;
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

    /** El {@code type} del 403 cuando un organizador pide el registro de un evento que no es suyo. */
    static final String EVENTO_AJENO = "evento-ajeno";

    @Inject
    EscaneoRepository escaneos;

    @Inject
    ClienteDeVentas ventas;

    @Inject
    ClienteDeEventos eventos;

    /** De dónde sale el "ahora". No es estado: es fijo, y los tests lo reemplazan por un reloj detenido. */
    Clock reloj = Clock.systemUTC();

    @Override
    public ResultadoDeEscaneoDto escanear(String codigoQr, String eventoId, String escaneadoPor) {
        // Un código tipeado a mano puede traer espacios de más; el que genera Ventas no tiene ninguno.
        String codigo = codigoQr.strip();
        String evento = eventoId.strip();

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
    public List<EscaneoDto> listar(String eventoId, String tokenDelOrganizador) {
        String evento = eventoId.strip();
        if (tokenDelOrganizador != null && !esDelOrganizador(evento, tokenDelOrganizador)) {
            throw new ErroresDeNegocio.SinPermiso(EVENTO_AJENO,
                    "Ese evento no es tuyo: solo ves el registro de la puerta de tus eventos.");
        }
        return escaneos.listarPorEvento(evento, MAXIMO_POR_CONSULTA).stream()
                .map(ServicioDeValidacion::aDto)
                .toList();
    }

    @Override
    public EscaneoDto obtener(String escaneoId, String tokenDelOrganizador) {
        return escaneos.buscarPorId(escaneoId)
                .filter(e -> tokenDelOrganizador == null || esDelOrganizador(e.getEventoId(), tokenDelOrganizador))
                .map(ServicioDeValidacion::aDto)
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe ese escaneo."));
    }

    /**
     * Si el evento es del organizador del token. Lo decide Eventos, que es
     * quien sabe quién creó cada evento; Validación no guarda dueños. Al
     * personal de puerta no se le pregunta: todavía no hay asignación de staff
     * a eventos (la otra mitad de la limitación L17).
     */
    private boolean esDelOrganizador(String eventoId, String tokenDelOrganizador) {
        return eventos.eventosDelOrganizador(tokenDelOrganizador).contains(eventoId);
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
