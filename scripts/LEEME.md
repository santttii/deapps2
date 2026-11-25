# scripts

## pruebas/escenarios.mjs

La prueba de integración del backend: 24 escenarios de compra, mensajería, conciliación de pagos, facturación, validación en puerta, reventa (con su conciliación y sus reembolsos), devolución de cupo con Eventos caído, el dueño de cada evento, la cancelación de un evento, las reglas entre compras y la cuenta y el catálogo, de punta a punta, por la API y por `jboss-cli`. Los 13 primeros son los que produjeron `docs/entrega3/evidencia/integracion-13-escenarios.txt`. Cada comprobación imprime qué se esperaba y qué se vio; al final hay un resumen por escenario.

```bash
node scripts/pruebas/escenarios.mjs                                # los 24 escenarios
node scripts/pruebas/escenarios.mjs 1 2 3 4 5 6 7 9 13 15 16 17 19 20 21 22 23 24 # solo los que no tocan los consumidores, las propiedades ni los despliegues
node scripts/pruebas/escenarios.mjs 14                             # la falla después del cobro y su conciliación
```

Sale con código 0 si todas las comprobaciones pasan y con 1 si alguna falla. `lib.mjs` tiene las ayudas comunes (pedidos HTTP, `jboss-cli`, lectura del `server.log` y el informe).

### Qué prueba cada escenario

| N.º | Escenario | Qué comprueba |
|---|---|---|
| 1 | Compra completa | Iniciar (201, reserva de cupo, vence a los 10 minutos), pagar contra Pagos, emisión asincrónica hasta `EMITIDA` con entradas `VALIDA`, el pago en Pagos y el listado de compras propias |
| 2 | Idempotencia | La misma `Idempotency-Key` al iniciar y al pagar devuelve la misma venta y el mismo pago: un solo cupo, un solo cobro, un solo mensaje |
| 3 | Pago rechazado | 402 en Problem Details, la venta sigue `EN_CHECKOUT` y no sale ningún mensaje; un segundo intento con clave nueva la completa |
| 4 | Reglas de negocio | Máximo de entradas, DNI repetido, titulares que no coinciden, sector inexistente, cupo agotado (con devolución del cupo de otro sector), datos inválidos (400) y una línea falsa inyectada en el log |
| 5 | Seguridad | 401 sin token o con la firma alterada, 404 con el token de otro usuario, 403 con un rol que no corresponde, y lo interno solo con rol `servicio`. Usa una cuenta recién registrada, que compra sin ningún paso más |
| 6 | Cancelación | Cancelar libera el cupo una sola vez y la venta ya no se puede pagar |
| 7 | Un mensaje por pago | Un mensaje a `cola.emision-entradas` y una copia por suscripción en `topico.compra-confirmada` (son tres: el aviso y las durables de auditoría y de facturación), con el selector `tipoEvento = 'PAGO_CONFIRMADO'` y el cuerpo del contrato |
| 8 | Mensaje repetido | Detiene la auditoría y pasa su copia a la cola de emisión con `move-messages`: el consumidor la reconoce como repetida y no cambia nada |
| 9 | Los dos suscriptores | El aviso (log) y la auditoría (fila) reciben cada uno su copia |
| 10 | Consumidor detenido (taller) | Con `stop-delivery` en la emisión, el mensaje espera en la cola y la venta queda `CONFIRMADA`; con `start-delivery` se emite |
| 11 | Suscripción durable | Con la auditoría detenida se publican dos mensajes; al reanudarla los recibe |
| 12 | Mensaje inválido | Se reintenta 5 veces, cada 2 segundos, y termina en la DLQ sin trabar a las compras; después se lo pasa de la DLQ a la cola de emisión y vuelve a la DLQ |
| 13 | Transacción deshecha | Si la transacción se deshace después de publicar, el mensaje no sale; el mismo mensaje sin la falla sí sale |
| 14 | Falla después del cobro y conciliación | Prende con `jboss-cli` la propiedad `palco.ventas.fallar-despues-del-cobro`, paga y la quita. El pago responde 500 `error-inesperado`; la venta sigue `EN_CHECKOUT` sin pago ni entradas, no sale ningún mensaje (ni a la cola ni al tópico), no hay fila de auditoría y el cupo sigue reservado, pero el cobro está `APROBADO` en Pagos (consulta por clave de idempotencia). Después espera a la conciliación (hasta 4 minutos y medio): la venta se confirma con ese mismo pago, se emite, sale un mensaje (una copia por suscripción del tópico) y queda un solo pago |
| 15 | Facturación | La compra del escenario 1 se factura sola, por el tópico: `GET /api/v1/facturas/{ventaId}` la trae (se reintenta hasta 30 segundos) como factura B, por el total, con número `00001-…` y CAE de 14 dígitos; consultada otra vez es la misma. 403 con rol `staff` y 404 si no existe. Una compra nueva sale con el número siguiente y una sola línea de factura en el log |
| 16 | Validación en puerta | `puerta@palco.test` escanea una entrada emitida: en la puerta de otro evento da `OTRO_EVENTO` y la entrada sigue `VALIDA`; en la suya, `VALIDA` (puede entrar, Ventas la deja `USADA`); de nuevo, `YA_USADA` con el uso anterior. Un código que no existe da `INEXISTENTE`, un comprador recibe 403 y el registro de la puerta (rol `organizer`) muestra los escaneos sin el código completo |
| 17 | Reventa de punta a punta | Publicar fuera del rango (422 por arriba y por abajo), una entrada que no existe (404), al tope exacto (201) y dos veces (409); la entrada queda `PUBLICADA`, aparece en la lista pública y en la puerta da `NO_HABILITADA`. Compra propia (409) y cobro rechazado (402); `otro.titular@palco.test` la compra (201), la repite con la misma clave (la misma entrada) y con otra (409 `publicacion-no-activa`). La original queda `VENDIDA` y su código ya no entra; la nueva es del comprador, `VALIDA`, y en la puerta da `VALIDA` |
| 18 | Devolución de cupo con Eventos caído | Saca de servicio el WAR de Eventos y cancela una compra: la devolución del cupo queda marcada, el barrido la reintenta sola al volver Eventos y se aplica una sola vez |
| 19 | Reventa con Pagos sin respuesta | Mientras el cobro no tiene resultado nadie más compra ni retira la publicación, y la conciliación de reventas la libera o transfiere la entrada según lo que diga Pagos |
| 20 | Reventa cobrada y sin entrada | Ventas rechaza la transferencia después del cobro: la publicación queda `ANULADA` y el pago se devuelve |
| 21 | Dueño del evento | Cada organizador ve y arma solo la puerta de sus eventos, y escanea solo el personal de puerta asignado |
| 22 | Cancelación de un evento | Anula las compras, también la hecha en la reventa, devuelve cada pago a quien corresponde, retira la reventa y avisa |
| 23 | Reglas entre compras | Hasta 6 entradas por comprador y evento, sumando sus compras, y un DNI con una sola entrada por evento |
| 24 | Cuenta y catálogo | Registro, login, contraseña olvidada, catálogo, búsqueda, alta de evento y resumen de ventas |

