import { useState, type FormEvent } from 'react';
import { mensajeDeError } from '../lib/api';
import { preguntarAlAsistente, type RespuestaAsistente } from '../servicios/asistente';
import Button from '../components/Button';
import Input from '../components/Input';
import PageTransition from '../components/PageTransition';

interface Intercambio {
  pregunta: string;
  respuesta?: RespuestaAsistente;
  error?: string;
}

const SUGERENCIAS = [
  '¿Hasta cuándo puedo revender mi entrada?',
  '¿Cuántas entradas puedo comprar?',
  '¿Qué tengo que llevar a la puerta?',
];

// Cómo se explica de dónde salió cada respuesta: el modelo, las preguntas frecuentes o un guardrail.
const ORIGEN: Record<RespuestaAsistente['origen'], string> = {
  MODELO: 'Respuesta del asistente',
  FALLBACK: 'Respuesta de las preguntas frecuentes',
  BLOQUEADA: 'Pregunta bloqueada por seguridad',
};

export default function AssistantPage() {
  const [pregunta, setPregunta] = useState('');
  const [enviando, setEnviando] = useState(false);
  const [historial, setHistorial] = useState<Intercambio[]>([]);

  async function enviar(texto: string) {
    const limpia = texto.trim();
    if (!limpia || enviando) return;
    setEnviando(true);
    setPregunta('');
    try {
      const respuesta = await preguntarAlAsistente(limpia);
      setHistorial((h) => [{ pregunta: limpia, respuesta }, ...h]);
    } catch (e) {
      setHistorial((h) => [{ pregunta: limpia, error: mensajeDeError(e) }, ...h]);
    } finally {
      setEnviando(false);
    }
  }

  function alEnviar(e: FormEvent) {
    e.preventDefault();
    void enviar(pregunta);
  }

  return (
    <PageTransition>
      <div className="mx-auto max-w-[820px] px-7 py-10">
        <h1 className="text-2xl font-semibold tracking-tight text-ink">Asistente de Palco</h1>
        <p className="mt-2 max-w-xl text-sm text-muted">
          Respondo dudas sobre compras, entradas, reventa y la puerta del evento. Solo leo: no compro, no publico
          ni cancelo. No compartas tu DNI ni datos de tarjeta.
        </p>

        <form onSubmit={alEnviar} className="mt-6 flex flex-col gap-3 sm:flex-row sm:items-end">
          <div className="flex-1">
            <Input
              label="Tu pregunta"
              value={pregunta}
              maxLength={500}
              onChange={(e) => setPregunta(e.target.value)}
              placeholder="Por ejemplo: ¿hasta cuándo puedo revender?"
            />
          </div>
          <Button type="submit" disabled={enviando || !pregunta.trim()}>
            {enviando ? 'Pensando…' : 'Preguntar'}
          </Button>
        </form>

        <div className="mt-3 flex flex-wrap gap-2">
          {SUGERENCIAS.map((s) => (
            <button
              key={s}
              type="button"
              onClick={() => void enviar(s)}
              disabled={enviando}
              className="border border-edge px-3 py-1.5 text-xs text-muted hover:border-accent hover:text-ink"
            >
              {s}
            </button>
          ))}
        </div>

        <ul className="mt-8 flex flex-col gap-4" aria-live="polite">
          {historial.map((h, i) => (
            <li key={historial.length - i} className="border border-edge bg-paper p-4">
              <p className="text-sm font-medium text-ink">{h.pregunta}</p>
              {h.error && <p className="mt-2 text-sm font-medium text-ink" role="alert">No pude preguntar: {h.error}</p>}
              {h.respuesta && (
                <>
                  <p className="mt-2 text-[15px] text-ink">{h.respuesta.respuesta}</p>
                  <p className="font-mono-label mt-3 text-[10px] text-muted">
                    {ORIGEN[h.respuesta.origen]}
                    {h.respuesta.motivo ? ` · ${h.respuesta.motivo}` : ''} · {h.respuesta.milisegundos} ms
                  </p>
                  {h.respuesta.derivarASoporte && (
                    <p className="mt-2 text-xs text-muted">¿Necesitás más ayuda? Escribinos a soporte.</p>
                  )}
                </>
              )}
            </li>
          ))}
        </ul>
      </div>
    </PageTransition>
  );
}
