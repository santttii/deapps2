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
  // Id de la venta en el backend cuando el cupo ya está reservado; null si todavía no se reservó.
  // Como el carrito se guarda en el navegador, la compra en curso sobrevive a recargar la página.
  ordenId: string | null;
  // Clave de idempotencia del pago de esa venta; null si no hay reserva.
  clavePago: string | null;
  // Milisegundos que el reloj del servidor le lleva al de este navegador (negativo si el del
  // navegador está adelantado). Se mide al reservar y lo usa la cuenta regresiva; 0 sin reserva.
  desfaseReloj: number;
}

export type Action =
  // Catálogo: copia de lo que devuelve el servidor.
  | { type: 'CATALOGO_CARGANDO' }
  | { type: 'CATALOGO_CARGADO'; payload: Evento[] }
  | { type: 'CATALOGO_FALLO'; payload: string }
  | { type: 'EVENTO_RECIBIDO'; payload: Evento }
  // Compra: el navegador recuerda qué compró el usuario; los datos son los del backend (Ventas).
  | { type: 'SET_CARRITO'; payload: Carrito | null }
  | { type: 'GUARDAR_COMPRA'; payload: { orden: Orden; entradas: Entrada[]; compradorId: string } }
  // Datos locales: lo que todavía no tiene backend.
  | { type: 'PUBLICAR_REVENTA'; payload: Publicacion }
  | { type: 'RETIRAR_PUBLICACION'; payload: { publicacionId: string } }
  | { type: 'COMPRAR_REVENTA'; payload: { publicacionId: string; entradaNueva: Entrada; compradorId: string } }
  | { type: 'MARCAR_ENTRADA_USADA'; payload: { entradaId: string } };
