package com.palco.eventos.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.Textos;
import com.palco.common.rest.FiltroDeEtag;
import com.palco.common.mensajeria.EventoCanceladoMensaje;
import com.palco.common.mensajeria.PublicadorDeCancelaciones;
import com.palco.eventos.api.Dtos.EditarEventoDto;
import com.palco.eventos.api.Dtos.EventoDto;
import com.palco.eventos.api.Dtos.NuevoEventoDto;
import com.palco.eventos.api.Dtos.NuevoSectorDto;
import com.palco.eventos.api.Dtos.SectorDto;
import com.palco.eventos.api.Dtos.SectorEditadoDto;
import com.palco.eventos.datos.DevolucionDeCupoRepository;
import com.palco.eventos.datos.EventoRepository;
import com.palco.eventos.datos.SectorRepository;
import com.palco.eventos.dominio.Categoria;
import com.palco.eventos.dominio.DevolucionDeCupo;
import com.palco.eventos.dominio.Evento;
import com.palco.eventos.dominio.Sector;

/**
 * Stateless: cada operación es autocontenida y no depende de la llamada
 * anterior, así que el contenedor reutiliza un pool de instancias.
 *
 * Tiene las reglas del catálogo (fecha futura, categoría válida, slug único,
 * evento empezado) y deja el acceso a la base en los repositorios de
 * {@code datos/}. Cada operación corre en una transacción que abre el
 * contenedor al entrar; los repositorios se suman a ella.
 *
 * Editar solo si nadie cambió el evento ({@code If-Match}). El organizador
 * lee el evento ({@code GET /api/eventos/{slug}}, que trae {@code ETag}) y lo
 * edita después. Si entre las dos cosas otro lo editó (una segunda pestaña,
 * otra persona de la misma cuenta), guardar a ciegas pisaría ese cambio; si
 * hubo una venta, decidió mirando unas vendidas que ya no son. Con {@code If-Match} el cliente dice "editá solo
 * si el evento sigue como lo leí" y, si no, recibe 412 {@code evento-modificado}
 * sin que se guarde nada. Es control de concurrencia optimista visto desde el
 * cliente: no retiene nada entre la lectura y la edición.
 *
 * <ul>
 * <li><b>Qué se compara.</b> El ETag del estado actual, calculado con la misma
 * función que usa el filtro del GET
 * ({@link FiltroDeEtag#etagDeLaRepresentacion}) sobre el mismo DTO: el valor es
 * idéntico por construcción. Es un ETag fuerte (sin {@code W/}) e
 * {@code If-Match} usa la comparación fuerte de RFC 9110 (13.1.1): un valor
 * débil nunca coincide. No hacía falta volverlos débiles: el hash es del JSON
 * exacto, así que dos respuestas con el mismo ETag son idénticas, que es lo que
 * promete uno fuerte.</li>
 * <li><b>Sin ventana entre comparar y guardar.</b> La entidad no tiene
 * {@code @Version} y no se le agrega: el ETag es el hash del evento con sus
 * sectores, y las vendidas cambian con un UPDATE directo sobre {@code sector}
 * ({@code sumarVendidas}) que no pasaría por la versión del evento; además el
 * cliente no tiene un número de versión, tiene el ETag del GET. En su lugar, la
 * edición lee el evento y sus sectores con {@code SELECT ... FOR UPDATE}
 * ({@link EventoRepository#buscarPorIdParaCambiar}): desde que se compara hasta
 * que la transacción termina, otra edición, una cancelación o una venta de ese
 * evento esperan. Dos ediciones con el mismo ETag no pueden pasar las dos: la
 * segunda lee lo que guardó la primera y recibe 412. El bloqueo dura lo que la
 * transacción (milisegundos), y una venta toca un solo sector, así que no hay
 * dos operaciones esperándose entre sí.</li>
 * <li><b>Sin {@code If-Match}</b> se edita como siempre: el panel del
 * organizador no lo manda y sigue andando igual.</li>
 * </ul>
 */
