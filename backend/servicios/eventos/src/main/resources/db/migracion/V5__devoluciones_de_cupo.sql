-- Las devoluciones de cupo ya aplicadas, una fila por clave. Con esto devolver es seguro de repetir:
-- si Ventas no supo la respuesta y vuelve a mandar la misma devolución, Eventos la reconoce por la
-- clave y no resta otra vez (si restara, ese lugar se podría vender dos veces).
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

create table devolucion_de_cupo (
    -- La arma quien devuelve, una por venta y sector: "devolucion:V-3f9a1c2b:sec-1-1"
    clave      varchar(120)  primary key,
    sector_id  varchar(40)   not null references sector (id) on delete cascade,
    -- Cuántos lugares se devolvieron (positivo)
    cantidad   integer       not null,
    aplicada   timestamp     not null,
    constraint devolucion_de_cupo_cantidad_valida check (cantidad > 0)
);
