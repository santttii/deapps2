# Plan A4 · docs-entrega3 — Documento de la Entrega Parcial N.º 2 (23/10, a confirmar)

- **Dueño:** Joaquin Molina, con Santiago Loto. **Rama:** `PLAN_AGENTES.md` nombra `docs/entrega3`; se trabaja en el árbol actual (`master`) y las ramas y los commits los maneja solo Santiago.
- **Toca:** `docs/entrega3/` y `docs/notas/docs-entrega3.md`. No toca código, `docs/api` ni `README.md`.
- **Depende de:** T2, A2 y A1 hasta `EmisionDeEntradasMDB` (evidencia), T3 (capturas) y T4 (captura de Postman). No depende de A3 ni de B1.
- **Dependen de este plan:** C5 (documento del 13/11) y el Revisor.
- **Fechas:** el grupo cursa en la comisión de los viernes y la consigna está escrita para la del lunes. La consigna fecha esta entrega el lunes 19/10 y el cronograma de los viernes no la nombra: se toma como fecha de trabajo el viernes 23/10/2026, cuatro días después como las otras fechas de la comisión, y **falta confirmarla con la cátedra**. La entrega obligatoria con defensa es el viernes 13/11 (09/11 en la consigna). Las fechas internas de este plan que todavía no pasaron son las del calendario de `docs/PLAN_AGENTES.md`: el corte del estado, el tag y la evidencia final, el miércoles 14/10; el paso a Word y el PDF, el jueves 15/10; y la subida, el jueves 22/10. Si ese calendario cambia, vale ese.

A1 a C5 son las tareas de `docs/PLAN_AGENTES.md`; T0 a T5, los pasos transversales de `docs/planes/00-contratos-compartidos.md` (acá, "contratos"), que explica las siglas de Jakarta EE. Otras: SOA, arquitectura orientada a servicios; SPA, el frontend (aplicación web de una sola página); SOAP, servicio web con XML y un contrato llamado WSDL; ESB, un producto de "bus" que centraliza integraciones; Runner, la función de Postman que corre toda la colección.

## Qué se construye

La consigna (`consigna.txt`, líneas 83 a 91) llama a esta instancia "Entrega Parcial N.º 2"; el plan viejo la llamaba "Entrega 3". Es un checkpoint formativo. Se entrega (línea 149) el enlace al repositorio y un documento técnico en PDF, sin cantidad de páginas fijada. La hora límite de la consigna, las 13:30, es la de la comisión del lunes: la de los viernes también está a confirmar.

| Entregable | Quién lo pide | Formato |
|---|---|---|
| Diagrama de arquitectura de integración (SOA) | Consigna, línea 87 | Figura en el PDF; fuente `.drawio` |
| Clasificación justificada de cada integración externa | Consigna, línea 88 | Tabla en el PDF |
| Un proceso con mensajería real y broker configurado | Consigna, línea 89 | Código en el repo y evidencia en el PDF |
| Documentación funcional, manual de usuario y prototipo en Figma | No están en la consigna. Los listan `PLAN_AGENTES.md`, los apuntes de la clase 10 (Notion) y el tablero | Anexos del mismo PDF |

**Para el 23/10**
- **Núcleo (camino crítico):** un PDF de 5 a 6 páginas con las secciones 0 a 6.
- **Anexos A (funcional) y B (manual), cortos:** no bloquean el núcleo. Se empiezan después de la primera evidencia; lo que no esté listo el miércoles 14/10 no entra, y no se hacen si la cátedra responde que no hacen falta (decisión 1).
- **Anexo C:** media página con el enlace a la SPA. El Figma no se hace salvo que la cátedra lo confirme.

**Para el 13/11 (C5):** el núcleo crece a 8 a 12 páginas, con un diagrama de secuencia por integración (cinco), SOAP con su WSDL, la tabla de fallas de la transacción y la declaración que la consigna pide en las entregas obligatorias, que redacta el grupo.

### Índice del documento principal

