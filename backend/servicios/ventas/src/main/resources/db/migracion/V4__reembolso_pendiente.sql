-- Reembolsos que Pagos no contestó al cancelarse un evento (ReintentoDeReembolsos).
-- SQL compatible con PostgreSQL (Supabase) y con H2 en modo PostgreSQL.

-- true desde que la venta se anuló por la cancelación de su evento sin que Pagos contestara el pedido de
-- reembolso, hasta que Pagos lo contesta (lo devolvió, o dijo que ese pago no se puede devolver). La pone
-- la misma transacción que anula la venta, así existe solo si la anulación quedó confirmada; la saca el
-- barrido ReintentoDeReembolsos, que mientras sea true vuelve a pedir el reembolso cada minuto.
-- Las ventas que ya estaban anuladas quedan en false: Ventas no guardó cuáles quedaron sin reembolso, y
-- esas se siguen resolviendo a mano con el pagoId del log, como antes de esta columna.
alter table venta add column reembolso_pendiente boolean not null default false;
