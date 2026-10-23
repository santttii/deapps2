-- Los 6 eventos de prueba se cargaron sin dueño (V2). Pasan a la cuenta organizadora de prueba
-- (user-organizador en ServicioDeUsuarios), así el panel y el registro de la puerta, que ahora
-- filtran por dueño, siguen mostrándolos. Solo toca los que no tienen dueño.
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

update evento set organizador_id = 'user-organizador'
where id in ('ev-1', 'ev-2', 'ev-3', 'ev-4', 'ev-5', 'ev-6') and organizador_id is null;

create index evento_organizador_idx on evento (organizador_id);
