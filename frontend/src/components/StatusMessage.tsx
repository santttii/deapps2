import Button from './Button';

interface StatusMessageProps {
  titulo: string;
  texto?: string;
  accion?: string;
  onAccion?: () => void;
}

// Bloque para los estados "vacío" y "error" de una pantalla, con un botón opcional
// (por ejemplo "Reintentar").
export default function StatusMessage({ titulo, texto, accion, onAccion }: StatusMessageProps) {
  return (
    <div className="border border-dashed border-edge p-14 text-center">
      <p className="text-lg font-medium text-ink">{titulo}</p>
      {texto && <p className="mt-2 text-sm text-muted">{texto}</p>}
      {accion && onAccion && (
        <Button variant="secondary" className="mt-5" onClick={onAccion}>
          {accion}
        </Button>
      )}
    </div>
  );
}
