-- Los cobros de reventa que hay que devolver (limitación L2).
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.
--
-- Si Pagos aprueba el cobro y Ventas rechaza la transferencia, la entrada no llega al comprador.
-- Antes de anular la publicación se anota acá el reembolso; después se le pide a Pagos. Si Pagos no
-- responde, la fila queda PENDIENTE y ConciliacionDeReventas la reintenta cada minuto. Una fila por
-- pago: la clave primaria impide anotar dos veces el mismo reembolso.
-- El estado no lleva check de lista (ver V1): los valores (PENDIENTE, HECHO, RECHAZADO) los controla
-- el enumerado EstadoReembolso.

create table reembolso (
    -- El pago aprobado que se devuelve, en ServicioDePagos (otro esquema, sin FK)
    pago_id         varchar(40)   primary key,
    publicacion_id  varchar(40)   not null,
    comprador_id    varchar(40)   not null,
    -- Pesos enteros: precio más cargo de servicio, lo que se cobró. Pagos devuelve el pago entero.
    monto           bigint        not null check (monto > 0),
    -- Por qué no se entregó la entrada; viaja a Pagos y queda guardado con el pago
    motivo          varchar(200)  not null,
    estado          varchar(20)   not null,
    -- Los dos en UTC; los pone el código. resuelto es null mientras está PENDIENTE.
    anotado         timestamp     not null,
    resuelto        timestamp
);

-- El barrido busca las pendientes
create index reembolso_estado_idx on reembolso (estado);
