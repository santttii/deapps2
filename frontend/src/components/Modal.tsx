import { AnimatePresence, motion } from 'framer-motion';
import { useEffect, useRef, type ReactNode } from 'react';

interface ModalProps {
  abierto: boolean;
  onCerrar: () => void;
  titulo: string;
  children: ReactNode;
}

const ENFOCABLES =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

// Tab y Shift+Tab dan la vuelta dentro del diálogo: mientras está abierto, la página de atrás no
// se puede usar. Los elementos ocultos (sin cajas en pantalla) no cuentan.
function mantenerFocoAdentro(e: KeyboardEvent, dialogo: HTMLElement) {
  const enfocables = [...dialogo.querySelectorAll<HTMLElement>(ENFOCABLES)].filter(
    (el) => el.getClientRects().length > 0,
  );
  const actual = document.activeElement;
  const adentro = actual !== null && dialogo.contains(actual);
  if (enfocables.length === 0) {
    e.preventDefault();
    dialogo.focus();
    return;
  }
  const primero = enfocables[0];
  const ultimo = enfocables[enfocables.length - 1];
  if (e.shiftKey && (!adentro || actual === primero || actual === dialogo)) {
    e.preventDefault();
    ultimo.focus();
  } else if (!e.shiftKey && (!adentro || actual === ultimo)) {
    e.preventDefault();
    primero.focus();
  }
}

export default function Modal({ abierto, onCerrar, titulo, children }: ModalProps) {
  const ref = useRef<HTMLDivElement>(null);

  // El foco pasa al diálogo solo al abrirlo, y al cerrarlo vuelve a lo que lo tenía antes (el
  // botón que abrió el modal): así el teclado no arranca otra vez desde el principio de la página.
  // Va en un efecto aparte del de las teclas porque ese depende de onCerrar, que suele ser una
  // función nueva en cada render del padre: si el foco se moviera ahí, cada tecla escrita en un
  // campo del modal se lo sacaría al campo.
  useEffect(() => {
    if (!abierto) return;
    const anterior = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    ref.current?.focus();
    return () => anterior?.focus();
  }, [abierto]);

  useEffect(() => {
    if (!abierto) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCerrar();
      if (e.key === 'Tab' && ref.current) mantenerFocoAdentro(e, ref.current);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [abierto, onCerrar]);

  return (
    <AnimatePresence>
      {abierto && (
        <motion.div
          className="fixed inset-0 z-50 flex items-center justify-center bg-ink/60 p-4"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          onClick={onCerrar}
        >
          <motion.div
            ref={ref}
            role="dialog"
            aria-modal="true"
            aria-label={titulo}
            tabIndex={-1}
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 16 }}
            transition={{ duration: 0.22, ease: [0.16, 1, 0.3, 1] }}
            onClick={(e) => e.stopPropagation()}
            className="max-h-[calc(100dvh-2rem)] w-full max-w-md overflow-y-auto bg-paper p-7 shadow-xl"
          >
            <div className="mb-4 flex items-start justify-between gap-3">
              <h2 className="text-xl font-semibold tracking-tight">{titulo}</h2>
              <button
                type="button"
                onClick={onCerrar}
                aria-label="Cerrar"
                className="-mr-2 -mt-1 flex h-11 w-11 shrink-0 items-center justify-center text-2xl leading-none text-muted hover:text-ink"
              >
                ×
              </button>
            </div>
            {children}
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
