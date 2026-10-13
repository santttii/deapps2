-- Esquema "notificaciones": auditoría de los mensajes PAGO_CONFIRMADO recibidos por el tópico.
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.
-- Sin check de lista de valores (en H2 2.4.240 se rompen) y sin valores por defecto:
-- todas las columnas las escribe ServicioDeAuditoria.

create table registro_auditoria (
    -- El JMSMessageID del mensaje. No cambia cuando el broker lo reentrega: por eso es
    -- la clave, y por eso una reentrega no puede generar una segunda fila.
    mensaje_id    varchar(100)  primary key,
    tipo_evento   varchar(40)   not null,
    -- ids de otros servicios (otros esquemas, sin FK)
    venta_id      varchar(40)   not null,
    comprador_id  varchar(40)   not null,
    evento_id     varchar(40)   not null,
    monto_total   bigint        not null,
    -- El JSON tal como llegó
    cuerpo        varchar(4000) not null,
    -- Hora UTC en que se registró, truncada a segundos. La pone el servicio, no la base.
    recibido_en   timestamp     not null
);

-- La consulta de auditoría pide los últimos registros por fecha.
create index registro_auditoria_recibido_idx on registro_auditoria (recibido_en);
