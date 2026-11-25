-- La auditoría deja de guardar el DNI de los titulares (DEF-37, criterio SEC-D05: minimizar los datos
-- personales). Desde el 24/11 PAGO_CONFIRMADO y REVENTA_CONFIRMADA viajan sin el DNI, y ServicioDeAuditoria
-- se lo saca al cuerpo de un mensaje publicado antes que llegue después. Esta migración se lo saca a las
-- filas que ya estaban.
--
-- Ese cuerpo lo escribió JSON-B (PagoConfirmadoMensaje.aJson): sin espacios y con los campos en orden
-- alfabético, así que cada titular quedó como {"dni":"30123456","nombre":"Ana Gómez"}. Se borra el par
-- "dni":"..." con la coma que lo separa del nombre; la segunda alternativa cubre el orden inverso. Dentro de
-- un texto (un nombre, un sector) esa secuencia no puede aparecer: JSON escribe cada comilla de un texto
-- como \", así que "dni":" con comillas sin barra es siempre una clave.
--
-- Con la marca 'g', regexp_replace reemplaza todas las apariciones en PostgreSQL. H2 en modo PostgreSQL
-- acepta la misma marca y también las reemplaza todas.
update registro_auditoria
   set cuerpo = regexp_replace(cuerpo, '"dni":"[0-9]*",|,"dni":"[0-9]*"', '', 'g')
 where cuerpo like '%"dni":"%';
