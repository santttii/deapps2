interface ProgressBarProps {
  porcentaje: number;
  altura?: number;
  sobreOscuro?: boolean;
}

export default function ProgressBar({ porcentaje, altura = 6, sobreOscuro = false }: ProgressBarProps) {
  const clamped = Math.max(0, Math.min(100, porcentaje));
  const excedido = porcentaje > 100;
  return (
    <div
      className={`w-full overflow-hidden ${sobreOscuro ? 'bg-darkLine' : 'bg-fill'}`}
      style={{ height: altura }}
      role="progressbar"
      aria-valuenow={Math.round(clamped)}
      aria-valuemin={0}
      aria-valuemax={100}
    >
      <div
        className={`h-full ${excedido ? 'bg-accent' : 'bg-accent'} transition-[width] duration-600`}
        style={{ width: `${clamped}%` }}
      />
    </div>
  );
}
