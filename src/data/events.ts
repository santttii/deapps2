import type { Evento } from '../types';

export const eventos: Evento[] = [
  {
    id: 'ev-1',
    slug: 'atletico-norte-racing-sur',
    titulo: 'Atlético Norte vs. Racing del Sur',
    categoria: 'Fútbol',
    venue: 'Estadio Monumental Sur',
    fechaISO: '2026-09-12T17:00:00',
    descripcion:
      'Torneo Clausura, fecha 12. Las puertas abren dos horas antes del inicio.',
    destacado: true,
    sectores: [
      { id: 'sec-1-1', nombre: 'Popular norte', precio: 18000, cupo: 4000, vendidas: 3680 },
      { id: 'sec-1-2', nombre: 'Popular sur', precio: 18000, cupo: 4000, vendidas: 3120 },
      { id: 'sec-1-3', nombre: 'Platea A', precio: 34000, cupo: 2000, vendidas: 2000 },
      { id: 'sec-1-4', nombre: 'Platea B', precio: 27500, cupo: 2000, vendidas: 1440 },
      { id: 'sec-1-5', nombre: 'Palcos', precio: 60000, cupo: 200, vendidas: 90 },
    ],
  },
  {
    id: 'ev-2',
    slug: 'kiroshi-tour-continental',
    titulo: 'Kiroshi — Tour Continental',
    categoria: 'Música',
    venue: 'Arena Central',
    fechaISO: '2026-09-25T21:00:00',
    descripcion:
      'Primera fecha en la región del tour continental. Apertura de puertas a las 19hs.',
    sectores: [
      { id: 'sec-2-1', nombre: 'Campo general', precio: 32000, cupo: 6000, vendidas: 4800 },
      { id: 'sec-2-2', nombre: 'Platea alta', precio: 41000, cupo: 2500, vendidas: 1300 },
    ],
  },
  {
    id: 'ev-3',
    slug: 'subsuelo-noche-09',
    titulo: 'Subsuelo · Noche 09',
    categoria: 'Fiesta',
    venue: 'Club Depósito',
    fechaISO: '2026-10-03T00:30:00',
    descripcion:
      'Novena edición del ciclo. Line up de artistas invitados a confirmar en puerta.',
    sectores: [
      { id: 'sec-3-1', nombre: 'Entrada general', precio: 12000, cupo: 1200, vendidas: 980 },
      { id: 'sec-3-2', nombre: 'Acceso anticipado', precio: 16000, cupo: 300, vendidas: 120 },
    ],
  },
  {
    id: 'ev-4',
    slug: 'el-metodo-gronholm',
    titulo: 'El método Grönholm',
    categoria: 'Teatro',
    venue: 'Teatro San Marcos',
    fechaISO: '2026-10-08T20:30:00',
    descripcion:
      'Comedia de enredos sobre un proceso de selección laboral. Duración: 90 minutos sin intervalo.',
    sectores: [
      { id: 'sec-4-1', nombre: 'Platea', precio: 9500, cupo: 400, vendidas: 310 },
      { id: 'sec-4-2', nombre: 'Pullman', precio: 6800, cupo: 200, vendidas: 80 },
    ],
  },
  {
    id: 'ev-5',
    slug: 'copa-federal-semifinal',
    titulo: 'Copa Federal — Semifinal',
    categoria: 'Fútbol',
    venue: 'Estadio del Parque',
    fechaISO: '2026-10-11T16:00:00',
    descripcion:
      'Partido único, define local por sorteo previo. Ingreso solo con entrada nominativa impresa o digital.',
    sectores: [
      { id: 'sec-5-1', nombre: 'Popular', precio: 22000, cupo: 3000, vendidas: 2600 },
      { id: 'sec-5-2', nombre: 'Platea', precio: 38000, cupo: 1000, vendidas: 940 },
    ],
  },
  {
    id: 'ev-6',
    slug: 'marea-baja-invitados',
    titulo: 'Marea Baja + invitados',
    categoria: 'Música',
    venue: 'Sala Prisma',
    fechaISO: '2026-10-16T20:00:00',
    descripcion:
      'Presentación de su último álbum de estudio, con banda invitada como apertura.',
    sectores: [
      { id: 'sec-6-1', nombre: 'General', precio: 15000, cupo: 800, vendidas: 520 },
    ],
  },
];
