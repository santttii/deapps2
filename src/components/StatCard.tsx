import { useEffect, useRef, useState } from 'react';

interface StatCardProps {
  etiqueta: string;
  valor: number;
  formatear?: (n: number) => string;
  duracionMs?: number;
}

export default function StatCard({ etiqueta, valor, formatear = (n) => n.toLocaleString('es-AR'), duracionMs = 900 }: StatCardProps) {
  const [mostrado, setMostrado] = useState(0);
  const inicio = useRef<number | null>(null);

  useEffect(() => {
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
      <p className="mt-2 text-3xl font-semibold tracking-tight text-ink">{formatear(mostrado)}</p>
    </div>
  );
}
