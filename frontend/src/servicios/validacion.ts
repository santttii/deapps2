import { pedir } from '../lib/api';

// Una función por operación de Validación (/api/v1/validacion). Escanear pide rol staff y estar
// asignado a la puerta de ese evento (si no, 403 puerta-no-asignada); el registro lo ven ese staff
// y el organizador dueño del evento. La puerta de cada evento la arma su organizador.
//
// Un escaneo responde 201 sea cual sea el resultado: que la persona no pueda entrar es un dato
// de la respuesta (resultado y puedeEntrar), no un error. El único error propio es 504, cuando
// Ventas no contesta y no se sabe si la entrada quedó usada.

export type ResultadoEscaneo = 'VALIDA' | 'YA_USADA' | 'INEXISTENTE' | 'OTRO_EVENTO' | 'NO_HABILITADA' | 'SIN_RESPUESTA';

export interface ResultadoDeEscaneo {
  id: string;
  resultado: ResultadoEscaneo;
  puedeEntrar: boolean;
  mensaje: string; // escrito por el backend para el personal de puerta
  eventoId: string;
  // Solo con VALIDA: la entrada que se acaba de usar, para contrastar el titular con el documento.
  entrada: { id: string; sectorId: string; sector: string; titular: { nombre: string; dni: string } } | null;
  // Solo con YA_USADA: el escaneo que la usó antes, si este servicio lo registró.
  usoAnterior: { escaneoId: string; resultado: string; escaneadoPor: string; escaneado: string } | null;
  escaneadoPor: string;
  escaneado: string;
}

// Un renglón del registro. El código viaja recortado (codigoParcial): el completo queda en la base.
export interface Escaneo {
  id: string;
  eventoId: string;
  codigoParcial: string;
  resultado: ResultadoEscaneo;
  entradaId: string | null;
  escaneadoPor: string;
  escaneado: string;
}

// El largo máximo de un código que acepta Validación (el de la columna en su base).
export const LARGO_MAXIMO_CODIGO = 200;

// Ventas genera los códigos como UUID en minúsculas y sin espacios, y la búsqueda distingue
// mayúsculas: así un código tipeado con espacios, o leído con Bloq Mayús, vale igual.
export async function escanear(codigoQr: string, eventoId: string): Promise<ResultadoDeEscaneo> {
  return pedir<ResultadoDeEscaneo>('/api/v1/validacion/escaneos', {
    metodo: 'POST',
    cuerpo: { codigoQr: codigoQr.replace(/\s+/g, '').toLowerCase(), eventoId },
    conSesion: true,
  });
}

// El registro de la puerta de un evento, del más nuevo al más viejo.
export async function listarEscaneos(eventoId: string): Promise<Escaneo[]> {
  return pedir<Escaneo[]>('/api/v1/validacion/escaneos', { consulta: { eventoId }, conSesion: true });
}

// Una cuenta de puerta asignada a un evento, por su email.
export interface PuertaAsignada {
  eventoId: string;
  staffEmail: string;
  asignadaPor: string;
  asignada: string;
}

// Los eventos donde puede escanear la cuenta de puerta de la sesión.
export async function misPuertas(): Promise<PuertaAsignada[]> {
  return pedir<PuertaAsignada[]>('/api/v1/validacion/puertas/mias', { conSesion: true });
}

// Las cuentas de la puerta de un evento propio (rol organizer). Uno ajeno responde 404.
export async function equipoDePuerta(eventoId: string): Promise<PuertaAsignada[]> {
  return pedir<PuertaAsignada[]>('/api/v1/validacion/puertas', { consulta: { eventoId }, conSesion: true });
}

const rutaDePuerta = (eventoId: string, email: string) =>
  `/api/v1/validacion/puertas/${encodeURIComponent(eventoId)}/${encodeURIComponent(email.trim())}`;

// Asignar dos veces la misma cuenta responde la misma asignación.
export async function asignarPuerta(eventoId: string, email: string): Promise<PuertaAsignada> {
  return pedir<PuertaAsignada>(rutaDePuerta(eventoId, email), { metodo: 'PUT', conSesion: true });
}

export async function quitarPuerta(eventoId: string, email: string): Promise<void> {
  await pedir<void>(rutaDePuerta(eventoId, email), { metodo: 'DELETE', conSesion: true });
}
