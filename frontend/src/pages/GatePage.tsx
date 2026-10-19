import { useEffect, useState, type FormEvent } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useApp } from '../store/AppContext';
import { mensajeDeError } from '../lib/api';
import { fechaHora, formatearDNI } from '../lib/format';
import { escanear, listarEscaneos, type Escaneo, type ResultadoDeEscaneo } from '../servicios/validacion';
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

function hora(iso: string): string {
  return new Date(iso).toLocaleTimeString('es-AR', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
}

// Control de acceso del personal de puerta (rol staff). Cada código leído (o tipeado) va a
// Validación (POST /api/v1/validacion/escaneos), que consulta a Ventas y marca la entrada como
// usada en el mismo paso: no hay un segundo botón de "marcar ingreso". El resultado, el texto y
// el titular para contrastar con el documento vienen del backend.
export default function GatePage() {
  const { state } = useApp();
  const [eventoId, setEventoId] = useState('');
  const [codigo, setCodigo] = useState('');
  const [resultado, setResultado] = useState<ResultadoDeEscaneo | null>(null);
  const [error, setError] = useState('');
  const [enviando, setEnviando] = useState(false);
  const [registro, setRegistro] = useState<Escaneo[]>([]);
  const [vueltas, setVueltas] = useState(0); // sube después de cada escaneo

  const evento = state.eventos.find((e) => e.id === eventoId) ?? state.eventos[0];
  const idEvento = evento?.id;

  // El registro de esta puerta, del más nuevo al más viejo. Si no se puede leer, la puerta sigue
  // escaneando: el registro es informativo.
  useEffect(() => {
    if (!idEvento) return;
    let vigente = true;
    listarEscaneos(idEvento)
      .then((lista) => vigente && setRegistro(lista))
      .catch(() => vigente && setRegistro([]));
    return () => {
      vigente = false;
    };
  }, [idEvento, vueltas]);

  async function enviar(e: FormEvent) {
    e.preventDefault();
    if (!idEvento || !codigo.trim()) return;
    setEnviando(true);
    setError('');
    try {
      setResultado(await escanear(codigo, idEvento));
      setCodigo('');
      setVueltas((n) => n + 1);
    } catch (err) {
      // 504: Ventas no respondió y no se sabe si la entrada quedó usada. Hay que escanear de nuevo.
      setResultado(null);
      setError(mensajeDeError(err));
    } finally {
      setEnviando(false);
    }
  }

  const ingresos = registro.filter((r) => r.resultado === 'VALIDA').length;
  const rechazos = registro.filter((r) => r.resultado !== 'VALIDA' && r.resultado !== 'SIN_RESPUESTA').length;

  return (
    <PageTransition>
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        <h1 className="text-2xl font-semibold tracking-tight text-ink">Validación en puerta</h1>

        <div className="mt-6 grid grid-cols-1 gap-8 md:grid-cols-2">
          <div>
            <div className="relative h-[320px] overflow-hidden border border-wire">
              <ImagePlaceholder etiqueta="visor de cámara" className="h-full w-full" />
              <div className="absolute left-1/2 top-1/2 h-[200px] w-[200px] -translate-x-1/2 -translate-y-1/2 overflow-hidden border-2 border-ink">
                <motion.div
                  className="absolute left-0 right-0 h-0.5 bg-accent"
                  animate={{ top: ['0%', '100%'] }}
                  transition={{ duration: 2, repeat: Infinity, repeatType: 'reverse', ease: 'easeInOut' }}
                />
              </div>
            </div>

            <form onSubmit={enviar} className="mt-4 flex flex-col gap-3">
              <label className="flex flex-col gap-1.5">
                <span className="font-mono-label text-[11px] text-muted">Evento de esta puerta</span>
                <select
                  value={idEvento ?? ''}
                  onChange={(e) => {
                    setEventoId(e.target.value);
                    setResultado(null);
                    setError('');
                  }}
                  className="min-h-[44px] border border-edge bg-paper px-3 text-sm"
                >
                  {state.eventos.map((ev) => (
                    <option key={ev.id} value={ev.id}>
                      {ev.titulo} · {fechaHora(ev.fechaISO)}
                    </option>
                  ))}
                </select>
              </label>
              {/* El lector de QR entrega el texto del código; acá también se puede tipear. */}
              <Input
                label="Código QR"
                value={codigo}
                onChange={(e) => setCodigo(e.target.value)}
                placeholder="Contenido del QR"
                autoComplete="off"
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
                  key={resultado?.id ?? 'error'}
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, y: -8 }}
                  transition={{ duration: 0.18 }}
                  role="status"
                  aria-live="polite"
                  className={`border-2 p-6 ${resultado?.puedeEntrar ? 'border-accent' : 'border-ink'}`}
                >
                  {error && !resultado && (
                    <>
                      <p className="text-lg font-semibold text-ink">No se pudo validar</p>
                      <p className="mt-3 text-sm text-graphite">{error}</p>
                      <p className="mt-2 text-xs text-muted">Escaneá la entrada de nuevo.</p>
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
                      {resultado.usoAnterior && (
                        <p className="mt-3 text-sm text-graphite">
                          Escaneo anterior: {fechaHora(resultado.usoAnterior.escaneado)} · {resultado.usoAnterior.escaneadoPor}
                        </p>
                      )}
                    </>
                  )}
                </motion.div>
              )}
            </AnimatePresence>

            <div className="mt-8 grid grid-cols-2 gap-3 text-center">
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
              {registro.length === 0 && <p className="text-sm text-muted">Todavía no hay escaneos en este evento.</p>}
              {registro.slice(0, 6).map((e) => (
                <div key={e.id} className="flex items-center justify-between border border-wire px-4 py-2 text-sm">
                  <span className="font-mono-label text-[11px]">…{e.codigoParcial}</span>
                  <span className={`font-mono-label text-[10px] ${e.resultado === 'VALIDA' ? 'text-accent' : 'text-faint'}`}>
                    {e.resultado.toLowerCase().replace('_', ' ')}
                  </span>
                  <span className="font-mono-label text-[10px] text-muted">{hora(e.escaneado)}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </PageTransition>
  );
}
