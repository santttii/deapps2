import { useEffect, useMemo, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useApp } from '../store/AppContext';
import { useToast } from '../components/ToastProvider';
import { calcularMinimoReventa, calcularTope, fecha, fechaHora, formatearDNI, money } from '../lib/format';
import { mensajeDeError } from '../lib/api';
import { listarCompras } from '../servicios/ventas';
import { HORAS_LIMITE_REVENTA, type EstadoOrden, type Orden } from '../types';
import QRCode from '../components/QRCode';
import Button from '../components/Button';
import Input from '../components/Input';
import ProgressBar from '../components/ProgressBar';
import StatusMessage from '../components/StatusMessage';
import PageTransition from '../components/PageTransition';

// Mientras una compra paga espera la emisión (unos segundos), el listado se vuelve a pedir.
const PAUSA_EMISION_MS = 2000;
const MAX_CONSULTAS_EMISION = 15;

function hora(iso: string): string {
  return new Date(iso).toLocaleTimeString('es-AR', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
}

// Cómo se muestra cada compra que no tiene entradas emitidas.
const ESTADO_DE_COMPRA: Record<Exclude<EstadoOrden, 'emitida'>, { etiqueta: string; texto: (o: Orden) => string }> = {
  en_checkout: {
    etiqueta: 'En curso',
    texto: (o) =>
      Date.parse(o.venceReservaISO) > Date.now()
        ? `Lugares reservados hasta las ${hora(o.venceReservaISO)}. Todavía no tiene un pago aprobado.`
        : 'Venció el plazo para pagar. Si quedó un pago sin resultado, los lugares siguen reservados hasta que se confirme.',
  },
  confirmada: { etiqueta: 'Pagada', texto: () => 'El pago se aprobó y las entradas se están emitiendo.' },
  cancelada: { etiqueta: 'Cancelada', texto: () => 'La compra se canceló sin pagar.' },
  vencida: { etiqueta: 'Vencida', texto: () => 'La reserva venció sin pago.' },
};

type Carga = { fase: 'cargando' } | { fase: 'error'; mensaje: string } | { fase: 'lista'; compras: Orden[] };

export default function MyTicketsPage() {
  const { state, dispatch, usuarioActual } = useApp();
  const { mostrarToast } = useToast();
  const [indice, setIndice] = useState(0);
  const [precioReventa, setPrecioReventa] = useState('');
  const [errorPrecio, setErrorPrecio] = useState('');
  const [carga, setCarga] = useState<Carga>({ fase: 'cargando' });
  const [vueltas, setVueltas] = useState(0); // sube con "Reintentar"

  // Las compras salen de Ventas (GET /api/v1/ventas): las del usuario de la sesión, de la más
  // nueva a la más vieja y en todos los estados. Las entradas emitidas se copian al estado local
  // porque la reventa todavía es local. Si hay una compra paga emitiéndose, se vuelve a pedir.
  const compradorId = usuarioActual?.id;
  useEffect(() => {
    if (!compradorId) return;
    let vigente = true;
    let temporizador: ReturnType<typeof setTimeout> | undefined;
    let consultas = 0;
    const consultar = async () => {
      consultas += 1;
      try {
        const recibidas = await listarCompras();
        if (!vigente) return;
        const emitidas = recibidas.flatMap((o) => o.entradas.filter((e) => e.codigoQr));
        dispatch({ type: 'GUARDAR_ENTRADAS', payload: { entradas: emitidas, compradorId } });
        setCarga({ fase: 'lista', compras: recibidas });
        if (recibidas.some((o) => o.estado === 'confirmada') && consultas < MAX_CONSULTAS_EMISION) {
          temporizador = setTimeout(consultar, PAUSA_EMISION_MS);
        }
      } catch (error) {
        if (vigente) setCarga({ fase: 'error', mensaje: mensajeDeError(error) });
      }
    };
    consultar();
    return () => {
      vigente = false;
      clearTimeout(temporizador);
    };
  }, [compradorId, vueltas, dispatch]);

  const compras = useMemo(() => (carga.fase === 'lista' ? carga.compras : []), [carga]);

  // Las entradas emitidas son las que devolvió Ventas, en su orden (de la compra más nueva a la más
  // vieja) y con el estado local de reventa encima; después van las compradas en la reventa local.
  // Si el evento ya no está en el catálogo (la base de desarrollo se reinicia), no se muestra.
  const misEntradas = useMemo(() => {
    const propias = state.entradas.filter(
      (e) => e.propietarioId === usuarioActual?.id && state.eventos.some((ev) => ev.id === e.eventoId),
    );
    const porId = new Map(propias.map((e) => [e.id, e]));
    const deVentas = compras
      .flatMap((o) => o.entradas.filter((e) => e.codigoQr))
      .flatMap((e) => porId.get(e.id) ?? []);
    return [...deVentas, ...propias.filter((e) => e.origen === 'reventa')];
  }, [compras, state.entradas, state.eventos, usuarioActual]);

  if (!usuarioActual) return null;

  if (carga.fase !== 'lista') {
    return (
      <PageTransition>
        <div className="mx-auto max-w-[1240px] px-7 py-10">
          {carga.fase === 'error' ? (
            <StatusMessage
              titulo="No pudimos traer tus compras"
              texto={carga.mensaje}
              accion="Reintentar"
              onAccion={() => {
                setCarga({ fase: 'cargando' });
                setVueltas((n) => n + 1);
              }}
            />
          ) : (
            <p role="status" className="text-sm text-muted">
              Cargando tus compras…
            </p>
          )}
        </div>
      </PageTransition>
    );
  }

  // Compras sin entradas emitidas: en curso, pagas emitiéndose, canceladas o vencidas.
  const otrasCompras = compras.filter((o) => o.estado !== 'emitida');
  const seccionOtrasCompras = otrasCompras.length > 0 && (
    <section className="mt-12" aria-labelledby="otras-compras">
      <h2 id="otras-compras" className="text-xl font-semibold tracking-tight text-ink">
        Compras en curso, canceladas o vencidas
      </h2>
      <ul className="mt-4 flex flex-col gap-2">
        {otrasCompras.map((o) => {
          const estado = ESTADO_DE_COMPRA[o.estado as Exclude<EstadoOrden, 'emitida'>];
          const evento = state.eventos.find((ev) => ev.id === o.eventoId);
          return (
            <li key={o.id} data-compra={o.id} className="border border-wire px-4 py-3 text-sm">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-ink">
                  {evento?.titulo ?? o.eventoId} · orden #{o.id} · {money(o.total)}
                </span>
                <span className="font-mono-label text-[10px] text-accent">{estado.etiqueta}</span>
              </div>
              <p className="mt-1 text-xs text-muted">
                {fecha(o.creadaISO)} · {estado.texto(o)}
              </p>
            </li>
          );
        })}
      </ul>
    </section>
  );

  if (misEntradas.length === 0) {
    return (
      <PageTransition>
        <div className="mx-auto max-w-[1240px] px-7 py-16">
          <p className="text-center text-lg text-graphite">Todavía no tenés entradas emitidas.</p>
          {seccionOtrasCompras}
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
    ...compras
      .filter((o) => o.estado === 'emitida')
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
        {seccionOtrasCompras}
      </div>
    </PageTransition>
  );
}
