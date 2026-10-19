-- Esquema "validacion": el registro de la puerta, un renglón por cada código escaneado.
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.
-- Las entradas no están acá: son de ServicioDeVentas, que es quien las marca como usadas.

create table escaneo (
    id              varchar(40)   primary key,
    -- El código tal como se leyó, exista o no. Mismo largo que entrada.codigo_qr en Ventas.
    codigo_qr       varchar(200)  not null,
    -- El evento que controla la puerta (id en ServicioDeEventos, otro esquema, sin FK)
    evento_id       varchar(40)   not null,
    -- Sin check de lista: en H2 2.4.240 se rompe al cerrarse la conexión de la migración
    -- y todo insert o update falla. Los valores válidos los controla el enumerado ResultadoEscaneo.
    resultado       varchar(20)   not null,
    -- La entrada que encontró Ventas; null si el código no existe o Ventas no respondió
    entrada_id      varchar(40),
    -- El usuario de puerta que escaneó (claim "sub" de su token)
    escaneado_por   varchar(80)   not null,
    -- En UTC y sin fracción de segundo; lo pone el código, no la base
    escaneado       timestamp     not null
);

-- El registro se consulta por evento, del más nuevo al más viejo
create index escaneo_evento_idx on escaneo (evento_id, escaneado);
-- Ante una entrada ya usada se busca el escaneo anterior del mismo código
create index escaneo_codigo_idx on escaneo (codigo_qr);