Los escenarios 8, 10, 11 y 12 detienen un consumidor (`stop-delivery`) o pasan mensajes de una cola a otra (`move-messages`), el 14 prende por unos segundos una propiedad que hace fallar todo pago después del cobro, también los de otras personas, y el 18 saca de servicio un rato el WAR de Eventos. Si alguien más está probando contra el mismo servidor, se corren solo los de la segunda línea del bloque de arriba. Cada uno vuelve a dejar como estaba lo que tocó, aunque falle. Los más lentos son el 14, el 19 y el 20, que esperan a una conciliación: corre una vez por minuto y solo toma los pagos que empezaron hace más de 2 minutos.

### Qué necesita

- Node 18 o más nuevo: usa el `fetch` que trae Node y ningún paquete de npm, así que no hay nada que instalar.
- WildFly levantado con todos los WAR (`cd backend && ./dev.sh`, o el compose como explica el `README.md`), incluido el ARCA simulado, y las cuentas de prueba de siempre: `titular@palco.test`, `otro.titular@palco.test`, `organizador@palco.test` y `puerta@palco.test`, con la contraseña `palco1234`.
- Con `./dev.sh`, el servidor armado en `backend/servidor/target/server/` de la misma máquina: de ahí salen `jboss-cli` (`jboss-cli.bat` en Windows, `jboss-cli.sh` en Linux y macOS) y el `server.log` que se lee para comprobar lo que hicieron los consumidores. Contra el compose, `JBOSS_CLI=scripts/qa/jboss-cli-docker.sh` (corre `jboss-cli` dentro del contenedor) y `PALCO_LOG=logs/server.log`, con el compose levantado con `PALCO_DIR_LOGS=./logs` y `PALCO_CORREO_LOG_COMPLETO=true` (el bloque de "Todo junto, con un solo comando" del `README.md`).
- Java para `jboss-cli`: el de `JAVA_HOME` o, si no está definida, el del `PATH`. En Git Bash, `JAVA_HOME` puede estar escrita al modo de Windows (`C:\...`) o al de Git Bash (`/c/...`): la prueba la convierte.
- El secreto entre servicios, para pedir el token de rol `servicio`. `lib.mjs` lo toma de `PALCO_SECRETO_SERVICIOS`, de la línea de `backend/.env` o de `backend/servidor/target/secreto-servicios`, donde `dev.sh` deja el que usa el servidor, en ese orden. No hay un valor por defecto: si no lo encuentra, la prueba lo dice y corta. Contra el compose hay que pasarle el mismo `PALCO_SECRETO_SERVICIOS` que recibió el backend.