@Stateless
public class ServicioDeEventos implements IEventos {

    /**
     * La fecha de un evento es la hora local del lugar, sin zona (como la
     * escribe el frontend), y los eventos son en Argentina. Por eso "ahora" se
     * mide en esa zona y no en la del servidor, que puede estar en UTC: con
     * UTC un evento de las 21 quedaría cerrado desde las 18.
     */
    static final ZoneId ZONA_DE_LOS_EVENTOS = ZoneId.of("America/Argentina/Buenos_Aires");

    /**
     * Slugs que no puede tener un evento porque son rutas de la API: un evento
     * llamado "Míos" quedaría tapado por {@code GET /api/eventos/mios}. Lleva
     * "-2" como cualquier slug repetido.
     */
    static final Set<String> SLUGS_RESERVADOS = Set.of(RUTA_MIOS, RUTA_IMAGENES);

    @Inject
    EventoRepository eventos;

    @Inject
    SectorRepository sectores;

    @Inject
    DevolucionDeCupoRepository devoluciones;

    @Inject
    PublicadorDeCancelaciones cancelaciones;

    /** No es privado para que el test fije la hora. */
    @SuppressWarnings("java:S8983") // Se fija al crear el bean, no cambia entre llamadas y los tests lo reemplazan.
    Clock reloj = Clock.system(ZONA_DE_LOS_EVENTOS);

    @Override
    public List<EventoDto> listarEventos(String categoria, String busqueda) {
        Categoria filtroCategoria = categoria == null || categoria.isBlank() ? null : parsearCategoria(categoria);
        String texto = Textos.normalizar(busqueda);

        return eventos.listar(filtroCategoria)
                .stream()
                // Búsqueda sin acentos en Java: el catálogo es chico y así funciona igual en H2 y PostgreSQL.
                .filter(e -> texto.isEmpty()
                        || Textos.normalizar(e.getTitulo()).contains(texto)
                        || Textos.normalizar(e.getVenue()).contains(texto))
                .map(ServicioDeEventos::aDto)
                .toList();
    }

    @Override
    public List<EventoDto> listarDelOrganizador(String organizadorId) {
        return eventos.listarDelOrganizador(organizadorId).stream().map(ServicioDeEventos::aDto).toList();
    }

    @Override
    public String organizadorDe(String eventoId) {
        return eventos.buscarPorId(eventoId)
                .map(Evento::getOrganizadorId)
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe el evento " + eventoId + "."));
    }

