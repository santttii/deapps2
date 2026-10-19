-- Esquema "facturacion": una factura por cada compra confirmada, con el número y el CAE que dio ARCA.
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.
-- Sin check de lista de valores (en H2 2.4.240 se rompen) y sin valores por defecto:
-- todas las columnas las escribe ServicioDeFacturacion.

create table factura (
    id                varchar(40)  primary key,
    -- ids de otros servicios (otros esquemas, sin FK)
    venta_id          varchar(40)  not null,
    comprador_id      varchar(40)  not null,
    evento_id         varchar(40)  not null,
    -- Código de ARCA del comprobante (6: factura B) y punto de venta
    tipo_comprobante  integer      not null,
    punto_de_venta    integer      not null,
    -- Lo asigna ARCA, correlativo por punto de venta y tipo
    numero            bigint       not null,
    -- Código de autorización electrónico: 14 dígitos
    cae               varchar(14)  not null,
    vencimiento_cae   date         not null,
    -- Pesos enteros: el total cobrado de la venta
    importe           bigint       not null check (importe > 0),
    -- Fecha del comprobante, en hora argentina
    fecha             date         not null,
    -- Hora UTC en que se guardó, truncada a segundos. La pone el servicio, no la base.
    emitida           timestamp    not null,
    -- Una sola factura por venta: es lo que hace que un mensaje repetido no facture dos veces,
    -- aunque dos entregas lleguen a la vez.
    constraint factura_venta_unica unique (venta_id)
);
-- Sin restricción única en (tipo, punto de venta, número): la numeración la lleva el ARCA simulado
-- en memoria y vuelve a 1 cuando se lo redespliega. Con una base que dure más que el simulado
-- (Supabase), esa restricción frenaría toda la facturación.
