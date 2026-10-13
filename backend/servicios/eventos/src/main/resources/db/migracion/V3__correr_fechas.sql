-- Corre los 6 eventos de prueba 15 semanas exactas: cada uno conserva su día de
-- la semana, su hora y el orden entre ellos.
-- Motivo: con las fechas de la V2 (12/09 al 16/10) ya pasaron todos para las
-- entregas del 19/10 y del 09/11, y la reventa exige que falten más de 3 horas.
-- Va en una migración nueva y no editando la V2: Flyway compara el contenido de
-- cada script con el que ya aplicó y rechaza uno modificado.

update evento set fecha = '2026-12-26 17:00:00' where id = 'ev-1';
update evento set fecha = '2027-01-08 21:00:00' where id = 'ev-2';
update evento set fecha = '2027-01-16 00:30:00' where id = 'ev-3';
update evento set fecha = '2027-01-21 20:30:00' where id = 'ev-4';
update evento set fecha = '2027-01-24 16:00:00' where id = 'ev-5';
update evento set fecha = '2027-01-29 20:00:00' where id = 'ev-6';
