import { createContext, useContext, useEffect, useMemo, useReducer, type ReactNode } from 'react';
import type { Action, Carrito } from './actions';
import type { Entrada, Evento, Orden, Publicacion, Usuario } from '../types';
import { eventos as eventosIniciales, imagenPorSemilla } from '../data/events';
import { publicacionesIniciales } from '../data/listings';

export interface EntradaConDueno extends Entrada {
  propietarioId: string;
}

export interface UsuarioConPassword extends Usuario {
  password: string;
}

export interface AppState {
  usuarioActualId: string | null;
  usuarios: UsuarioConPassword[];
  eventos: Evento[];
  entradas: EntradaConDueno[];
  publicaciones: Publicacion[];
  ordenes: Orden[];
  carrito: Carrito | null;
}

const usuariosSemilla: UsuarioConPassword[] = [
  {
    id: 'user-titular',
    nombre: 'Martina',
    apellido: 'Suárez',
    dni: '29888777',
    email: 'titular@palco.test',
    password: 'palco1234',
    rol: 'user',
    identidadVerificada: true,
  },
  {
    id: 'user-organizador',
    nombre: 'Cuenta',
    apellido: 'Organizadora',
    dni: '27444555',
    email: 'organizador@palco.test',
    password: 'palco1234',
    rol: 'organizer',
    identidadVerificada: true,
  },
  {
    id: 'user-staff',
    nombre: 'Cuenta',
    apellido: 'de Puerta',
    dni: '29666777',
    email: 'puerta@palco.test',
    password: 'palco1234',
    rol: 'staff',
    identidadVerificada: true,
  },
];

const STORAGE_KEY = 'palco.state';

function estadoInicial(): AppState {
  return {
    usuarioActualId: null,
    usuarios: usuariosSemilla,
    eventos: eventosIniciales,
    entradas: [],
    publicaciones: publicacionesIniciales,
    ordenes: [],
    carrito: null,
  };
}

function cargarEstado(): AppState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return estadoInicial();
    const parsed = JSON.parse(raw) as AppState;
    const base = estadoInicial();
    return {
      ...base,
      ...parsed,
      usuarios: parsed.usuarios?.length ? parsed.usuarios : base.usuarios,
      eventos: parsed.eventos?.length
        ? parsed.eventos.map((ev) => ({
            ...ev,
            imagenUrl: ev.imagenUrl ?? base.eventos.find((b) => b.id === ev.id)?.imagenUrl ?? imagenPorSemilla(ev.slug),
          }))
        : base.eventos,
      publicaciones: parsed.publicaciones ?? base.publicaciones,
      entradas: parsed.entradas ?? [],
      ordenes: parsed.ordenes ?? [],
      carrito: parsed.carrito ?? null,
    };
  } catch {
    return estadoInicial();
  }
}

function reducer(state: AppState, action: Action): AppState {
  switch (action.type) {
    case 'SET_CARRITO':
      return { ...state, carrito: action.payload };
    case 'LOGIN':
      return { ...state, usuarioActualId: action.payload.id };
    case 'LOGOUT':
      return { ...state, usuarioActualId: null };
    case 'REGISTER': {
      const nuevo: UsuarioConPassword = { ...action.payload };
      return { ...state, usuarios: [...state.usuarios, nuevo], usuarioActualId: nuevo.id };
    }
    case 'CREAR_ORDEN': {
      const { orden, entradas, stockDescontado } = action.payload;
      const propietarioId = state.usuarioActualId ?? '';
      const nuevasEntradas: EntradaConDueno[] = entradas.map((e) => ({ ...e, propietarioId }));
      const eventos = state.eventos.map((ev) => {
        const descuentos = stockDescontado.filter((d) => d.eventoId === ev.id);
        if (descuentos.length === 0) return ev;
        return {
          ...ev,
          sectores: ev.sectores.map((s) => {
            const d = descuentos.find((x) => x.sectorId === s.id);
            return d ? { ...s, vendidas: s.vendidas + d.cantidad } : s;
          }),
        };
      });
      return {
        ...state,
        entradas: [...state.entradas, ...nuevasEntradas],
        ordenes: [...state.ordenes, orden],
        eventos,
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
    case 'CREAR_EVENTO':
      return { ...state, eventos: [...state.eventos, action.payload] };
    default:
      return state;
  }
}

interface AppContextValue {
  state: AppState;
  dispatch: React.Dispatch<Action>;
  usuarioActual: Usuario | null;
}

const AppContext = createContext<AppContextValue | null>(null);

export function AppProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(reducer, undefined, cargarEstado);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  }, [state]);

  const usuarioActual = useMemo(
    () => state.usuarios.find((u) => u.id === state.usuarioActualId) ?? null,
    [state.usuarios, state.usuarioActualId],
  );

  const value = useMemo(() => ({ state, dispatch, usuarioActual }), [state, usuarioActual]);

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp(): AppContextValue {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error('useApp debe usarse dentro de AppProvider');
  return ctx;
}