Las rutas salen de la ubicación del archivo, así que se puede correr desde cualquier carpeta. Variables opcionales: `PALCO_URL` (`http://localhost:8080`), `JBOSS_CLI` y `PALCO_LOG` (si `jboss-cli` o el log no están en la ruta del proyecto).

### Qué deja y qué tener en cuenta

- Cada escenario crea su propio evento de prueba (como organizador), con un sector igual a Platea B (27.500), uno de 3 lugares y uno de 50, y le asigna la puerta a `puerta@palco.test`: así el cupo se compara exacto aunque otra persona compre en otros eventos, y el tope de 6 entradas por comprador y evento no se suma de un escenario a otro.
- Deja en la base ventas emitidas y canceladas de `titular@palco.test`, las cuentas nuevas que registran algunos escenarios y esos eventos (el del 22, cancelado), con las facturas de las compras, los escaneos de la puerta y publicaciones de reventa vendidas a `otro.titular@palco.test`.
- Los contadores del broker son de todo el servidor. Si alguien compra mientras corre, la prueba busca esas ventas en el log y las descuenta; si aun así no cierra, se repite.
- El escenario 8 corta si en la suscripción durable hay copias de otras ventas, para no dejarlas sin auditar. La venta que usa queda sin fila de auditoría, y el 12 deja el mensaje inválido en la DLQ.

## pruebas/etag.mjs

`ETag` e `If-Match` en la API, contra el servidor en marcha. Cada GET con `@ConEtag` devuelve `ETag`; con `If-None-Match` responde 304 sin cuerpo mientras el recurso no cambie, y cuando cambia, cambia el `ETag`. La edición de un evento (`PUT /api/eventos/{id}`) con `If-Match` solo se guarda si el evento sigue como se leyó; si no, 412 en Problem Details.

```bash
node scripts/pruebas/etag.mjs
```

## pruebas/asistente.mjs

El asistente por su gateway, con el modelo simulado: respuestas del modelo, guardrails de entrada y de salida, tiempo máximo de 5 segundos con fallback y plan de apagado. Cambia la configuración con `jboss-cli` sin redesplegar y la deja como estaba.

```bash
node scripts/pruebas/asistente.mjs
```

## pruebas/patron-state.mjs

El patrón State de la venta, por la API: cada transición válida cambia el estado y cada transición inválida responde 409. Crea un evento propio, para no agotar el tope de 6 entradas por comprador y evento de los eventos fijos.

```bash
node scripts/pruebas/patron-state.mjs
```

## pruebas/broker-apagado.mjs

Si se apaga el broker no se pierden mensajes. Deja un `PAGO_CONFIRMADO` esperando en la cola (con el consumidor detenido), reinicia el servidor entero (el broker está embebido en WildFly) y comprueba que al volver el mensaje sigue ahí y la entrada se emite.

```bash
node scripts/pruebas/broker-apagado.mjs   # va última: reinicia el servidor
```

Necesita una base que sobreviva al reinicio, porque la venta tiene que seguir ahí cuando el mensaje se procese: el compose (PostgreSQL) o `dev.sh` con un H2 en archivo, por ejemplo `PALCO_DB_URL="jdbc:h2:file:./h2/palco;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;LOCK_TIMEOUT=10000" ./dev.sh`. Con el H2 en memoria que usa `dev.sh` por defecto, la base vuelve vacía y la venta ya no existe.

En el compose el journal del broker está en el volumen `datos-servidor` (ADR-005), así que el mensaje sobrevive también a recrear el contenedor. Para probarlo, con el compose levantado: `docker compose exec -T backend /opt/palco/server/bin/jboss-cli.sh --connect --command="/deployment=palco-ventas.war/subsystem=ejb3/message-driven-bean=EmisionDeEntradas:stop-delivery"` (en Git Bash, con `MSYS_NO_PATHCONV=1` adelante), una compra pagada (queda `CONFIRMADA`), `docker compose down` (sin `-v`) y `docker compose up -d --wait`: al volver, la venta pasa a `EMITIDA`. La salida de esa prueba está en `docs/testing/evidencia/checkpoint-3/broker-recreado.txt`.

