package com.palco.eventos.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.palco.common.ErroresDeNegocio;
import com.palco.eventos.api.Dtos.EventoDto;
import com.palco.eventos.api.Dtos.NuevoEventoDto;
import com.palco.eventos.api.Dtos.NuevoSectorDto;
import com.palco.eventos.api.Dtos.SectorDto;
import com.palco.eventos.datos.DevolucionDeCupoRepository;
import com.palco.eventos.datos.EventoRepository;
import com.palco.eventos.datos.SectorRepository;
import com.palco.eventos.dominio.Categoria;
import com.palco.eventos.dominio.DevolucionDeCupo;
import com.palco.eventos.dominio.Evento;
import com.palco.eventos.dominio.Sector;

/**
 * Las reglas del catálogo: el alta (fecha futura, categoría válida, slug
 * único), el detalle, el listado con sus filtros y el cupo (un evento que ya
 * empezó no vende más lugares, pero siempre acepta que se le devuelvan). Sin
 * servidor ni base: los repositorios son dobles y el reloj está fijo.
 */
class ServicioDeEventosTest {

    /** 21:30 UTC son las 18:30 en Buenos Aires: la hora de "ahora" en todos los casos. */
    private static final Instant AHORA = Instant.parse("2026-10-16T21:30:00Z");
    private static final LocalDateTime AHORA_LOCAL = LocalDateTime.of(2026, 10, 16, 18, 30);
    private static final LocalDateTime MAS_TARDE = LocalDateTime.of(2026, 10, 16, 21, 0);

    private static final String EVENTO = "ev-1";
    private static final String SECTOR = "sec-1-1";

    private final EventoRepository eventos = mock(EventoRepository.class);
    private final SectorRepository sectores = mock(SectorRepository.class);
    private final DevolucionDeCupoRepository devoluciones = mock(DevolucionDeCupoRepository.class);
    private final ServicioDeEventos servicio = new ServicioDeEventos();

    ServicioDeEventosTest() {
        servicio.eventos = eventos;
        servicio.sectores = sectores;
        servicio.devoluciones = devoluciones;
        when(devoluciones.buscar(any())).thenReturn(Optional.empty());
        servicio.reloj = Clock.fixed(AHORA, ServicioDeEventos.ZONA_DE_LOS_EVENTOS);
        when(sectores.sumarVendidas(any(), anyInt())).thenReturn(true);
    }

    // --- Crear un evento ---

    @Test
    void crearUnEventoConFechaPasadaEsUnDatoInvalidoDelCampoFechaISO() {
        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.crearEvento(nuevoEvento(LocalDateTime.of(2020, 1, 1, 21, 0)), "org-1"));

