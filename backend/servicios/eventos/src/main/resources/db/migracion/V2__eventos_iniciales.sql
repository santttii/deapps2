-- Los 6 eventos de prueba (los mismos que frontend/src/data/events.ts).

insert into evento (id, slug, titulo, categoria, venue, fecha, descripcion, destacado, imagen_url) values
('ev-1', 'atletico-norte-racing-sur', 'Atlético Norte vs. Racing del Sur', 'Fútbol', 'Estadio Monumental Sur',
 '2026-09-12 17:00:00', 'Torneo Clausura, fecha 12. Las puertas abren dos horas antes del inicio.', true,
 'https://picsum.photos/seed/atletico-norte-racing-sur/900/560'),
('ev-2', 'kiroshi-tour-continental', 'Kiroshi — Tour Continental', 'Música', 'Arena Central',
 '2026-09-25 21:00:00', 'Primera fecha en la región del tour continental. Apertura de puertas a las 19hs.', false,
 'https://picsum.photos/seed/kiroshi-tour-continental/900/560'),
('ev-3', 'subsuelo-noche-09', 'Subsuelo · Noche 09', 'Fiesta', 'Club Depósito',
 '2026-10-03 00:30:00', 'Novena edición del ciclo. Line up de artistas invitados a confirmar en puerta.', false,
 'https://picsum.photos/seed/subsuelo-noche-09/900/560'),
('ev-4', 'el-metodo-gronholm', 'El método Grönholm', 'Teatro', 'Teatro San Marcos',
 '2026-10-08 20:30:00', 'Comedia de enredos sobre un proceso de selección laboral. Duración: 90 minutos sin intervalo.', false,
 'https://picsum.photos/seed/el-metodo-gronholm/900/560'),
('ev-5', 'copa-federal-semifinal', 'Copa Federal — Semifinal', 'Fútbol', 'Estadio del Parque',
 '2026-10-11 16:00:00', 'Partido único, define local por sorteo previo. Ingreso solo con entrada nominativa impresa o digital.', false,
 'https://picsum.photos/seed/copa-federal-semifinal/900/560'),
('ev-6', 'marea-baja-invitados', 'Marea Baja + invitados', 'Música', 'Sala Prisma',
 '2026-10-16 20:00:00', 'Presentación de su último álbum de estudio, con banda invitada como apertura.', false,
 'https://picsum.photos/seed/marea-baja-invitados/900/560');

insert into sector (id, evento_id, nombre, precio, cupo, vendidas, orden) values
('sec-1-1', 'ev-1', 'Popular norte', 18000, 4000, 3680, 1),
('sec-1-2', 'ev-1', 'Popular sur',   18000, 4000, 3120, 2),
('sec-1-3', 'ev-1', 'Platea A',      34000, 2000, 2000, 3),
('sec-1-4', 'ev-1', 'Platea B',      27500, 2000, 1440, 4),
('sec-1-5', 'ev-1', 'Palcos',        60000,  200,   90, 5),
('sec-2-1', 'ev-2', 'Campo general', 32000, 6000, 4800, 1),
('sec-2-2', 'ev-2', 'Platea alta',   41000, 2500, 1300, 2),
('sec-3-1', 'ev-3', 'Entrada general',   12000, 1200, 980, 1),
('sec-3-2', 'ev-3', 'Acceso anticipado', 16000,  300, 120, 2),
('sec-4-1', 'ev-4', 'Platea',  9500, 400, 310, 1),
('sec-4-2', 'ev-4', 'Pullman', 6800, 200,  80, 2),
('sec-5-1', 'ev-5', 'Popular', 22000, 3000, 2600, 1),
('sec-5-2', 'ev-5', 'Platea',  38000, 1000,  940, 2),
('sec-6-1', 'ev-6', 'General', 15000,  800,  520, 1);
