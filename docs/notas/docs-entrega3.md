# A4 — Documento de la Entrega Parcial N.º 2 (23/10, a confirmar)

Nota para Joaquin Molina, que lo entrega con Santiago Loto. Escrita el 13/10/2026, con el servidor de ese día. Corregida el mismo día, después de las 14:04, con lo que encontró la revisión del documento (ver la última sección).

**Fecha de entrega.** El grupo cursa en la comisión de los viernes y la consigna está escrita para la del lunes. La consigna fecha esta entrega el lunes 19/10 y el cronograma de los viernes no la nombra: se toma como fecha de trabajo el viernes 23/10/2026, y falta confirmarla con la cátedra. La entrega obligatoria con defensa es el viernes 13/11 (09/11 en la consigna).

## Qué contiene y de dónde salió cada parte

Todo está en `docs/entrega3/`: el documento (`Entrega_Parcial_2_Integracion.md`), la figura (`Diagrama_Integracion_SOA.svg`), su fuente editable (`.drawio`) y `evidencia/`. Es solo el núcleo que pide la consigna.

- **Sección 2, diagrama y componentes:** del código y de los ocho `health`, que respondieron el 13/10.
- **Sección 3, clasificación:** los criterios son los de los talleres de las clases 6, 8 y 9; el estado de cada flecha sale del código y del servidor, no de los planes.
- **Sección 4, proceso asincrónico:** de `evidencia/`. El guion `reproducir.sh` repite todo, y su salida es `reproduccion.txt` (corrida de las 15:46, venta `V-3c150ebb`). La compra paso por paso sale de `tomar-evidencia.sh` (venta `V-91934f4a`) y la suscripción durable, de `suscriptor-durable.sh`.
- **Sección 5, diferencias:** de comparar la Entrega Parcial N.º 1, los talleres y la clase 10 con el código.
- **Sección 6:** real y simulado, y pendientes del 13/11.

## Decisiones de redacción y por qué

1. **Dos estados, "Implementado" y "Diseñado, pendiente".** La rúbrica pide que el documento refleje lo implementado. Ningún sistema externo está conectado, y lo dice la primera tabla.
2. **Un solo diagrama, con el estado en el trazo** y sin caja de ESB: no se dibuja una pieza que no existe. Los tres servicios que solo tienen `health` van con borde punteado.
3. **El broker es infraestructura propia,** no un sistema externo: corre dentro de WildFly.
4. **El webhook figura por su canal, REST,** y la fila aclara que para la compra el resultado llega de forma asincrónica. La consigna solo admite cola o tópico como canal asincrónico.
5. **ARCA es síncrono porque SOAP es pedido y respuesta,** no porque la compra necesite la factura. La clase 9 decía lo contrario; el cambio está declarado en la sección 5.
6. **El proveedor de email no es un sistema externo del documento.** El grupo no eligió proveedor ni canal, y una integración sin canal no se puede clasificar. Quedó como trabajo futuro en 6.2. Los externos son dos: Mercado Pago y ARCA.
7. **Las diferencias se listan,** empezando por la más incómoda: la clase 9 prometió la factura SOAP para esta entrega y no está.
8. **Largo:** unas 6.100 palabras contando las tablas, más que las 5 o 6 páginas del plan. Para recortar: 4.8, 6.2 y las filas menores de la sección 5.

## Las cinco preguntas más probables de la cátedra

1. **¿Qué integraciones externas funcionan hoy?** Ninguna. Funcionan la API propia, las llamadas REST entre servicios (Ventas llama a Usuarios, a Eventos y a Pagos) y la mensajería. Mercado Pago y ARCA están diseñados. La pasarela de Pagos es un simulador, `PagosFake`.
2. **¿El broker está realmente configurado?** Sí: ActiveMQ Artemis embebido en WildFly, con la cola, el tópico, 5 reintentos cada 2 segundos y DLQ. Está en `configurar.cli` y se lee del servidor (paso 0 de `reproduccion.txt`).
3. **¿Por qué el mismo evento va por cola y por tópico?** Emitir la entrada lo hace un único responsable, una sola vez: cola. El aviso y la auditoría son dos interesados que no se conocen: tópico.
4. **¿Qué pasa si el consumidor está caído, falla o recibe el mensaje dos veces?** Caído: el mensaje espera (64 esperaron y se procesaron). Falla: 5 entregas y DLQ. Repetido: no emite dos veces, porque el `UPDATE` exige `PENDIENTE_EMISION`.
5. **¿Cómo evitan publicar el mensaje de una venta que falló? ¿Es un commit en dos fases?** No es un commit en dos fases completo. Es una transacción declarativa: el publicador es `MANDATORY` y el mensaje sale con el commit. El broker es un recurso XA y la base no: entra como último recurso, sin fase de preparación. Quien defienda esta parte tiene que poder explicar qué es "último recurso" y para qué sirve que el broker sea XA. Probado: 409 y contadores sin cambios. El orden de los dos commits y la caída entre uno y otro no se probaron.

## Qué falta antes de entregar

