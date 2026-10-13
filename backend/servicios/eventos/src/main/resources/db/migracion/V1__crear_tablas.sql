-- Esquema "eventos": catálogo de eventos y sus sectores (tipos de entrada).
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

create table evento (
    id              varchar(40)   primary key,
    slug            varchar(160)  not null unique,
    titulo          varchar(200)  not null,
    -- Sin check de lista: en H2 2.4.240 se rompe al cerrarse la conexión de la migración
    -- y todo insert o update falla. Los valores válidos los controla el enumerado Categoria.
    categoria       varchar(20)   not null,
    venue           varchar(200)  not null,
    fecha           timestamp     not null,
    descripcion     varchar(2000) not null,
    destacado       boolean       not null default false,
    imagen_url      varchar(1000),
    -- id del usuario organizador en ServicioDeUsuarios (otro esquema, sin FK)
    organizador_id  varchar(40)
);

create table sector (
    id         varchar(40)  primary key,
    evento_id  varchar(40)  not null references evento (id) on delete cascade,
    nombre     varchar(120) not null,
    precio     bigint       not null check (precio > 0),
    cupo       integer      not null check (cupo > 0),
    vendidas   integer      not null default 0,
    orden      integer      not null,
    constraint sector_vendidas_validas check (vendidas >= 0 and vendidas <= cupo)
);

create index sector_evento_idx on sector (evento_id);
