-- DEF-33: los comprobantes que ARCA rechazó (Resultado "R"): lo que se le pidió y por qué no lo autorizó.
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL. Sin valores por defecto: todas
-- las columnas las escribe ServicioDeFacturacion.
--
-- Por qué una tabla aparte y no un estado en "factura": una factura siempre tiene número y CAE, y un
-- rechazo no tiene ninguno de los dos. Con una sola tabla, esas columnas dejarían de ser obligatorias
-- para todas las facturas.
--
-- Por qué se anota: un rechazo no se arregla reintentando (el pedido es el mismo y ARCA lo vuelve a
-- rechazar), así que el mensaje del tópico no se devuelve al broker. Con esta fila, la consulta de la
-- factura de esa venta dice que ARCA la rechazó, y no que todavía se está generando, y queda el motivo
-- para revisarla a mano.

create table comprobante_rechazado (
    id            varchar(40)   primary key,
    -- ids de otros servicios (otros esquemas, sin FK)
    venta_id      varchar(40)   not null,
    comprador_id  varchar(40)   not null,
    evento_id     varchar(40)   not null,
    -- Pesos enteros: el total que se pidió facturar. Sin check: un importe imposible es justamente
    -- uno de los motivos por los que ARCA rechaza, y tiene que poder quedar anotado.
    importe       bigint        not null,
    -- Fecha del comprobante que se pidió, en hora argentina
    fecha         date          not null,
    -- La observación de ARCA, en un renglón; una más larga se guarda recortada
    motivo        varchar(500)  not null,
    -- Hora UTC en que se anotó, truncada a segundos. La pone el servicio, no la base.
    rechazado     timestamp     not null,
    -- Un solo rechazo por venta: un mensaje repetido de esa venta no anota otro.
    constraint comprobante_rechazado_venta_unica unique (venta_id)
);
