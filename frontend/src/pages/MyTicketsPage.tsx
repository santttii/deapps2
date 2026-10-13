import { useEffect, useMemo, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useApp } from '../store/AppContext';
import { useToast } from '../components/ToastProvider';
import { calcularMinimoReventa, calcularTope, fecha, fechaHora, formatearDNI, money } from '../lib/format';
import { obtenerEntradas, obtenerOrden } from '../servicios/ventas';
import { HORAS_LIMITE_REVENTA } from '../types';
import QRCode from '../components/QRCode';
import Button from '../components/Button';
import Input from '../components/Input';
import ProgressBar from '../components/ProgressBar';
import PageTransition from '../components/PageTransition';

export default function MyTicketsPage() {
  const { state, dispatch, usuarioActual } = useApp();
  const { mostrarToast } = useToast();
  const [indice, setIndice] = useState(0);
  const [precioReventa, setPrecioReventa] = useState('');
  const [errorPrecio, setErrorPrecio] = useState('');

  // El backend no tiene un listado de las entradas del usuario: se muestran las que este navegador
  // guardó al terminar cada compra (más las de la reventa, que es local). Los eventos vienen del
  // servidor: si un evento ya no existe allá (la base de desarrollo se reinicia), sus entradas no
  // se muestran.
  const misEntradas = useMemo(
    () =>
      state.entradas.filter(
        (e) => e.propietarioId === usuarioActual?.id && state.eventos.some((ev) => ev.id === e.eventoId),
      ),
    [state.entradas, state.eventos, usuarioActual],
  );

  // Compras pagas cuyas entradas todavía no se guardaron: el usuario se fue de la confirmación
  // antes de que terminara la emisión. Se guardan los ids juntos en un texto para que el efecto
  // de abajo corra solo cuando esa lista cambia.
  const compradorId = usuarioActual?.id;
  const idsPendientes = state.ordenes
    .filter((o) => o.compradorId === compradorId && o.estado === 'confirmada')
    .map((o) => o.id)
    .join(',');
  const comprasPendientes = idsPendientes ? idsPendientes.split(',').length : 0;

  // Al abrir la página se le pregunta al backend por cada una. Si ya está emitida, se guardan sus
  // entradas y aparecen solas. Si falla o sigue emitiéndose no se hace nada: se vuelve a
  // preguntar la próxima vez que se abra la página.
  useEffect(() => {
    if (!compradorId || !idsPendientes) return;
    let vigente = true;
    idsPendientes.split(',').forEach(async (ordenId) => {
      try {
        const orden = await obtenerOrden(ordenId);
        if (orden.estado !== 'emitida') return;
        const entradas = await obtenerEntradas(orden);
        if (vigente) dispatch({ type: 'GUARDAR_COMPRA', payload: { orden, entradas, compradorId } });
      } catch {
        // Se reintenta en la próxima visita.
      }
    });
    return () => {
      vigente = false;
    };
  }, [idsPendientes, compradorId, dispatch]);

  if (!usuarioActual) return null;

  const avisoDeEmision = comprasPendientes > 0 && (
    <p role="status" className="mb-6 border-l-2 border-accent pl-2 text-sm text-ink">
      Emitiendo las entradas de {comprasPendientes} {comprasPendientes === 1 ? 'compra' : 'compras'}…
    </p>
  );

  if (misEntradas.length === 0) {
    return (
      <PageTransition>
        <div className="mx-auto max-w-[1240px] px-7 py-16 text-center">
          {avisoDeEmision || <p className="text-lg text-graphite">Todavía no tenés entradas.</p>}
        </div>
      </PageTransition>
    );
  }

  const indiceSeguro = Math.min(indice, misEntradas.length - 1);
  const entrada = misEntradas[indiceSeguro];
  const evento = state.eventos.find((e) => e.id === entrada.eventoId);
  const sector = evento?.sectores.find((s) => s.id === entrada.sectorId);
  const publicacion = state.publicaciones.find((p) => p.entradaId === entrada.id && p.estado === 'activa');

  const tope = calcularTope(entrada.precioPagado);
  const minimo = calcularMinimoReventa(entrada.precioPagado);
  const horasParaEvento = evento ? (new Date(evento.fechaISO).getTime() - Date.now()) / 36e5 : Infinity;
  const cierraPorHorario = horasParaEvento < HORAS_LIMITE_REVENTA;

  const precioNumerico = Number(precioReventa || 0);
  const porcentajeTope = tope > 0 ? (precioNumerico / tope) * 100 : 0;

  function validarPrecio(): boolean {
    if (entrada.estado === 'usada') {
      setErrorPrecio('No se puede publicar una entrada ya utilizada.');
      return false;
    }
    if (cierraPorHorario) {
      setErrorPrecio('La reventa cierra 3 horas antes del evento.');
      return false;
    }
    if (!precioReventa || precioNumerico <= 0) {
      setErrorPrecio('Ingresá un precio.');
      return false;
    }
    if (precioNumerico > tope) {
      setErrorPrecio(`El máximo permitido es ${money(tope)}.`);
      return false;
    }
    if (precioNumerico < minimo) {
      setErrorPrecio(`El mínimo es ${money(minimo)}.`);
      return false;
    }
    setErrorPrecio('');
    return true;
  }

  function publicar() {
    if (!usuarioActual || !validarPrecio()) return;
    dispatch({
      type: 'PUBLICAR_REVENTA',
      payload: {
        id: `pub-${Date.now()}`,
        entradaId: entrada.id,
        eventoId: entrada.eventoId,
        sectorId: entrada.sectorId,
        precioOriginal: entrada.precioPagado,
        precio: precioNumerico,
        // Quien vende es el usuario de la sesión, aunque la entrada esté a nombre de otra persona.
        vendedorDni: usuarioActual.dni,
        estado: 'activa',
        creadaISO: new Date().toISOString(),
      },
    });
    setPrecioReventa('');
    mostrarToast('Publicaste tu entrada en la reventa oficial.');
  }

  function retirar() {
    if (!publicacion) return;
    dispatch({ type: 'RETIRAR_PUBLICACION', payload: { publicacionId: publicacion.id } });
    mostrarToast('Retiraste la publicación de reventa.');
  }

  const historial = [
    ...state.ordenes
      .filter((o) => o.compradorId === usuarioActual.id)
      .map((o) => ({ texto: `Compra · orden #${o.id} · ${money(o.total)}`, fechaISO: o.creadaISO })),
    ...misEntradas
      .filter((e) => e.estado === 'usada')
      .map((e) => ({ texto: `Entrada usada · ${e.id}`, fechaISO: e.emitidaISO })),
    ...state.publicaciones
      .filter((p) => p.estado === 'vendida' && p.vendedorDni === usuarioActual.dni)
      .map((p) => ({ texto: `Reventa vendida · ${money(p.precio)}`, fechaISO: p.creadaISO })),
  ].sort((a, b) => new Date(b.fechaISO).getTime() - new Date(a.fechaISO).getTime());

  return (
    <PageTransition>
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        {avisoDeEmision}
        <div className="grid grid-cols-1 gap-10 md:grid-cols-2">
          <div>
            {misEntradas.length > 1 && (
              <div className="mb-4 flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => setIndice((indiceSeguro - 1 + misEntradas.length) % misEntradas.length)}
                  className="h-9 w-9 border border-edge"
                  aria-label="Entrada anterior"
                >
                  ‹
                </button>
                <span className="font-mono-label text-[11px] text-muted">
                  Entrada {indiceSeguro + 1} de {misEntradas.length}
                </span>
                <button
                  type="button"
                  onClick={() => setIndice((indiceSeguro + 1) % misEntradas.length)}
                  className="h-9 w-9 border border-edge"
                  aria-label="Entrada siguiente"
                >
                  ›
                </button>
              </div>
            )}
            <AnimatePresence mode="wait">
              <motion.div
                key={entrada.id}
                initial={{ opacity: 0 }}
                animate={{ opacity: entrada.estado === 'publicada' ? 0.55 : 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.2 }}
                className="border border-wire p-6"
              >
                <div className="flex items-center justify-between">
                  <p className="font-mono-label text-[11px] text-muted">{entrada.id}</p>
                  <span className="font-mono-label text-[10px] text-accent">{entrada.estado}</span>
                </div>
                <h2 className="mt-2 text-xl font-semibold tracking-tight text-ink">{evento?.titulo}</h2>
                <p className="font-mono-label mt-1 text-[11px] text-muted">
                  {evento && fechaHora(evento.fechaISO)}
                </p>

                <div className="mt-5">
                  {/* El código lo genera Ventas al emitir; las entradas de la reventa local no lo tienen. */}
                  <QRCode id={entrada.codigoQr ?? entrada.id} />
                </div>

                <div className="mt-5 grid grid-cols-2 gap-3 border-t border-dashed border-edge pt-4 text-sm sm:grid-cols-3">
                  <div>
                    <p className="font-mono-label text-[10px] text-muted">Titular</p>
                    <p>{entrada.titular.nombre || '—'} {entrada.titular.apellido}</p>
                  </div>
                  <div>
                    <p className="font-mono-label text-[10px] text-muted">DNI</p>
                    <p>{entrada.titular.dni ? formatearDNI(entrada.titular.dni) : '—'}</p>
                  </div>
                  <div>
                    <p className="font-mono-label text-[10px] text-muted">Sector</p>
                    <p>{sector?.nombre}</p>
                  </div>
                </div>

                {entrada.estado === 'publicada' && (
                  <Button variant="secondary" className="mt-5 w-full" onClick={retirar}>
                    Retirar de reventa
                  </Button>
                )}
              </motion.div>
            </AnimatePresence>
          </div>

          <div>
            <h2 className="text-xl font-semibold tracking-tight text-ink">Publicar en reventa oficial</h2>
            <div className="mt-4 grid grid-cols-3 gap-3 text-sm">
              <div className="border border-wire p-3">
                <p className="font-mono-label text-[10px] text-muted">pagaste</p>
                <p className="font-semibold">{money(entrada.precioPagado)}</p>
              </div>
              <div className="border border-wire p-3">
                <p className="font-mono-label text-[10px] text-muted">tope +10%</p>
                <p className="font-semibold">{money(tope)}</p>
              </div>
              <Input
                label="Precio"
                hideLabel
                type="number"
                value={precioReventa}
                onChange={(e) => setPrecioReventa(e.target.value)}
                onBlur={validarPrecio}
                error={errorPrecio}
                disabled={entrada.estado !== 'valida'}
              />
            </div>
            <div className="mt-4">
              <ProgressBar porcentaje={porcentajeTope} />
            </div>
            <Button className="mt-4" disabled={entrada.estado !== 'valida' || cierraPorHorario} onClick={publicar}>
              Publicar
            </Button>
            {cierraPorHorario && entrada.estado === 'valida' && (
              <p className="mt-2 text-xs text-muted">La reventa cierra 3 horas antes del evento.</p>
            )}

            <h2 className="mt-10 text-xl font-semibold tracking-tight text-ink">Historial</h2>
            <div className="mt-4 flex flex-col gap-2">
              {historial.length === 0 && <p className="text-sm text-muted">Sin movimientos todavía.</p>}
              {historial.map((h, i) => (
                <div key={i} className="flex items-center justify-between border border-wire px-4 py-3 text-sm">
                  <span>{h.texto}</span>
                  <span className="font-mono-label text-[10px] text-muted">{fecha(h.fechaISO)}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </PageTransition>
  );
}
