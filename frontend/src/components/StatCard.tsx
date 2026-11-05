import { useEffect, useRef, useState } from 'react';

interface StatCardProps {
  etiqueta: string;
  // null mientras el dato no llegó. Así la tarjeta no muestra un 0 que parezca un dato.
  valor: number | null;
  formatear?: (n: number) => string;
  duracionMs?: number;
  // Si el pedido falló: la tarjeta muestra el mensaje en vez del número.
  error?: string;
  onReintentar?: () => void;
}

export default function StatCard({
  etiqueta,
  valor,
  formatear = (n) => n.toLocaleString('es-AR'),
  duracionMs = 900,
  error = '',
  onReintentar,
}: StatCardProps) {
  const [mostrado, setMostrado] = useState(0);
  const inicio = useRef<number | null>(null);

  useEffect(() => {
    if (valor === null) return;
    let frame: number;
    const paso = (t: number) => {
      if (inicio.current === null) inicio.current = t;
      const progreso = Math.min(1, (t - inicio.current) / duracionMs);
      const eased = 1 - Math.pow(1 - progreso, 3);
      setMostrado(Math.round(eased * valor));
      if (progreso < 1) frame = requestAnimationFrame(paso);
    };
    frame = requestAnimationFrame(paso);
    return () => cancelAnimationFrame(frame);
  }, [valor, duracionMs]);

  return (
    <div className="border border-wire bg-paper p-5">
      <p className="font-mono-label text-[10px] text-muted">{etiqueta}</p>
      {error ? (
        <div role="alert" className="mt-2">
          <p className="text-3xl font-semibold tracking-tight text-muted" aria-hidden="true">
            —
          </p>
          <p className="mt-1 text-xs text-muted">
            {error}{' '}
            {onReintentar && (
              <button type="button" className="text-accent underline" onClick={onReintentar}>
                Reintentar
              </button>
            )}
          </p>
        </div>
      ) : valor === null ? (
        <p className="mt-2 text-3xl font-semibold tracking-tight text-muted">
          <span aria-hidden="true">…</span>
          <span className="sr-only">Cargando</span>
        </p>
      ) : (
        <p className="mt-2 text-3xl font-semibold tracking-tight text-ink">{formatear(mostrado)}</p>
      )}
    </div>
  );
}
