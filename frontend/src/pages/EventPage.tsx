import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { useApp } from '../store/AppContext';
import { ErrorApi, mensajeDeError } from '../lib/api';
import { fechaHora, money } from '../lib/format';
import { obtenerEvento } from '../servicios/eventos';
import { MAX_ENTRADAS_POR_ORDEN } from '../types';
import { useAnimatedNumber } from '../lib/useAnimatedNumber';
import ImagePlaceholder from '../components/ImagePlaceholder';
import Button from '../components/Button';
import Skeleton from '../components/Skeleton';
import StatusMessage from '../components/StatusMessage';
import PageTransition from '../components/PageTransition';

// Por qué falló el pedido del detalle, junto con el slug al que corresponde.
interface FalloDeCarga {
  slug: string;
  noExiste: boolean;
  mensaje: string;
}

export default function EventPage() {
  const { slug } = useParams();
  const { state, dispatch, usuarioActual } = useApp();
  const navigate = useNavigate();
  const evento = state.eventos.find((e) => e.slug === slug);
  const [cantidades, setCantidades] = useState<Record<string, number>>({});
  const [fallo, setFallo] = useState<FalloDeCarga | null>(null);
  const [reintentos, setReintentos] = useState(0);

  // Si el evento ya está en el catálogo cargado se muestra enseguida. En paralelo se pide su
  // detalle al servidor: trae el stock actual y deja el evento en el estado para el checkout.
  useEffect(() => {
    if (!slug) return;
    let vigente = true;
    obtenerEvento(slug)
      .then((recibido) => {
        if (vigente) dispatch({ type: 'EVENTO_RECIBIDO', payload: recibido });
      })
      .catch((error: unknown) => {
        if (!vigente) return;
        const noExiste = error instanceof ErrorApi && error.estado === 404;
        setFallo({ slug, noExiste, mensaje: mensajeDeError(error) });
      });
    return () => {
      vigente = false;
    };
  }, [slug, dispatch, reintentos]);

  const publicacionesEvento = useMemo(
    () => (evento ? state.publicaciones.filter((p) => p.eventoId === evento.id && p.estado === 'activa') : []),
    [evento, state.publicaciones],
  );

  const totalEntradas = Object.values(cantidades).reduce((a, b) => a + b, 0);
  const total = evento ? evento.sectores.reduce((acc, s) => acc + (cantidades[s.id] ?? 0) * s.precio, 0) : 0;
  const totalMostrado = useAnimatedNumber(total, 300);

  const falloActual = fallo?.slug === slug ? fallo : null;

  if (falloActual?.noExiste) {
    return (
      <PageTransition>
        <div className="mx-auto max-w-[1240px] px-7 py-10">
          <StatusMessage
            titulo="No encontramos ese evento"
            texto="Puede que el enlace esté mal escrito o que el evento ya no esté publicado."
            accion="Ver el catálogo"
            onAccion={() => navigate('/')}
          />
        </div>
      </PageTransition>
    );
  }

  // Sin el evento en memoria (se entró por un enlace directo): cargando, o error con reintento.
  if (!evento) {
    return (
      <PageTransition>
        <div className="mx-auto max-w-[1240px] px-7 py-10">
          {falloActual ? (
            <StatusMessage
              titulo="No pudimos cargar el evento"
              texto={falloActual.mensaje}
              accion="Reintentar"
              onAccion={() => {
                setFallo(null);
                setReintentos((n) => n + 1);
              }}
            />
          ) : (
            <div className="flex flex-col gap-4" role="status" aria-label="Cargando evento">
              <Skeleton className="min-h-[320px] w-full" />
              <Skeleton className="h-10 w-2/3" />
              <Skeleton className="h-4 w-1/3" />
            </div>
          )}
        </div>
      </PageTransition>
    );
  }

  function ajustar(sectorId: string, delta: number, cupoDisponible: number) {
    setCantidades((prev) => {
      const actual = prev[sectorId] ?? 0;
      const nuevoTotal = totalEntradas + delta;
      if (delta > 0 && (nuevoTotal > MAX_ENTRADAS_POR_ORDEN || actual + 1 > cupoDisponible)) return prev;
      const nuevo = Math.max(0, actual + delta);
      return { ...prev, [sectorId]: nuevo };
    });
  }

  function continuarCheckout() {
    if (!evento) return;
    const items = Object.entries(cantidades)
      .filter(([, cant]) => cant > 0)
      .map(([sectorId, cantidad]) => ({ sectorId, cantidad }));
    dispatch({
      type: 'SET_CARRITO',
      payload: { eventoId: evento.id, items, usuarioId: usuarioActual?.id ?? null },
    });
    navigate('/checkout');
  }

  return (
    <PageTransition>
      <div className="mx-auto grid max-w-[1240px] grid-cols-1 gap-10 px-7 py-10 pb-28 md:grid-cols-[1fr_360px] md:pb-10">
        <div>
          <ImagePlaceholder etiqueta="foto del evento" className="min-h-[320px] w-full" src={evento.imagenUrl} />
          <h1 className="mt-6 text-[clamp(30px,4vw,44px)] font-bold tracking-[-0.03em] text-ink">
            {evento.titulo}
          </h1>
          <p className="font-mono-label mt-2 text-[12px] text-muted">
            {fechaHora(evento.fechaISO)} · {evento.venue}
          </p>
          <p className="mt-4 max-w-2xl text-[16px] text-graphite">{evento.descripcion}</p>

          <h2 className="mt-10 text-[26px] font-semibold tracking-tight text-ink">Mapa de sectores</h2>
          <div className="mt-4 grid grid-cols-[repeat(auto-fit,minmax(180px,1fr))] gap-3">
            {evento.sectores.map((s) => {
              const agotado = s.vendidas >= s.cupo;
              const seleccionado = (cantidades[s.id] ?? 0) > 0;
              return (
                <div
                  key={s.id}
                  className={`border p-4 ${
                    agotado
                      ? 'cursor-not-allowed border-edge bg-fill text-faint'
                      : seleccionado
                        ? 'border-2 border-accent'
                        : 'border-edge'
                  }`}
                >
                  <p className="text-sm font-medium">{s.nombre}</p>
                  <p className="mt-1 text-sm">{agotado ? 'agotada' : money(s.precio)}</p>
                </div>
              );
            })}
          </div>
          <p className="mt-6 max-w-2xl text-sm text-muted">
            Política de reventa: cada entrada queda ligada al DNI del titular. Solo puede
            revenderse por el canal oficial, con un tope de precio de +10% sobre lo
            pagado, hasta 3 horas antes del evento.
          </p>
        </div>

        {/* El selector se ve en todos los anchos: en pantalla angosta queda debajo de la descripción.
            Si se ocultara, desde un teléfono no habría cómo elegir entradas. */}
        <aside className="self-start border border-wire p-6 md:sticky md:top-[104px]">
          <h2 className="text-lg font-semibold tracking-tight text-ink">Elegí tus entradas</h2>
          <div className="mt-5 flex flex-col gap-4">
            {evento.sectores.map((s) => {
              const cupoDisponible = s.cupo - s.vendidas;
              const agotado = cupoDisponible <= 0;
              const cantidad = cantidades[s.id] ?? 0;
              return (
                <div key={s.id} className="flex items-center justify-between border-b border-line pb-3">
                  <div>
                    <p className="text-sm font-medium text-ink">{s.nombre}</p>
                    <p className="text-sm text-muted">{money(s.precio)}</p>
                  </div>
                  {agotado ? (
                    <span className="font-mono-label text-[11px] text-faint">agotada · —</span>
                  ) : (
                    <div className="flex items-center gap-3">
                      <button
                        type="button"
                        aria-label={`Quitar entrada de ${s.nombre}`}
                        disabled={cantidad === 0}
                        onClick={() => ajustar(s.id, -1, cupoDisponible)}
                        className="flex h-9 w-9 items-center justify-center border border-edge disabled:opacity-30"
                      >
                        –
                      </button>
                      <span className="w-4 text-center text-sm">{cantidad}</span>
                      <button
                        type="button"
                        aria-label={`Sumar entrada de ${s.nombre}`}
                        disabled={totalEntradas >= MAX_ENTRADAS_POR_ORDEN || cantidad >= cupoDisponible}
                        onClick={() => ajustar(s.id, 1, cupoDisponible)}
                        className="flex h-9 w-9 items-center justify-center border border-edge disabled:opacity-30"
                      >
                        +
                      </button>
                    </div>
                  )}
                </div>
              );
            })}
          </div>

          <AnimatePresence>
            {totalEntradas > 0 && (
              <motion.p
                initial={{ opacity: 0, height: 0 }}
                animate={{ opacity: 1, height: 'auto' }}
                exit={{ opacity: 0, height: 0 }}
                className="mt-4 text-xs text-muted"
              >
                Nominativa: al confirmar se piden nombre y DNI de cada asistente.
              </motion.p>
            )}
          </AnimatePresence>

          {/* En pantalla angosta el total y el botón están en la barra fija de abajo. */}
          <div className="hidden md:block">
            <div className="mt-6 flex items-center justify-between border-t border-line pt-4">
              <span className="font-mono-label text-[11px] text-muted">Total</span>
              <span className="text-xl font-semibold text-ink">{money(totalMostrado)}</span>
            </div>

            <Button className="mt-5 w-full" disabled={total === 0} onClick={continuarCheckout}>
              Continuar al checkout
            </Button>
          </div>

          {publicacionesEvento.length > 0 && (
            <Link
              to={`/reventa?evento=${evento.slug}`}
              className="mt-4 block text-center text-sm text-accent underline"
            >
              ¿Agotado? Ver reventa oficial ({publicacionesEvento.length} publicaciones)
            </Link>
          )}
        </aside>

        <div className="fixed inset-x-0 bottom-0 z-30 flex items-center justify-between border-t border-line bg-paper px-5 py-4 shadow-lg md:hidden">
          <div>
            <p className="font-mono-label text-[10px] text-muted">Total</p>
            <p className="text-lg font-semibold text-ink">{money(totalMostrado)}</p>
          </div>
          <Button disabled={total === 0} onClick={continuarCheckout}>
            Continuar
          </Button>
        </div>
      </div>
    </PageTransition>
  );
}
