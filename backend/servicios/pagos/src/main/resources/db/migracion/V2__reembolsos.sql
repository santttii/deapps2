-- Los reembolsos (limitación L2): un pago APROBADO puede pasar a REEMBOLSADO cuando el servicio que lo
-- cobró no pudo entregar lo que vendía (hoy, una compra en la reventa cuya transferencia Ventas rechazó).
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

-- Por qué se devolvió, en palabras del servicio que lo pidió; null si no se devolvió
alter table pago add column motivo_reembolso varchar(200);
-- Cuándo, en UTC; null si no se devolvió
alter table pago add column reembolsado      timestamp;