- **Tag y evidencia final.** El tag `entrega-parcial-2` no existe. La evidencia de las 15:45 a 15:54 se tomó con un servidor armado desde cero, pero con código que no tenía commit (entre otros, la verificación de identidad en Usuarios). Hay que hacer el commit, crear el tag, armar el servidor desde ese commit, correr los tres guiones (`tomar-evidencia.sh`, `reproducir.sh > docs/entrega3/evidencia/reproduccion.txt 2>&1` y `suscriptor-durable.sh`) y actualizar la carátula, las filas S1 de 3.2, la sección 4.5, la fila del suscriptor durable de 4.6 y `LEEME.txt` con los ids, las horas y los hilos de esa corrida. `integracion-13-escenarios.txt` no se puede repetir solo con el repositorio: o se suma su guion o se aclara que quedó de la corrida del 13/10. Si para entonces Ventas volviera a cobrar con `CobrosFake`, hay que reescribir 2.2, 2.4, 3.2, 4.5 y 6.1 y la flecha Ventas → Pagos del diagrama: el paso 6 del guion muestra quién cobra.
- **Leerlo los cinco.** La consigna pide que cada integrante pueda explicar y defender cualquier parte, no solo la suya.
- **La declaración que la consigna pide en las entregas obligatorias** la redacta el grupo. Este documento no la incluye.
- **Sección 11 de `docs/planes/00-contratos-compartidos.md`.** Se sigue titulando "Decisiones pendientes de aprobación" y el documento no la cita. Si el grupo la aprueba, cambiar el título a "Decisiones tomadas", con la fecha.
- **Email.** Si el grupo elige proveedor y canal, se suma una fila I6 en 3.1 ("`ServicioDeNotificaciones` → proveedor de email"), con tipo, canal, porqué, qué pasa si no responde y estado "Diseñado, pendiente"; se vuelve a dibujar la caja y se cambia "I1 a I5" por "I1 a I6" en 2.1 y en la leyenda.
- **Anexos (funcional, manual, Figma):** no se hicieron. Dependen de la consulta a la cátedra, que no tiene respuesta.
- **Diagrama:** abrir el `.drawio` en app.diagrams.net (nunca se abrió), acomodarlo y exportar el PNG. Si no abre bien, alcanza el SVG, que Word inserta. Conviene página apaisada.
- **Captura del Runner de Postman** (`evidencia/postman-runner.png`).
- **Git:** commit de `docs/entrega3/` y de esta nota. Borrar los dos comentarios "PENDIENTE" del documento cuando estén resueltos: están junto a la carátula (la fecha de la entrega y el tag).
- **Word y PDF:** los de la carpeta son del 13/10 a las 15:43, anteriores a la evidencia nueva. Hay que volver a exportarlos desde el `.md` y subirlos el jueves 22/10, el día anterior a la clase, porque la hora límite del viernes 23/10 no está confirmada. Probar el enlace al repo sin sesión iniciada.
- **Confirmar con la cátedra:** la fecha de esta entrega (la carátula dice 23/10/2026); la hora límite, porque las 13:30 de la consigna son de la comisión del lunes y la de los viernes cursa de 08:45 a 11:45; y el grupo de cinco, cuando la consigna fija 3 a 4. Si la fecha resulta otra, se cambia primero en la carátula del documento y después en el resto del repo, buscando `23/10`.

## Qué cambió con la revisión del 13/10

La revisión marcó diez puntos. Los diez se comprobaron contra el código, el servidor o la consigna antes de tocar nada, y los diez eran ciertos; uno había quedado viejo en sus datos.

1. **Ventas y Pagos.** El hallazgo decía que el código nuevo cobraba contra Pagos y el servidor seguía en `fake`. Cuando se corrigió, el servidor ya cobraba contra Pagos: Ventas se redesplegó a las 13:54:20 y el log de las 13:54:39 dice "Cobros: Ventas cobra llamando al servicio de Pagos". Se volvió a correr `reproducir.sh` a las 14:04 y se reescribieron 2.2, 2.4 ("funcionan 8"), 2.5, 3.2, 4.1, 4.5, 6.1 y 6.2, la flecha del diagrama (ahora llena), `LEEME.txt` y el guion. La carátula dice que el tag no existe y que el código de la evidencia no tiene commit.
2. **Proveedor de email sin clasificar.** Se sacó de los sistemas externos y del diagrama, y quedó como trabajo futuro en 6.2. No se inventó un canal que el grupo no eligió.
3. **Clasificación de ARCA y del webhook.** Se reescribieron el criterio general y las filas I2 (ahora dos renglones) e I3, y se agregaron dos filas a la sección 5 que declaran el cambio respecto de las clases 9 y 10.
4. **Declaración.** Se sacó del documento: la consigna la pide en las entregas obligatorias y la redacta el grupo. Ver pendientes.
5. **Respaldo de las afirmaciones.** El guion tiene dos pasos nuevos: el 9 (401, 403 y 200) y el 10 (vendidas de 1635 a 1636 y de vuelta a 1635). Se citan en 2.5 y 3.2. La tabla de 4.8 dice qué trae cada archivo y suma `vencimiento-de-reserva.txt`. La consulta de Ventas a Usuarios quedó como "implementado, sin salida guardada".
6. **Cajas del diagrama.** Facturación, Reventa y Validación tienen borde punteado y el texto "desplegado, solo health". La flecha de Eventos a Usuarios lleva "S2" al lado.
7. **Fila de Notificaciones caído.** Dice "configurado y no probado para este documento" y ya no cita `docs/notas/mensajeria.md`.
8. **Cuatro detalles.** Se quitó la cita "Clase 10: la entrada queda EMITIDA", que no está en el texto de la clase 10. Se agregó `IUsuarios` a la fila de interfaces. 2.2 dice que solo los cinco servicios implementados exponen interfaz y API. La cuenta de 11 nodos se mantiene, porque sin el proveedor de email el documento vuelve a tener los mismos 11 de la clase 6.
9. **Garantía transaccional.** El párrafo usa las palabras del contrato ("último recurso") y dice qué se ve en la configuración y qué no se observó.
10. **Pago simulado y 6.2.** La sección 1 y la 4.1 avisan que la pasarela es simulada. 6.2 ya no dice "Ya funcionan".

Los diagramas salen de un guion (`diagrama.mjs`) que no está en el repo: genera el SVG y el `.drawio` desde una misma lista, así que los dos quedaron iguales.
