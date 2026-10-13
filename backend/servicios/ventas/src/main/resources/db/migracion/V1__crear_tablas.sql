-- Esquema "ventas": la compra (venta), sus renglones por sector (venta_item) y las entradas que emite (entrada).
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.
--
-- Reglas que valen para las tres tablas:
--   * Los ids los asigna el código ("V-" o "T-" más 8 caracteres de un UUID), no la base.
--   * Los montos son pesos enteros.
--   * Las fechas van en UTC y las pone el código: ninguna columna usa "default current_timestamp".
--   * Los estados no llevan check de lista: en H2 2.4.240 se rompe al cerrarse la conexión de la
--     migración y todo insert o update falla. Los valores válidos los controlan los enumerados
--     EstadoVenta, EstadoFactura y EstadoEntrada, que son quienes escriben esas columnas.
--   * Todas las restricciones llevan nombre: si una migración posterior tiene que quitar alguna,
--     el nombre es el mismo en las dos bases.

create table venta (
    id                  varchar(40)  primary key,
    -- id del comprador en ServicioDeUsuarios y del evento en ServicioDeEventos (otros esquemas, sin FK)
    comprador_id        varchar(40)  not null,
    evento_id           varchar(40)  not null,
    -- EN_CHECKOUT, CONFIRMADA, EMITIDA, CANCELADA o VENCIDA
    estado              varchar(20)  not null,
    -- NO_APLICA hasta que exista la facturación; después AUTORIZADA, PENDIENTE o RECHAZADA
    factura_estado      varchar(20)  not null,
    -- La venta se guarda antes de reservar el cupo, todavía sin renglones: por eso los montos pueden ser 0
    subtotal            bigint       not null,
    cargo_servicio      bigint       not null,
    monto_total         bigint       not null,
    -- Hasta cuándo vale la reserva del cupo. Es el único reloj de la reserva
    vence_reserva       timestamp    not null,
    -- La Idempotency-Key del pedido que creó la venta
    clave_idempotencia  varchar(80)  not null,
    -- La Idempotency-Key del intento de pago; null si nadie intentó pagar
    clave_pago          varchar(80),
    -- id del pago aprobado; null hasta que se confirma
    pago_id             varchar(40),
    creada              timestamp    not null,
    confirmada          timestamp,
    -- Repetir el mismo pedido no crea otra venta: lo impide la base
    constraint venta_clave_idempotencia_unica unique (comprador_id, clave_idempotencia),
    constraint venta_subtotal_valido check (subtotal >= 0),
    constraint venta_cargo_servicio_valido check (cargo_servicio >= 0),
    constraint venta_total_coherente check (monto_total = subtotal + cargo_servicio)
);

-- Para el barrido que vence las reservas, que corre cada minuto
create index venta_estado_vence_idx on venta (estado, vence_reserva);

create table venta_item (
    -- El id de la venta más el número de renglón: "V-3f9a1c2b-1"
    id               varchar(40)   primary key,
    venta_id         varchar(40)   not null,
    -- id del sector en ServicioDeEventos (otro esquema, sin FK)
    sector_id        varchar(40)   not null,
    -- Nombre y precio del sector al momento de reservar: son una copia, el sector vive en Eventos
    sector           varchar(120)  not null,
    precio_unitario  bigint        not null,
    cantidad         integer       not null,
    -- Posición del renglón en el pedido
    orden            integer       not null,
    constraint venta_item_venta_fk foreign key (venta_id) references venta (id),
    -- Un sector aparece una sola vez por venta
    constraint venta_item_sector_unico unique (venta_id, sector_id),
    constraint venta_item_precio_valido check (precio_unitario > 0),
    constraint venta_item_cantidad_valida check (cantidad > 0)
);

create table entrada (
    id              varchar(40)   primary key,
    venta_id        varchar(40)   not null,
    sector_id       varchar(40)   not null,
    sector          varchar(120)  not null,
    -- Nombre y apellido en un solo campo, como en la API y en el mensaje PAGO_CONFIRMADO
    titular_nombre  varchar(200)  not null,
    titular_dni     varchar(8)    not null,
    -- El precio del sector, sin el cargo de servicio. Es la base del tope de reventa
    precio_pagado   bigint        not null,
    -- PENDIENTE_EMISION, VALIDA, USADA, PUBLICADA, VENDIDA o ANULADA
    estado          varchar(20)   not null,
    -- Los dos son null mientras la entrada está en PENDIENTE_EMISION
    codigo_qr       varchar(200),
    emitida         timestamp,
    -- Posición del titular en el pedido, para devolver las entradas en ese mismo orden
    orden           integer       not null,
    constraint entrada_venta_fk foreign key (venta_id) references venta (id),
    -- Una persona no puede tener dos entradas de la misma compra
    constraint entrada_titular_unico unique (venta_id, titular_dni),
    -- Dos entradas no pueden compartir el código. Las que todavía no lo tienen (null) no chocan entre sí
    constraint entrada_codigo_qr_unico unique (codigo_qr),
    constraint entrada_precio_valido check (precio_pagado > 0)
);
-- El token de la tarjeta no se guarda en ninguna tabla.
