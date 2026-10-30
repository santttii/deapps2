-- Qué cuenta de puerta (staff) trabaja en qué evento (limitación L17). Solo una cuenta asignada escanea
-- y ve el registro de ese evento; asigna y quita el organizador dueño del evento.
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.
--
-- La cuenta va por su email, que es lo que tipea el organizador y lo que trae el token (claim "upn"):
-- así no hace falta preguntarle a Usuarios el id. No se comprueba que el email sea de una cuenta staff:
-- uno que no existe no deja escanear a nadie.

create table puerta_asignada (
    -- El evento (id en ServicioDeEventos, otro esquema, sin FK)
    evento_id     varchar(40)   not null,
    -- El email de la cuenta de puerta, en minúsculas
    staff_email   varchar(200)  not null,
    -- El organizador que la asignó (claim "sub" de su token)
    asignada_por  varchar(40)   not null,
    -- En UTC y sin fracción de segundo; lo pone el código
    asignada      timestamp     not null,
    constraint puerta_asignada_pk primary key (evento_id, staff_email)
);

-- La pantalla de puerta pide los eventos de una cuenta
create index puerta_asignada_staff_idx on puerta_asignada (staff_email);

-- La cuenta de puerta de prueba trabaja en los seis eventos iniciales, que son de organizador@palco.test
-- (V6 de Eventos).
insert into puerta_asignada (evento_id, staff_email, asignada_por, asignada) values
('ev-1', 'puerta@palco.test', 'user-organizador', '2026-10-23 00:00:00'),
('ev-2', 'puerta@palco.test', 'user-organizador', '2026-10-23 00:00:00'),
('ev-3', 'puerta@palco.test', 'user-organizador', '2026-10-23 00:00:00'),
('ev-4', 'puerta@palco.test', 'user-organizador', '2026-10-23 00:00:00'),
('ev-5', 'puerta@palco.test', 'user-organizador', '2026-10-23 00:00:00'),
('ev-6', 'puerta@palco.test', 'user-organizador', '2026-10-23 00:00:00');
