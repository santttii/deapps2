-- Un reembolso puede ser de una parte del pago: cuando se cancela un evento, a quien había revendido alguna de
-- sus entradas se le devuelve solo lo que pagó por las que todavía tenía (las revendidas ya las cobró en la
-- reventa). SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

-- Cuánto se devolvió, en pesos enteros; null si no se devolvió
alter table pago add column monto_reembolsado bigint;
-- Hasta esta migración un reembolso era siempre del pago entero
update pago set monto_reembolsado = monto where estado = 'REEMBOLSADO';
