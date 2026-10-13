import { pedir } from '../lib/api';
import { soloDigitosDNI } from '../lib/validate';
import type { CarritoItem } from '../store/actions';
import {
  MINUTOS_RESERVA,
  type Asistente,
  type Entrada,
  type EstadoEntrada,
  type EstadoOrden,
  type MedioPago,
  type Orden,
} from '../types';

// Una función por operación de ServicioDeVentas (/api/v1). Todas piden sesión con rol user.
//
// La compra tiene dos pedidos que cambian algo: iniciar (reserva el cupo 10 minutos) y pagar.
// Los dos viajan con una clave de idempotencia: si el mismo pedido llega dos veces con la misma
// clave (doble clic, reintento después de un corte), el backend no lo hace dos veces y devuelve
// la misma venta.

export interface NuevaCompra {
  eventoId: string;
  items: CarritoItem[];
  titulares: Asistente[]; // uno por entrada, en el orden de items
}

export interface DatosDePago {
  medioPago: MedioPago;
  token: string; // lo que devolvería la pasarela de pagos; ver tokenDePrueba()
}

// Así llega una venta del backend. Solo se declaran los campos que el frontend usa.
interface VentaDelServidor {
  id: string;
  estado: string; // EN_CHECKOUT, CONFIRMADA, EMITIDA, CANCELADA o VENCIDA
  eventoId: string;
  items: { sectorId: string; cantidad: number; precioUnitario: number }[];
  subtotal: number;
  cargoServicio: number;
  montoTotal: number;
  venceReserva: string;
  entradas: { id: string }[];
}

// Así llega una entrada del backend (GET /api/v1/entradas/{id}).
interface EntradaDelServidor {
  id: string;
  ventaId: string;
  eventoId: string;
  sectorId: string;
  precioPagado: number;
  titular: { nombre: string; dni: string };
  estado: string; // PENDIENTE_EMISION, VALIDA, USADA, PUBLICADA, VENDIDA o ANULADA
  emitida: string | null; // null mientras no se emitió
  codigoQr: string | null; // null mientras no se emitió
}

// Único lugar donde una venta del servidor pasa al tipo del frontend.
function aOrden(venta: VentaDelServidor): Orden {
  // El backend no manda la fecha de la compra: se calcula desde el vencimiento de la reserva.
  const creada = new Date(Date.parse(venta.venceReserva) - MINUTOS_RESERVA * 60_000);
  return {
    id: venta.id,
    estado: venta.estado.toLowerCase() as EstadoOrden,
    eventoId: venta.eventoId,
    items: venta.items.map((it) => ({
      sectorId: it.sectorId,
      cantidad: it.cantidad,
      precioUnitario: it.precioUnitario,
    })),
    subtotal: venta.subtotal,
    servicio: venta.cargoServicio,
    total: venta.montoTotal,
    venceReservaISO: venta.venceReserva,
    creadaISO: creada.toISOString(),
    entradaIds: venta.entradas.map((e) => e.id),
  };
}

// Único lugar donde una entrada del servidor pasa al tipo del frontend.
function aEntrada(entrada: EntradaDelServidor): Entrada {
  return {
    id: entrada.id,
    ordenId: entrada.ventaId,
    eventoId: entrada.eventoId,
    sectorId: entrada.sectorId,
    precioPagado: entrada.precioPagado,
    // El backend guarda nombre y apellido juntos y no hay forma segura de volver a separarlos.
    titular: { nombre: entrada.titular.nombre, apellido: '', dni: entrada.titular.dni },
    // Las entradas se piden cuando la venta ya está emitida: a esa altura ya no están pendientes.
    estado: entrada.estado.toLowerCase() as EstadoEntrada,
    origen: 'compra',
    emitidaISO: entrada.emitida ?? '',
    codigoQr: entrada.codigoQr ?? undefined,
  };
}

function rutaDeVenta(ordenId: string): string {
  return `/api/v1/ventas/${encodeURIComponent(ordenId)}`;
}

// Clave de idempotencia nueva. El backend exige un UUID.
export function nuevaClave(): string {
  return crypto.randomUUID();
}

// Reserva el cupo por 10 minutos. Los titulares viajan acá y después no se pueden cambiar.
// El precio no se manda: lo pone el backend.
export async function iniciarCompra(datos: NuevaCompra, clave: string): Promise<Orden> {
  const venta = await pedir<VentaDelServidor>('/api/v1/ventas', {
    metodo: 'POST',
    cuerpo: {
      eventoId: datos.eventoId,
      items: datos.items.map((it) => ({ sectorId: it.sectorId, cantidad: it.cantidad })),
      titulares: datos.titulares.map((t) => ({
        nombre: `${t.nombre.trim()} ${t.apellido.trim()}`,
        dni: soloDigitosDNI(t.dni),
      })),
    },
    conSesion: true,
    claveIdempotencia: clave,
  });
  return aOrden(venta);
}

export async function obtenerOrden(ordenId: string): Promise<Orden> {
  return aOrden(await pedir<VentaDelServidor>(rutaDeVenta(ordenId), { conSesion: true }));
}

// Devuelve la orden ya confirmada. Las entradas se emiten unos segundos después: hasta entonces
// la orden queda en 'confirmada' y hay que volver a consultarla.
export async function pagarCompra(ordenId: string, pago: DatosDePago, clave: string): Promise<Orden> {
  const venta = await pedir<VentaDelServidor>(`${rutaDeVenta(ordenId)}/pagos`, {
    metodo: 'POST',
    cuerpo: { medioDePago: pago.medioPago, token: pago.token },
    conSesion: true,
    claveIdempotencia: clave,
  });
  return aOrden(venta);
}

// Cancela una compra sin pagar y devuelve el cupo. Repetirlo no falla.
export async function cancelarCompra(ordenId: string): Promise<void> {
  await pedir<void>(rutaDeVenta(ordenId), { metodo: 'DELETE', conSesion: true });
}

// Cancela sin esperar ni avisar, para cuando el usuario abandona la compra. Si falla no pasa
// nada: la reserva vence sola a los 10 minutos.
export function soltarReserva(ordenId: string): void {
  cancelarCompra(ordenId).catch(() => {});
}

export async function obtenerEntrada(entradaId: string): Promise<Entrada> {
  const entrada = await pedir<EntradaDelServidor>(`/api/v1/entradas/${encodeURIComponent(entradaId)}`, {
    conSesion: true,
  });
  return aEntrada(entrada);
}

// La venta trae sus entradas resumidas (sin sector, precio ni QR): se pide cada una por su id.
export function obtenerEntradas(orden: Orden): Promise<Entrada[]> {
  return Promise.all(orden.entradaIds.map(obtenerEntrada));
}

// Simula el SDK de la pasarela de pagos, que en un sistema real convierte los datos de la tarjeta
// en un token: así la tarjeta nunca viaja a nuestro backend. Como el cobro es simulado, los
// últimos cuatro dígitos eligen el resultado, para poder mostrar cada caso en la demo.
export function tokenDePrueba(medioPago: MedioPago, numeroTarjeta: string): string {
  const digitos = numeroTarjeta.replace(/\D/g, '');
  if (medioPago === 'tarjeta' && digitos.endsWith('0002')) return 'tok_rechazado';
  if (medioPago === 'tarjeta' && digitos.endsWith('0504')) return 'tok_sin_respuesta';
  return 'tok_test';
}
