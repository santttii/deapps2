-- Lo que necesita la conciliación de los pagos sin resultado (ConciliacionDePagos).
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

-- Cuándo empezó el último intento de pago, en UTC; null si nadie intentó pagar (o si el intento es
-- anterior a esta columna). Lo anota el mismo UPDATE que anota clave_pago. La conciliación no toca una
-- venta cuyo último intento es más nuevo que su margen: ese cobro puede seguir en camino.
alter table venta add column pago_intentado timestamp;

-- Los titulares de una compra que mandó a cobrar, copiados de la instancia stateful justo antes del
-- cobro. Si después la instancia se pierde (una falla inesperada, un redespliegue o el fin de su tiempo
-- de vida), la conciliación crea las entradas con estos datos. Una compra que nunca se intentó pagar no
-- tiene filas acá: sus titulares viven solo en la instancia.
create table venta_titular (
    -- El id de la venta más la posición del titular: "V-3f9a1c2b-1"
    id          varchar(40)   primary key,
    venta_id    varchar(40)   not null,
    -- Nombre y apellido en un solo campo, como en entrada.titular_nombre
    nombre      varchar(200)  not null,
    dni         varchar(8)    not null,
    -- Posición del titular en el pedido, empezando en 1: es el orden en que se reparten los sectores
    orden       integer       not null,
    constraint venta_titular_venta_fk foreign key (venta_id) references venta (id),
    constraint venta_titular_orden_unico unique (venta_id, orden),
    constraint venta_titular_dni_unico unique (venta_id, dni),
    constraint venta_titular_orden_valido check (orden > 0)
);
