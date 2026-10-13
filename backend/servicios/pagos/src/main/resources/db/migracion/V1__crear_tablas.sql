-- Esquema "pagos": un registro por cada cobro que la pasarela resolvió.
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

create table pago (
    id                  varchar(40)   primary key,
    -- id de la venta en ServicioDeVentas (otro esquema, sin FK). Puede repetirse:
    -- un rechazo y el reintento aprobado son dos pagos de la misma venta.
    venta_id            varchar(40)   not null,
    -- Pesos enteros
    monto               bigint        not null check (monto > 0),
    medio_de_pago       varchar(40)   not null,
    -- Qué pasarela lo cobró: "fake" o, más adelante, "mercadopago"
    proveedor           varchar(40)   not null,
    -- Sin check de lista: en H2 2.4.240 se rompe al cerrarse la conexión de la migración
    -- y todo insert o update falla. Los valores válidos los controla el enumerado EstadoPago.
    estado              varchar(20)   not null,
    -- Por qué se rechazó; null si se aprobó
    motivo              varchar(200),
    -- La clave que llega en Idempotency-Key. Única: es lo que impide cobrar dos veces el mismo pedido.
    clave_idempotencia  varchar(80)   not null,
    -- El id del pago en la pasarela. Único: el aviso de la pasarela (webhook) tiene que encontrar un solo pago.
    id_externo          varchar(120)  not null,
    -- Los dos en UTC; los pone el código, no la base
    creado              timestamp     not null,
    actualizado         timestamp     not null,
    constraint pago_clave_idempotencia_unica unique (clave_idempotencia),
    constraint pago_id_externo_unico unique (id_externo)
);
-- El token de la tarjeta no se guarda: sirve para un solo cobro y es un dato sensible.
