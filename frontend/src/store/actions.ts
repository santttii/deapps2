import type { Entrada, Evento, Orden, Publicacion } from '../types';

export interface CarritoItem {
  sectorId: string;
  cantidad: number;
}

export interface Carrito {
  eventoId: string;
  items: CarritoItem[];
  // Id de quien lo armó, o null si se armó sin sesión (lo normal al venir de la página del evento).
  usuarioId: string | null;
}

export type Action =
  // Catálogo: copia de lo que devuelve el servidor.
  | { type: 'CATALOGO_CARGANDO' }
  | { type: 'CATALOGO_CARGADO'; payload: Evento[] }
  | { type: 'CATALOGO_FALLO'; payload: string }
  | { type: 'EVENTO_RECIBIDO'; payload: Evento }
  // Datos locales: lo que todavía no tiene backend.
  | { type: 'SET_CARRITO'; payload: Carrito | null }
  | { type: 'CREAR_ORDEN'; payload: { orden: Orden; entradas: Entrada[]; compradorId: string } }
  | { type: 'PUBLICAR_REVENTA'; payload: Publicacion }
  | { type: 'RETIRAR_PUBLICACION'; payload: { publicacionId: string } }
  | { type: 'COMPRAR_REVENTA'; payload: { publicacionId: string; entradaNueva: Entrada; compradorId: string } }
  | { type: 'MARCAR_ENTRADA_USADA'; payload: { entradaId: string } };
