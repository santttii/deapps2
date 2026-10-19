import { useEffect, useMemo, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useApp } from '../store/AppContext';
import { useToast } from '../components/ToastProvider';
import { calcularMinimoReventa, calcularTope, fecha, fechaHora, formatearDNI, money } from '../lib/format';
import { mensajeDeError } from '../lib/api';
import { listarCompras } from '../servicios/ventas';
import { listarMisPublicaciones, publicarEntrada, retirarPublicacion } from '../servicios/reventa';
import { HORAS_LIMITE_REVENTA, type EstadoOrden, type Orden, type Publicacion } from '../types';
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

type Carga =
  | { fase: 'cargando' }
  | { fase: 'error'; mensaje: string }
  // errorReventa: Reventa no respondió; las entradas se muestran igual, sin poder publicarlas.
  | { fase: 'lista'; compras: Orden[]; publicaciones: Publicacion[]; errorReventa: string };

export default function MyTicketsPage() {
  const { state, usuarioActual } = useApp();
  const { mostrarToast } = useToast();
  const [indice, setIndice] = useState(0);
  const [precioReventa, setPrecioReventa] = useState('');
  const [errorPrecio, setErrorPrecio] = useState('');
  const [carga, setCarga] = useState<Carga>({ fase: 'cargando' });
  const [vueltas, setVueltas] = useState(0); // sube con "Reintentar" y después de publicar o retirar
  const [enviando, setEnviando] = useState(false);

  // Las compras salen de Ventas (GET /api/v1/ventas): las del usuario de la sesión, de la más
  // nueva a la más vieja y en todos los estados, con el estado actual de cada entrada (también
  // PUBLICADA o USADA). Una entrada comprada en la reventa llega como una venta más. Las
  // publicaciones propias salen de Reventa (GET /api/v1/reventas/mias). Si hay una compra paga
  // emitiéndose, se vuelve a pedir.
  const compradorId = usuarioActual?.id;
  useEffect(() => {
    if (!compradorId) return;
    let vigente = true;
    let temporizador: ReturnType<typeof setTimeout> | undefined;
    let consultas = 0;
    const consultar = async () => {
      consultas += 1;
      try {
        const [recibidas, mias] = await Promise.all([
          listarCompras(),
          listarMisPublicaciones().then(
            (publicaciones) => ({ publicaciones, error: '' }),
            (error: unknown) => ({ publicaciones: [] as Publicacion[], error: mensajeDeError(error) }),
          ),
        ]);
        if (!vigente) return;
        setCarga({ fase: 'lista', compras: recibidas, publicaciones: mias.publicaciones, errorReventa: mias.error });
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
  }, [compradorId, vueltas]);

  const compras = useMemo(() => (carga.fase === 'lista' ? carga.compras : []), [carga]);
  const publicaciones = useMemo(() => (carga.fase === 'lista' ? carga.publicaciones : []), [carga]);

  // Las entradas emitidas, en el orden de Ventas (de la compra más nueva a la más vieja). Una
  // vendida en la reventa ya es de otra persona y no se muestra: queda en el historial. Si el
  // evento ya no está en el catálogo (la base de desarrollo se reinicia), tampoco.
  const misEntradas = useMemo(
    () =>
      compras
        .flatMap((o) => o.entradas)
        .filter(
          (e) =>
            e.codigoQr &&
            e.estado !== 'vendida' &&
            e.estado !== 'anulada' &&
            state.eventos.some((ev) => ev.id === e.eventoId),
        ),
    [compras, state.eventos],
  );

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
  const publicacion = publicaciones.find((p) => p.entradaId === entrada.id && p.estado === 'activa');
  const errorReventa = carga.errorReventa;

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

  // El backend vuelve a validar todo (entrada propia y VALIDA, rango de precio, cierre): si
  // rechaza, se muestra su mensaje debajo del precio.
  async function publicar() {
    if (!validarPrecio()) return;
    setEnviando(true);
    try {
      await publicarEntrada(entrada.id, precioNumerico);
      setPrecioReventa('');
      mostrarToast('Publicaste tu entrada en la reventa oficial.');
      setVueltas((n) => n + 1);
    } catch (error) {
      setErrorPrecio(mensajeDeError(error));
    } finally {
      setEnviando(false);
    }
  }

  async function retirar() {
    if (!publicacion) return;
    setEnviando(true);
    try {
      await retirarPublicacion(publicacion.id);
      mostrarToast('Retiraste la publicación de reventa.');
    } catch (error) {
      mostrarToast(mensajeDeError(error));
    } finally {
      setEnviando(false);
      setVueltas((n) => n + 1);
    }
  }

  const historial = [
    ...compras
      .filter((o) => o.estado === 'emitida')
      .map((o) => ({ texto: `Compra · orden #${o.id} · ${money(o.total)}`, fechaISO: o.creadaISO })),
    ...misEntradas
      .filter((e) => e.estado === 'usada')
      .map((e) => ({ texto: `Entrada usada · ${e.id}`, fechaISO: e.emitidaISO })),
    ...publicaciones
      .filter((p) => p.estado === 'vendida')
      .map((p) => ({ texto: `Reventa vendida · ${money(p.precio)}`, fechaISO: p.publicadaISO })),
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
                  {/* El código lo genera Ventas al emitir; una entrada comprada en la reventa trae uno nuevo. */}
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

                {entrada.estado === 'publicada' && publicacion && (
                  <Button variant="secondary" className="mt-5 w-full" loading={enviando} onClick={retirar}>
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
                disabled={entrada.estado !== 'valida' || !!errorReventa}
              />
            </div>
            <div className="mt-4">
              <ProgressBar porcentaje={porcentajeTope} />
            </div>
            <Button
              className="mt-4"
              loading={enviando}
              disabled={entrada.estado !== 'valida' || cierraPorHorario || !!errorReventa}
              onClick={publicar}
            >
              Publicar
            </Button>
            {errorReventa && (
              <p role="alert" className="mt-2 text-xs text-muted">
                La reventa no está disponible ahora: {errorReventa}
              </p>
            )}
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
