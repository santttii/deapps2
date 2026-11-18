-- Las fotos de los eventos, guardadas en la base. Es el almacén que se usa cuando no hay Supabase
-- configurado: así subir una foto funciona apenas se levanta el sistema, sin configurar nada, y la
-- foto dura lo mismo que el evento (no se pierde al redesplegar, como pasaría en un disco efímero).
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

create table imagen (
    -- UUID más la extensión ("3f9a….jpg"): es lo que va en la URL pública.
    id         varchar(60)  primary key,
    tipo       varchar(40)  not null,
    contenido  bytea        not null,
    tamano     integer      not null,
    subida     timestamp    not null
);
