export type Categoria = 'Fútbol' | 'Música' | 'Fiesta' | 'Teatro';
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
}

export interface Asistente {
  nombre: string;
  apellido: string;
  dni: string;
}

export type EstadoEntrada = 'valida' | 'usada' | 'publicada' | 'vendida' | 'anulada';
export type OrigenEntrada = 'compra' | 'reventa';

export interface Entrada {
  id: string;
  ordenId: string;
  eventoId: string;
  sectorId: string;
  fila: string;
  precioPagado: number;
  titular: Asistente;
  estado: EstadoEntrada;
  origen: OrigenEntrada;
  emitidaISO: string;
}

export type EstadoPublicacion = 'activa' | 'vendida' | 'retirada';

export interface Publicacion {
  id: string;
  entradaId: string;
  eventoId: string;
  sectorId: string;
  precioOriginal: number;
  precio: number;
  vendedorDni: string;
  estado: EstadoPublicacion;
  creadaISO: string;
}

export interface Usuario {
  id: string;
  nombre: string;
  apellido: string;
  dni: string;
  email: string;
  rol: Rol;
  identidadVerificada: boolean;
}

export type MedioPago = 'pasarela' | 'tarjeta' | 'transferencia';

export interface OrdenItem {
  sectorId: string;
  cantidad: number;
  precioUnitario: number;
}

export interface Orden {
  id: string;
  eventoId: string;
  items: OrdenItem[];
  asistentes: Asistente[];
  subtotal: number;
  servicio: number;
  total: number;
  medioPago: MedioPago;
  creadaISO: string;
}

export const TOPE_REVENTA = 0.1;
export const TASA_SERVICIO = 0.1;
export const MAX_ENTRADAS_POR_ORDEN = 6;
export const HORAS_LIMITE_REVENTA = 3;
export const MINUTOS_RESERVA = 10;
