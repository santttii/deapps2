import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, type ReactNode } from 'react';
import type { Action, Carrito } from './actions';
import type { Entrada, Evento, Publicacion, Usuario } from '../types';
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
  // Lo que vive en este navegador, asociado al usuario de la sesión. La reventa todavía no tiene
  // backend y es local: por eso se guarda una copia de las entradas emitidas que devolvió Ventas,
  // con el estado de reventa que les da este navegador. Qué compras tiene el usuario no se guarda
  // acá: "Mis entradas" se lo pide a Ventas cada vez.
  entradas: EntradaConDueno[];
  publicaciones: Publicacion[];
  carrito: Carrito | null;
}

type DatosLocales = Pick<AppState, 'entradas' | 'publicaciones' | 'carrito'>;

// La "v2" es de cuando la compra pasó a hacerse en el backend: lo guardado con la clave anterior
// eran compras armadas en el navegador, que el servidor nunca conoció. Por eso se borra.
const STORAGE_KEY = 'palco.state.v2';
const STORAGE_KEY_ANTERIOR = 'palco.state';

function cargarEstado(): AppState {
  const inicial: AppState = {
    eventos: [],
    catalogo: { estado: 'cargando', error: '' },
    entradas: [],
    publicaciones: publicacionesIniciales,
    carrito: null,
  };
  try {
    localStorage.removeItem(STORAGE_KEY_ANTERIOR);
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return inicial;
    const guardado = JSON.parse(raw) as Partial<DatosLocales>;
    return {
      ...inicial,
      entradas: guardado.entradas ?? [],
      publicaciones: guardado.publicaciones ?? publicacionesIniciales,
      carrito: guardado.carrito ?? null,
    };
  } catch {
    return inicial;
  }
}

// Estados de una entrada que hoy solo cambia este navegador (reventa y control de acceso locales).
const ESTADOS_LOCALES: Entrada['estado'][] = ['publicada', 'vendida', 'usada'];

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
    case 'GUARDAR_ENTRADAS': {
      // Agrega las que no estaban. Una que ya estaba toma los datos del backend, salvo que este
      // navegador le haya cambiado el estado: publicada o vendida en la reventa local, o usada en
      // el control de acceso local. El backend todavía no conoce esos cambios y no hay que pisarlos.
      const { entradas, compradorId } = action.payload;
      const recibidas = new Map(entradas.map((e) => [e.id, e]));
      const actualizadas = state.entradas.map((guardada) => {
        const recibida = recibidas.get(guardada.id);
        if (!recibida || ESTADOS_LOCALES.includes(guardada.estado)) return guardada;
        return { ...recibida, propietarioId: guardada.propietarioId };
      });
      const nuevas: EntradaConDueno[] = entradas
        .filter((e) => !state.entradas.some((guardada) => guardada.id === e.id))
        .map((e) => ({ ...e, propietarioId: compradorId }));
      return { ...state, entradas: [...actualizadas, ...nuevas] };
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

  // Se guardan solo los datos locales, no el catálogo.
  useEffect(() => {
    const datos: DatosLocales = {
      entradas: state.entradas,
      publicaciones: state.publicaciones,
      carrito: state.carrito,
    };
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(datos));
    } catch {
      // Sin almacenamiento disponible los datos locales duran hasta recargar la página.
    }
  }, [state.entradas, state.publicaciones, state.carrito]);

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
