import { pedir } from '../lib/api';

// ServicioDeAsistente (/api/v1/asistente): el gateway de IA. Solo responde dudas; no compra ni publica.

export type OrigenRespuesta = 'MODELO' | 'FALLBACK' | 'BLOQUEADA';

export interface RespuestaAsistente {
  respuesta: string;
  origen: OrigenRespuesta;
  motivo: string | null; // timeout, apagado, inyeccion, datos-personales...; null si respondió el modelo
  derivarASoporte: boolean;
  milisegundos: number;
}

export function preguntarAlAsistente(pregunta: string): Promise<RespuestaAsistente> {
  return pedir<RespuestaAsistente>('/api/v1/asistente/preguntas', {
    metodo: 'POST',
    cuerpo: { pregunta },
    conSesion: true,
  });
}