        // DatosInvalidos responde 400 y su mapa de campos sale como "errores" del Problem Details.
        assertEquals("datos-invalidos", error.tipo());
        assertEquals(Map.of("fechaISO", error.getMessage()), error.campos());
        verify(eventos, never()).guardar(any());
    }

    @Test
    void crearUnEventoParaEsteMismoMinutoTambienSeRechaza() {
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.crearEvento(nuevoEvento(AHORA_LOCAL), "org-1"));
    }

    @Test
    void crearUnEventoParaMasTardeSeGuarda() {
        // 21:00 en Buenos Aires todavía no pasó, aunque en UTC ya sean las 21:30.
        EventoDto creado = servicio.crearEvento(nuevoEvento(MAS_TARDE), "org-1");

        assertEquals("2026-10-16T21:00:00", creado.fechaISO());
        verify(eventos).guardar(any(Evento.class));
    }

    @Test
    void crearUnEventoConUnaCategoriaQueNoExisteEsUnDatoInvalidoDelCampoCategoria() {
        NuevoEventoDto nuevo = new NuevoEventoDto("Show", "Ópera", "Estadio", MAS_TARDE, "Descripción", null,
                List.of(new NuevoSectorDto("Campo", 50_000, 100)));

        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.crearEvento(nuevo, "org-1"));

        assertEquals(Map.of("categoria", "La categoría tiene que ser Fútbol, Música, Fiesta o Teatro."),
                error.campos());
        verify(eventos, never()).guardar(any());
    }

    @Test
    void laCategoriaSeComparaConLaEtiquetaExactaConAcento() {
        NuevoEventoDto sinAcento = new NuevoEventoDto("Show", "Musica", "Estadio", MAS_TARDE, "Descripción",
                null, List.of(new NuevoSectorDto("Campo", 50_000, 100)));

        assertThrows(ErroresDeNegocio.DatosInvalidos.class, () -> servicio.crearEvento(sinAcento, "org-1"));
    }

    @Test
    void guardaLosDatosSinEspaciosDeMasYANombreDelOrganizador() {
        NuevoEventoDto nuevo = new NuevoEventoDto("  El método Grönholm ", "Teatro", " Teatro San Marcos ",
                MAS_TARDE, " Comedia. ", "https://picsum.photos/x.jpg",
                List.of(new NuevoSectorDto(" Platea ", 30_000, 200), new NuevoSectorDto("Pullman", 20_000, 80)));

        EventoDto creado = servicio.crearEvento(nuevo, "org-1");
        Evento guardado = eventoGuardado();

        assertEquals("El método Grönholm", guardado.getTitulo());
        assertEquals("Teatro San Marcos", guardado.getVenue());
        assertEquals("Comedia.", guardado.getDescripcion());
        assertEquals(Categoria.TEATRO, guardado.getCategoria());
        assertEquals("org-1", guardado.getOrganizadorId());
        assertEquals("https://picsum.photos/x.jpg", guardado.getImagenUrl());
        assertFalse(guardado.isDestacado());
        assertTrue(guardado.getId().startsWith("ev-"), guardado.getId());
        // Hacia afuera la categoría sale con su etiqueta, como la usa el frontend.
        assertEquals("Teatro", creado.categoria());
    }

    @Test
    void losSectoresQuedanEnElOrdenDelPedidoSinVentasYDelEvento() {
        NuevoEventoDto nuevo = new NuevoEventoDto("Show", "Música", "Estadio", MAS_TARDE, "Descripción", null,
                List.of(new NuevoSectorDto(" Platea ", 30_000, 200), new NuevoSectorDto("Campo", 20_000, 80)));

        EventoDto creado = servicio.crearEvento(nuevo, "org-1");
        Evento guardado = eventoGuardado();

        List<Sector> guardados = guardado.getSectores();
        assertEquals(2, guardados.size());
        assertEquals("Platea", guardados.get(0).getNombre());
        assertEquals(1, guardados.get(0).getOrden());
        assertEquals("Campo", guardados.get(1).getNombre());
        assertEquals(2, guardados.get(1).getOrden());
        guardados.forEach(s -> {
            assertSame(guardado, s.getEvento());
            assertEquals(0, s.getVendidas());
            assertTrue(s.getId().startsWith("sec-"), s.getId());
        });
        assertEquals(List.of(new SectorDto(guardados.get(0).getId(), "Platea", 30_000, 200, 0),
                new SectorDto(guardados.get(1).getId(), "Campo", 20_000, 80, 0)), creado.sectores());
    }

    @Test
    void elSlugSaleDelTituloSinAcentosNiSignos() {
        NuevoEventoDto nuevo = new NuevoEventoDto("Kiroshi — Tour Continental!", "Música", "Arena", MAS_TARDE,
                "Descripción", null, List.of(new NuevoSectorDto("Campo", 50_000, 100)));

        assertEquals("kiroshi-tour-continental", servicio.crearEvento(nuevo, "org-1").slug());
    }

    @Test
    void siElSlugYaExisteSeLeSumaElPrimerNumeroLibre() {
        when(eventos.existeSlug("show")).thenReturn(true);
        when(eventos.existeSlug("show-2")).thenReturn(true);

        EventoDto creado = servicio.crearEvento(nuevoEvento(MAS_TARDE), "org-1");

        assertEquals("show-3", creado.slug());
        assertEquals("show-3", eventoGuardado().getSlug());
    }

    @Test
    void unTituloSinLetrasNiNumerosQuedaConElSlugEvento() {
        NuevoEventoDto nuevo = new NuevoEventoDto("¡¡¡!!!", "Fiesta", "Club", MAS_TARDE, "Descripción", null,
                List.of(new NuevoSectorDto("General", 10_000, 50)));

        assertEquals("evento", servicio.crearEvento(nuevo, "org-1").slug());
    }

    @Test
    void ningunEventoRecibeElSlugMiosQueEsLaRutaDeLosEventosPropios() {
        NuevoEventoDto nuevo = new NuevoEventoDto("Míos", "Música", "Arena", MAS_TARDE, "Descripción", null,
                List.of(new NuevoSectorDto("Campo", 50_000, 100)));

        assertEquals("mios-2", servicio.crearEvento(nuevo, "org-1").slug());
    }

    private Evento eventoGuardado() {
        ArgumentCaptor<Evento> guardado = ArgumentCaptor.forClass(Evento.class);
        verify(eventos).guardar(guardado.capture());
        return guardado.getValue();
    }

    // --- Ver el detalle ---

    @Test
    void elDetalleDevuelveElEventoConSusSectores() {
        Evento evento = evento(EVENTO, "show", "Show", Categoria.MUSICA, "Estadio", MAS_TARDE);
        Sector campo = new Sector(SECTOR, evento, "Campo", 50_000, 100, 1);
        evento.agregarSector(campo);
        when(eventos.buscarPorSlug("show")).thenReturn(Optional.of(evento));

        EventoDto detalle = servicio.obtenerDetalle("show");

        assertEquals(new EventoDto(EVENTO, "show", "Show", "Música", "Estadio", "2026-10-16T21:00:00",
                "Descripción", false, null, List.of(new SectorDto(SECTOR, "Campo", 50_000, 100, 0))), detalle);
    }

    @Test
    void unSlugQueNoExisteEsNoEncontrado() {
        when(eventos.buscarPorSlug("no-existe")).thenReturn(Optional.empty());

        ErroresDeNegocio.NoEncontrado error = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> servicio.obtenerDetalle("no-existe"));

        // NoEncontrado responde 404 con "type": "/problemas/no-encontrado".
        assertEquals("no-encontrado", error.tipo());
        assertEquals("No existe el evento \"no-existe\".", error.getMessage());
    }

    // --- Listar el catálogo ---

    private final Evento kiroshi = evento("ev-2", "kiroshi", "Kiroshi — Tour", Categoria.MUSICA,
            "Arena Central", MAS_TARDE);
    private final Evento gronholm = evento("ev-4", "gronholm", "El método Grönholm", Categoria.TEATRO,
            "Teatro San Marcos", MAS_TARDE.plusDays(1));

    @Test
    void elListadoSinFiltrosDevuelveTodoEnElOrdenDelRepositorio() {
        when(eventos.listar(null)).thenReturn(List.of(kiroshi, gronholm));

        assertEquals(List.of("kiroshi", "gronholm"), slugs(servicio.listarEventos(null, null)));
    }

    @Test
    void listarConUnaCategoriaEnBlancoEsSinFiltro() {
        when(eventos.listar(null)).thenReturn(List.of(kiroshi, gronholm));

        assertEquals(2, servicio.listarEventos("  ", "").size());
        verify(eventos).listar(isNull());
    }

    @Test
    void laCategoriaSePasaAlRepositorioComoEnumerado() {
        when(eventos.listar(Categoria.TEATRO)).thenReturn(List.of(gronholm));

        assertEquals(List.of("gronholm"), slugs(servicio.listarEventos("Teatro", null)));
    }

    @Test
    void listarConUnaCategoriaQueNoExisteEsUnDatoInvalido() {
        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.listarEventos("Ópera", null));

        assertTrue(error.campos().containsKey("categoria"));
        verify(eventos, never()).listar(any());
    }

    @Test
    void laBusquedaNoDistingueAcentosNiMayusculas() {
        when(eventos.listar(null)).thenReturn(List.of(kiroshi, gronholm));

        assertEquals(List.of("gronholm"), slugs(servicio.listarEventos(null, "  GRONHOLM ")));
        assertEquals(List.of("kiroshi"), slugs(servicio.listarEventos(null, "kiróshi")));
    }

    @Test
    void laBusquedaTambienMiraElLugar() {
        when(eventos.listar(null)).thenReturn(List.of(kiroshi, gronholm));

        assertEquals(List.of("kiroshi"), slugs(servicio.listarEventos(null, "arena")));
    }

    @Test
    void losDosFiltrosDelListadoSeCombinan() {
        when(eventos.listar(Categoria.MUSICA)).thenReturn(List.of(kiroshi));

        assertEquals(List.of(), slugs(servicio.listarEventos("Música", "teatro")));
        assertEquals(List.of("kiroshi"), slugs(servicio.listarEventos("Música", "tour")));
    }

    @Test
    void losEventosDelOrganizadorSalenDelRepositorioPorSuId() {
        when(eventos.listarDelOrganizador("org-1")).thenReturn(List.of(kiroshi, gronholm));

        assertEquals(List.of("kiroshi", "gronholm"), slugs(servicio.listarDelOrganizador("org-1")));
        assertEquals(List.of(), servicio.listarDelOrganizador("org-2"));
    }

    private static List<String> slugs(List<EventoDto> lista) {
        return lista.stream().map(EventoDto::slug).toList();
    }

    // --- Cambiar el cupo ---

    @Test
    void reservarLugaresDeUnEventoQueYaEmpezoEsEventoFinalizado() {
        sectorDeUnEventoEl(LocalDateTime.of(2026, 10, 16, 18, 0));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.actualizarCupo(EVENTO, SECTOR, 2));

        // Conflicto responde 409; el tipo sale como "type": "/problemas/evento-finalizado".
        assertEquals(TiposDeProblema.EVENTO_FINALIZADO, error.tipo());
        verify(sectores, never()).sumarVendidas(any(), anyInt());
    }

    @Test
    void elEventoEmpiezaALaHoraDeSuFecha() {
        sectorDeUnEventoEl(AHORA_LOCAL);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.actualizarCupo(EVENTO, SECTOR, 1));

        assertEquals(TiposDeProblema.EVENTO_FINALIZADO, error.tipo());
    }

    @Test
    void devolverLugaresDeUnEventoQueYaEmpezoSigueFuncionando() {
        // Cancelar o vencer una compra no puede fallar porque el evento ya empezó.
        Sector sector = sectorDeUnEventoEl(LocalDateTime.of(2020, 1, 1, 21, 0));

        servicio.actualizarCupo(EVENTO, SECTOR, -2);

        verify(sectores).sumarVendidas(sector, -2);
    }

    @Test
    void reservarLugaresDeUnEventoQueTodaviaNoEmpezoSigueFuncionando() {
        Sector sector = sectorDeUnEventoEl(MAS_TARDE);

        SectorDto resultado = servicio.actualizarCupo(EVENTO, SECTOR, 2);

        verify(sectores).sumarVendidas(sector, 2);
        assertEquals(new SectorDto(SECTOR, "Campo", 50_000, 100, 0), resultado);
    }

    @Test
    void sinLugaresSuficientesEsUnConflictoConElNombreDelSector() {
        Sector sector = sectorDeUnEventoEl(MAS_TARDE);
        when(sectores.sumarVendidas(sector, 3)).thenReturn(false);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.actualizarCupo(EVENTO, SECTOR, 3));

        assertEquals("conflicto", error.tipo());
        assertEquals("No quedan suficientes entradas en Campo.", error.getMessage());
    }

    @Test
    void liberarMasDeLasVendidasEsUnConflicto() {
        Sector sector = sectorDeUnEventoEl(MAS_TARDE);
        when(sectores.sumarVendidas(sector, -5)).thenReturn(false);

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.actualizarCupo(EVENTO, SECTOR, -5));

        assertEquals("conflicto", error.tipo());
        assertEquals("No se pueden liberar más entradas de las vendidas.", error.getMessage());
    }

    @Test
    void unaCantidadCeroDevuelveElSectorSinTocarLaBase() {
        // Ni siquiera mira la fecha: un evento empezado también responde.
        sectorDeUnEventoEl(LocalDateTime.of(2020, 1, 1, 21, 0));

        SectorDto resultado = servicio.actualizarCupo(EVENTO, SECTOR, 0);

        assertEquals(new SectorDto(SECTOR, "Campo", 50_000, 100, 0), resultado);
        verify(sectores, never()).sumarVendidas(any(), anyInt());
    }

    @Test
    void unSectorQueNoEsDelEventoSigueSiendoNoEncontrado() {
        sectorDeUnEventoEl(LocalDateTime.of(2020, 1, 1, 21, 0));

        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> servicio.actualizarCupo("ev-2", SECTOR, 2));
        verify(sectores, never()).sumarVendidas(any(), anyInt());
    }

    @Test
    void unSectorQueNoExisteEsNoEncontrado() {
        when(sectores.buscarPorId("sec-x")).thenReturn(Optional.empty());

        ErroresDeNegocio.NoEncontrado error = assertThrows(ErroresDeNegocio.NoEncontrado.class,
                () -> servicio.actualizarCupo(EVENTO, "sec-x", 2));

        assertEquals("No existe ese sector en el evento.", error.getMessage());
    }

    // --- Devolver el cupo con clave (seguro de repetir) ---

    private static final String CLAVE = "devolucion:V-1:" + SECTOR;

    @Test
    void laPrimeraDevolucionConUnaClaveRestaYLaGuarda() {
        Sector sector = sectorDeUnEventoEl(MAS_TARDE);

        servicio.devolverCupo(EVENTO, SECTOR, 2, CLAVE);

        verify(sectores).sumarVendidas(sector, -2);
        ArgumentCaptor<DevolucionDeCupo> guardada = ArgumentCaptor.forClass(DevolucionDeCupo.class);
        verify(devoluciones).registrar(guardada.capture());
        assertEquals(CLAVE, guardada.getValue().getClave());
        assertEquals(SECTOR, guardada.getValue().getSectorId());
        assertEquals(2, guardada.getValue().getCantidad());
        assertEquals(AHORA_LOCAL, guardada.getValue().getAplicada());
    }

    @Test
    void repetirUnaDevolucionYaAplicadaNoRestaOtraVez() {
        // Ventas devolvió, no supo la respuesta y repite: si se restara de nuevo, ese lugar se vendería dos veces.
        Sector sector = sectorDeUnEventoEl(MAS_TARDE);
        when(devoluciones.buscar(CLAVE)).thenReturn(Optional.of(new DevolucionDeCupo(CLAVE, SECTOR, 2, AHORA_LOCAL)));

        SectorDto resultado = servicio.devolverCupo(EVENTO, SECTOR, 2, CLAVE);

        assertEquals(new SectorDto(SECTOR, "Campo", 50_000, 100, 0), resultado);
        verify(sectores, never()).sumarVendidas(any(), anyInt());
        verify(devoluciones, never()).registrar(any());
    }

    @Test
    void unaClaveUsadaParaOtraCantidadEsClaveReutilizada() {
        sectorDeUnEventoEl(MAS_TARDE);
        when(devoluciones.buscar(CLAVE)).thenReturn(Optional.of(new DevolucionDeCupo(CLAVE, SECTOR, 3, AHORA_LOCAL)));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.devolverCupo(EVENTO, SECTOR, 2, CLAVE));

        assertEquals(TiposDeProblema.CLAVE_REUTILIZADA, error.tipo());
        verify(sectores, never()).sumarVendidas(any(), anyInt());
    }

    @Test
    void unaDevolucionConClaveDeUnEventoQueYaEmpezoTambienSeAplica() {
        Sector sector = sectorDeUnEventoEl(LocalDateTime.of(2020, 1, 1, 21, 0));

        servicio.devolverCupo(EVENTO, SECTOR, 1, CLAVE);

        verify(sectores).sumarVendidas(sector, -1);
        verify(devoluciones).registrar(any());
    }

    @Test
    void siNoSePuedeRestarNoSeGuardaLaClave() {
        // Si se guardara, el reintento creería que ya se devolvió.
        Sector sector = sectorDeUnEventoEl(MAS_TARDE);
        when(sectores.sumarVendidas(sector, -5)).thenReturn(false);

        assertThrows(ErroresDeNegocio.Conflicto.class, () -> servicio.devolverCupo(EVENTO, SECTOR, 5, CLAVE));

        verify(devoluciones, never()).registrar(any());
    }

    @Test
    void unaClaveConCantidadParaReservarEsUnDatoInvalido() {
        sectorDeUnEventoEl(MAS_TARDE);

        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.devolverCupo(EVENTO, SECTOR, 0, CLAVE));

        assertEquals(Map.of("cantidad", error.getMessage()), error.campos());
        verify(sectores, never()).sumarVendidas(any(), anyInt());
    }

    @Test
    void devolverConClaveUnSectorDeOtroEventoEsNoEncontrado() {
        sectorDeUnEventoEl(MAS_TARDE);

        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> servicio.devolverCupo("ev-2", SECTOR, 1, CLAVE));
        verify(devoluciones, never()).registrar(any());
    }

    private Sector sectorDeUnEventoEl(LocalDateTime fecha) {
        Evento evento = evento(EVENTO, "show", "Show", Categoria.MUSICA, "Estadio", fecha);
        Sector sector = new Sector(SECTOR, evento, "Campo", 50_000, 100, 1);
        evento.agregarSector(sector);
        when(sectores.buscarPorId(SECTOR)).thenReturn(Optional.of(sector));
        return sector;
    }

    private static Evento evento(String id, String slug, String titulo, Categoria categoria, String venue,
            LocalDateTime fecha) {
        return new Evento(id, slug, titulo, categoria, venue, fecha, "Descripción", null, "org-1");
    }

    private static NuevoEventoDto nuevoEvento(LocalDateTime fecha) {
        return new NuevoEventoDto("Show", "Música", "Estadio", fecha, "Descripción", null,
                List.of(new NuevoSectorDto("Campo", 50_000, 100)));
    }
}