El estado de cada integración es "Implementado" o "Diseñado, pendiente" y se completa el 14/10 mirando el código y el servidor, nunca los planes. "Tras el MDB" es cuando A2 y A1 llegan a `EmisionDeEntradasMDB` (lunes 12/10). Las secciones 1, 5 y 6 se escriben de cero.

| § | Contenido | Fuente | Cuándo |
|---|---|---|---|
| 0 | Carátula: enlace al repo y tag `entrega-parcial-2` | `git remote` | 15/10 |
| 1 | Qué pide la entrega y dónde está cada punto | Consigna | Ya |
| 2 | Diagrama, modelo de integración y `ServicioDeUsuarios` como servicio reutilizable | Drawio de `docs/entrega1/`; clase 6; diagramas de las clases 8 y 9 | Ya |
| 3 | Clasificación por flecha: tipo, criterio, qué pasa si el otro lado no responde y estado | Clases 6, 8 y 9 (`docs/clases/`) y 10 (`actividad_integradora_clase10/`); `openapi.yaml` | Ya; estado, 14/10 |
| 4 | Dos procesos que dispara `PAGO_CONFIRMADO`, "Emisión de entradas" (cola) y "Aviso y auditoría de la compra" (tópico): broker, mensaje, consumidores, garantía transaccional, cómo reproducirlo y evidencia | Contratos §4; contrato del mensaje; notas de A1 y A2; `GUIA_POSTMAN.md` | Broker y mensaje, ya; resto, tras el MDB; evidencia final, 14/10 |
| 5 | Diferencias con documentos anteriores | Tabla de abajo; contratos §8.3 | Ya |
| 6 | Qué es real y qué simulado; pendientes del 13/11; la declaración que la consigna pide en las entregas obligatorias, que redacta el grupo | Contratos §2 | Ya; ajuste, 14/10 |

### Diagrama: nodos y flechas

Nodos: clientes (SPA y Postman); WildFly con los 8 WAR, con `EmisionDeEntradasMDB` dentro de Ventas y `AvisoDeCompraMDB` y `AuditoriaDeComprasMDB` dentro de Notificaciones; el broker ActiveMQ Artemis embebido, con `cola.emision-entradas`, `topico.compra-confirmada` y `DLQ`; los externos Mercado Pago, ARCA (ex AFIP) y proveedor de email. Cada flecha lleva su número y el texto SÍNCRONO o ASÍNCRONO más el canal; línea llena es "Implementado" y punteada, "Diseñado, pendiente".

| N.º | Flecha | Etiqueta | Estado verificado el 09/10 |
|---|---|---|---|
| I1 | Cliente → API REST de Palco | SÍNCRONO · REST | Usuarios y Eventos responden |
| I2 | Pagos → Mercado Pago (cobro); Mercado Pago → Pagos (webhook) | SÍNCRONO · REST | Pendiente; cobra `PagosFake` |
| I3 | Facturación → ARCA; si no responde, → `cola.reintento-facturacion` | SÍNCRONO · SOAP (5 s); reintento ASÍNCRONO · cola | Pendiente; la cola no existe |
| I4 | Ventas → `cola.emision-entradas` → `EmisionDeEntradasMDB` | ASÍNCRONO · cola | Cola creada, 0 consumidores |
| I5 | Ventas → `topico.compra-confirmada` → los dos MDB de Notificaciones → proveedor de email | ASÍNCRONO · tópico | Tópico creado, 0 suscripciones; email pendiente |
| S1 | Ventas → Eventos (cupo), Usuarios (`GET /api/usuarios/yo`) y Pagos (`POST /api/v1/pagos`) | SÍNCRONO · REST interno | Nadie llama todavía; el de Pagos no existe |
| S2 | Todos → Usuarios (`/api/usuarios/jwks`, token de servicio) | SÍNCRONO · REST interno | La clave, en uso; el token, con T2 |
| S3 | Ventas → Facturación; Reventa → Usuarios, Pagos y Ventas; Validación → Ventas | SÍNCRONO · REST interno | Pendiente (C1, C2, B3) |

