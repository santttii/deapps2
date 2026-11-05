import { MINIMO_REVENTA, TASA_SERVICIO, TOPE_REVENTA, type Rol, type Sector } from '../types';

export function money(value: number): string {
  return `$${Math.round(value).toLocaleString('es-AR')}`;
}

// El precio más bajo que todavía se puede comprar. Si están todos agotados, el más bajo de todos:
// la tarjeta ya dice "agotado" y el precio queda como referencia.
export function precioDesde(sectores: Sector[]): number {
  const conLugar = sectores.filter((s) => s.vendidas < s.cupo);
  return Math.min(...(conLugar.length > 0 ? conLugar : sectores).map((s) => s.precio));
}

// Cómo se nombra cada rol en los textos para el usuario.
export function nombreDelRol(rol: Rol): string {
  if (rol === 'organizer') return 'organizador';
  if (rol === 'staff') return 'personal de puerta';
  return 'comprador';
}

export function fecha(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleDateString('es-AR', { day: '2-digit', month: 'short', year: 'numeric' });
}

// Reloj de 24 horas: sin hourCycle el navegador escribe "09:00 p. m." y quedaba "09:00 p. m.hs".
export function hora(iso: string): string {
  return new Date(iso).toLocaleTimeString('es-AR', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
}

export function fechaHora(iso: string): string {
  return `${fecha(iso)} · ${hora(iso)} hs`;
}

export function calcularTope(precioPagado: number): number {
  return Math.floor(precioPagado * (1 + TOPE_REVENTA));
}

export function calcularMinimoReventa(precioPagado: number): number {
  return Math.ceil(precioPagado * MINIMO_REVENTA);
}

// Cargo por servicio estimado, para el detalle del evento y el resumen del checkout antes de
// reservar. El importe que vale es el que calcula el backend al iniciar la compra.
export function calcularServicio(subtotal: number): number {
  return Math.round(subtotal * TASA_SERVICIO);
}

// Vencimiento de tarjeta: solo dígitos, con la barra después del mes. Así se puede tipear con el
// teclado numérico del teléfono, que no tiene "/".
export function formatearVencimiento(valor: string): string {
  const digits = valor.replace(/\D/g, '').slice(0, 4);
  return digits.length > 2 ? `${digits.slice(0, 2)}/${digits.slice(2)}` : digits;
}

export function formatearDNI(dni: string): string {
  const digits = dni.replace(/\D/g, '');
  if (digits.length <= 2) return digits;
  if (digits.length <= 5) return `${digits.slice(0, digits.length - 3)}.${digits.slice(-3)}`;
  return `${digits.slice(0, digits.length - 6)}.${digits.slice(-6, -3)}.${digits.slice(-3)}`;
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
