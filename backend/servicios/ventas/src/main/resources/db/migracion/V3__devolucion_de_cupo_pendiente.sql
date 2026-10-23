-- Devoluciones de cupo que faltan hacer (DevolucionDeCupos).
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

-- true desde que la venta se cerró sin pagarse (CANCELADA o VENCIDA) hasta que Eventos aceptó la
-- devolución de su cupo. La pone el mismo UPDATE que cierra la venta, así no se pierde aunque el
-- servidor se caiga justo después; la saca quien devolvió el cupo. Mientras sea true, el barrido
-- DevolucionDeCupos la vuelve a intentar cada minuto.
-- Las ventas que ya estaban cerradas quedan en false: su cupo se devolvió (o se avisó en el log)
-- antes de esta columna, sin clave, y devolverlo otra vez lo restaría dos veces.
alter table venta add column devolucion_pendiente boolean not null default false;