### Diferencias entre documentos que se unifican (sección 5)

| Tema | Qué dicen | Qué vale |
|---|---|---|
| Emisión | Clase 8: "hoy es una llamada síncrona interna". Clase 10: la entrada queda EMITIDA | Por cola. Las entradas nacen `PENDIENTE_EMISION` en `confirmarCompra`, el MDB las pasa a `VALIDA`, y `EMITIDA` es de la venta |
| Facturación | Clases 9 y 10: SOAP síncrono con cola de reintento; la clase 9 lo promete "para la Entrega 3". `TODO.md`: asincrónica | Síncrona con reintento, "Diseñado, pendiente" hasta el 13/11; el documento lo avisa |
| Broker | Entrega Parcial 1 y clase 6: entre los sistemas externos | Artemis embebido, infraestructura propia; externos son Mercado Pago, ARCA y email |
| Tópico | Entrega 1: la flecha "async" sale de Notificaciones. Clase 9: avisa a comprador y organizador | Publica Ventas; se avisa solo al comprador |
| Modelo | Clase 6: bus (ESB conceptual) contra 55 conexiones posibles | REST directo, broker y adaptadores detrás de `PasarelaDePagos`; se cuentan las flechas reales contra esas 55 |
| Interfaces | Entrega Parcial 1: `IVentas` con 4 operaciones, `INotificaciones` con 3, `IPagos` con 3 | `IVentas` con 3; `enviarConfirmacion` más `IAuditoria`; `IPagos` (`procesarPago`, `obtener`) más `PasarelaDePagos` |
| Garantía | Contrato del mensaje: no queda "una venta sin mensaje ni al revés". `TODO.md`: "XA entre la base y el broker" | Contratos §4.7: transacción JTA declarativa, base como último recurso; no es commit en dos fases |
| API y nombres | Clase 10: todo en `/api/v1`. "Entrega 3", "AFIP/ARCA", ids `V-00231` | `/api/v1` en Ventas y Pagos y los desvíos de contratos §8.3. "Entrega Parcial N.º 2", "ARCA (ex AFIP)", ids como `V-3f9a1c2b` |

### Anexos (solo si se mantiene la decisión 1)

- **A · Funcional:** actores y roles, reglas de negocio y flujos de comprador, organizador, reventa y staff, cada uno con su estado. Sale de la sección 6 de la Entrega Parcial 1 y del `README.md`; comprador y staff se escriben de cero.
- **B · Manual:** cómo abrir la aplicación (`npm run dev` en `frontend/`), cuentas de prueba y cinco recorridos. Cada uno avisa si usa datos simulados o la API.

| Recorrido | Cuenta | Capturas |
|---|---|---|
| R1 Registrarse e ingresar | Nueva | Registro (paso de identidad); login |
| R2 Comprar | `titular@palco.test` | Catálogo; detalle con sector elegido; titulares; confirmación con QR; mis entradas |
| R3 Revender | Titular y otra cuenta | Precio sobre el tope rechazado; `/reventa` con la publicación |
| R4 Organizador | `organizador@palco.test` | Panel; "Crear evento" |
| R5 Puerta | `puerta@palco.test` | Entrada válida (pantalla simulada) |

Son 12 capturas de 1280 de ancho, en `capturas/NN-recorrido-pantalla.png`, borrando antes `palco.state` del `localStorage`.

## Decisiones para aprobar

Todas están pendientes de aprobación; el plan se escribe con la recomendada.

