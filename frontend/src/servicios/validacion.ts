import { pedir } from '../lib/api';

// Una función por operación de Validación (/api/v1/validacion). Escanear pide rol staff; el
// registro de la puerta lo ven staff y organizer.
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

export async function escanear(codigoQr: string, eventoId: string): Promise<ResultadoDeEscaneo> {
  return pedir<ResultadoDeEscaneo>('/api/v1/validacion/escaneos', {
    metodo: 'POST',
    cuerpo: { codigoQr: codigoQr.trim(), eventoId },
    conSesion: true,
  });
}

// El registro de la puerta de un evento, del más nuevo al más viejo.
export async function listarEscaneos(eventoId: string): Promise<Escaneo[]> {
  return pedir<Escaneo[]>('/api/v1/validacion/escaneos', { consulta: { eventoId }, conSesion: true });
}
