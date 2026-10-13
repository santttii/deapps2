-- Fotos reales de los 6 eventos de prueba. Reemplazan las de picsum.photos, que eran al azar.
-- Son rutas del sitio del frontend, que guarda las imágenes en frontend/public/eventos.

update evento set imagen_url = '/eventos/atletico-norte-racing-sur.jpg' where id = 'ev-1';
update evento set imagen_url = '/eventos/kiroshi-tour-continental.jpg'  where id = 'ev-2';
update evento set imagen_url = '/eventos/subsuelo-noche-09.jpg'         where id = 'ev-3';
update evento set imagen_url = '/eventos/el-metodo-gronholm.jpg'        where id = 'ev-4';
update evento set imagen_url = '/eventos/copa-federal-semifinal.jpg'    where id = 'ev-5';
update evento set imagen_url = '/eventos/marea-baja-invitados.jpg'      where id = 'ev-6';
