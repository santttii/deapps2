import { useState } from 'react';
import { mensajeDeError } from '../lib/api';
import { fechaHora } from '../lib/format';
import { cancelarEvento } from '../servicios/eventos';
import type { Evento } from '../types';
import Modal from './Modal';
import Button from './Button';

interface CancelarEventoModalProps {
  // El evento a cancelar; null con el modal cerrado.
  evento: Evento | null;
  // Entradas pagadas del evento según Ventas, para decir a cuántas afecta. null si no se sabe.
  vendidas: number | null;
  onCerrar: () => void;
  onCancelado: (evento: Evento) => void;
}

const MOTIVO_MAXIMO = 500;

// Cancelar un evento no tiene vuelta atrás: el modal dice qué va a pasar y pide confirmarlo con un
// botón propio. El motivo es opcional y es lo que le llega a cada comprador en el aviso.
export default function CancelarEventoModal({ evento, vendidas, onCerrar, onCancelado }: CancelarEventoModalProps) {
  const [motivo, setMotivo] = useState('');
  const [error, setError] = useState('');
  const [cancelando, setCancelando] = useState(false);

  function cerrar() {
    if (cancelando) return;
    setMotivo('');
    setError('');
    onCerrar();
  }

  async function confirmar() {
    if (!evento) return;
    setCancelando(true);
    setError('');
    try {
      const cancelado = await cancelarEvento(evento.id, motivo);
      setMotivo('');
      onCancelado(cancelado);
    } catch (e) {
      setError(mensajeDeError(e));
    } finally {
      setCancelando(false);
    }
  }

  return (
    <Modal abierto={evento !== null} onCerrar={cerrar} titulo="Cancelar evento">
      {evento && (
        <div className="flex flex-col gap-4">
          <div>
            <p className="text-lg font-semibold text-ink">{evento.titulo}</p>
            <p className="font-mono-label mt-1 text-[11px] text-muted">
              {fechaHora(evento.fechaISO)} · {evento.venue}
            </p>
          </div>
          <div className="border-l-2 border-accent pl-3 text-sm text-ink">
            <p className="font-semibold">Esto no se puede deshacer.</p>
            <ul className="mt-2 list-disc pl-4 text-graphite">
              <li>El evento sale del catálogo y no se venden más entradas.</li>
              <li>
                {vendidas === null
                  ? 'Se anulan todas las entradas vendidas'
                  : vendidas === 0
                    ? 'Todavía no hay entradas vendidas'
                    : vendidas === 1
                      ? 'Se anula la única entrada vendida'
                      : `Se anulan las ${vendidas.toLocaleString('es-AR')} entradas vendidas`}
                {vendidas !== 0 && ', se le devuelve el pago a cada comprador y le llega un email con el motivo.'}
                {vendidas === 0 && ': no hay pagos para devolver.'}
              </li>
            </ul>
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="motivo-cancelacion" className="font-mono-label text-[11px] text-muted">
              Motivo (opcional, lo ven los compradores)
            </label>
            <textarea
              id="motivo-cancelacion"
              value={motivo}
              onChange={(e) => setMotivo(e.target.value)}
              rows={3}
              maxLength={MOTIVO_MAXIMO}
              placeholder="Por ejemplo: el estadio no fue habilitado."
              className="border border-edge bg-paper px-3.5 py-2.5 text-[15px]"
            />
            <p className="text-right text-[11px] text-muted">
              {motivo.length} / {MOTIVO_MAXIMO}
            </p>
          </div>
          {error && (
            <p role="alert" className="border-l-2 border-accent pl-2 text-sm text-ink">
              {error}
            </p>
          )}
          <div className="flex flex-col gap-2 sm:flex-row-reverse">
            <Button onClick={confirmar} loading={cancelando} loadingText="Cancelando…" className="sm:flex-1">
              Sí, cancelar el evento
            </Button>
            <Button variant="secondary" onClick={cerrar} disabled={cancelando} className="sm:flex-1">
              Volver
            </Button>
          </div>
        </div>
      )}
    </Modal>
  );
}
