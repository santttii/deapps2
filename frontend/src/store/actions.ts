import type { Entrada, Evento, MedioPago, Publicacion } from '../types';

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
  | { type: 'SET_CARRITO'; payload: Carrito | null }
  // Entradas emitidas que llegaron de Ventas. Se copian para la reventa, que todavía es local.
  | { type: 'GUARDAR_ENTRADAS'; payload: { entradas: Entrada[]; compradorId: string } }
  // Datos locales: lo que todavía no tiene backend.
  | { type: 'PUBLICAR_REVENTA'; payload: Publicacion }
  | { type: 'RETIRAR_PUBLICACION'; payload: { publicacionId: string } }
  | { type: 'COMPRAR_REVENTA'; payload: { publicacionId: string; entradaNueva: Entrada; compradorId: string } }
  | { type: 'MARCAR_ENTRADA_USADA'; payload: { entradaId: string } };
