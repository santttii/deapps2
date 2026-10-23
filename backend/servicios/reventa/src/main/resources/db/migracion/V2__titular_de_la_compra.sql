-- Lo que necesita la conciliación de las compras sin resultado (ConciliacionDeReventas).
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

-- El titular de la entrada que se está comprando, anotado por el mismo UPDATE que toma la publicación,
-- antes de cobrar. Si Pagos cobra pero la compra no llega a terminar (Pagos no respondió a tiempo y el
-- comprador no reintentó), la conciliación transfiere la entrada a este titular sin esperar al comprador.
alter table publicacion add column titular_nombre varchar(200);
alter table publicacion add column titular_dni    varchar(8);