1. **Alcance (P12 de contratos).** (a) Solo el núcleo. (b) Todo obligatorio, como dice la guía de la tarea: ata la entrega a capturas y a un Figma. (c) Núcleo como camino crítico; anexos A y B cortos después de la primera evidencia, con corte el 14/10; sin Figma, con la SPA como prototipo; Santiago lo consulta hoy por Teams y, si la cátedra dice que no hacen falta, no se hacen. **Recomendada: (c).** La consigna no los pide, pero los apuntes del grupo sobre la clase 10, en Notion, los anotan para esta entrega ("19/10 Entrega 3 (mensajería asincrónica real en vivo + documentación funcional, manual de usuario y Figma)"), y el tablero tiene las tarjetas "Redactar documentación funcional + manual de usuario" (Parcial N.º 2, Joaquin, límite 17/10) y "Armar prototipo navegable en Figma". Con (c) el núcleo no espera la respuesta de la cátedra y, si los pedía, hay algo para entregar. El Figma le saca horas a Valentina (A3 y B2), y la Entrega Parcial 1 ya presentó el frontend como "prototipo navegable de UI (React)". Defensa: "Primero lo que pide la consigna; lo anunciado en clase va como anexo". El 09/10 la fila P12 de contratos todavía recomienda (a): hasta que Santiago la cambie (paso 1) vale (a), como se explica en "Riesgos y dudas abiertas".
2. **Un diagrama, sin caja de ESB.** (a) Una caja "Bus de integración", como sugiere la clase 6: muestra una pieza que no existe. (b) Dos diagramas, hoy y objetivo: doble mantenimiento. (c) Uno, con el estado en el trazo. **Recomendada: (c).** Defensa: "El criterio de bus se cumple con el broker y con adaptadores detrás de `PasarelaDePagos`; no hay un producto de ESB".
3. **Webhook y email.** (a) Webhook asincrónico, porque avisa después: la consigna solo admite cola o tópico para lo asincrónico. (b) Webhook síncrono (REST entrante) y email asincrónico. **Recomendada: (b).** Defensa: "Clasificamos por canal: el webhook es una llamada HTTP con respuesta inmediata; el email lo desacopla el tópico".
4. **Cinco integraciones, dos procesos.** (a) Numerar cada flecha: obliga a C5 a doce diagramas de secuencia. (b) Agrupar por sistema y canal, con dos procesos sobre el mismo evento. **Recomendada: (b).** Defensa: "El mismo evento resuelve una tarea de un único responsable, por cola, y una difusión a varios interesados, por tópico".
5. **Diferencias entre documentos.** (a) Seguir el más viejo. (b) Seguir lo más reciente y el código, y listar cada cambio. **Recomendada: (b).** Defensa: "Mostramos qué cambió y por qué, en vez de entregar documentos que se contradicen".
6. **La declaración que la consigna pide en las entregas obligatorias, desde el 23/10.** (a) Esperar al 13/11, cuando es obligatoria. (b) Incluirla ya en el documento del 23/10. **Recomendada: (b).** El texto lo redacta y lo aprueba el grupo.

## Archivos

Todos nuevos, en `docs/entrega3/`. La guía de la tarea pide Word; se escribe en Markdown, que se puede revisar por diferencias, y se pasa a Word y a PDF el 15/10.
- `README.md`: índice de la carpeta y tabla que asigna cada punto de la consigna a una sección.
- `Entrega_Parcial_2_Integracion.md`: fuente del documento principal.
- `Diagrama_Integracion_SOA.drawio` y `.drawio.png`: fuente del diagrama y exportación, que se hace a mano.
- `evidencia/`: `broker-antes.txt`, `broker-despues.txt`, `server-log-extracto.txt` (el `.gitignore` excluye `*.log`), `auditoria.json`, `entrada.json` y `postman-runner.png`.
- `Anexo_A_Documentacion_Funcional.md`, `Anexo_B_Manual_de_Usuario.md`, `Anexo_C_Prototipo.md` y `capturas/`: solo con la decisión 1.
- `Entrega_Parcial_2_Integracion.docx` y `.pdf`: lo que se sube; se arman a mano desde Word.

Fuera de la carpeta: `docs/notas/docs-entrega3.md`, con el porqué de cada decisión y cinco preguntas probables con respuesta corta, en el formato de las otras notas.

## Contratos con las otras tareas

