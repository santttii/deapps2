import { useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import ImagePlaceholder from '../components/ImagePlaceholder';
import Button from '../components/Button';
import PageTransition from '../components/PageTransition';

type EstadoResultado = 'valida' | 'usada' | 'invalida';

interface Escaneo {
  id: string;
  hora: string;
  resultado: EstadoResultado;
}

const RESULTADO_VALIDO = {
  titular: 'Martina Suárez',
  dni: '30.111.222',
  sector: 'Platea B',
  origen: 'compra' as const,
};

export default function GatePage() {
  const [estado, setEstado] = useState<EstadoResultado>('valida');
  const [codigoManual, setCodigoManual] = useState(false);
  const [ingresos, setIngresos] = useState(8212);
  const [rechazos, setRechazos] = useState(14);
  const [restantes, setRestantes] = useState(728);
  const [escaneos, setEscaneos] = useState<Escaneo[]>([
    { id: 'PLC-9F2K1A', hora: '20:14', resultado: 'valida' },
    { id: 'PLC-7B3M2C', hora: '20:13', resultado: 'valida' },
    { id: 'PLC-2X9K0P', hora: '20:11', resultado: 'usada' },
  ]);

  function marcarIngreso() {
    setIngresos((v) => v + 1);
    setRestantes((v) => Math.max(0, v - 1));
    registrarEscaneo('valida');
  }

  function rechazar() {
    setRechazos((v) => v + 1);
    registrarEscaneo(estado === 'valida' ? 'usada' : estado);
  }

  function registrarEscaneo(resultado: EstadoResultado) {
    const hora = new Date().toLocaleTimeString('es-AR', { hour: '2-digit', minute: '2-digit' });
    setEscaneos((prev) => [
      { id: `PLC-${Math.random().toString(36).slice(2, 8).toUpperCase()}`, hora, resultado },
      ...prev,
    ]);
  }

  return (
    <PageTransition>
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        <h1 className="text-2xl font-semibold tracking-tight text-ink">Validación en puerta</h1>

        <div className="mt-6 grid grid-cols-1 gap-8 md:grid-cols-2">
          <div>
            <div className="relative h-[420px] overflow-hidden border border-wire">
              <ImagePlaceholder etiqueta="visor de cámara" className="h-full w-full" />
              <div className="absolute left-1/2 top-1/2 h-[220px] w-[220px] -translate-x-1/2 -translate-y-1/2 overflow-hidden border-2 border-ink">
                <motion.div
                  className="absolute left-0 right-0 h-0.5 bg-accent"
                  animate={{ top: ['0%', '100%'] }}
                  transition={{ duration: 2, repeat: Infinity, repeatType: 'reverse', ease: 'easeInOut' }}
                />
              </div>
            </div>
            <div className="mt-4 flex flex-wrap gap-3">
              <Button variant="secondary" onClick={() => setCodigoManual((v) => !v)}>
                Ingresar código manual
              </Button>
              <Button variant="secondary">Modo offline</Button>
            </div>
            {codigoManual && (
              <input
                placeholder="PLC-XXXXXX"
                className="mt-3 min-h-[44px] w-full max-w-xs border border-edge px-3 text-sm uppercase"
              />
            )}

            <div className="mt-8 flex gap-3">
              {(['valida', 'usada', 'invalida'] as EstadoResultado[]).map((e) => (
                <button
                  key={e}
                  onClick={() => setEstado(e)}
                  className={`font-mono-label px-3 py-2 text-[11px] ${
                    estado === e ? 'bg-ink text-paper' : 'border border-edge text-graphite'
                  }`}
                >
                  demo: {e}
                </button>
              ))}
            </div>
          </div>

          <div>
            <AnimatePresence mode="wait">
              <motion.div
                key={estado}
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -8 }}
                transition={{ duration: 0.18 }}
                className={`border-2 p-6 ${estado === 'valida' ? 'border-accent' : 'border-ink'}`}
              >
                {estado === 'valida' && (
                  <>
                    <p className="text-lg font-semibold text-accent">✓ Entrada válida</p>
                    <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
                      <div>
                        <p className="font-mono-label text-[10px] text-muted">Titular</p>
                        <p>{RESULTADO_VALIDO.titular}</p>
                      </div>
                      <div>
                        <p className="font-mono-label text-[10px] text-muted">Documento</p>
                        <p>{RESULTADO_VALIDO.dni}</p>
                      </div>
                      <div>
                        <p className="font-mono-label text-[10px] text-muted">Sector</p>
                        <p>{RESULTADO_VALIDO.sector}</p>
                      </div>
                      <div>
                        <p className="font-mono-label text-[10px] text-muted">Origen</p>
                        <p className="capitalize">{RESULTADO_VALIDO.origen}</p>
                      </div>
                    </div>
                    <p className="mt-4 text-xs text-muted">
                      Pedí el documento y contrastá el nombre antes de marcar el ingreso.
                    </p>
                    <div className="mt-5 flex gap-3">
                      <Button onClick={marcarIngreso}>Marcar ingreso</Button>
                      <Button variant="secondary" onClick={rechazar}>
                        Rechazar
                      </Button>
                    </div>
                  </>
                )}
                {estado === 'usada' && (
                  <>
                    <p className="text-lg font-semibold text-ink">Entrada ya utilizada</p>
                    <p className="mt-3 text-sm text-graphite">Primer ingreso: hoy 19:42hs</p>
                    <p className="text-sm text-graphite">Puesto: Portón 1 · Popular norte</p>
                  </>
                )}
                {estado === 'invalida' && (
                  <>
                    <p className="text-lg font-semibold text-ink">Código no reconocido</p>
                    <p className="font-mono-label mt-3 text-[12px] text-muted">PLC-000000</p>
                  </>
                )}
              </motion.div>
            </AnimatePresence>

            <div className="mt-8 grid grid-cols-3 gap-3 text-center">
              <div className="border border-wire p-4">
                <p className="text-xl font-semibold text-ink">{ingresos.toLocaleString('es-AR')}</p>
                <p className="font-mono-label text-[10px] text-muted">ingresos</p>
              </div>
              <div className="border border-wire p-4">
                <p className="text-xl font-semibold text-ink">{rechazos.toLocaleString('es-AR')}</p>
                <p className="font-mono-label text-[10px] text-muted">rechazos</p>
              </div>
              <div className="border border-wire p-4">
                <p className="text-xl font-semibold text-ink">{restantes.toLocaleString('es-AR')}</p>
                <p className="font-mono-label text-[10px] text-muted">restantes</p>
              </div>
            </div>

            <h2 className="mt-8 text-sm font-semibold text-ink">Últimos escaneos</h2>
            <div className="mt-3 flex flex-col gap-2">
              <AnimatePresence initial={false}>
                {escaneos.slice(0, 6).map((e) => (
                  <motion.div
                    layout
                    key={e.id}
                    initial={{ opacity: 0, y: -8 }}
                    animate={{ opacity: 1, y: 0 }}
                    className="flex items-center justify-between border border-wire px-4 py-2 text-sm"
                  >
                    <span className="font-mono-label text-[11px]">{e.id}</span>
                    <span
                      className={`font-mono-label text-[10px] ${
                        e.resultado === 'valida' ? 'text-accent' : 'text-faint'
                      }`}
                    >
                      {e.resultado}
                    </span>
                    <span className="font-mono-label text-[10px] text-muted">{e.hora}</span>
                  </motion.div>
                ))}
              </AnimatePresence>
            </div>
          </div>
        </div>
      </div>
    </PageTransition>
  );
}
