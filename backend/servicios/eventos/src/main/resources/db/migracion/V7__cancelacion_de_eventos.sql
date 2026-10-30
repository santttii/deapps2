-- Cancelar un evento: no se borra, queda marcado. Así el detalle sigue respondiendo y quien tenga
-- el enlace se entera de que se canceló, en lugar de recibir un 404 sin explicación.
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

alter table evento add column cancelado boolean not null default false;
alter table evento add column cancelado_en timestamp;
alter table evento add column cancelacion_motivo varchar(500);

-- Un evento cancelado tiene cuándo; uno que no, no. Evita quedarse a mitad de camino.
alter table evento add constraint evento_cancelado_coherente
    check ((cancelado = false and cancelado_en is null) or (cancelado = true and cancelado_en is not null));

-- El catálogo público filtra los cancelados, y el panel del organizador los sigue mostrando.
create index evento_cancelado_idx on evento (cancelado);