## pruebas/proveedor-openai-local.mjs

Un proveedor de IA de prueba con la API de chat de OpenAI (`POST /v1/chat/completions`). Sirve para probar por HTTP el adaptador `ModeloCompatibleOpenAI` del asistente sin una clave paga y sin red. No es un modelo de lenguaje: contesta con reglas fijas. Usa solo Node, sin paquetes de npm.

```bash
node scripts/pruebas/proveedor-openai-local.mjs                          # en http://127.0.0.1:8089/v1
node scripts/pruebas/proveedor-openai-local.mjs --puerto=9000 --modo=lento
```

Muestra en la consola cada pedido que recibe: las cabeceras, el modelo y cada mensaje con su rol. De `Authorization` solo dice si vino, nunca el valor. Ahí se ve que las instrucciones del gateway y los datos de Palco llegan en el mensaje `system`, y la pregunta, sola y ya enmascarada, en el `user`.

| Modo | Qué contesta |
|---|---|
| `normal` | 200 con la cita textual del dato de Palco que corresponde a la pregunta, tal como llegó en el `system` |
| `lento` | Lo mismo, pero a los 8 segundos (`?demoraMs=N` para otra demora): más que los 5 que espera el gateway |
| `401` | El error de OpenAI para una clave inválida |
| `500` | El error de OpenAI para una falla de su servidor |
| `json-roto` | 200 con el JSON cortado a la mitad |
| `con-dni`, `con-email` | 200 con un DNI o un email en el texto |

El modo se cambia con `curl -X POST http://127.0.0.1:8089/control/modo/<modo>` y queda así hasta el próximo cambio. `GET /control` devuelve el modo y cuántos pedidos llegaron, y `GET /control/recibidos` los pedidos, sin `Authorization`. Variables opcionales: `PROVEEDOR_PUERTO` (`8089`) y `PROVEEDOR_HOST` (`127.0.0.1`).

