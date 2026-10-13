import type { Categoria, Evento } from '../types';

// Los eventos vienen del backend (servicios/eventos.ts). Acá quedan solo las fotos, que están
// en public/eventos (dominio público, ver CREDITOS.md en esa carpeta).
const IMAGEN_POR_CATEGORIA: Record<Categoria, string> = {
  'Fútbol': '/eventos/categoria-futbol.jpg',
  'Música': '/eventos/categoria-musica.jpg',
  'Fiesta': '/eventos/categoria-fiesta.jpg',
  'Teatro': '/eventos/categoria-teatro.jpg',
};

// Foto propia de cada evento de prueba del backend, por id.
const FOTO_POR_EVENTO: Record<string, string> = {
  'ev-1': '/eventos/atletico-norte-racing-sur.jpg',
  'ev-2': '/eventos/kiroshi-tour-continental.jpg',
  'ev-3': '/eventos/subsuelo-noche-09.jpg',
  'ev-4': '/eventos/el-metodo-gronholm.jpg',
  'ev-5': '/eventos/copa-federal-semifinal.jpg',
  'ev-6': '/eventos/marea-baja-invitados.jpg',
};

export function imagenPorCategoria(categoria: Categoria): string {
  return IMAGEN_POR_CATEGORIA[categoria];
}

// El backend todavía devuelve imágenes de relleno de picsum.photos, que cambian al azar: se
// reemplazan por la foto local del evento o, si no tiene, por la de su categoría.
export function imagenDeEvento(ev: Pick<Evento, 'id' | 'categoria' | 'imagenUrl'>): string {
  if (ev.imagenUrl && !ev.imagenUrl.includes('picsum.photos')) return ev.imagenUrl;
  return FOTO_POR_EVENTO[ev.id] ?? imagenPorCategoria(ev.categoria);
}