**Expone:** la numeración I1 a I5 y S1 a S3 y la convención de trazo, para C5; la regla de estado; los nombres "Entrega Parcial N.º 2", "ARCA (ex AFIP)" y "broker ActiveMQ Artemis embebido en WildFly"; `docs/entrega3/evidencia/`, para el Revisor.

**Consume**
- De A2: `PagoConfirmadoMensaje`, `PublicadorPagoConfirmado.publicar(PagoConfirmadoMensaje)` y `DestinosJms`; `AvisoDeCompraMDB` y `AuditoriaDeComprasMDB`; `GET /api/notificaciones/auditoria` y `POST /api/notificaciones/pruebas/pago-confirmado-con-falla` (rol `servicio`); la tabla `registro_auditoria`.
- De A1: `POST /api/v1/ventas`, `POST /api/v1/ventas/{ventaId}/pagos` y `GET /api/v1/entradas/{entradaId}`; `EmisionDeEntradasMDB`; los estados `CONFIRMADA` → `EMITIDA` y `PENDIENTE_EMISION` → `VALIDA`; la tabla de fallas de `docs/notas/ventas-core.md`.
- De A3, sin bloquear: `IPagos`, `PasarelaDePagos`, `PagosFake`, `FabricaDePasarela` y `POST /api/v1/pagos`.
- De T2, `POST /api/usuarios/servicios/token`; de T3, las fechas futuras; de T4, la colección ajustada.
- Del servidor: `java:/JmsXA`, `java:/jms/queue/emisionEntradas`, `java:/jms/topic/compraConfirmada`, `java:/jms/queue/DLQ` y la línea `INFO` con el `ventaId` de cada MDB.
- **OpenAPI:** se describen las rutas que respondan el 14/10; la sección 5 lista dónde se apartan de `openapi.yaml`, que A4 no edita.
- **Postman:** A4 pega la captura del Runner con lo que tiene que pasar al cerrar la Ola 1: carpetas 1 y 2 y requests 1 a 3 de "3 Diseño v1". Los requests 4 a 7 son de C2 y B3.

## Pasos

Cada paso termina en un commit de Santiago con mensaje `docs: …`. Los pasos 1 a 4 conservan sus fechas originales, que ya pasaron. Los pasos 6 a 9 siguen el calendario de `docs/PLAN_AGENTES.md`. El estado se corta, se crea el tag y se toma la evidencia el miércoles 14/10, antes de que entre código de la Ola 3. El PDF queda listo el jueves 15/10, por si la fecha de la entrega resulta anterior. Se sube el jueves 22/10, el día anterior a la fecha de trabajo (viernes 23/10, a confirmar; la consigna dice lunes 19/10).

1. **Vie 09/10.** Santiago consulta a la cátedra por Teams si los anexos y el Figma hacen falta, y deja la decisión 1 escrita igual en contratos §11 (fila P12), en contratos §9 y en la línea 4 de `A3-pagos.md`; los textos están en "Riesgos y dudas abiertas".
2. **Sáb 10/10.** `README.md` con la tabla consigna → sección; secciones 1, 2, 3 (sin estado), 5 y 6 y la parte de broker y mensaje de la 4; `.drawio` con todos los nodos y flechas.
3. **Dom 11/10.** Joaquin abre el `.drawio` en app.diagrams.net, lo acomoda y exporta el PNG. Primera lectura del texto.
4. **Lun 12/10.** Apenas A1 llega a `EmisionDeEntradasMDB`: primera evidencia en `evidencia/` y el resto de la sección 4, con los nombres reales.
5. **Del mar 13/10 al corte del paso 6.** `docs/notas/docs-entrega3.md`. Si la fila P12 de contratos ya recomienda (c) y la cátedra no respondió que no hacen falta: Anexo A y texto del Anexo B; después de T3, Joaquin toma las 12 capturas.
6. **Mié 14/10.** Santiago hace el commit de lo que quedó sin commit y crea el tag `entrega-parcial-2`. Con el servidor armado desde ese tag: evidencia definitiva (reemplaza a la primera si llegó B1); columna de estado; captura del Runner después de T4. Corte de anexos.
7. **Jue 15/10.** Paso a Word; leen los cinco, cada uno al menos la parte que va a defender; se exporta el PDF, que queda listo para subir.
8. **Jue 22/10.** Santiago comprueba que `master` y el tag `entrega-parcial-2` estén subidos a GitHub. Se prueba el enlace sin sesión iniciada. Joaquin sube el PDF y el enlace al aula virtual; suplente, Santiago.
9. **Vie 23/10, antes de la hora límite.** Colchón. La hora está a confirmar: la consigna dice 13:30 para la comisión del lunes, y la de los viernes cursa de 08:45 a 11:45. Como el paso 8 deja todo subido el jueves, la respuesta no cambia el plan.