Para que el asistente lo use, el servidor tiene que apuntar a él: ver más abajo [cómo apuntar el servidor al proveedor](#cómo-apuntar-el-servidor-al-proveedor).

## pruebas/asistente-proveedor.mjs

La prueba de punta a punta del asistente con el proveedor de prueba. Hace las preguntas por la API del asistente (`POST /api/v1/asistente/preguntas`) con la sesión de `titular@palco.test`. Después compara lo que contesta Palco con lo que recibió y respondió el proveedor (su registro, `GET /control/recibidos`) y con lo que escribió el servidor en el `server.log`. Levanta el proveedor y lo baja al terminar; si ya hay uno escuchando en el puerto, usa ese.

```bash
node scripts/pruebas/asistente-proveedor.mjs                # el servidor ya arrancó apuntando al proveedor
node scripts/pruebas/asistente-proveedor.mjs --configurar   # lo apunta con jboss-cli y al final lo deja como estaba
```

Sale con código 0 si todas las comprobaciones pasan y con 1 si alguna falla. Sale con 2 si el servidor no usa el proveedor, y en ese caso explica cómo configurarlo.

### Qué comprueba

| Parte | Qué comprueba |
|---|---|
| A. Respuesta del proveedor | La pregunta llega por `POST /v1/chat/completions` en dos mensajes: las instrucciones y los datos de Palco en el `system` (con los valores de `ReglasDeNegocio`) y la pregunta, sola, en el `user`. Palco responde con origen `MODELO` y el texto del proveedor tal cual, que cita el dato del `system`. El `server.log` registra `modelo=openai-compatible/<modelo>` |
| B. Datos personales | Una pregunta con DNI, email y tarjeta le llega al proveedor con `[dni oculto]`, `[email oculto]` y `[tarjeta oculta]`, y así queda en el log. Si el proveedor contesta con un DNI o un email, el guardrail de salida no muestra esa respuesta: origen `BLOQUEADA` (`salida-con-datos-personales`), con derivación a soporte |
| C. Guardrail de entrada | Una inyección de prompt, un pedido de datos de terceros y una pregunta de más de 500 caracteres se bloquean, y el proveedor no recibe nada |
| D. Proveedor lento | El proveedor tarda 8 s: a los 5 s responde el fallback (`timeout`) con la respuesta frecuente, y el adaptador corta la conexión |
| E. Proveedor con error | 401, 500 y JSON roto terminan en el fallback (`error-del-modelo`): la respuesta frecuente o, si no hay, soporte. El log dice que el proveedor respondió 401, sin el cuerpo del error |
| F. Plan de apagado | Con `palco.asistente.habilitado=false` (por `jboss-cli`, sin redesplegar) responde el fallback (`apagado`) sin llamar al proveedor. Al volver a encenderlo, responde otra vez el proveedor |
| G. Seguridad | Sin token responde 401, y el proveedor no recibe nada |

### Cómo apuntar el servidor al proveedor

El asistente lee su configuración con MicroProfile Config: de una variable de entorno (`PALCO_ASISTENTE_OPENAI_URL` es `palco.asistente.openai.url`) o de una propiedad del sistema de WildFly, que tiene prioridad. Hay dos maneras:

- **Al arrancar.** `dev.sh` le pasa el entorno a WildFly. También carga `backend/.env`, que gana si define las mismas variables. Con la base H2 en memoria, reiniciar borra los datos.

  ```bash
  cd backend && PALCO_ASISTENTE_MODELO=openai PALCO_ASISTENTE_OPENAI_URL=http://127.0.0.1:8089/v1 ./dev.sh
  ```

- **En marcha, con `--configurar`.** El script define con `jboss-cli` las propiedades `palco.asistente.modelo=openai` y `palco.asistente.openai.url` y redespliega solo el asistente (`/deployment=palco-asistente.war:redeploy`). Al terminar, aunque falle, las deja como estaban y lo vuelve a redesplegar. Mientras corre, todo el asistente del servidor usa el proveedor de prueba.

El redeploy hace falta porque `FabricaDeModelo` elige el modelo una sola vez por despliegue (`@ApplicationScoped`): cambiar `palco.asistente.modelo` en caliente no cambia el modelo hasta redesplegar el asistente. La dirección, el modelo y la clave del proveedor, en cambio, `ModeloCompatibleOpenAI` los lee en cada pedido. Por eso se pueden cambiar con `jboss-cli` sin redesplegar, como la demora del modelo simulado y `palco.asistente.habilitado`. Las propiedades que se agregan con `jboss-cli` quedan guardadas en el `standalone.xml` y siguen después de reiniciar, hasta que se quitan con `/system-property=<nombre>:remove`.

### Qué necesita

- Node 18 o más nuevo, sin paquetes de npm.
- El servidor levantado en esta máquina, con la cuenta `titular@palco.test` (contraseña `palco1234`).
- `jboss-cli` y el `server.log` del servidor de `backend/servidor/target/server/`, igual que `escenarios.mjs` (variables `JBOSS_CLI` y `PALCO_LOG`). Sin el log no se hacen las comprobaciones del registro, y el script lo avisa.
- El puerto 8089 libre, o `PROVEEDOR_PUERTO` con otro, que tiene que ser el mismo de `PALCO_ASISTENTE_OPENAI_URL`.

## postman/generar.js

Genera la colección de Postman, `docs/api/palco-api.postman_collection.json`, que no se edita a mano: se cambia el generador y se corre `node scripts/postman/generar.js` desde la raíz (solo Node, sin paquetes de npm). La guía está en `docs/api/GUIA_POSTMAN.md`, sección 12.

## Los demás guiones

`pruebas/seguridad.mjs` (tokens manipulados, mass assignment, XSS, XXE y cabeceras), `carga/` (k6) y `qa/` (cobertura, matriz de trazabilidad, mutación, estabilidad, análisis estático y ZAP) están explicados, con el comando de cada uno contra el compose, en `docs/testing/plan-de-pruebas.md`.

Otros dos de `qa/`:

- `qa/jboss-cli-docker.sh`: `jboss-cli` del WildFly que corre en el compose, para `escenarios.mjs` (`JBOSS_CLI=scripts/qa/jboss-cli-docker.sh`). Manda al contenedor el archivo de comandos que arma la prueba y lo corre allá.
- `qa/control-de-logs.sh`: el control del `server.log` después de una corrida contra el compose, que corren los pasos "Control de logs" de `ci.yml`, `contrato.yml`, `pruebas-seguridad.yml` y `zap.yml` (DEF-40). Falla si el log tiene un token, una contraseña de prueba o un DNI y, con los emails enmascarados (`PALCO_CORREO_LOG_COMPLETO` sin definir o en `false`), un email `@palco.test` entero. Lo que encuentra sale con el número de renglón y qué es, nunca el renglón.

  ```bash
  bash scripts/qa/control-de-logs.sh logs/server.log
  ```
