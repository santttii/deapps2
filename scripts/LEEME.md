# scripts

## demo-entrega3.sh

El guion de la demo de la Entrega Parcial N.º 2 de la consigna (viernes 23/10/2026, fecha a confirmar con la cátedra). El plan viejo la llamaba "Entrega 3", y de ahí viene el nombre del archivo. Hace una compra de punta a punta con `curl` contra el servidor ya levantado y muestra, paso por paso, el proceso de negocio con su parte asincrónica: al pagar, Ventas publica `PAGO_CONFIRMADO` en la cola `cola.emision-entradas` y en el tópico `topico.compra-confirmada`.

Se corre con Git Bash en Windows, o con Bash en Linux y macOS, desde cualquier carpeta:

```bash
bash scripts/demo-entrega3.sh              # compra con pago aprobado
bash scripts/demo-entrega3.sh rechazado    # compra con pago rechazado
```

Necesita `bash`, `curl`, `grep`, `sed` y `od`. No usa `jq`. La terminal tiene que estar en UTF-8, como viene Git Bash; en PowerShell, antes hay que correr `chcp 65001`.

### Qué muestra

**Pago aprobado (12 pasos).** Login del comprador, contadores del broker, cupo del sector, inicio de la compra (la venta queda `EN_CHECKOUT` y el cupo ya está reservado), pago, estado al volver el pago (`CONFIRMADA`, con las entradas en `PENDIENTE_EMISION`), espera, estado final (`EMITIDA`, con las entradas en `VALIDA`), una entrada con su código, cupo después, la fila de auditoría de la venta y otra vez los contadores.

**Pago rechazado (9 pasos).** Los cuatro primeros son los mismos. Después: el pago responde 402 `pago-rechazado`, la venta sigue `EN_CHECKOUT` sin pago ni entradas, auditoría no tiene ninguna fila de esa venta y los contadores no suben. Al final cancela la compra y comprueba que el cupo volvió.

Cada paso comprueba lo que muestra. Si algo no da lo esperado, el guion corta en ese paso, dice qué esperaba y qué llegó, y sale con código 1. Si quedó una venta sin pagar, pide cancelarla. Con un argumento desconocido o sin `curl` sale con código 2.

Cada corrida usa claves de idempotencia nuevas y toma los ids de las respuestas, así que se puede repetir. Cada compra aprobada ocupa 2 lugares del sector.

### Los contadores del broker

Se leen con `jboss-cli`, de `backend/servidor/target/server/bin/` (`jboss-cli.bat` en Windows, `jboss-cli.sh` en Linux y macOS). Si no está, si falta Java o si `PALCO_URL` no apunta a esta máquina, el guion lo dice y sigue sin contadores.

- `jboss-cli` usa el Java de `JAVA_HOME` o, si no está definida, el del `PATH`. En Git Bash, `JAVA_HOME` puede estar escrita al modo de Windows (`C:\...`) o al de Git Bash (`/c/...`): el guion la convierte, porque `jboss-cli.bat` solo entiende la primera.
- Son de todo el servidor, no de una venta. Una compra suma 1 a la cola y 2 al tópico, porque el tópico cuenta una copia por suscripción y tiene dos (el aviso y la auditoría).
- Si mientras corre el guion alguien más compra, suben de más. En ese caso el guion nombra las otras ventas que registró auditoría. En el modo rechazado falla solo si esas ventas no alcanzan para explicar la suba.

### Variables de entorno

| Variable | Para qué | Valor por defecto |
|---|---|---|
| `PALCO_URL` | Dirección del servidor | `http://localhost:8080` |
| `EVENTO_SLUG` | Evento del que se compra | `kiroshi-tour-continental` |
| `SECTOR_ID` | Sector del que se compra | `sec-2-2` |
| `COMPRADOR_EMAIL`, `COMPRADOR_CLAVE` | Cuenta del comprador | `titular@palco.test`, `palco1234` |
| `SECRETO_SERVICIO` | Secreto para pedir el token de rol `servicio` | `palco-desarrollo` |
| `ESPERA_MAXIMA` | Segundos que se espera la emisión | `15` |
| `DETALLE=1` | Imprime además el JSON completo de cada respuesta | apagado |
| `JBOSS_CLI` | Ruta de `jboss-cli`, si no está en la del proyecto | la del proyecto |
| `NO_COLOR=1` | Salida sin colores | con colores |

### Qué no muestra

