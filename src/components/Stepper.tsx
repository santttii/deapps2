interface StepperProps {
  pasos: string[];
  activo: number;
  onVolver?: (index: number) => void;
}

export default function Stepper({ pasos, activo, onVolver }: StepperProps) {
  return (
    <ol className="flex w-full" role="list">
      {pasos.map((paso, i) => {
        const estado = i < activo ? 'completo' : i === activo ? 'activo' : 'pendiente';
        const clickeable = estado === 'completo' && onVolver;
        return (
          <li key={paso} className="flex-1">
            <button
              type="button"
              disabled={!clickeable}
              onClick={() => clickeable && onVolver?.(i)}
              className={`font-mono-label w-full border border-l-0 first:border-l px-3 py-3 text-center text-[11px] ${
                estado === 'activo'
                  ? 'bg-accent text-white'
                  : estado === 'completo'
                    ? 'bg-ink text-paper'
                    : 'bg-fill text-faint'
              } ${clickeable ? 'cursor-pointer' : 'cursor-default'}`}
            >
              {i + 1} · {paso}
            </button>
          </li>
        );
      })}
    </ol>
  );
}
