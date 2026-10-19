import type { Evento, MedioPago } from '../types';

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
  // navegador está adelantado). Se mide al reservar y lo usa la cuenta regresiva de esa reserva;
  // sin reserva no se usa.
  desfaseReloj: number;
  // El último pago de esa venta terminó sin resultado (504 o sin conexión): no se sabe si se cobró.
  // Guarda con qué se pagó, para repetir el mismo pedido con la misma clave. El token es el que
  // entrega la pasarela, no los datos de la tarjeta. Sin un pago así queda undefined o null.
  pagoSinResultado?: { medioPago: MedioPago; token: string } | null;
}

export type Action =
  // Catálogo: copia de lo que devuelve el servidor.
  | { type: 'CATALOGO_CARGANDO' }
  | { type: 'CATALOGO_CARGADO'; payload: Evento[] }
  | { type: 'CATALOGO_FALLO'; payload: string }
  | { type: 'EVENTO_RECIBIDO'; payload: Evento }
  // Compra en curso. Las compras hechas no se guardan: se piden a Ventas (GET /api/v1/ventas).
  // Las publicaciones de reventa y los escaneos tampoco: se piden a Reventa y a Validación.
  | { type: 'SET_CARRITO'; payload: Carrito | null };
