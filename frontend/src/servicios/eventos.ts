import { pedir } from '../lib/api';
import { imagenDeEvento } from '../data/events';
import type { Categoria, Evento } from '../types';

// Una función por endpoint de ServicioDeEventos (/api/eventos).

export interface FiltrosEventos {
  categoria?: Categoria;
  q?: string; // busca en título y lugar, sin distinguir mayúsculas ni tildes
}

export interface NuevoEvento {
  titulo: string;
  categoria: Categoria;
  venue: string;
  fechaISO: string; // fecha y hora local sin zona, como la entrega el input datetime-local
  descripcion: string;
  sectores: { nombre: string; precio: number; cupo: number }[];
}

// El backend manda imagenUrl en null cuando el evento no tiene imagen.
type EventoDelServidor = Omit<Evento, 'imagenUrl'> & { imagenUrl?: string | null };

// Único lugar donde un evento del servidor pasa al tipo del frontend y recibe su foto local.
function aEvento(ev: EventoDelServidor): Evento {
  const imagenUrl = ev.imagenUrl ?? undefined;
  return { ...ev, imagenUrl: imagenDeEvento({ id: ev.id, categoria: ev.categoria, imagenUrl }) };
}

// Sin filtros devuelve el catálogo completo, ordenado por fecha.
export async function listarEventos(filtros: FiltrosEventos = {}): Promise<Evento[]> {
  const eventos = await pedir<EventoDelServidor[]>('/api/eventos', {
    consulta: { categoria: filtros.categoria, q: filtros.q },
  });
  return eventos.map(aEvento);
}

// Requiere sesión con rol organizer: los eventos que creó quien tiene la sesión, ordenados por fecha.
export async function listarMisEventos(): Promise<Evento[]> {
  const eventos = await pedir<EventoDelServidor[]>('/api/eventos/mios', { conSesion: true });
  return eventos.map(aEvento);
}

export async function obtenerEvento(slug: string): Promise<Evento> {
  return aEvento(await pedir<EventoDelServidor>(`/api/eventos/${encodeURIComponent(slug)}`));
}

// Requiere sesión con rol organizer. El id, el slug y los ids de sector los genera el servidor.
export async function crearEvento(datos: NuevoEvento): Promise<Evento> {
  const creado = await pedir<EventoDelServidor>('/api/eventos', {
    metodo: 'POST',
    cuerpo: datos,
    conSesion: true,
  });
  return aEvento(creado);
}
