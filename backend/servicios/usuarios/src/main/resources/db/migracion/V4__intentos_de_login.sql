-- Inicios de sesión fallidos, para frenar a quien prueba contraseñas (SEC-W04).
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.
-- Va en la base y no en memoria para que el límite valga igual con varias instancias del servicio.
create table intento_login (
    id          varchar(36)  primary key,
    -- SHA-256 del email normalizado: también se anotan emails que no tienen cuenta
    email_hash  varchar(64)  not null,
    momento     timestamp    not null
);

-- La consulta de cada login: los fallidos de un email desde hace 15 minutos.
create index intento_login_email_momento on intento_login (email_hash, momento);
