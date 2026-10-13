import { ErrorApi, comprobarSesion } from '../lib/api';
import { calcularServicio, sleep } from '../lib/format';
import { soloDigitosDNI } from '../lib/validate';
import type { CarritoItem } from '../store/actions';
import type { Asistente, Entrada, Evento, MedioPago, Orden } from '../types';

// Enganche del checkout con ServicioDeVentas (/api/ventas). Ese servicio todavía no está
// implementado en el backend, así que hoy la compra se arma en el navegador. Cuando exista,
// cambia el cuerpo de confirmarCompra() por la llamada real: CheckoutPage ya la espera con
// await y ya muestra el error si falla.
//
// Las demás operaciones del checkout se agregan acá cuando Ventas las ofrezca, una función por
// operación: reservar (se llamará desde continuarCheckout() de EventPage), cancelar (desde el
// modal "Se liberó tu reserva" de CheckoutPage) y consultar el estado de emisión (desde
// ConfirmationPage). No se crean vacías ahora para no inventar un contrato que todavía no existe.

export interface TitularCompra {
  sectorId: string;
  precio: number;
  // Vacíos cuando el comprador eligió "asignar después".
  nombre: string;
  apellido: string;
  dni: string;
}

export interface DatosCompra {
  evento: Evento;
  items: CarritoItem[];
  titulares: TitularCompra[]; // uno por entrada
  medioPago: MedioPago;
}

export interface CompraConfirmada {
  orden: Orden;
  entradas: Entrada[];
}

function generarId(prefijo: string, largo: number): string {
  const alfabeto = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let out = '';
  for (let i = 0; i < largo; i++) out += alfabeto[Math.floor(Math.random() * alfabeto.length)];
  return `${prefijo}-${out}`;
}

export async function confirmarCompra(datos: DatosCompra): Promise<CompraConfirmada> {
  const { evento, items, titulares, medioPago } = datos;

  // Comprar exige una sesión vigente, aunque la compra todavía se arme en el navegador. Cuando
  // esto llame a Ventas, la comprobación la hace pedir() con conSesion y estas líneas se van.
  if (!comprobarSesion()) throw new ErrorApi(401, 'Tenés que iniciar sesión.');

  // Demora simulada del pago, hasta que exista el backend.
  await sleep(1400);

  const idOrden = `A-${Math.floor(10000 + Math.random() * 89999)}`;
  const ahora = new Date().toISOString();
  const subtotal = titulares.reduce((acc, t) => acc + t.precio, 0);
  const servicio = calcularServicio(subtotal);

  const asistentes: Asistente[] = titulares.map((t) => ({
    nombre: t.nombre,
    apellido: t.apellido,
    dni: soloDigitosDNI(t.dni),
  }));

  const orden: Orden = {
    id: idOrden,
    eventoId: evento.id,
    items: items.map((it) => ({
      sectorId: it.sectorId,
      cantidad: it.cantidad,
      precioUnitario: evento.sectores.find((s) => s.id === it.sectorId)?.precio ?? 0,
    })),
    asistentes,
    subtotal,
    servicio,
    total: subtotal + servicio,
    medioPago,
    creadaISO: ahora,
  };

  const entradas: Entrada[] = titulares.map((t, i) => ({
    id: generarId('PLC', 6),
    ordenId: idOrden,
    eventoId: evento.id,
    sectorId: t.sectorId,
    fila: String(1 + (i % 30)),
    precioPagado: t.precio,
    titular: asistentes[i],
    estado: 'valida',
    origen: 'compra',
    emitidaISO: ahora,
  }));

  return { orden, entradas };
}
