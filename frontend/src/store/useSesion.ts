import { createContext, useContext } from 'react';
import type { Usuario } from '../types';

// El contexto de la sesión y su hook. Van en un archivo aparte de SesionContext.tsx, que tiene el
// proveedor, por lo mismo que useToast.ts: así el refresco en caliente de Vite recarga solo el componente.

// Quién está logueado. Las páginas leen el usuario con useApp().usuarioActual; este contexto
// se usa directo solo para las acciones: ingresar, verificarEmail y salir.
export interface SesionContextValue {
  usuario: Usuario | null;
  ingresar: (email: string, password: string) => Promise<void>;
  verificarEmail: (email: string, codigo: string) => Promise<void>;
  salir: () => void;
}

export const SesionContext = createContext<SesionContextValue | null>(null);

export function useSesion(): SesionContextValue {
  const ctx = useContext(SesionContext);
  if (!ctx) throw new Error('useSesion debe usarse dentro de SesionProvider');
  return ctx;
}