El cobro es el simulado: `tok_test` aprueba y `tok_rechazado` rechaza. La factura, Mercado Pago, la reventa y la validación en puerta están diseñadas y pendientes, y el guion no las toca.

## pruebas/escenarios.mjs

La prueba de integración del backend: 13 escenarios de compra y mensajería, de punta a punta, por la API y por `jboss-cli`. Es la que produjo `docs/entrega3/evidencia/integracion-13-escenarios.txt`. Cada comprobación imprime qué se esperaba y qué se vio; al final hay un resumen por escenario.

```bash
node scripts/pruebas/escenarios.mjs                       # los 13 escenarios
node scripts/pruebas/escenarios.mjs 1 2 3 4 5 6 7 9 13    # solo los que no tocan los consumidores
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
| 7 | Un mensaje por pago | Un mensaje a `cola.emision-entradas` y una copia por suscripción en `topico.compra-confirmada`, con el selector `tipoEvento = 'PAGO_CONFIRMADO'` y el cuerpo del contrato |
| 8 | Mensaje repetido | Detiene la auditoría y pasa su copia a la cola de emisión con `move-messages`: el consumidor la reconoce como repetida y no cambia nada |
| 9 | Los dos suscriptores | El aviso (log) y la auditoría (fila) reciben cada uno su copia |
| 10 | Consumidor detenido (taller) | Con `stop-delivery` en la emisión, el mensaje espera en la cola y la venta queda `CONFIRMADA`; con `start-delivery` se emite |
| 11 | Suscripción durable | Con la auditoría detenida se publican dos mensajes; al reanudarla los recibe |
| 12 | Mensaje inválido | Se reintenta 5 veces, cada 2 segundos, y termina en la DLQ sin trabar a las compras; después se lo pasa de la DLQ a la cola de emisión y vuelve a la DLQ |
| 13 | Transacción deshecha | Si la transacción se deshace después de publicar, el mensaje no sale; el mismo mensaje sin la falla sí sale |

Los escenarios 8, 10, 11 y 12 detienen un consumidor (`stop-delivery`) o pasan mensajes de una cola a otra (`move-messages`). Si alguien más está probando contra el mismo servidor, se corren solo los otros nueve. Cada uno vuelve a arrancar el consumidor que detuvo, aunque falle.

### Qué necesita

- Node 18 o más nuevo: usa el `fetch` que trae Node y ningún paquete de npm, así que no hay nada que instalar.
- WildFly levantado con los 8 WAR (`cd backend && ./dev.sh`) y las cuentas de prueba de siempre: `titular@palco.test`, `organizador@palco.test` y `puerta@palco.test`, con la contraseña `palco1234`.
- El servidor armado en `backend/servidor/target/server/` de la misma máquina: de ahí salen `jboss-cli` (`jboss-cli.bat` en Windows, `jboss-cli.sh` en Linux y macOS) y el `server.log` que se lee para comprobar lo que hicieron los consumidores.
- Java para `jboss-cli`: el de `JAVA_HOME` o, si no está definida, el del `PATH`. En Git Bash, `JAVA_HOME` puede estar escrita al modo de Windows (`C:\...`) o al de Git Bash (`/c/...`): la prueba la convierte.

Las rutas salen de la ubicación del archivo, así que se puede correr desde cualquier carpeta. Variables opcionales: `PALCO_URL` (`http://localhost:8080`), `JBOSS_CLI` y `PALCO_LOG` (si `jboss-cli` o el log no están en la ruta del proyecto).

### Qué deja y qué tener en cuenta

- Crea un evento de prueba propio (como organizador), con un sector igual a Platea B (27.500) y uno de 3 lugares: así el cupo se compara exacto aunque otra persona compre en otros eventos.
- Deja en la base ventas emitidas y canceladas de `titular@palco.test`, una cuenta nueva por corrida y ese evento.
- Los contadores del broker son de todo el servidor. Si alguien compra mientras corre, la prueba busca esas ventas en el log y las descuenta; si aun así no cierra, se repite.
- El escenario 8 corta si en la suscripción durable hay copias de otras ventas, para no dejarlas sin auditar. La venta que usa queda sin fila de auditoría, y el 12 deja el mensaje inválido en la DLQ.

## postman/generar.js

Genera la colección de Postman, `docs/api/palco-api.postman_collection.json`, que no se edita a mano: se cambia el generador y se corre `node scripts/postman/generar.js` desde la raíz (solo Node, sin paquetes de npm). La guía está en `docs/api/GUIA_POSTMAN.md`, sección 12.
