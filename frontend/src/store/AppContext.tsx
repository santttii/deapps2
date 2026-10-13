import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, type ReactNode } from 'react';
import type { Action, Carrito } from './actions';
import type { Entrada, Evento, Orden, Publicacion, Usuario } from '../types';
import { publicacionesIniciales } from '../data/listings';
import { mensajeDeError } from '../lib/api';
import { listarEventos } from '../servicios/eventos';
import { useSesion } from './SesionContext';

export interface EntradaConDueno extends Entrada {
  propietarioId: string;
}

export interface AppState {
  // Copia del catálogo completo del servidor (GET /api/eventos). No se guarda en el navegador.
  eventos: Evento[];
  catalogo: { estado: 'cargando' | 'listo' | 'error'; error: string };
  // Lo que todavía no tiene backend: vive en este navegador, asociado al usuario de la sesión.
  entradas: EntradaConDueno[];
  publicaciones: Publicacion[];
  ordenes: Orden[];
  carrito: Carrito | null;
}

type DatosLocales = Pick<AppState, 'entradas' | 'publicaciones' | 'ordenes' | 'carrito'>;

const STORAGE_KEY = 'palco.state';

function cargarEstado(): AppState {
  const inicial: AppState = {
    eventos: [],
    catalogo: { estado: 'cargando', error: '' },
    entradas: [],
    publicaciones: publicacionesIniciales,
    ordenes: [],
    carrito: null,
  };
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return inicial;
    // De lo guardado se leen solo los datos locales. Un estado de una versión anterior traía
    // además usuarios y eventos de mentira: se ignoran y desaparecen en el primer guardado.
    const guardado = JSON.parse(raw) as Partial<DatosLocales>;
    return {
      ...inicial,
      entradas: guardado.entradas ?? [],
      publicaciones: guardado.publicaciones ?? publicacionesIniciales,
      ordenes: guardado.ordenes ?? [],
      carrito: guardado.carrito ?? null,
    };
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
    case 'CREAR_ORDEN': {
      // No se suman las vendidas del evento: el stock que se muestra es el del servidor.
      const { orden, entradas, compradorId } = action.payload;
      const nuevasEntradas: EntradaConDueno[] = entradas.map((e) => ({ ...e, propietarioId: compradorId }));
      return {
        ...state,
        entradas: [...state.entradas, ...nuevasEntradas],
        ordenes: [...state.ordenes, orden],
      };
    }
    case 'PUBLICAR_REVENTA': {
      const publicaciones = [...state.publicaciones, action.payload];
      const entradas = state.entradas.map((e) =>
        e.id === action.payload.entradaId ? { ...e, estado: 'publicada' as const } : e,
      );
      return { ...state, publicaciones, entradas };
    }
    case 'RETIRAR_PUBLICACION': {
      const pub = state.publicaciones.find((p) => p.id === action.payload.publicacionId);
      const publicaciones = state.publicaciones.map((p) =>
        p.id === action.payload.publicacionId ? { ...p, estado: 'retirada' as const } : p,
      );
      const entradas = state.entradas.map((e) =>
        pub && e.id === pub.entradaId ? { ...e, estado: 'valida' as const } : e,
      );
      return { ...state, publicaciones, entradas };
    }
    case 'COMPRAR_REVENTA': {
      const { publicacionId, entradaNueva, compradorId } = action.payload;
      const pub = state.publicaciones.find((p) => p.id === publicacionId);
      const publicaciones = state.publicaciones.map((p) =>
        p.id === publicacionId ? { ...p, estado: 'vendida' as const } : p,
      );
      const entradas = state.entradas.map((e) =>
        pub && e.id === pub.entradaId ? { ...e, estado: 'vendida' as const } : e,
      );
      const nueva: EntradaConDueno = { ...entradaNueva, propietarioId: compradorId };
      return { ...state, publicaciones, entradas: [...entradas, nueva] };
    }
    case 'MARCAR_ENTRADA_USADA': {
      const entradas = state.entradas.map((e) =>
        e.id === action.payload.entradaId ? { ...e, estado: 'usada' as const } : e,
      );
      return { ...state, entradas };
    }
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

  // Se guardan solo los datos locales. El primer guardado ocurre al montar: ahí se pisa
  // cualquier estado viejo (que incluía contraseñas en texto plano).
  useEffect(() => {
    const datos: DatosLocales = {
      entradas: state.entradas,
      publicaciones: state.publicaciones,
      ordenes: state.ordenes,
      carrito: state.carrito,
    };
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(datos));
    } catch {
      // Sin almacenamiento disponible los datos locales duran hasta recargar la página.
    }
  }, [state.entradas, state.publicaciones, state.ordenes, state.carrito]);

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
