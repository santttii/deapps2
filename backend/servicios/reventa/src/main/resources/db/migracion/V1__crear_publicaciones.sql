-- Esquema "reventa": las publicaciones de la reventa oficial.
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.
--
-- La entrada vive en ServicioDeVentas: acá se guarda su id y una copia de lo que hace falta para
-- listar y cobrar (evento, sector, precio pagado), sin claves foráneas a otro esquema.
-- Los montos son pesos enteros. Las fechas van en UTC y las pone el código.
-- El estado no lleva check de lista (en H2 2.4.240 se rompe al cerrarse la conexión de la migración):
-- los valores válidos (ACTIVA, VENDIDA, RETIRADA) los controla el enumerado EstadoPublicacion.

create table publicacion (
    -- "R-" más 8 caracteres de un UUID; lo asigna el código
    id               varchar(40)   primary key,
    entrada_id       varchar(40)   not null,
    -- El usuario que publica (claim "sub"); vive en ServicioDeUsuarios
    vendedor_id      varchar(40)   not null,
    evento_id        varchar(40)   not null,
    sector_id        varchar(40)   not null,
    sector           varchar(120)  not null,
    -- Lo que pagó el vendedor, sin cargo de servicio: la base del mínimo y del tope
    precio_pagado    bigint        not null,
    precio           bigint        not null,
    precio_maximo    bigint        not null,
    estado           varchar(20)   not null,
    publicada        timestamp     not null,
    -- La compra en curso: quién la está comprando, con qué Idempotency-Key y desde cuándo.
    -- Los anota un solo UPDATE condicional antes de cobrar; así de dos compras a la vez cobra una sola.
    comprador_id     varchar(40),
    clave_compra     varchar(80),
    compra_intentada timestamp,
    -- Cuando se vende: la venta nueva del comprador en Ventas y el pago en Pagos
    venta_id         varchar(40),
    pago_id          varchar(40),
    cerrada          timestamp,
    constraint publicacion_precio_valido check (precio > 0),
    constraint publicacion_precio_tope check (precio <= precio_maximo)
);

-- El catálogo de la reventa se lista por evento y estado; "mis publicaciones", por vendedor
create index publicacion_evento_estado_idx on publicacion (evento_id, estado);
create index publicacion_vendedor_idx on publicacion (vendedor_id);
