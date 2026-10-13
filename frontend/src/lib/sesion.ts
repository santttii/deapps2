import type { Usuario } from '../types';

// La sesión que devuelve el backend al ingresar. Este archivo es el único que la lee y la
// escribe en localStorage, para que sobreviva a recargar la página.
export interface Sesion {
  token: string;
  expiraEn: number; // momento en que vence el token, en milisegundos
  usuario: Usuario;
}

export const CLAVE_SESION = 'palco.sesion';

// Devuelve la sesión guardada, o null si no hay, no se puede leer o ya venció.
// Solo lee: no borra nada, así borrarSesion() puede decir si había algo guardado.
export function leerSesion(): Sesion | null {
  try {
    const guardado = localStorage.getItem(CLAVE_SESION);
    if (!guardado) return null;
    const sesion = JSON.parse(guardado) as Partial<Sesion> | null;
    if (!sesion || typeof sesion.token !== 'string' || !sesion.usuario) return null;
    if (typeof sesion.expiraEn !== 'number' || sesion.expiraEn <= Date.now()) return null;
    return { token: sesion.token, expiraEn: sesion.expiraEn, usuario: sesion.usuario };
  } catch {
    return null;
  }
}

export function guardarSesion(sesion: Sesion): void {
  try {
    localStorage.setItem(CLAVE_SESION, JSON.stringify(sesion));
  } catch {
    // Sin almacenamiento disponible no hay dónde guardar la sesión: se pierde al cambiar de
    // página o en el primer pedido con token.
  }
}

// Devuelve true si había algo guardado, aunque estuviera vencido o roto.
export function borrarSesion(): boolean {
  try {
    const habia = localStorage.getItem(CLAVE_SESION) !== null;
    localStorage.removeItem(CLAVE_SESION);
    return habia;
  } catch {
    return false;
  }
}
