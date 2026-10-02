-- Esquema "usuarios": identidad, credenciales y roles.
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

create table usuario (
    id                    varchar(40)  primary key,
    nombre                varchar(80)  not null,
    apellido              varchar(80)  not null,
    dni                   varchar(8)   not null unique,
    email                 varchar(200) not null unique,
    telefono              varchar(30),
    fecha_nacimiento      date,
    -- PBKDF2: "pbkdf2-sha256$<iteraciones>$<sal base64>$<hash base64>"
    password_hash         varchar(200) not null,
    rol                   varchar(20)  not null check (rol in ('user', 'organizer', 'staff')),
    email_verificado      boolean      not null default false,
    identidad_verificada  boolean      not null default false,
    creado                timestamp    not null
);

-- Código de 6 dígitos que se manda por email al registrarse (uno vigente por usuario).
create table codigo_verificacion (
    usuario_id   varchar(40)  primary key references usuario (id) on delete cascade,
    -- SHA-256 del código, nunca el código en claro
    codigo_hash  varchar(64)  not null,
    expira       timestamp    not null,
    enviado      timestamp    not null,
    intentos     integer      not null default 0
);
