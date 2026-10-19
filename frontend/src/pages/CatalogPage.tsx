import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useApp } from '../store/AppContext';
import { mensajeDeError } from '../lib/api';
import { money } from '../lib/format';
import { useDebouncedValue } from '../lib/useDebouncedValue';
import { useReventas } from '../lib/useReventas';
import { listarEventos } from '../servicios/eventos';
import EventCard from '../components/EventCard';
import ImagePlaceholder from '../components/ImagePlaceholder';
import ListingCard from '../components/ListingCard';
import Button from '../components/Button';
import Skeleton from '../components/Skeleton';
import StatusMessage from '../components/StatusMessage';
import PageTransition from '../components/PageTransition';
import type { Categoria, Evento } from '../types';

const CATEGORIAS: { label: string; value: Categoria | 'Todos' }[] = [
  { label: 'Todos', value: 'Todos' },
  { label: 'Deportes', value: 'Fútbol' },
  { label: 'Música', value: 'Música' },
  { label: 'Fiestas', value: 'Fiesta' },
  { label: 'Teatro', value: 'Teatro' },
];

// Respuesta de un pedido con filtros, guardada junto con los filtros a los que corresponde.
interface ResultadoFiltrado {
  filtros: string;
  eventos: Evento[];
  error: string;
}

