import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useApp } from '../store/AppContext';
import { ErrorApi, mensajeDeError } from '../lib/api';
import { fechaHora, formatearDNI } from '../lib/format';
import { listarEventos } from '../servicios/eventos';
import {
  LARGO_MAXIMO_CODIGO,
  escanear,
  listarEscaneos,
  misPuertas,
  type Escaneo,
  type ResultadoDeEscaneo,
} from '../servicios/validacion';
import ImagePlaceholder from '../components/ImagePlaceholder';
import Button from '../components/Button';
import Input from '../components/Input';
import PageTransition from '../components/PageTransition';

const TITULO_RESULTADO: Record<ResultadoDeEscaneo['resultado'], string> = {
  VALIDA: '✓ Entrada válida',
  YA_USADA: 'Entrada ya utilizada',
  INEXISTENTE: 'Código no reconocido',
  OTRO_EVENTO: 'Entrada de otro evento',
  NO_HABILITADA: 'Entrada no habilitada',
  SIN_RESPUESTA: 'Sin respuesta',
};

// Cada cuánto se vuelven a pedir el registro y las puertas asignadas mientras la pestaña está a la
// vista: otras pantallas escanean en la misma puerta y el organizador suma o quita puertas.
const SEGUNDOS_ENTRE_REFRESCOS = 15;

