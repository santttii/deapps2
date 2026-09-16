import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useApp } from '../store/AppContext';
import { useToast } from '../components/ToastProvider';
import { calcularMinimoReventa, calcularTope, money, sleep } from '../lib/format';
import { HORAS_LIMITE_REVENTA } from '../types';
import Button from '../components/Button';
import Modal from '../components/Modal';
import Input from '../components/Input';
import ProgressBar from '../components/ProgressBar';
import PageTransition from '../components/PageTransition';

type Orden = 'menor' | 'mayor';

export default function ResalePage() {
  const { state, dispatch, usuarioActual } = useApp();
  const { mostrarToast } = useToast();
  const navigate = useNavigate();
  const [params] = useSearchParams();

  const [eventoFiltro, setEventoFiltro] = useState(params.get('evento') ?? 'todos');
  const [sectorFiltro, setSectorFiltro] = useState('todos');
  const [orden, setOrden] = useState<Orden>('menor');
  const [comprando, setComprando] = useState<string | null>(null);
  const [procesandoCompra, setProcesandoCompra] = useState(false);

  const [entradaAPublicar, setEntradaAPublicar] = useState('');
  const [precioPublicar, setPrecioPublicar] = useState('');
  const [errorPublicar, setErrorPublicar] = useState('');

  const publicacionesActivas = state.publicaciones.filter((p) => p.estado === 'activa');

  const eventoActivo = state.eventos.find((e) => e.slug === eventoFiltro);

  const publicacionesFiltradas = useMemo(() => {
    let lista = publicacionesActivas;
    if (eventoActivo) lista = lista.filter((p) => p.eventoId === eventoActivo.id);
    if (sectorFiltro !== 'todos') lista = lista.filter((p) => p.sectorId === sectorFiltro);
    lista = [...lista].sort((a, b) => (orden === 'menor' ? a.precio - b.precio : b.precio - a.precio));
    return lista;
  }, [publicacionesActivas, eventoActivo, sectorFiltro, orden]);

  const sectoresDisponibles = eventoActivo ? eventoActivo.sectores : [];

  const publicacion = state.publicaciones.find((p) => p.id === comprando);
  const eventoCompra = publicacion ? state.eventos.find((e) => e.id === publicacion.eventoId) : undefined;
  const sectorCompra = publicacion && eventoCompra ? eventoCompra.sectores.find((s) => s.id === publicacion.sectorId) : undefined;

  async function confirmarCompra() {
    if (!publicacion || !eventoCompra || !sectorCompra || !usuarioActual) return;
    setProcesandoCompra(true);
    await sleep(900);
    const entradaOriginal = state.entradas.find((e) => e.id === publicacion.entradaId);
    dispatch({
      type: 'COMPRAR_REVENTA',
      payload: {
        publicacionId: publicacion.id,
        compradorId: usuarioActual.id,
        entradaNueva: {
          id: `PLC-${Math.random().toString(36).slice(2, 8).toUpperCase()}`,
          ordenId: entradaOriginal?.ordenId ?? '',
          eventoId: eventoCompra.id,
          sectorId: sectorCompra.id,
          fila: entradaOriginal?.fila ?? '1',
          precioPagado: publicacion.precio,
          titular: { nombre: usuarioActual.nombre, apellido: usuarioActual.apellido, dni: usuarioActual.dni },
          estado: 'valida',
          origen: 'reventa',
          emitidaISO: new Date().toISOString(),
        },
      },
    });
    setProcesandoCompra(false);
    setComprando(null);
    mostrarToast('Compraste la entrada por reventa oficial.');
    navigate('/mis-entradas');
  }

  const entradasPropiasPublicables = usuarioActual
    ? state.entradas.filter((e) => e.propietarioId === usuarioActual.id && e.estado === 'valida')
    : [];
  const entradaSeleccionada = entradasPropiasPublicables.find((e) => e.id === entradaAPublicar);
  const topeSeleccion = entradaSeleccionada ? calcularTope(entradaSeleccionada.precioPagado) : 0;
  const minimoSeleccion = entradaSeleccionada ? calcularMinimoReventa(entradaSeleccionada.precioPagado) : 0;
  const precioNumerico = Number(precioPublicar || 0);

  function publicarLaMia() {
    if (!entradaSeleccionada) {
      setErrorPublicar('Elegí una entrada.');
      return;
    }
    const evento = state.eventos.find((e) => e.id === entradaSeleccionada.eventoId);
    const horas = evento ? (new Date(evento.fechaISO).getTime() - Date.now()) / 36e5 : Infinity;
    if (horas < HORAS_LIMITE_REVENTA) {
      setErrorPublicar('La reventa cierra 3 horas antes del evento.');
      return;
    }
    if (precioNumerico > topeSeleccion) {
      setErrorPublicar(`El máximo permitido es ${money(topeSeleccion)}.`);
      return;
    }
    if (precioNumerico < minimoSeleccion) {
      setErrorPublicar(`El mínimo es ${money(minimoSeleccion)}.`);
      return;
    }
    dispatch({
      type: 'PUBLICAR_REVENTA',
      payload: {
        id: `pub-${Date.now()}`,
        entradaId: entradaSeleccionada.id,
        eventoId: entradaSeleccionada.eventoId,
        sectorId: entradaSeleccionada.sectorId,
        precioOriginal: entradaSeleccionada.precioPagado,
        precio: precioNumerico,
        vendedorDni: entradaSeleccionada.titular.dni,
        estado: 'activa',
        creadaISO: new Date().toISOString(),
      },
    });
    setEntradaAPublicar('');
    setPrecioPublicar('');
    setErrorPublicar('');
    mostrarToast('Publicaste tu entrada en la reventa oficial.');
  }

  return (
    <PageTransition>
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        <h1 className="text-2xl font-semibold tracking-tight text-ink">Reventa oficial</h1>

        <div className="mt-4 flex flex-wrap items-center justify-between gap-4">
          <p className="max-w-xl text-sm text-muted">
            Todas las publicaciones son de titulares verificados · el tope es el precio
            pagado + 10%
          </p>
          <span className="font-mono-label text-[11px] text-muted">
            {publicacionesFiltradas.length} publicaciones
          </span>
        </div>

        <div className="mt-6 flex flex-wrap gap-3">
          <select
            value={eventoFiltro}
            onChange={(e) => {
              setEventoFiltro(e.target.value);
              setSectorFiltro('todos');
            }}
            className="min-h-[44px] border border-edge bg-paper px-3 text-sm"
          >
            <option value="todos">Todos los eventos</option>
            {state.eventos.map((e) => (
              <option key={e.id} value={e.slug}>
                {e.titulo}
              </option>
            ))}
          </select>
          <select
            value={sectorFiltro}
            onChange={(e) => setSectorFiltro(e.target.value)}
            disabled={!eventoActivo}
            className="min-h-[44px] border border-edge bg-paper px-3 text-sm disabled:opacity-40"
          >
            <option value="todos">Todos los sectores</option>
            {sectoresDisponibles.map((s) => (
              <option key={s.id} value={s.id}>
                {s.nombre}
              </option>
            ))}
          </select>
          <select
            value={orden}
            onChange={(e) => setOrden(e.target.value as Orden)}
            className="min-h-[44px] border border-edge bg-paper px-3 text-sm"
          >
            <option value="menor">Precio: menor a mayor</option>
            <option value="mayor">Precio: mayor a menor</option>
          </select>
        </div>

        <div className="mt-6 hidden overflow-x-auto border border-wire sm:block">
          <table className="w-full text-sm">
            <thead className="bg-fill">
              <tr>
                {['Evento', 'Sector', 'Original', 'Tope', 'Publicada', 'Acción'].map((h) => (
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
                const esPropia = usuarioActual && p.vendedorDni === usuarioActual.dni;
                return (
                  <motion.tr layout key={p.id} className="border-t border-wire">
                    <td className="px-4 py-3">{evento?.titulo}</td>
                    <td className="px-4 py-3">{sector?.nombre}</td>
                    <td className="px-4 py-3">{money(p.precioOriginal)}</td>
                    <td className="px-4 py-3">{money(calcularTope(p.precioOriginal))}</td>
                    <td className="px-4 py-3 font-medium text-ink">{money(p.precio)}</td>
                    <td className="px-4 py-3">
                      {esPropia ? (
                        <span className="font-mono-label text-[10px] text-muted">tu publicación</span>
                      ) : (
                        <button
                          type="button"
                          onClick={() => setComprando(p.id)}
                          className="border border-wire px-3 py-1.5 text-xs"
                        >
                          Comprar
                        </button>
                      )}
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
            const esPropia = usuarioActual && p.vendedorDni === usuarioActual.dni;
            return (
              <div key={p.id} className="border border-wire p-4 text-sm">
                <p className="font-medium">{evento?.titulo}</p>
                <p className="text-muted">{sector?.nombre}</p>
                <div className="mt-2 flex justify-between">
                  <span>Original: {money(p.precioOriginal)}</span>
                  <span>Tope: {money(calcularTope(p.precioOriginal))}</span>
                </div>
                <p className="mt-1 font-semibold">{money(p.precio)}</p>
                {esPropia ? (
                  <span className="font-mono-label text-[10px] text-muted">tu publicación</span>
                ) : (
                  <Button variant="secondary" className="mt-2 w-full" onClick={() => setComprando(p.id)}>
                    Comprar
                  </Button>
                )}
              </div>
            );
          })}
        </div>

        <div className="mt-14 grid grid-cols-1 gap-10 md:grid-cols-2">
          <div>
            <h2 className="text-lg font-semibold tracking-tight text-ink">Cómo funciona el tope</h2>
            <p className="mt-2 text-sm text-graphite">
              El precio publicado nunca puede superar el 110% de lo que pagó el
              vendedor. La barra muestra cuánto del tope se está usando.
            </p>
            <div className="mt-4">
              <ProgressBar porcentaje={78} />
            </div>
          </div>

          <div>
            <h2 className="text-lg font-semibold tracking-tight text-ink">Publicar la mía</h2>
            {!usuarioActual ? (
              <p className="mt-2 text-sm text-muted">Ingresá a tu cuenta para publicar una entrada.</p>
            ) : entradasPropiasPublicables.length === 0 ? (
              <p className="mt-2 text-sm text-muted">No tenés entradas disponibles para publicar.</p>
            ) : (
              <div className="mt-4 flex flex-col gap-3">
                <select
                  value={entradaAPublicar}
                  onChange={(e) => setEntradaAPublicar(e.target.value)}
                  className="min-h-[44px] border border-edge bg-paper px-3 text-sm"
                >
                  <option value="">Elegí una entrada</option>
                  {entradasPropiasPublicables.map((e) => {
                    const evento = state.eventos.find((ev) => ev.id === e.eventoId);
                    return (
                      <option key={e.id} value={e.id}>
                        {evento?.titulo} · {e.id}
                      </option>
                    );
                  })}
                </select>
                {entradaSeleccionada && (
                  <p className="text-xs text-muted">
                    Pagaste {money(entradaSeleccionada.precioPagado)} · tope {money(topeSeleccion)}
                  </p>
                )}
                <Input
                  label="Precio de publicación"
                  hideLabel
                  type="number"
                  value={precioPublicar}
                  onChange={(e) => setPrecioPublicar(e.target.value)}
                  error={errorPublicar}
                  disabled={!entradaSeleccionada}
                />
                <Button onClick={publicarLaMia} disabled={!entradaSeleccionada}>
                  Publicar
                </Button>
              </div>
            )}
          </div>
        </div>
      </div>

      <Modal abierto={!!comprando} onCerrar={() => setComprando(null)} titulo="Confirmar compra">
        {publicacion && eventoCompra && sectorCompra && (
          <div className="flex flex-col gap-3 text-sm text-graphite">
            <p>
              {eventoCompra.titulo} · {sectorCompra.nombre}
            </p>
            <p>
              Precio: <span className="font-semibold text-ink">{money(publicacion.precio)}</span> ·
              tope {money(calcularTope(publicacion.precioOriginal))}
            </p>
            <p>La entrada se remite a tu nombre, ligada a tu DNI verificado.</p>
            <Button loading={procesandoCompra} loadingText="Procesando…" onClick={confirmarCompra}>
              Confirmar compra
            </Button>
          </div>
        )}
      </Modal>
    </PageTransition>
  );
}