export default function CatalogPage() {
  const { state, recargarCatalogo } = useApp();
  const [categoria, setCategoria] = useState<Categoria | 'Todos'>('Todos');
  const [busqueda, setBusqueda] = useState('');
  const [resultado, setResultado] = useState<ResultadoFiltrado | null>(null);
  const [reintentos, setReintentos] = useState(0);
  // Reventa lista por evento: se piden las publicaciones activas de cada evento del catálogo. Si
  // Reventa no responde, la franja y las marcas de reventa no se muestran y el catálogo sigue.
  const { publicaciones: publicacionesActivas } = useReventas(state.eventos.map((e) => e.id));
  const eventosConReventa = new Set(publicacionesActivas.map((p) => p.eventoId));

  const termino = busqueda.trim();
  const terminoDemorado = useDebouncedValue(termino, 300);
  // Lo que se le pide al servidor va 300 ms detrás de lo que se tipea. Al vaciar el campo no se espera.
  const terminoAPedir = termino === '' ? '' : terminoDemorado;
  const hayFiltros = categoria !== 'Todos' || termino !== '';
  const filtros = `${categoria}|${termino}`;

  // Con categoría o búsqueda filtra el servidor. Sin filtros no hace falta pedir nada: la
  // grilla usa el catálogo completo, que ya está cargado.
  useEffect(() => {
    // Mientras se tipea, el término demorado va atrasado: se espera a que alcance al campo.
    if (!hayFiltros || terminoAPedir !== termino) return;
    let vigente = true;
    listarEventos({ categoria: categoria === 'Todos' ? undefined : categoria, q: termino })
      .then((eventos) => {
        if (vigente) setResultado({ filtros, eventos, error: '' });
      })
      .catch((error: unknown) => {
        if (vigente) setResultado({ filtros, eventos: [], error: mensajeDeError(error) });
      });
    // Si los filtros cambian antes de que llegue la respuesta, esa respuesta se descarta.
    return () => {
      vigente = false;
    };
  }, [hayFiltros, categoria, termino, terminoAPedir, filtros, reintentos]);

  // Qué muestra la grilla: el catálogo completo, o la respuesta del servidor para los filtros
  // actuales. Una respuesta de otros filtros no sirve: mientras llega la nueva, está cargando.
  let grilla: { estado: 'cargando' | 'listo' | 'error'; eventos: Evento[]; error: string };
  if (!hayFiltros) {
    grilla = { estado: state.catalogo.estado, eventos: state.eventos, error: state.catalogo.error };
  } else if (!resultado || resultado.filtros !== filtros) {
    grilla = { estado: 'cargando', eventos: [], error: '' };
  } else {
    grilla = { estado: resultado.error ? 'error' : 'listo', eventos: resultado.eventos, error: resultado.error };
  }

  function reintentar() {
    // Vuelve a pedir el catálogo completo (del que salen el destacado y la reventa) y repite la búsqueda.
    recargarCatalogo();
    setResultado(null);
    setReintentos((n) => n + 1);
  }

  function limpiarFiltros() {
    setBusqueda('');
    setCategoria('Todos');
  }

  // El destacado y la franja de reventa salen del catálogo completo, no de la grilla filtrada:
  // así no desaparecen al elegir una categoría.
  const eventoDestacado = state.eventos.find((e) => e.destacado);
  const franjaReventa = publicacionesActivas
    .flatMap((publicacion) => {
      const evento = state.eventos.find((e) => e.id === publicacion.eventoId);
      const sector = evento?.sectores.find((s) => s.id === publicacion.sectorId);
      return evento && sector ? [{ publicacion, evento, sector }] : [];
    })
    .slice(0, 4);

  let tituloVacio = 'Todavía no hay eventos publicados';
  if (termino) tituloVacio = `Sin resultados para «${termino}»`;
  else if (hayFiltros) tituloVacio = 'No hay eventos en esta categoría';

  return (
    <PageTransition>
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        {!termino && state.catalogo.estado === 'cargando' && <Skeleton className="mb-14 min-h-[420px] w-full" />}
        {!termino && state.catalogo.estado !== 'cargando' && eventoDestacado && (
          <section className="mb-14 grid grid-cols-1 gap-8 md:grid-cols-2">
            <div className="flex flex-col justify-center gap-4">
              <span className="font-mono-label text-[11px] text-accent">
                Destacado · {eventoDestacado.categoria}
              </span>
              <h1 className="text-[clamp(38px,5vw,62px)] font-bold leading-[0.96] tracking-[-0.045em] text-ink">
                {eventoDestacado.titulo}
              </h1>
              <p className="max-w-md text-[15px] text-graphite">{eventoDestacado.descripcion}</p>
              <p className="text-lg font-semibold text-ink">
                Desde {money(Math.min(...eventoDestacado.sectores.map((s) => s.precio)))}
              </p>
              <div>
                <Link to={`/evento/${eventoDestacado.slug}`}>
                  <Button>Ver entradas</Button>
                </Link>
              </div>
            </div>
            <ImagePlaceholder
              etiqueta="foto del evento"
              className="min-h-[420px] w-full"
              src={eventoDestacado.imagenUrl}
            />
          </section>
        )}

        <div className="mb-8 flex flex-col gap-4">
          <label htmlFor="buscador" className="sr-only">
            Buscar eventos
          </label>
          <input
            id="buscador"
            type="search"
            placeholder="Buscar por evento o venue"
            value={busqueda}
            onChange={(e) => setBusqueda(e.target.value)}
            className="min-h-[44px] w-full max-w-md border border-edge bg-paper px-4 py-2.5 text-[15px] placeholder:text-faint focus:border-accent"
          />
          <div className="flex gap-2 overflow-x-auto">
            {CATEGORIAS.map((c) => (
              <button
                key={c.value}
                type="button"
                onClick={() => setCategoria(c.value)}
                className={`font-mono-label whitespace-nowrap px-4 py-2.5 text-[11px] ${
                  categoria === c.value ? 'bg-ink text-paper' : 'border border-edge text-graphite'
                }`}
              >
                {c.label}
              </button>
            ))}
          </div>
        </div>

        <h2 className="mb-6 text-[clamp(26px,3vw,36px)] font-semibold tracking-tight text-ink">
          {termino ? 'Resultados' : 'Próximos eventos'}
        </h2>

        {grilla.estado === 'cargando' && (
          <div
            className="grid grid-cols-[repeat(auto-fill,minmax(min(320px,100%),1fr))] gap-9"
            role="status"
            aria-label="Cargando eventos"
          >
            {[0, 1, 2].map((i) => (
              <div key={i} className="flex flex-col gap-3">
                <Skeleton className="aspect-[16/10] w-full" />
                <Skeleton className="h-4 w-1/3" />
                <Skeleton className="h-6 w-2/3" />
              </div>
            ))}
          </div>
        )}

        {grilla.estado === 'error' && (
          <StatusMessage
            titulo="No pudimos cargar los eventos"
            texto={grilla.error}
            accion="Reintentar"
            onAccion={reintentar}
          />
        )}

        {grilla.estado === 'listo' && grilla.eventos.length === 0 && (
          <StatusMessage
            titulo={tituloVacio}
            texto={termino ? 'Probá con otro término o quitá los filtros.' : undefined}
            accion={hayFiltros ? 'Limpiar filtros' : undefined}
            onAccion={limpiarFiltros}
          />
        )}

        {grilla.estado === 'listo' && grilla.eventos.length > 0 && (
          <div className="grid grid-cols-[repeat(auto-fill,minmax(min(320px,100%),1fr))] gap-9">
            {grilla.eventos.map((evento, i) => (
              <EventCard
                key={evento.id}
                evento={evento}
                tieneReventa={eventosConReventa.has(evento.id)}
                index={i}
              />
            ))}
          </div>
        )}
      </div>

      {franjaReventa.length > 0 && (
        <section className="bg-ink py-14">
          <div className="mx-auto max-w-[1240px] px-7">
            <span className="font-mono-label text-[11px] text-accentLt">Canal oficial</span>
            <h2 className="mt-2 text-[clamp(26px,3vw,36px)] font-semibold tracking-tight text-paper">
              Reventa entre hinchas, con techo
            </h2>
            <div className="mt-8 grid grid-cols-[repeat(auto-fit,minmax(min(300px,100%),1fr))] gap-6">
              {franjaReventa.map(({ publicacion, evento, sector }) => (
                <ListingCard
                  key={publicacion.id}
                  publicacion={publicacion}
                  evento={evento}
                  sector={sector}
                  href={`/reventa?evento=${evento.slug}`}
                />
              ))}
            </div>
          </div>
        </section>
      )}
    </PageTransition>
  );
}
