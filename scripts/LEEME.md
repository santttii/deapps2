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
