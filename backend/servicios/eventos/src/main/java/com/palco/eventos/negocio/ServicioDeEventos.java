package com.palco.eventos.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import jakarta.ejb.Stateless;
import jakarta.inject.Inject;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.Textos;
import com.palco.eventos.api.Dtos.EventoDto;
import com.palco.eventos.api.Dtos.NuevoEventoDto;
import com.palco.eventos.api.Dtos.NuevoSectorDto;
import com.palco.eventos.api.Dtos.SectorDto;
import com.palco.eventos.datos.EventoRepository;
import com.palco.eventos.datos.SectorRepository;
import com.palco.eventos.dominio.Categoria;
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

    @Inject
    EventoRepository eventos;

    @Inject
    SectorRepository sectores;

    /** No es privado para que el test fije la hora. */
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
    public SectorDto actualizarCupo(String eventoId, String sectorId, int cantidad) {
        Sector sector = sectores.buscarPorId(sectorId).orElse(null);
        if (sector == null || !sector.getEvento().getId().equals(eventoId)) {
            throw new ErroresDeNegocio.NoEncontrado("No existe ese sector en el evento.");
        }
        if (cantidad == 0) {
            return aDto(sector);
        }
        // Solo se frena la reserva. Devolver lugares (cantidad negativa) tiene que andar siempre:
        // cancelar o vencer una compra no puede fallar porque el evento ya empezó.
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

    /** Un evento empezó cuando llega la hora de su fecha, medida en la zona de los eventos. */
    private boolean yaEmpezo(LocalDateTime fecha) {
        return !LocalDateTime.now(reloj).isBefore(fecha);
    }

    private Categoria parsearCategoria(String etiqueta) {
        return Categoria.desdeEtiqueta(etiqueta)
                .orElseThrow(() -> new ErroresDeNegocio.DatosInvalidos("categoria",
                        "La categoría tiene que ser Fútbol, Música, Fiesta o Teatro."));
    }

    private String slugUnico(String titulo) {
        String base = Textos.slug(titulo);
        String candidato = base;
        for (int n = 2; eventos.existeSlug(candidato); n++) {
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
                e.getSectores().stream().map(ServicioDeEventos::aDto).toList());
    }

    static SectorDto aDto(Sector s) {
        return new SectorDto(s.getId(), s.getNombre(), s.getPrecio(), s.getCupo(), s.getVendidas());
    }
}
