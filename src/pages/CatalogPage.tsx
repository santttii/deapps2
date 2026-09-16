import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useApp } from '../store/AppContext';
import { money, normalizar } from '../lib/format';
import EventCard from '../components/EventCard';
import ImagePlaceholder from '../components/ImagePlaceholder';
import ListingCard from '../components/ListingCard';
import Button from '../components/Button';
import PageTransition from '../components/PageTransition';
import type { Categoria } from '../types';

const CATEGORIAS: { label: string; value: Categoria | 'Todos' }[] = [
  { label: 'Todos', value: 'Todos' },
  { label: 'Deportes', value: 'Fútbol' },
  { label: 'Música', value: 'Música' },
  { label: 'Fiestas', value: 'Fiesta' },
  { label: 'Teatro', value: 'Teatro' },
];

export default function CatalogPage() {
  const { state } = useApp();
  const [categoria, setCategoria] = useState<Categoria | 'Todos'>('Todos');
  const [busqueda, setBusqueda] = useState('');

  const eventoDestacado = state.eventos.find((e) => e.destacado);

  const eventosFiltrados = useMemo(() => {
    const q = normalizar(busqueda.trim());
    return state.eventos.filter((e) => {
      const matchCategoria = categoria === 'Todos' || e.categoria === categoria;
      const matchBusqueda =
        q.length === 0 || normalizar(e.titulo).includes(q) || normalizar(e.venue).includes(q);
      return matchCategoria && matchBusqueda;
    });
  }, [state.eventos, categoria, busqueda]);

  const publicacionesActivas = state.publicaciones.filter((p) => p.estado === 'activa');
  const eventosConReventa = new Set(publicacionesActivas.map((p) => p.eventoId));
  const hayBusquedaActiva = busqueda.trim().length > 0;

  return (
    <PageTransition>
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        {!hayBusquedaActiva && eventoDestacado && (
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
            <ImagePlaceholder etiqueta="foto del evento" className="min-h-[420px] w-full" />
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
          {hayBusquedaActiva ? 'Resultados' : 'Próximos eventos'}
        </h2>

        {eventosFiltrados.length === 0 ? (
          <div className="border border-dashed border-edge p-14 text-center">
            <p className="text-lg font-medium text-ink">Sin resultados para «{busqueda}»</p>
            <p className="mt-2 text-sm text-muted">Probá con otro término o quitá los filtros.</p>
            <Button
              variant="secondary"
              className="mt-5"
              onClick={() => {
                setBusqueda('');
                setCategoria('Todos');
              }}
            >
              Limpiar filtros
            </Button>
          </div>
        ) : (
          <div className="grid grid-cols-[repeat(auto-fill,minmax(min(320px,100%),1fr))] gap-9">
            {eventosFiltrados.map((evento, i) => (
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

      {publicacionesActivas.length > 0 && (
        <section className="bg-ink py-14">
          <div className="mx-auto max-w-[1240px] px-7">
            <span className="font-mono-label text-[11px] text-accentLt">Canal oficial</span>
            <h2 className="mt-2 text-[clamp(26px,3vw,36px)] font-semibold tracking-tight text-paper">
              Reventa entre hinchas, con techo
            </h2>
            <div className="mt-8 grid grid-cols-[repeat(auto-fit,minmax(min(300px,100%),1fr))] gap-6">
              {publicacionesActivas.slice(0, 4).map((p) => {
                const evento = state.eventos.find((e) => e.id === p.eventoId);
                const sector = evento?.sectores.find((s) => s.id === p.sectorId);
                if (!evento || !sector) return null;
                return (
                  <ListingCard
                    key={p.id}
                    publicacion={p}
                    evento={evento}
                    sector={sector}
                    href={`/reventa?evento=${evento.slug}`}
                  />
                );
              })}
            </div>
          </div>
        </section>
      )}
    </PageTransition>
  );
}
