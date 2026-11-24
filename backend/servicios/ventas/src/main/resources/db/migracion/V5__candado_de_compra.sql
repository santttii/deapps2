-- Los candados de las reglas entre compras (DEF-46): el tope de entradas por comprador y evento, y un DNI por evento.
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.
--
-- Una fila no guarda ningún dato: existe para que dos compras que pueden chocar se bloqueen sobre ella con
-- SELECT ... FOR UPDATE (el bloqueo pesimista de JPA) y miren la regla una después de la otra. La clave dice qué
-- protege: "comprador|<evento>|<comprador>" o "dni|<evento>|<dni>". La crea la primera compra que la necesita,
-- con INSERT ... ON CONFLICT DO NOTHING, en la misma transacción que la bloquea.
--
-- Las filas no se borran: son chicas, una por comprador y evento y otra por DNI y evento, y borrarlas volvería a
-- abrir la carrera que cierran (dos compras podrían bloquear dos filas distintas con la misma clave).
create table candado_de_compra (
    clave  varchar(200)  primary key
);
