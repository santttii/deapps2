import { useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useApp } from '../store/AppContext';
import { useToast } from '../components/ToastProvider';
import { money, nombreDelRol } from '../lib/format';
import { useReventas } from '../lib/useReventas';
import { AVISO_REVENTA_CERRADA, reventaCerrada } from '../servicios/reventa';
import type { Evento, Publicacion } from '../types';
import Button from '../components/Button';
import CompraReventaModal from '../components/CompraReventaModal';
import PageTransition from '../components/PageTransition';

type Orden = 'menor' | 'mayor';

// Listado de la reventa oficial de todos los eventos del catálogo. Las publicaciones son de
// Reventa; publicar y retirar se hace desde "Mis entradas", donde está cada entrada propia.
export default function ResalePage() {
  const { state, usuarioActual } = useApp();
  const { mostrarToast } = useToast();
  const navigate = useNavigate();
  const [params] = useSearchParams();

  const [eventoFiltro, setEventoFiltro] = useState(params.get('evento') ?? 'todos');
  const [sectorFiltro, setSectorFiltro] = useState('todos');
  const [orden, setOrden] = useState<Orden>('menor');
  const [comprando, setComprando] = useState<Publicacion | null>(null);

  // Solo el rol user compra: organizer y staff ven la reventa, pero el backend les rechaza la compra.
  const esComprador = usuarioActual?.rol === 'user';
  const reventas = useReventas(state.eventos.map((e) => e.id), esComprador);
  const eventoActivo = state.eventos.find((e) => e.slug === eventoFiltro);
  const { cargando, error } = reventas;

  const publicacionesFiltradas = useMemo(() => {
    let lista = reventas.publicaciones;
    if (eventoActivo) lista = lista.filter((p) => p.eventoId === eventoActivo.id);
    if (sectorFiltro !== 'todos') lista = lista.filter((p) => p.sectorId === sectorFiltro);
    return [...lista].sort((a, b) => (orden === 'menor' ? a.precio - b.precio : b.precio - a.precio));
  }, [reventas.publicaciones, eventoActivo, sectorFiltro, orden]);

  const sectoresDisponibles = eventoActivo ? eventoActivo.sectores : [];
  const eventoCompra = comprando ? state.eventos.find((e) => e.id === comprando.eventoId) : undefined;
  const cantidad = publicacionesFiltradas.length;

  let mensajeVacio = 'Por ahora no hay entradas publicadas en la reventa.';
  if (sectorFiltro !== 'todos') mensajeVacio = 'No hay entradas publicadas en la reventa de este sector.';
  else if (eventoActivo) mensajeVacio = 'No hay entradas publicadas en la reventa de este evento.';

  function intentarComprar(publicacion: Publicacion) {
    if (!usuarioActual) {
      mostrarToast('Iniciá sesión para comprar en la reventa oficial.');
      navigate('/ingresar?next=/reventa');
      return;
    }
    setComprando(publicacion);
  }

  return (
    <PageTransition>
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        <h1 className="text-2xl font-semibold tracking-tight text-ink">Reventa oficial</h1>

        <div className="mt-4 flex flex-wrap items-center justify-between gap-4">
          {/* No dice "verificados": las cuentas nuevas no pasan por una verificación de identidad. */}
          <p className="max-w-xl text-sm text-muted">
            Todas las publicaciones son de titulares registrados · el tope es el precio
            pagado + 10%
          </p>
          {!cargando && !error && (
            <span className="font-mono-label text-[11px] text-muted">
              {cantidad} {cantidad === 1 ? 'publicación' : 'publicaciones'}
            </span>
          )}
        </div>

        <div className="mt-6 flex flex-wrap gap-3">
          <label className="flex w-full flex-col gap-1.5 sm:w-auto sm:max-w-full">
            <span className="font-mono-label text-[11px] text-muted">Evento</span>
            <select
              value={eventoFiltro}
              onChange={(e) => {
                setEventoFiltro(e.target.value);
                setSectorFiltro('todos');
              }}
              className="min-h-[44px] max-w-full border border-edge bg-paper px-3 text-sm"
            >
              <option value="todos">Todos los eventos</option>
              {state.eventos.map((e) => (
                <option key={e.id} value={e.slug}>
                  {e.titulo}
                </option>
              ))}
            </select>
          </label>
          <label className="flex w-full flex-col gap-1.5 sm:w-auto sm:max-w-full">
            <span className="font-mono-label text-[11px] text-muted">Sector</span>
            <select
              value={sectorFiltro}
              onChange={(e) => setSectorFiltro(e.target.value)}
              disabled={!eventoActivo}
              className="min-h-[44px] max-w-full border border-edge bg-paper px-3 text-sm disabled:opacity-40"
            >
              <option value="todos">Todos los sectores</option>
              {sectoresDisponibles.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.nombre}
                </option>
              ))}
            </select>
          </label>
          <label className="flex w-full flex-col gap-1.5 sm:w-auto sm:max-w-full">
            <span className="font-mono-label text-[11px] text-muted">Ordenar por</span>
            <select
              value={orden}
              onChange={(e) => setOrden(e.target.value as Orden)}
              className="min-h-[44px] max-w-full border border-edge bg-paper px-3 text-sm"
            >
              <option value="menor">Precio: menor a mayor</option>
              <option value="mayor">Precio: mayor a menor</option>
            </select>
          </label>
        </div>

        {usuarioActual && !esComprador && (
          <p className="mt-6 border-l-2 border-accent pl-3 text-sm text-graphite">
            Las compras se hacen con una cuenta de comprador. Ingresaste como {nombreDelRol(usuarioActual.rol)}.
          </p>
        )}

        {cargando && (
          <p role="status" className="mt-6 text-sm text-muted">
            Cargando publicaciones…
          </p>
        )}
        {error && (
          <p role="alert" className="mt-6 text-sm text-muted">
            {error}{' '}
            <button type="button" className="text-accent underline" onClick={reventas.recargar}>
              Reintentar
            </button>
          </p>
        )}

        {!cargando && !error && cantidad === 0 && (
          <p className="mt-6 text-sm text-muted">{mensajeVacio}</p>
        )}

        {cantidad > 0 && (
          <>
            <div className="mt-6 hidden overflow-x-auto border border-wire sm:block">
              <table className="w-full text-sm">
                <thead className="bg-fill">
                  <tr>
                    {['Evento', 'Sector', 'Tope', 'Precio', 'Acción'].map((h) => (
                      <th key={h} className="font-mono-label px-4 py-3 text-left text-[10px] text-muted">
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {publicacionesFiltradas.map((p) => {
                    const evento = state.eventos.find((e) => e.id === p.eventoId);
                    const sector = evento?.sectores.find((s) => s.id === p.sectorId);
                    return (
                      <motion.tr layout key={p.id} className="border-t border-wire">
                        <td className="px-4 py-3">{evento?.titulo}</td>
                        <td className="px-4 py-3">{sector?.nombre}</td>
                        <td className="px-4 py-3">{money(p.precioMaximo)}</td>
                        <td className="px-4 py-3 font-medium text-ink">{money(p.precio)}</td>
                        <td className="px-4 py-3">
                          <Accion
                            propia={reventas.propias.has(p.id)}
                            evento={evento}
                            sinPermiso={!!usuarioActual && !esComprador}
                            onComprar={() => intentarComprar(p)}
                          />
                        </td>
                      </motion.tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <div className="mt-6 flex flex-col gap-4 sm:hidden">
              {publicacionesFiltradas.map((p) => {
                const evento = state.eventos.find((e) => e.id === p.eventoId);
                const sector = evento?.sectores.find((s) => s.id === p.sectorId);
                return (
                  <div key={p.id} className="border border-wire p-4 text-sm">
                    <p className="font-medium">{evento?.titulo}</p>
                    <p className="text-muted">{sector?.nombre}</p>
                    <p className="mt-2">Tope: {money(p.precioMaximo)}</p>
                    <p className="mt-1 font-semibold">{money(p.precio)}</p>
                    <div className="mt-2">
                      <Accion
                        propia={reventas.propias.has(p.id)}
                        evento={evento}
                        sinPermiso={!!usuarioActual && !esComprador}
                        onComprar={() => intentarComprar(p)}
                        enTarjeta
                      />
                    </div>
                  </div>
                );
              })}
            </div>
          </>
        )}

        <div className="mt-14 grid grid-cols-1 gap-10 md:grid-cols-2">
          <div>
            <h2 className="text-lg font-semibold tracking-tight text-ink">Cómo funciona el tope</h2>
            <p className="mt-2 text-sm text-graphite">
              El precio publicado nunca puede superar el 110% de lo que pagó el
              vendedor. Ese máximo es el tope que figura en cada publicación.
            </p>
          </div>

          <div>
            <h2 className="text-lg font-semibold tracking-tight text-ink">Publicar la mía</h2>
            <p className="mt-2 text-sm text-muted">
              Se publica desde <Link to="/mis-entradas" className="text-accent underline">Mis entradas</Link>,
              eligiendo la entrada. Si la compra la hace otra persona, la entrada se emite de nuevo a su nombre.
            </p>
          </div>
        </div>
      </div>

      <CompraReventaModal
        publicacion={comprando}
        evento={eventoCompra}
        onCerrar={() => setComprando(null)}
        onListadoVencido={reventas.recargar}
      />
    </PageTransition>
  );
}

interface AccionProps {
  propia: boolean;
  evento: Evento | undefined;
  sinPermiso: boolean; // sesión de organizer o staff
  onComprar: () => void;
  enTarjeta?: boolean; // la vista de teléfono usa un botón a lo ancho
}

// Lo que se ofrece en cada publicación. La propia no se compra, y la de un evento con la reventa
// cerrada tampoco: el listado de Reventa las trae igual y comprarlas da error.
function Accion({ propia, evento, sinPermiso, onComprar, enTarjeta = false }: AccionProps) {
  if (propia) {
    return (
      <p className="text-xs text-muted">
        Es tu publicación ·{' '}
        <Link to="/mis-entradas" className="text-accent underline">
          ver en Mis entradas
        </Link>
      </p>
    );
  }
  if (evento && reventaCerrada(evento.fechaISO)) {
    return <p className="text-xs text-muted">{AVISO_REVENTA_CERRADA}</p>;
  }
  if (enTarjeta) {
    return (
      <Button variant="secondary" className="w-full" disabled={sinPermiso} onClick={onComprar}>
        Comprar
      </Button>
    );
  }
  return (
    <button
      type="button"
      disabled={sinPermiso}
      onClick={onComprar}
      className="border border-wire px-3 py-1.5 text-xs disabled:cursor-not-allowed disabled:opacity-40"
    >
      Comprar
    </button>
  );
}
