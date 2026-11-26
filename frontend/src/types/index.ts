export type Categoria = 'Deportes' | 'Música' | 'Fiesta' | 'Teatro';
export type Rol = 'user' | 'organizer' | 'staff';

export interface Sector {
  id: string;
  nombre: string;
  precio: number;
  cupo: number;
  vendidas: number;
}

export interface Evento {
  id: string;
  slug: string;
  titulo: string;
  categoria: Categoria;
  venue: string;
  fechaISO: string;
  descripcion: string;
  sectores: Sector[];
  destacado?: boolean;
  imagenUrl?: string;
  // La URL de la foto tal como la guarda Eventos (null si no tiene). imagenUrl es la que se
  // muestra, con la foto local de reemplazo; al editar se manda esta, para no pisarla.
  imagenDelServidor?: string | null;
  // Un evento cancelado sigue respondiendo en su detalle (no en el catálogo) y trae el motivo.
  cancelado?: boolean;
  cancelacionMotivo?: string | null;
}

export interface Asistente {
  nombre: string;
  apellido: string;
  dni: string;
}

// 'pendiente_emision' es la entrada de una compra paga que todavía no tiene código QR.
export type EstadoEntrada = 'pendiente_emision' | 'valida' | 'usada' | 'publicada' | 'vendida' | 'anulada';
export type OrigenEntrada = 'compra' | 'reventa';

export interface Entrada {
  id: string;
  ordenId: string;
  eventoId: string;
  sectorId: string;
  precioPagado: number;
  titular: Asistente;
  estado: EstadoEntrada;
  origen: OrigenEntrada;
  // Cuándo se obtuvo la entrada. En una compra es la fecha de la venta: Ventas no informa la hora
  // de emisión en el listado.
  emitidaISO: string;
  // Lo que lleva el QR. Lo genera Ventas al emitir; una compra en la reventa trae uno nuevo.
  codigoQr?: string;
}

export type EstadoPublicacion = 'activa' | 'vendida' | 'retirada';

// Una publicación de la reventa oficial, como la devuelve Reventa (/api/v1/reventas). El backend
// calcula precioMaximo (el precio de lista del sector + 10%); el frontend no lo recalcula para mostrarlo.
export interface Publicacion {
  id: string;
  entradaId: string;
  eventoId: string;
  sectorId: string;
  precio: number;
  precioMaximo: number;
  estado: EstadoPublicacion;
  publicadaISO: string;
}

export interface Usuario {
  id: string;
  nombre: string;
  apellido: string;
  dni: string;
  email: string;
  rol: Rol;
}

export type MedioPago = 'pasarela' | 'tarjeta' | 'transferencia';

export interface OrdenItem {
  sectorId: string;
  cantidad: number;
  precioUnitario: number;
}

// Estados de una venta en el backend, en minúsculas. 'en_checkout' es la reserva sin pagar;
// 'confirmada' es paga con las entradas todavía emitiéndose; 'emitida' es con las entradas listas.
export type EstadoOrden = 'en_checkout' | 'confirmada' | 'emitida' | 'cancelada' | 'vencida';

// La venta del backend vista por el frontend. Los importes los calcula el servidor.
export interface Orden {
  id: string;
  estado: EstadoOrden;
  eventoId: string;
  items: OrdenItem[];
  subtotal: number;
  servicio: number;
  total: number;
  venceReservaISO: string; // hasta cuándo se puede pagar
  creadaISO: string;
  entradas: Entrada[]; // vacío hasta que se paga
  // El backend ofrece el enlace "pagar": la venta sigue EN_CHECKOUT y admite un pedido de pago.
  sePuedePagar: boolean;
}

// Copia de common/ReglasDeNegocio, en porcentajes enteros como allá: la reventa va del 50% al 110% del precio de
// lista del sector y el cargo de servicio es el 10% del subtotal.
export const TOPE_REVENTA_PORCENTAJE = 10;
export const MINIMO_REVENTA_PORCENTAJE = 50;
export const TASA_SERVICIO_PORCENTAJE = 10;
export const MAX_ENTRADAS_POR_ORDEN = 6;
export const HORAS_LIMITE_REVENTA = 3;
export const MINUTOS_RESERVA = 10;
