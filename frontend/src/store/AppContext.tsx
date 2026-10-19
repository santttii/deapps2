import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, type ReactNode } from 'react';
import type { Action, Carrito } from './actions';
import type { Evento, Usuario } from '../types';
import { mensajeDeError } from '../lib/api';
import { listarEventos } from '../servicios/eventos';
import { useSesion } from './SesionContext';

export interface AppState {
  // Copia del catálogo completo del servidor (GET /api/eventos). No se guarda en el navegador.
  eventos: Evento[];
  catalogo: { estado: 'cargando' | 'listo' | 'error'; error: string };
  // Lo único que vive en este navegador: la compra en curso. Entradas, publicaciones de reventa y
  // escaneos se piden al backend cada vez, así no hay una copia local que se desactualice.
  carrito: Carrito | null;
}

type DatosLocales = Pick<AppState, 'carrito'>;

// La "v3" es de cuando la reventa pasó al backend: lo guardado con las claves anteriores eran
// compras, entradas y publicaciones armadas en el navegador, que el servidor nunca conoció. Lo
// único que se conserva de la v2 es el carrito, que tiene el mismo formato.
const STORAGE_KEY = 'palco.state.v3';
const STORAGE_KEYS_ANTERIORES = ['palco.state', 'palco.state.v2'];

function cargarEstado(): AppState {
  const inicial: AppState = {
    eventos: [],
    catalogo: { estado: 'cargando', error: '' },
    carrito: null,
  };
  try {
    const raw = localStorage.getItem(STORAGE_KEY) ?? localStorage.getItem('palco.state.v2');
    for (const clave of STORAGE_KEYS_ANTERIORES) localStorage.removeItem(clave);
    if (!raw) return inicial;
    const guardado = JSON.parse(raw) as Partial<DatosLocales>;
    return { ...inicial, carrito: guardado.carrito ?? null };
  } catch {
    return inicial;
  }
}

function reducer(state: AppState, action: Action): AppState {
  switch (action.type) {
    case 'CATALOGO_CARGANDO':
      return { ...state, catalogo: { estado: 'cargando', error: '' } };
    case 'CATALOGO_CARGADO':
      return { ...state, eventos: action.payload, catalogo: { estado: 'listo', error: '' } };
    case 'CATALOGO_FALLO':
      return { ...state, catalogo: { estado: 'error', error: action.payload } };
    case 'EVENTO_RECIBIDO': {
      // Inserta el evento o reemplaza el que había, y mantiene el orden por fecha del servidor.
      const eventos = [...state.eventos.filter((e) => e.id !== action.payload.id), action.payload].sort(
        (a, b) => new Date(a.fechaISO).getTime() - new Date(b.fechaISO).getTime(),
      );
      return { ...state, eventos };
    }
    case 'SET_CARRITO':
      return { ...state, carrito: action.payload };
    default:
      return state;
  }
}

interface AppContextValue {
  state: AppState;
  dispatch: React.Dispatch<Action>;
  usuarioActual: Usuario | null;
  recargarCatalogo: () => void;
}

const AppContext = createContext<AppContextValue | null>(null);

export function AppProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, undefined, cargarEstado);
  // El usuario sale de la sesión real (SesionContext), no de este estado.
  const { usuario: usuarioActual } = useSesion();

  const pedirCatalogo = useCallback(() => {
    listarEventos()
      .then((eventos) => dispatch({ type: 'CATALOGO_CARGADO', payload: eventos }))
      .catch((error: unknown) => dispatch({ type: 'CATALOGO_FALLO', payload: mensajeDeError(error) }));
  }, []);

  // El catálogo completo se pide una vez, al abrir la aplicación. Las pantallas que cruzan
  // entradas, órdenes y publicaciones con sus eventos lo buscan acá por id.
  useEffect(() => {
    pedirCatalogo();
  }, [pedirCatalogo]);

  const recargarCatalogo = useCallback(() => {
    dispatch({ type: 'CATALOGO_CARGANDO' });
    pedirCatalogo();
  }, [pedirCatalogo]);

  // Se guardan solo los datos locales, no el catálogo.
  useEffect(() => {
    const datos: DatosLocales = { carrito: state.carrito };
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(datos));
    } catch {
      // Sin almacenamiento disponible los datos locales duran hasta recargar la página.
    }
  }, [state.carrito]);

  const value = useMemo(
    () => ({ state, dispatch, usuarioActual, recargarCatalogo }),
    [state, usuarioActual, recargarCatalogo],
  );

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp(): AppContextValue {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('useApp debe usarse dentro de AppProvider');
  return ctx;
}
