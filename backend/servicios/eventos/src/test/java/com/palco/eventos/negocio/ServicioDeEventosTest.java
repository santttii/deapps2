package com.palco.eventos.negocio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.junit.jupiter.api.Test;

import com.palco.common.ErroresDeNegocio;
import com.palco.eventos.api.Dtos.EventoDto;
import com.palco.eventos.api.Dtos.NuevoEventoDto;
import com.palco.eventos.api.Dtos.NuevoSectorDto;
import com.palco.eventos.dominio.Categoria;
import com.palco.eventos.dominio.Evento;
import com.palco.eventos.dominio.Sector;

/**
 * Un evento que ya empezó no se crea ni vende más lugares, pero siempre
 * acepta que se le devuelvan. Sin servidor ni base: el {@code EntityManager}
 * es un doble y el reloj está fijo.
 */
class ServicioDeEventosTest {

    /** 21:30 UTC son las 18:30 en Buenos Aires: la hora de "ahora" en todos los casos. */
    private static final Instant AHORA = Instant.parse("2026-10-16T21:30:00Z");
    private static final LocalDateTime AHORA_LOCAL = LocalDateTime.of(2026, 10, 16, 18, 30);

    private static final String EVENTO = "ev-1";
    private static final String SECTOR = "sec-1-1";

    private final EntityManager em = mock(EntityManager.class);
    private final Query consulta = mock(Query.class, RETURNS_SELF);
    private final ServicioDeEventos servicio = new ServicioDeEventos();

    ServicioDeEventosTest() {
        servicio.em = em;
        servicio.reloj = Clock.fixed(AHORA, ServicioDeEventos.ZONA_DE_LOS_EVENTOS);
        when(em.createQuery(anyString())).thenReturn(consulta);
        when(consulta.getResultList()).thenReturn(List.of());
        when(consulta.executeUpdate()).thenReturn(1);
    }

    // --- Crear un evento ---

    @Test
    void crearUnEventoConFechaPasadaEsUnDatoInvalidoDelCampoFechaISO() {
        ErroresDeNegocio.DatosInvalidos error = assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.crearEvento(nuevoEvento(LocalDateTime.of(2020, 1, 1, 21, 0)), "org-1"));

        // DatosInvalidos responde 400 y su mapa de campos sale como "errores" del Problem Details.
        assertEquals("datos-invalidos", error.tipo());
        assertEquals(Map.of("fechaISO", error.getMessage()), error.campos());
        verify(em, never()).persist(any());
    }

    @Test
    void crearUnEventoParaEsteMismoMinutoTambienSeRechaza() {
        assertThrows(ErroresDeNegocio.DatosInvalidos.class,
                () -> servicio.crearEvento(nuevoEvento(AHORA_LOCAL), "org-1"));
    }

    @Test
    void crearUnEventoParaMasTardeSeGuarda() {
        // 21:00 en Buenos Aires todavía no pasó, aunque en UTC ya sean las 21:30.
        EventoDto creado = servicio.crearEvento(nuevoEvento(LocalDateTime.of(2026, 10, 16, 21, 0)), "org-1");

        assertEquals("2026-10-16T21:00:00", creado.fechaISO());
        verify(em).persist(any(Evento.class));
    }

    // --- Cambiar el cupo ---

    @Test
    void reservarLugaresDeUnEventoQueYaEmpezoEsEventoFinalizado() {
        sectorDeUnEventoEl(LocalDateTime.of(2026, 10, 16, 18, 0));

        ErroresDeNegocio.Conflicto error = assertThrows(ErroresDeNegocio.Conflicto.class,
                () -> servicio.actualizarCupo(EVENTO, SECTOR, 2));

        // Conflicto responde 409; el tipo sale como "type": "/problemas/evento-finalizado".
        assertEquals(TiposDeProblema.EVENTO_FINALIZADO, error.tipo());
        verify(consulta, never()).executeUpdate();
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
        sectorDeUnEventoEl(LocalDateTime.of(2020, 1, 1, 21, 0));

        servicio.actualizarCupo(EVENTO, SECTOR, -2);

        verify(consulta).setParameter("cantidad", -2);
        verify(consulta).executeUpdate();
    }

    @Test
    void reservarLugaresDeUnEventoQueTodaviaNoEmpezoSigueFuncionando() {
        sectorDeUnEventoEl(LocalDateTime.of(2026, 10, 16, 21, 0));

        servicio.actualizarCupo(EVENTO, SECTOR, 2);

        verify(consulta).setParameter("cantidad", 2);
        verify(consulta).executeUpdate();
    }

    @Test
    void unSectorQueNoEsDelEventoSigueSiendoNoEncontrado() {
        sectorDeUnEventoEl(LocalDateTime.of(2020, 1, 1, 21, 0));

        assertThrows(ErroresDeNegocio.NoEncontrado.class, () -> servicio.actualizarCupo("ev-2", SECTOR, 2));
    }

    private void sectorDeUnEventoEl(LocalDateTime fecha) {
        Evento evento = new Evento(EVENTO, "show", "Show", Categoria.MUSICA,
                "Estadio", fecha, "Descripción", null, "org-1");
        Sector sector = new Sector(SECTOR, evento, "Campo", 50_000, 100, 1);
        evento.agregarSector(sector);
        when(em.find(Sector.class, SECTOR)).thenReturn(sector);
    }

    private static NuevoEventoDto nuevoEvento(LocalDateTime fecha) {
        return new NuevoEventoDto("Show", "Música", "Estadio", fecha, "Descripción", null,
                List.of(new NuevoSectorDto("Campo", 50_000, 100)));
    }
}
