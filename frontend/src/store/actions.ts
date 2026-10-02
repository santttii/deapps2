import type { Entrada, Evento, Orden, Publicacion, Usuario } from '../types';

export interface CarritoItem {
  sectorId: string;
  cantidad: number;
}

export interface Carrito {
  eventoId: string;
  items: CarritoItem[];
}

export type Action =
  | { type: 'LOGIN'; payload: Usuario }
  | { type: 'LOGOUT' }
  | { type: 'REGISTER'; payload: Usuario & { password: string } }
  | { type: 'SET_CARRITO'; payload: Carrito | null }
  | {
      type: 'CREAR_ORDEN';
      payload: {
        orden: Orden;
        entradas: Entrada[];
        stockDescontado: { eventoId: string; sectorId: string; cantidad: number }[];
      };
    }
  | { type: 'PUBLICAR_REVENTA'; payload: Publicacion }
  | { type: 'RETIRAR_PUBLICACION'; payload: { publicacionId: string } }
  | { type: 'COMPRAR_REVENTA'; payload: { publicacionId: string; entradaNueva: Entrada; compradorId: string } }
  | { type: 'MARCAR_ENTRADA_USADA'; payload: { entradaId: string } }
  | { type: 'CREAR_EVENTO'; payload: Evento };
