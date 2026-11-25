import { createContext, useContext, type Dispatch } from 'react';
import type { Action, Carrito } from './actions';
import type { Evento, Usuario } from '../types';

// El estado de la aplicación, su contexto y su hook. Van en un archivo aparte de AppContext.tsx, que
// tiene el proveedor, por lo mismo que useToast.ts: así el refresco en caliente de Vite recarga solo
// el componente.

export interface AppState {
  // Copia del catálogo completo del servidor (GET /api/eventos). No se guarda en el navegador.
  eventos: Evento[];
  catalogo: { estado: 'cargando' | 'listo' | 'error'; error: string };
  // Lo único que vive en este navegador: la compra en curso. Entradas, publicaciones de reventa y
  // escaneos se piden al backend cada vez, así no hay una copia local que se desactualice.
  carrito: Carrito | null;
}

export interface AppContextValue {
  state: AppState;
  dispatch: Dispatch<Action>;
  usuarioActual: Usuario | null;
  recargarCatalogo: () => void;
}

export const AppContext = createContext<AppContextValue | null>(null);

export function useApp(): AppContextValue {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('useApp debe usarse dentro de AppProvider');
  return ctx;
}