**Se hacen a mano:** la consulta a la cátedra y el cambio de P12 (paso 1); exportar el PNG; las capturas; el Figma, solo si la cátedra lo confirma; las dos lecturas; Word y PDF; aprobar el texto de la declaración que pide la consigna; el tag y la subida.

## Cómo se verifica

La evidencia del proceso es texto que el docente puede repetir (contratos §9), no un video ni capturas sueltas, y se toma sin esperar a B1.

- **Cobertura:** la tabla de `README.md` asigna una sección a las líneas 87, 88 y 89 de la consigna.
- **Estado:** `grep -rl "@MessageDriven" backend/servicios --include=*.java` tiene que listar los tres MDB antes de escribir "Implementado". Hoy no devuelve nada.
- **Broker**, desde la raíz del repo en Git Bash (probado hoy; cada resultado sale dos veces, sin consecuencias). Con `jboss-cli.bat` hace falta `NOPAUSE=true`.

  ```bash
  export JAVA_HOME="/c/Users/santi/.jdks/temurin-25"
  B='/subsystem=messaging-activemq/server=default'
  backend/servidor/target/server/bin/jboss-cli.sh --connect --commands="$B/jms-queue=cola.emision-entradas:read-resource(include-runtime=true),$B/jms-topic=topico.compra-confirmada:read-resource(include-runtime=true),$B/jms-queue=DLQ:read-attribute(name=message-count)" > docs/entrega3/evidencia/broker-antes.txt 2>/dev/null
  ```

  Hoy da `consumer-count` 0, `messages-added` 0 y `subscription-count` 0. Después de una compra, comparando con el archivo anterior: en la cola, `messages-added` sube 1, `message-count` queda en 0 y `consumer-count` es mayor que 0; en el tópico, `messages-added` sube, `subscription-count` es 2 y `durable-subscription-count` es 1; la `DLQ` no cambia.
- **Compra:** con el bloque de verificación de A1 (login de `titular@palco.test`, clave generada con `node -e "console.log(crypto.randomUUID())"`, pago con `tok_test`) y los ids de la respuesta, `GET /api/v1/entradas/{entradaId}` devuelve `estado` `VALIDA` y `codigoQr` distinto de `null`.
- **Log y auditoría:** `grep -n "<ventaId>" backend/servidor/target/server/standalone/log/server.log` muestra una línea `INFO` de cada MDB, y `curl -H "Authorization: Bearer <token de servicio>" localhost:8080/api/notificaciones/auditoria` trae una fila con ese `ventaId`.
- **Rollback:** `POST /api/notificaciones/pruebas/pago-confirmado-con-falla` responde 409 y los contadores no cambian. Sin esta prueba, la sección 4 no afirma que el mensaje no sale cuando la venta se deshace.
- **Nombres:** `grep -rnE "/cobros|2PC|detrás de .IPagos" docs/entrega3/*.md` no devuelve nada, y `grep -rn "Entrega 3" docs/entrega3/*.md` solo devuelve filas de la tabla de diferencias.
- **PDF:** abre, trae el enlace y el tag, y las figuras se leen en blanco y negro.

