-- DEF-32: el registro de la puerta mostraba el id interno de la cuenta que escaneó (user-staff) y no su
-- email, que es como el organizador la conoce: la asigna a la puerta por el email. El email sale del mismo
-- token que el id (claim "upn") y se guarda en minúsculas.
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.
--
-- La columna admite null: los escaneos guardados antes de esta migración no lo tienen, y no se completan
-- acá porque los emails son de Usuarios, otro esquema que este servicio no lee. Para esos el registro sigue
-- mostrando el id. Mismo largo que puerta_asignada.staff_email: solo escanea una cuenta asignada a la puerta,
-- así que su email ya entró en esa columna.

alter table escaneo add column escaneado_por_email varchar(200);
