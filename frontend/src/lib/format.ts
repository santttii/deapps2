import { MINIMO_REVENTA, TASA_SERVICIO, TOPE_REVENTA } from '../types';

export function money(value: number): string {
  return `$${Math.round(value).toLocaleString('es-AR')}`;
}

export function fecha(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleDateString('es-AR', { day: '2-digit', month: 'short', year: 'numeric' });
}

export function fechaHora(iso: string): string {
  const d = new Date(iso);
  const fechaStr = d.toLocaleDateString('es-AR', { day: '2-digit', month: 'short', year: 'numeric' });
  // Reloj de 24 horas: sin esto el navegador escribe "09:00 p. m." y quedaba "09:00 p. m.hs".
  const horaStr = d.toLocaleTimeString('es-AR', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
  return `${fechaStr} · ${horaStr} hs`;
}

export function calcularTope(precioPagado: number): number {
  return Math.floor(precioPagado * (1 + TOPE_REVENTA));
}

export function calcularMinimoReventa(precioPagado: number): number {
  return Math.ceil(precioPagado * MINIMO_REVENTA);
}

// Cargo por servicio estimado, para el resumen del checkout antes de reservar. El importe que
// vale es el que calcula el backend al iniciar la compra.
export function calcularServicio(subtotal: number): number {
  return Math.round(subtotal * TASA_SERVICIO);
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
