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

// Los datos de un evento al editarlo. PUT reemplaza el evento completo: un sector con id es uno
// que ya existe y se cambia, uno sin id se agrega, y los que no vengan se quitan.
export interface EventoEditado extends Omit<NuevoEvento, 'sectores'> {
  imagenUrl: string | null;
  sectores: { id?: string; nombre: string; precio: number; cupo: number }[];
}

// El backend manda imagenUrl en null cuando el evento no tiene imagen.
type EventoDelServidor = Omit<Evento, 'imagenUrl'> & { imagenUrl?: string | null };

// Único lugar donde un evento del servidor pasa al tipo del frontend y recibe su foto local.
function aEvento(ev: EventoDelServidor): Evento {
  const imagenUrl = ev.imagenUrl ?? undefined;
  return {
    ...ev,
    imagenUrl: imagenDeEvento({ id: ev.id, categoria: ev.categoria, imagenUrl }),
    imagenDelServidor: ev.imagenUrl ?? null,
  };
}

// Sin filtros devuelve el catálogo completo, ordenado por fecha.
export async function listarEventos(filtros: FiltrosEventos = {}): Promise<Evento[]> {
  const eventos = await pedir<EventoDelServidor[]>('/api/eventos', {
    consulta: { categoria: filtros.categoria, q: filtros.q },
  });
  return eventos.map(aEvento);
}

// Los eventos del organizador de la sesión (rol organizer), ordenados por fecha. Es lo que muestra
// su panel: el catálogo tiene también los de otros organizadores.
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

// PUT /api/eventos/{id}: solo el organizador dueño. Responde 409 si el evento está cancelado o ya
// empezó, si se quita un sector con ventas, si se baja su cupo por debajo de lo vendido o si se le
// cambia el precio.
export async function editarEvento(eventoId: string, datos: EventoEditado): Promise<Evento> {
  const editado = await pedir<EventoDelServidor>(`/api/eventos/${encodeURIComponent(eventoId)}`, {
    metodo: 'PUT',
    cuerpo: datos,
    conSesion: true,
  });
  return aEvento(editado);
}

// POST /api/eventos/{id}/cancelacion: no se puede deshacer. Ventas anula cada compra pagada, pide
// el reembolso y Notificaciones le avisa a cada comprador. El motivo es opcional.
export async function cancelarEvento(eventoId: string, motivo: string): Promise<Evento> {
  const cancelado = await pedir<EventoDelServidor>(`/api/eventos/${encodeURIComponent(eventoId)}/cancelacion`, {
    metodo: 'POST',
    cuerpo: { motivo: motivo.trim() || null },
    conSesion: true,
  });
  return aEvento(cancelado);
}
