-- Códigos para volver a poner la contraseña (uno por cuenta). Tabla aparte de codigo_verificacion:
-- un código pedido para reiniciar la contraseña nunca sirve para verificar un email, ni al revés.
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

create table reinicio_password (
    usuario_id   varchar(40)  primary key references usuario (id) on delete cascade,
    -- SHA-256 del código, nunca el código en claro
    codigo_hash  varchar(64)  not null,
    expira       timestamp    not null,
    enviado      timestamp    not null,
    intentos     integer      not null default 0
);
