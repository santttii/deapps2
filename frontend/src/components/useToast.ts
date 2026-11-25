import { createContext, useContext } from 'react';

// El contexto de los avisos y su hook. Van en un archivo aparte de ToastProvider.tsx porque un
// archivo de componentes que además exporta un hook hace que el refresco en caliente de Vite
// recargue la página entera en lugar del componente.

export interface ToastContextValue {
  mostrarToast: (mensaje: string) => void;
}

export const ToastContext = createContext<ToastContextValue | null>(null);

export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast debe usarse dentro de ToastProvider');
  return ctx;
}