function hora(iso: string): string {
  return new Date(iso).toLocaleTimeString('es-AR', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
}

// Control de acceso del personal de puerta (rol staff). Cada código leído (o tipeado) va a
// Validación (POST /api/v1/validacion/escaneos), que consulta a Ventas y marca la entrada como
// usada en el mismo paso: no hay un segundo botón de "marcar ingreso". El resultado, el texto y
// el titular para contrastar con el documento vienen del backend. Solo se ofrecen los eventos a los
// que el organizador asignó esta cuenta (GET /api/v1/validacion/puertas/mias).
type Puertas = { fase: 'cargando' } | { fase: 'error'; mensaje: string } | { fase: 'lista'; eventoIds: string[] };

// El registro guarda de qué evento es, así no se muestran los escaneos de otro mientras llega el
// nuevo. escaneos en null: no se pudo leer.
type Registro = { eventoId: string; escaneos: Escaneo[] | null };

export default function GatePage() {
  const { state, dispatch, usuarioActual } = useApp();
  const [eventoId, setEventoId] = useState('');
  const [codigo, setCodigo] = useState('');
  const [resultado, setResultado] = useState<ResultadoDeEscaneo | null>(null);
  const [error, setError] = useState('');
  const [enviando, setEnviando] = useState(false);
  const [registro, setRegistro] = useState<Registro | null>(null);
  const [vueltas, setVueltas] = useState(0); // sube después de cada escaneo y en cada refresco
  const [puertas, setPuertas] = useState<Puertas>({ fase: 'cargando' });
  const formulario = useRef<HTMLFormElement>(null);
  // Los ids asignados que faltaban en el catálogo y por los que ya se lo pidió de nuevo.
  const yaBuscados = useRef('');

  // El título y la fecha de cada puerta salen del catálogo, que se pide al abrir la aplicación. Si
  // una puerta es de un evento que no está ahí (uno creado después), el catálogo se pide de nuevo, una
  // vez por cada grupo de faltantes. Los eventos cancelados no aparecen ni así: salen del catálogo.
  const catalogoListo = state.catalogo.estado !== 'cargando';
  useEffect(() => {
    let vigente = true;
    async function cargar(): Promise<string[]> {
      const ids = (await misPuertas()).map((p) => p.eventoId);
      const faltan = ids.filter((id) => !state.eventos.some((e) => e.id === id)).join(',');
      // Mientras llega el catálogo del arranque no se pide otro: este efecto corre de nuevo cuando llega.
      if (catalogoListo && faltan && faltan !== yaBuscados.current) {
        // Se anota antes de pedirlo, así dos pedidos de puertas que se cruzan no traen el catálogo dos
        // veces; si falla, se borra y el próximo refresco lo vuelve a pedir.
        yaBuscados.current = faltan;
        const eventos = await listarEventos().catch((err: unknown) => {
          yaBuscados.current = '';
          throw err;
        });
        dispatch({ type: 'CATALOGO_CARGADO', payload: eventos });
        yaBuscados.current = ids.filter((id) => !eventos.some((e) => e.id === id)).join(',');
      }
      return ids;
    }
    cargar()
      .then((ids) => vigente && setPuertas({ fase: 'lista', eventoIds: ids }))
      // Si falla un refresco, la puerta sigue con la lista que tenía.
      .catch(
        (err: unknown) =>
          vigente && setPuertas((p) => (p.fase === 'lista' ? p : { fase: 'error', mensaje: mensajeDeError(err) })),
      );
    return () => {
      vigente = false;
    };
  }, [vueltas, state.eventos, catalogoListo, dispatch]);

  useEffect(() => {
    function refrescar() {
      if (document.visibilityState === 'visible') setVueltas((n) => n + 1);
    }
    const intervalo = setInterval(refrescar, SEGUNDOS_ENTRE_REFRESCOS * 1000);
    document.addEventListener('visibilitychange', refrescar);
    return () => {
      clearInterval(intervalo);
      document.removeEventListener('visibilitychange', refrescar);
    };
  }, []);

  const idsAsignados = puertas.fase === 'lista' ? puertas.eventoIds : [];
  const eventosDeLaPuerta = state.eventos.filter((e) => idsAsignados.includes(e.id));
  const sinEventosAMostrar = idsAsignados.length > 0 && eventosDeLaPuerta.length === 0;
  // El primer evento queda elegido apenas aparece: si después cambia la lista, la puerta no pasa a
  // otro evento sin que nadie lo elija.
  if (!eventoId && eventosDeLaPuerta.length > 0) setEventoId(eventosDeLaPuerta[0].id);
  const evento = eventosDeLaPuerta.find((e) => e.id === eventoId);
  const idEvento = evento?.id;

  // El registro de esta puerta, del más nuevo al más viejo. Si no se puede leer, la puerta sigue
  // escaneando: el registro es informativo.
  useEffect(() => {
    if (!idEvento) return;
    let vigente = true;
    listarEscaneos(idEvento)
      .then((escaneos) => vigente && setRegistro({ eventoId: idEvento, escaneos }))
      .catch(
        () => vigente && setRegistro((r) => (r?.eventoId === idEvento ? r : { eventoId: idEvento, escaneos: null })),
      );
    return () => {
      vigente = false;
    };
  }, [idEvento, vueltas]);

  // El lector de QR "tipea" el código y un Enter donde esté el foco: por eso vuelve siempre al campo.
  // Input no reenvía la ref, así que el campo se busca por su nombre dentro del formulario.
  function enfocarCodigo() {
    const campo = formulario.current?.elements.namedItem('codigoQr');
    if (campo instanceof HTMLInputElement) campo.focus({ preventScroll: true });
  }

  // En el celular el resultado queda debajo del formulario: se lo trae a la vista cuando aparece.
  const mostrarResultado = useCallback((caja: HTMLDivElement | null) => {
    caja?.scrollIntoView({ block: 'nearest' });
  }, []);

  async function enviar(e: FormEvent) {
    e.preventDefault();
    if (!idEvento || !codigo.trim()) return;
    setEnviando(true);
    setError('');
    try {
      setResultado(await escanear(codigo, idEvento));
    } catch (err) {
      // 504: Ventas no respondió y no se sabe si la entrada quedó usada. El texto del backend ya
      // pide escanear de nuevo.
      setResultado(null);
      setError(err instanceof ErrorApi ? (err.campos.codigoQr ?? err.message) : mensajeDeError(err));
      // El organizador quitó esta cuenta de la puerta: el evento sale de la lista sin esperar al refresco.
      if (err instanceof ErrorApi && err.tipo === 'puerta-no-asignada') {
        setPuertas((p) => (p.fase === 'lista' ? { ...p, eventoIds: p.eventoIds.filter((id) => id !== idEvento) } : p));
      }
    } finally {
      // Con respuesta o sin ella el campo queda vacío: si el lector tipeara encima, mandaría dos códigos juntos.
      setCodigo('');
      setEnviando(false);
      setVueltas((n) => n + 1);
      enfocarCodigo();
    }
  }

  const escaneos = registro && registro.eventoId === idEvento ? registro.escaneos : undefined;
  const ingresos = escaneos?.filter((r) => r.resultado === 'VALIDA').length ?? 0;
  const rechazos = escaneos?.filter((r) => r.resultado !== 'VALIDA' && r.resultado !== 'SIN_RESPUESTA').length ?? 0;

  return (
    <PageTransition>
      <div className="mx-auto max-w-[1240px] px-7 py-6 md:py-10">
        <h1 className="text-2xl font-semibold tracking-tight text-ink">Validación en puerta</h1>
        {(puertas.fase === 'cargando' || (sinEventosAMostrar && !catalogoListo)) && (
          <p role="status" className="mt-4 text-sm text-muted">
            Buscando tus puertas…
          </p>
        )}
        {puertas.fase === 'error' && (
          <p role="alert" className="mt-4 text-sm text-muted">
            {puertas.mensaje}
          </p>
        )}
        {puertas.fase === 'lista' && idsAsignados.length === 0 && (
          <p role="alert" className="mt-4 max-w-prose text-sm text-muted">
            No estás asignado a la puerta de ningún evento. Pedile al organizador que te agregue a su equipo de
            puerta: cuando lo haga, el evento aparece acá sin recargar la página.
          </p>
        )}
        {sinEventosAMostrar && catalogoListo && (
          <p role="alert" className="mt-4 max-w-prose text-sm text-muted">
            Tus puertas asignadas son de eventos que ya no están en el catálogo, como los cancelados.
          </p>
        )}

        {eventosDeLaPuerta.length > 0 && (
          <div className="mt-6 grid grid-cols-1 gap-6 md:grid-cols-2 md:gap-8">
            <div>
              {/* En el celular no se muestra el visor, que es una imagen fija: así el resultado entra en la pantalla. */}
              <div className="relative hidden h-[320px] overflow-hidden border border-wire md:mb-4 md:block">
                <ImagePlaceholder etiqueta="visor de cámara" className="h-full w-full" />
                <div className="absolute left-1/2 top-1/2 h-[200px] w-[200px] -translate-x-1/2 -translate-y-1/2 overflow-hidden border-2 border-ink">
                  <motion.div
                    className="absolute left-0 right-0 h-0.5 bg-accent"
                    animate={{ top: ['0%', '100%'] }}
                    transition={{ duration: 2, repeat: Infinity, repeatType: 'reverse', ease: 'easeInOut' }}
                  />
                </div>
              </div>

              <form ref={formulario} onSubmit={enviar} className="flex flex-col gap-3">
                <label className="flex flex-col gap-1.5">
                  <span className="font-mono-label text-[11px] text-muted">Evento de esta puerta</span>
                  <select
                    value={idEvento ?? ''}
                    onChange={(e) => {
                      setEventoId(e.target.value);
                      setResultado(null);
                      setError('');
                      enfocarCodigo();
                    }}
                    className="min-h-[44px] border border-edge bg-paper px-3 text-sm"
                  >
                    {!evento && (
                      <option value="" disabled>
                        Elegí un evento
                      </option>
                    )}
                    {eventosDeLaPuerta.map((ev) => (
                      <option key={ev.id} value={ev.id}>
                        {ev.titulo} · {fechaHora(ev.fechaISO)}
                      </option>
                    ))}
                  </select>
                </label>
                {!evento && (
                  <p role="alert" className="text-xs text-ink">
                    Ese evento ya no está entre tus puertas. Elegí otro para seguir.
                  </p>
                )}
                {/* El lector de QR entrega el texto del código; acá también se puede tipear. */}
                <Input
                  label="Código QR"
                  name="codigoQr"
                  value={codigo}
                  onChange={(e) => setCodigo(e.target.value)}
                  placeholder="Contenido del QR"
                  autoComplete="off"
                  autoCapitalize="none"
                  spellCheck={false}
                  maxLength={LARGO_MAXIMO_CODIGO}
                  autoFocus
                />
                <Button type="submit" loading={enviando} loadingText="Validando…" disabled={!idEvento || !codigo.trim()}>
                  Validar entrada
                </Button>
              </form>
            </div>

            <div>
              <AnimatePresence mode="wait">
                {(resultado || error) && (
                  <motion.div
                    ref={mostrarResultado}
                    key={resultado?.id ?? 'error'}
                    initial={{ opacity: 0, y: 8 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0, y: -8 }}
                    transition={{ duration: 0.18 }}
                    role="status"
                    aria-live="polite"
                    className={`mb-8 border-2 p-6 ${resultado?.puedeEntrar ? 'border-accent' : 'border-ink'}`}
                  >
                    {error && !resultado && (
                      <>
                        <p className="text-lg font-semibold text-ink">No se pudo validar</p>
                        <p className="mt-3 text-sm text-graphite">{error}</p>
                      </>
                    )}
                    {resultado && (
                      <>
                        <p className={`text-lg font-semibold ${resultado.puedeEntrar ? 'text-accent' : 'text-ink'}`}>
                          {TITULO_RESULTADO[resultado.resultado] ?? resultado.resultado}
                        </p>
                        <p className="mt-2 text-sm text-graphite">{resultado.mensaje}</p>
                        {resultado.entrada && (
                          <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
                            <div>
                              <p className="font-mono-label text-[10px] text-muted">Titular</p>
                              <p>{resultado.entrada.titular.nombre}</p>
                            </div>
                            <div>
                              <p className="font-mono-label text-[10px] text-muted">Documento</p>
                              <p>{formatearDNI(resultado.entrada.titular.dni)}</p>
                            </div>
                            <div>
                              <p className="font-mono-label text-[10px] text-muted">Sector</p>
                              <p>{resultado.entrada.sector}</p>
                            </div>
                            <div>
                              <p className="font-mono-label text-[10px] text-muted">Entrada</p>
                              <p className="font-mono-label text-[11px]">{resultado.entrada.id}</p>
                            </div>
                          </div>
                        )}
                        {resultado.puedeEntrar && (
                          <p className="mt-4 text-xs text-muted">
                            Pedí el documento y contrastá el nombre: la entrada ya quedó marcada como usada.
                          </p>
                        )}
                        {/* La API identifica la cuenta que escaneó solo por su id interno: se dice si fue esta u otra. */}
                        {resultado.usoAnterior && (
                          <p className="mt-3 text-sm text-graphite">
                            Escaneo anterior: {fechaHora(resultado.usoAnterior.escaneado)} ·{' '}
                            {resultado.usoAnterior.escaneadoPor === usuarioActual?.id
                              ? 'con esta cuenta'
                              : 'con otra cuenta de puerta'}
                          </p>
                        )}
                      </>
                    )}
                  </motion.div>
                )}
              </AnimatePresence>

              {idEvento && escaneos === undefined && (
                <p role="status" className="text-sm text-muted">
                  Cargando el registro de la puerta…
                </p>
              )}
              {escaneos === null && (
                <p className="text-sm text-muted">No pudimos leer el registro de esta puerta. Se vuelve a pedir solo.</p>
              )}
              {escaneos && (
                <>
                  <div className="grid grid-cols-2 gap-3 text-center">
                    <div className="border border-wire p-4">
                      <p className="text-xl font-semibold text-ink">{ingresos.toLocaleString('es-AR')}</p>
                      <p className="font-mono-label text-[10px] text-muted">ingresos</p>
                    </div>
                    <div className="border border-wire p-4">
                      <p className="text-xl font-semibold text-ink">{rechazos.toLocaleString('es-AR')}</p>
                      <p className="font-mono-label text-[10px] text-muted">rechazos</p>
                    </div>
                  </div>

                  <h2 className="mt-8 text-sm font-semibold text-ink">Últimos escaneos</h2>
                  <div className="mt-3 flex flex-col gap-2">
                    {escaneos.length === 0 && (
                      <p className="text-sm text-muted">Todavía no hay escaneos en este evento.</p>
                    )}
                    {escaneos.slice(0, 6).map((e) => (
                      <div key={e.id} className="flex items-center justify-between border border-wire px-4 py-2 text-sm">
                        {/* Tal como llega de Validación, que ya antepone el "…": font-mono-label lo pasaría a mayúsculas. */}
                        <span className="font-mono text-[11px]">{e.codigoParcial}</span>
                        <span
                          className={`font-mono-label text-[10px] ${e.resultado === 'VALIDA' ? 'text-accent' : 'text-faint'}`}
                        >
                          {e.resultado.toLowerCase().replace('_', ' ')}
                        </span>
                        <span className="font-mono-label text-[10px] text-muted">{hora(e.escaneado)}</span>
                      </div>
                    ))}
                  </div>
                </>
              )}
            </div>
          </div>
        )}
      </div>
    </PageTransition>
  );
}