    @Override
    public EventoDto obtenerDetalle(String slug) {
        return eventos.buscarPorSlug(slug)
                .map(ServicioDeEventos::aDto)
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe el evento \"" + slug + "\"."));
    }

    @Override
    public EventoDto crearEvento(NuevoEventoDto nuevo, String organizadorId) {
        // Un evento que ya empezó no tiene a quién venderle. Se valida acá y no con @Future en el DTO
        // porque @Future compara con la zona del servidor, y la fecha es hora de Argentina.
        if (yaEmpezo(nuevo.fechaISO())) {
            throw new ErroresDeNegocio.DatosInvalidos("fechaISO", "La fecha del evento tiene que ser futura.");
        }
        Evento evento = new Evento(
                nuevoId("ev"),
                slugUnico(nuevo.titulo()),
                nuevo.titulo().trim(),
                parsearCategoria(nuevo.categoria()),
                nuevo.venue().trim(),
                nuevo.fechaISO(),
                nuevo.descripcion().trim(),
                nuevo.imagenUrl(),
                organizadorId);

        int orden = 1;
        for (NuevoSectorDto s : nuevo.sectores()) {
            evento.agregarSector(new Sector(nuevoId("sec"), evento, s.nombre().trim(), s.precio(), s.cupo(), orden++));
        }
        eventos.guardar(evento);
        return aDto(evento);
    }

    @Override
    public EventoDto editarEvento(String eventoId, EditarEventoDto cambios, String organizadorId, String ifMatch) {
        // Bloqueado desde acá hasta el final de la transacción (ver el comentario de la clase).
        Evento evento = propio(eventoId, organizadorId);
        if (evento.isCancelado()) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.EVENTO_CANCELADO,
                    "El evento está cancelado: no se puede editar.");
        }
        if (yaEmpezo(evento.getFecha())) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.EVENTO_FINALIZADO,
                    "El evento ya empezó: no se puede editar.");
        }
        // La condición se mira después del 404 y de los 409 y antes de leer los cambios, como pide
        // RFC 9110 (13.2.1): lo que fallaría igual sin If-Match gana, porque dice qué pasó.
        exigirQueNoHayaCambiado(evento, ifMatch);
        if (yaEmpezo(cambios.fechaISO())) {
            throw new ErroresDeNegocio.DatosInvalidos("fechaISO", "La fecha del evento tiene que ser futura.");
        }

        evento.actualizarDatos(
                cambios.titulo().trim(),
                parsearCategoria(cambios.categoria()),
                cambios.venue().trim(),
                cambios.fechaISO(),
                cambios.descripcion().trim(),
                cambios.imagenUrl());
        aplicarSectores(evento, cambios.sectores());
        return aDto(evento);
    }

    /**
     * Si vino {@code If-Match}, el evento tiene que seguir como lo leyó el
     * cliente: su ETag actual, calculado igual que el del GET, tiene que estar
     * en la lista, o ser {@code *} (que vale porque el evento existe). Si no,
     * 412 {@code evento-modificado} y la transacción se deshace sin guardar.
     * Sin {@code If-Match} no hay condición.
     */
    private static void exigirQueNoHayaCambiado(Evento evento, String ifMatch) {
        if (ifMatch == null) {
            return;
        }
        if (!FiltroDeEtag.coincideFuerte(ifMatch, FiltroDeEtag.etagDeLaRepresentacion(aDto(evento)))) {
            throw new ErroresDeNegocio.PrecondicionFallida(TiposDeProblema.EVENTO_MODIFICADO,
                    "El evento cambió desde que lo cargaste. Volvé a cargarlo y repetí el cambio.");
        }
    }

    /**
     * Deja el evento con exactamente los sectores de la lista: cambia los que
     * traen id, agrega los que no y quita los que no aparecen. El orden del
     * resultado es el de la lista que llegó.
     */
    private void aplicarSectores(Evento evento, List<SectorEditadoDto> pedidos) {
        Map<String, Sector> actuales = evento.getSectores().stream()
                .collect(Collectors.toMap(Sector::getId, s -> s));

        // Primero los que se van: un sector con entradas vendidas no se puede quitar.
        Set<String> quedan = pedidos.stream()
                .map(SectorEditadoDto::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (Sector sector : List.copyOf(evento.getSectores())) {
            if (!quedan.contains(sector.getId())) {
                if (sector.getVendidas() > 0) {
                    throw new ErroresDeNegocio.Conflicto(TiposDeProblema.SECTOR_CON_VENTAS,
                            "No se puede quitar " + sector.getNombre() + ": ya tiene "
                                    + sector.getVendidas() + " entradas vendidas.");
                }
                evento.quitarSector(sector);
            }
        }

        int orden = 1;
        for (SectorEditadoDto pedido : pedidos) {
            if (pedido.id() == null || pedido.id().isBlank()) {
                evento.agregarSector(new Sector(nuevoId("sec"), evento, pedido.nombre().trim(),
                        pedido.precio(), pedido.cupo(), orden++));
                continue;
            }
            Sector sector = actuales.get(pedido.id());
            if (sector == null) {
                throw new ErroresDeNegocio.DatosInvalidos("sectores",
                        "El sector " + pedido.id() + " no es de este evento.");
            }
            cambiar(sector, pedido, orden++);
        }
    }

    /** Un sector que ya existe: lo que se puede cambiar depende de si tiene ventas. */
    private void cambiar(Sector sector, SectorEditadoDto pedido, int orden) {
        if (pedido.cupo() < sector.getVendidas()) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.SECTOR_CON_VENTAS,
                    "El cupo de " + sector.getNombre() + " no puede ser menor que las "
                            + sector.getVendidas() + " entradas ya vendidas.");
        }
        if (pedido.precio() != sector.getPrecio() && sector.getVendidas() > 0) {
            // El tope de la reventa se calcula sobre lo que pagó cada comprador: mover el
            // precio de lista después de vender dejaría topes que no se corresponden.
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.SECTOR_CON_VENTAS,
                    "No se puede cambiar el precio de " + sector.getNombre() + ": ya tiene entradas vendidas.");
        }
        sector.renombrar(pedido.nombre().trim());
        sector.cambiarPrecio(pedido.precio());
        sector.cambiarCupo(pedido.cupo());
        sector.cambiarOrden(orden);
    }

    /**
     * El evento, si es de ese organizador. Si no existe o es de otro, 404: para él no existe.
     *
     * Lo usan las dos operaciones del organizador que cambian el evento (editar y cancelar), y por
     * eso lo lee bloqueado: así una cancelación no pisa una edición que se guarda a la vez, ni al revés.
     */
    private Evento propio(String eventoId, String organizadorId) {
        Evento evento = eventos.buscarPorIdParaCambiar(eventoId).orElse(null);
        if (evento == null || evento.getOrganizadorId() == null
                || !evento.getOrganizadorId().equals(organizadorId)) {
            throw new ErroresDeNegocio.NoEncontrado("No existe ese evento entre los tuyos.");
        }
        return evento;
    }

    @Override
    public EventoDto cancelarEvento(String eventoId, String motivo, String organizadorId) {
        Evento evento = propio(eventoId, organizadorId);
        if (evento.isCancelado()) {
            // Cortar acá es lo que evita que una segunda cancelación publique otro mensaje
            // y Ventas reembolse dos veces.
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.EVENTO_CANCELADO,
                    "El evento ya está cancelado.");
        }
        if (yaEmpezo(evento.getFecha())) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.EVENTO_FINALIZADO,
                    "El evento ya empezó: no se puede cancelar.");
        }

        LocalDateTime ahora = LocalDateTime.now(reloj);
        evento.cancelar(motivo, ahora);
        // En la misma transacción que la marca: si el mensaje no sale, el evento no queda
        // cancelado, y si la marca se deshace, nadie reembolsa. El publicador es MANDATORY.
        cancelaciones.publicarEventoCancelado(new EventoCanceladoMensaje(
                evento.getId(), evento.getTitulo(), evento.getCancelacionMotivo(),
                ahora.atZone(ZONA_DE_LOS_EVENTOS).toInstant()));
        return aDto(evento);
    }

    @Override
    public SectorDto actualizarCupo(String eventoId, String sectorId, int cantidad) {
        Sector sector = delEvento(eventoId, sectorId);
        if (cantidad == 0) {
            return aDto(sector);
        }
        // Solo se frena la reserva. Devolver lugares (cantidad negativa) tiene que andar siempre:
        // cancelar o vencer una compra no puede fallar porque el evento esté cancelado o ya haya empezado.
        if (cantidad > 0 && sector.getEvento().isCancelado()) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.EVENTO_CANCELADO,
                    "El evento está cancelado: no se venden más entradas.");
        }
        if (cantidad > 0 && yaEmpezo(sector.getEvento().getFecha())) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.EVENTO_FINALIZADO,
                    "El evento ya empezó: no se venden más entradas.");
        }

        // El repositorio lo resuelve en un solo UPDATE condicional: si dos compras llegan a la
        // vez por el último lugar, la base deja pasar solo a una.
        if (!sectores.sumarVendidas(sector, cantidad)) {
            throw new ErroresDeNegocio.Conflicto(cantidad > 0
                    ? "No quedan suficientes entradas en " + sector.getNombre() + "."
                    : "No se pueden liberar más entradas de las vendidas.");
        }
        return aDto(sector);
    }

    @Override
    public SectorDto devolverCupo(String eventoId, String sectorId, int cantidad, String clave) {
        if (cantidad <= 0) {
            throw new ErroresDeNegocio.DatosInvalidos("cantidad", "Para devolver cupo la cantidad tiene que ser negativa.");
        }
        if (clave == null || clave.isBlank()) {
            throw new ErroresDeNegocio.DatosInvalidos("clave", "Falta la clave de la devolución.");
        }
        Sector sector = delEvento(eventoId, sectorId);

        // Ya se aplicó: no se resta otra vez. Es el caso de quien devolvió, no supo la respuesta y repite.
        DevolucionDeCupo anterior = devoluciones.buscar(clave).orElse(null);
        if (anterior != null) {
            if (!anterior.getSectorId().equals(sectorId) || anterior.getCantidad() != cantidad) {
                throw new ErroresDeNegocio.Conflicto(TiposDeProblema.CLAVE_REUTILIZADA,
                        "Esa clave de devolución ya se usó para otro sector o con otra cantidad.");
            }
            return aDto(sector);
        }

        // Devolver no se frena aunque el evento ya haya empezado: cancelar o vencer una compra no puede fallar.
        if (!sectores.sumarVendidas(sector, -cantidad)) {
            throw new ErroresDeNegocio.Conflicto("No se pueden liberar más entradas de las vendidas.");
        }
        // En la misma transacción que la resta: si el registro falla, la resta también se deshace.
        devoluciones.registrar(new DevolucionDeCupo(clave, sectorId, cantidad, LocalDateTime.now(reloj)));
        return aDto(sector);
    }

    /** El sector, si es de ese evento. Si no existe o es de otro evento, 404. */
    private Sector delEvento(String eventoId, String sectorId) {
        Sector sector = sectores.buscarPorId(sectorId).orElse(null);
        if (sector == null || !sector.getEvento().getId().equals(eventoId)) {
            throw new ErroresDeNegocio.NoEncontrado("No existe ese sector en el evento.");
        }
        return sector;
    }

    /** Un evento empezó cuando llega la hora de su fecha, medida en la zona de los eventos. */
    private boolean yaEmpezo(LocalDateTime fecha) {
        return !LocalDateTime.now(reloj).isBefore(fecha);
    }

    private Categoria parsearCategoria(String etiqueta) {
        return Categoria.desdeEtiqueta(etiqueta)
                .orElseThrow(() -> new ErroresDeNegocio.DatosInvalidos("categoria",
                        "La categoría tiene que ser Deportes, Música, Fiesta o Teatro."));
    }

    private String slugUnico(String titulo) {
        String base = Textos.slug(titulo);
        String candidato = base;
        for (int n = 2; SLUGS_RESERVADOS.contains(candidato) || eventos.existeSlug(candidato); n++) {
            candidato = base + "-" + n;
        }
        return candidato;
    }

    private static String nuevoId(String prefijo) {
        return prefijo + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static EventoDto aDto(Evento e) {
        return new EventoDto(
                e.getId(),
                e.getSlug(),
                e.getTitulo(),
                e.getCategoria().etiqueta(),
                e.getVenue(),
                e.getFecha().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                e.getDescripcion(),
                e.isDestacado(),
                e.getImagenUrl(),
                e.isCancelado(),
                e.getCancelacionMotivo(),
                // Por orden, como los carga la base (@OrderBy). Después de una edición la lista en memoria
                // puede estar en otro (los sectores nuevos quedan al final), y la respuesta del PUT tiene que
                // ser igual a lo que dará el GET: mismo cuerpo, mismo ETag.
                e.getSectores().stream()
                        .sorted(Comparator.comparingInt(Sector::getOrden))
                        .map(ServicioDeEventos::aDto)
                        .toList());
    }

    static SectorDto aDto(Sector s) {
        return new SectorDto(s.getId(), s.getNombre(), s.getPrecio(), s.getCupo(), s.getVendidas());
    }
}