## Riesgos y dudas abiertas

- **Alcance (decisión 1): tres archivos con tres versiones.** Comprobado el 09/10. Contratos §11, fila P12, recomienda "(a), salvo que la cátedra los haya pedido por otro canal": los anexos no se hacen hasta que alguien confirme el pedido. Este plan recomienda (c): se hacen salvo que la cátedra diga que no. `A3-pagos.md`, línea 4, dice "El prototipo en Figma es tarea de Valentina y no se planifica acá", y acá el Figma no se hace. Ante una diferencia vale contratos (su sección 1), así que hoy vale (a). Esos dos archivos no son de A4; para dejar una sola versión, Santiago cambia:
  - Contratos §11, fila P12: sumar la alternativa (c) y poner como recomendada "núcleo como camino crítico; anexos A y B cortos después de la primera evidencia, con corte el 14/10; sin Figma, la SPA como prototipo; Santiago lo consulta hoy por Teams y, si la cátedra dice que no hacen falta, no se hacen".
  - Contratos §9: agregar que los anexos no bloquean el núcleo.
  - `A3-pagos.md`, línea 4: reemplazar la oración del Figma por "el Figma no se hace salvo que la cátedra lo confirme (P12)".
- **Si el grupo deja P12 como está,** vale (a): los anexos salen de los pasos 5 y 6 y de la lista de archivos. El núcleo no cambia, y hasta el lunes 12/10 solo se trabaja en el núcleo, así que hay tiempo hasta el paso 5 para resolverlo.
- **Alcance: qué pidió la cátedra.** Nadie lo confirmó con la cátedra. Los apuntes son de segunda mano y las dos tarjetas del tablero no se tocan desde el 04/09, un mes antes de la clase 10: ninguno es un pedido escrito. Si la cátedra confirma el Figma, lo hace Valentina y el grupo decide de dónde salen esas horas, hoy puestas en A3 y B2.
- **Demostración en vivo.** Los apuntes dicen que en esta entrega el proceso se muestra en vivo; la consigna, que no hay defensa. "Cómo reproducirlo" (sección 4) sirve de guion; el ensayo no es de esta tarea.
- **Si el 14/10 el flujo no corre de punta a punta,** el documento describe lo que ande. La evidencia de reserva es `POST /api/notificaciones/pruebas/pago-confirmado` (A2), que muestra el proceso del tópico sin Ventas.
- **Sin probar:** la caída entre los dos commits (se describe así, con el texto de contratos §4.7); que el MDB figure en `consumer-count` y cuánto sube `messages-added` del tópico por compra; que un `.drawio` escrito como XML abra sin retoques; el paso de Markdown a Word (hay Word y Edge; no hay pandoc, LibreOffice ni draw.io de escritorio).
- **Flechas S3 e `IFacturacion`.** Salen de los talleres y de `TODO.md`, no de contratos: las definen C1, C2 y B3.
- **Capturas.** `MyTicketsPage.tsx` bloquea publicar en reventa a menos de 3 horas del evento, y los eventos de prueba terminan el 16/10. Sin T3, la salida es crear un evento futuro desde el panel del organizador (no probado).
- **Fuentes sin commitear.** `docs/api`, `docs/clases`, `docs/entrega1`, `docs/planes` y `actividad_integradora_clase10/` no están en git: tienen que entrar antes del tag.
- **A confirmar por el grupo:** que el docente pueda abrir el repo; la fecha y la hora de esta entrega para la comisión de los viernes (se toma el 23/10; la consigna, escrita para la comisión del lunes, dice 19/10 a las 13:30); el grupo de cinco (la consigna fija 3 a 4); dónde está el documento de la Entrega Obligatoria N.º 1 (14/09), que la entrega del 13/11 pide "actualizado"; y qué hay que entregar en la "Entrega del diseño del TP Obligatorio", que el cronograma de los viernes fecha el 06/11 (los apuntes la anotan como "02/11 diseño del TP Obligatorio", la fecha del lunes).
