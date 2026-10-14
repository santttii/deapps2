# Pagos (tarea A3)

Nota para la defensa oral. A cargo de Valentina Frisoli. Cerrada el 13/10/2026: lo que figura como probado se corrió ese día en el WildFly de desarrollo. Los pasos T y las decisiones P son los de `docs/planes/00-contratos-compartidos.md`.

## Qué se construyó

`ServicioDePagos` cobra una venta contra una pasarela (el servicio externo que cobra) y guarda el resultado. Es una API interna: sus dos operaciones piden el rol `servicio`, así que la llama Ventas y nunca el comprador.

- `POST /api/v1/pagos`, con la cabecera `Idempotency-Key` y el cuerpo `{ventaId, monto, medioDePago, token}`. Responde 201 con `{pagoId, ventaId, monto, estado, motivo, idExterno}`. Es idempotente: repetirlo con la misma clave devuelve el mismo pago y no cobra otra vez.
- `GET /api/v1/pagos/{pagoId}`: 200 o 404.
- La tabla `pagos.pago`, con `clave_idempotencia` e `id_externo` únicas, y 22 tests que corren sin servidor.

Hoy la pasarela es `PagosFake`, un simulador que no mueve plata. El resultado lo decide el token: `tok_rechazado` rechaza con motivo `fondos_insuficientes`, `tok_sin_respuesta` no contesta y cualquier otro aprueba.

## Decisiones: qué se eligió, contra qué alternativa y por qué

1. **El servicio decide y guarda; la pasarela solo habla con el exterior.** `ServicioDePagos` implementa `IPagos` y `PagosFake` implementa `PasarelaDePagos`, nuestra interfaz hacia afuera. En la guía de la tarea (`docs/PLAN_AGENTES.md`) cada pasarela implementaba `IPagos`: cada una tendría que guardar el pago y detectar los repetidos. Así eso se escribe una sola vez.
2. **La pasarela la elige una Factory.** `FabricaDePasarela` lee `palco.pagos.proveedor` (variable `PALCO_PAGOS_PROVEEDOR`, por defecto `fake`) y crea la pasarela; el servicio la recibe con `@Inject` y no sabe cuál es. La alternativa, un `if` dentro del servicio, mezcla configuración con negocio. Un valor desconocido impide el despliegue, en lugar de cobrar de mentira sin avisar.
3. **Un rechazo responde 201, no 402.** Para Pagos el pago se procesó y `RECHAZADO` es su resultado: queda guardado y se puede consultar. El 402 se lo da Ventas al comprador. Si Pagos respondiera 402, el intento no quedaría registrado.
4. **El pedido repetido lo frena la base, no un `if`.** Si la clave ya existe se devuelve el pago guardado. Si dos pedidos iguales llegan a la vez, los dos pasan esa búsqueda, pero la restricción única deja guardar a uno solo; el otro relee y devuelve ese mismo pago. Verificar solo en Java dejaría pasar a los dos. La misma clave con otra venta u otro monto responde 409 `clave-reutilizada`.
5. **Ninguna transacción abierta mientras se espera a la pasarela.** `ServicioDePagos` lleva `NOT_SUPPORTED` (sin transacción) y cada método de `PagoRepositoryJpa` corre la suya, corta. Una sola transacción retendría una de las 10 conexiones que comparten los 8 servicios mientras contesta un tercero y, después de una clave duplicada, quedaría marcada para deshacerse: ya no se podría releer el pago que ganó.
6. **Qué no va a la base.** El token de la tarjeta no se guarda: es sensible y sirve para un solo cobro. La columna `estado` no lleva `check` de lista de valores, porque en el H2 de desarrollo se rompen (paso T6); los valores válidos los controla el enumerado `EstadoPago`.

## Qué le dice cada respuesta a Ventas

Ventas marca la venta con `clave_pago` antes de cobrar, y una venta marcada no vence, no se cancela ni se paga con otra clave. Por eso importa saber, para cada respuesta, si se cobró:

| Pagos responde | ¿Se cobró? | Qué tiene que hacer Ventas |
|---|---|---|
| 201 `APROBADO` | Sí | Confirma la venta |
| 201 `RECHAZADO` | No, y esa clave ya no cobra | Borra `clave_pago` y responde 402 |
| 400 `datos-invalidos` | No: se rechaza antes de mirar la clave, que queda libre | Borra `clave_pago` |
| 409 `clave-reutilizada` | No: esa clave ya tiene guardado el pago de otro pedido | Borra `clave_pago` |
| 504, 500 o sin respuesta | No se sabe | Deja `clave_pago` y reintenta con la misma clave |

Lo que responde Pagos se probó en vivo, salvo el 500: sale de leer el código y es el caso del guardado que falla después de cobrar. Lo de Ventas sale de su plan y de su repositorio, porque B1 todavía no está escrita.

Las filas del 400 y del 409 son una regla nueva (revisión del 13/10) y todavía no están en la sección 5.4 de contratos ni en el plan de Ventas, que deja la marca ante todo lo que no sea un rechazo. Si B1 no la borra, esa venta queda `EN_CHECKOUT` con su cupo retenido: alcanza con pagar varias ventas con una clave ya usada para retener el cupo de un evento sin pagar.

## Qué quedó pendiente o simulado y por qué

- **La pasarela es simulada.** Mercado Pago, el webhook (el aviso que manda la pasarela) y el reembolso quedan para después del checkpoint (decisión P10), en la tarea D1 de `docs/PLAN_AGENTES.md`, que tiene como límite el 19/11: el 23/10 (fecha de la Entrega Parcial N.º 2, a confirmar con la cátedra) se evalúa el proceso asincrónico, que no pasa por Pagos, y la firma del webhook no se pudo verificar contra la documentación oficial. `PENDIENTE`, `VENCIDO` y `REEMBOLSADO` están en el enumerado, pero hoy nadie los escribe.
- **Ventas todavía no llama a este servicio.** Lo conecta la tarea B1; hasta entonces cobra con su simulador, `CobrosFake`, que usa los mismos tokens.
- **Límites que se aceptan.**
  - Tras un `RECHAZADO`, la misma clave devuelve siempre ese rechazo, aunque llegue otro token: el token no se guarda, así que no distingue un pedido de otro. Un intento nuevo necesita una clave nueva, y la genera el cliente (el checkout), no Ventas, que reenvía la que recibe. La misma clave se repite solo ante un 504 o un error de red.
  - Dos pedidos simultáneos con la misma clave llaman dos veces a la pasarela, también si eran pedidos distintos y uno termina en 409: con el simulador da igual, y con Mercado Pago va a depender de que deduplique por la clave.
  - Si la pasarela cobra y falla el guardado, el pago queda sin registro hasta que Ventas reintente con la misma clave.
  - El `monto` se valida después de convertirlo. El conversor de JSON trunca los decimales (60500.75 se cobra como 60500), recorta sin avisar un número que no entra en un `long` (18446744073709551621 se cobra como 5) y acepta un número entre comillas. Pagos confía en que Ventas, su único cliente, manda un `long` de Java. Rechazarlo exige recibir el monto como `BigDecimal`: queda a decisión del grupo.
- **Sin probar.** Que un proveedor desconocido impida el despliegue: lo cubre un test, pero verlo en el servidor exige reiniciarlo con la variable cambiada. Tampoco se corrió la migración contra PostgreSQL (Supabase).

## Las cinco preguntas más probables

1. **¿Por qué `ServicioDePagos` es stateless?** No recuerda nada entre una llamada y la siguiente: el pago y su clave quedan en la base, así que cualquier instancia del pool atiende cualquier pedido, incluso la repetición de uno anterior. Un stateful es para la conversación de un usuario, como la reserva de Ventas; acá hay un pedido y una respuesta.
2. **¿Qué patrón aplicaron?** Factory, en `FabricaDePasarela`. `MercadoPagoPagos` va a ser un Adapter, porque traduce una API ajena a `PasarelaDePagos`: está diseñado y pendiente. `PagosFake` no adapta nada, es una implementación simulada. Que sean intercambiables tiene forma de Strategy, pero lo que se resuelve es una interfaz ajena incompatible.
3. **¿Qué pasa si el mismo pago llega dos veces?** Vuelve el mismo pago y la pasarela no se llama de nuevo (decisión 4), también si ese pago fue un rechazo. Se probó con 8 pedidos simultáneos: los 8 respondieron 201 con el mismo `pagoId`, y en el log quedó el rechazo de la base (`SQLState: 23505`) seguido de "Dos pedidos simultáneos con la misma clave".
4. **¿Y si la pasarela no contesta?** Se responde 504 `sin-respuesta` y no se guarda nada, así que Ventas reintenta con la misma clave. No hay transacción que deshacer, porque nunca se abrió una.
5. **¿Es una integración real?** Todavía no. Funcionan de verdad la interfaz `PasarelaDePagos`, el guardado, la idempotencia y el control por rol; lo simulado es quién cobra. Para Mercado Pago faltan una cuenta de desarrollador con credenciales de prueba, escribir el Adapter y una URL pública para el webhook.

## Cómo probarlo a mano

En Git Bash, desde `backend/`, con WildFly levantado:

```bash
export JAVA_HOME=/c/Users/santi/.jdks/temurin-25
mvn -q -B -pl servicios/pagos package        # 22 tests en verde; deja servicios/pagos/target/pagos.war
cp servicios/pagos/target/pagos.war servidor/target/server/standalone/deployments/palco-pagos.war   # se redespliega en unos segundos
P=localhost:8080/api/v1/pagos; J='Content-Type: application/json'
curl -s $P/health                            # {"servicio":"pagos","baseDeDatos":"ok","estado":"ok"}
token() { sed -E 's/.*"token":"([^"]+)".*/\1/'; }
TS=$(curl -s -X POST -H "$J" -d '{"secreto":"palco-desarrollo"}' localhost:8080/api/usuarios/servicios/token | token)   # rol servicio; vence a los 5 minutos
TU=$(curl -s -X POST -H "$J" -d '{"email":"titular@palco.test","password":"palco1234"}' localhost:8080/api/usuarios/login | token)   # un comprador
pagar() { curl -s -i -X POST $P -H "$J" -H "Authorization: Bearer $1" -H "Idempotency-Key: $2" \
  -d "{\"ventaId\":\"$3\",\"monto\":60500,\"medioDePago\":\"tarjeta\",\"token\":\"$4\"}"; echo; }
K=demo-$(date +%s)                           # una clave nueva en cada corrida
pagar "$TS" $K   V-demo01 tok_test           # 201, Location y "estado":"APROBADO"
pagar "$TS" $K   V-demo01 tok_test           # 201 con el mismo pagoId: no se cobró de nuevo
pagar "$TS" $K   V-demo02 tok_test           # 409 clave-reutilizada: esa clave ya se usó con otra venta
pagar "$TS" $K-r V-demo03 tok_rechazado      # 201, "estado":"RECHAZADO" y "motivo":"fondos_insuficientes"
pagar "$TS" $K-r V-demo03 tok_test           # 201 con el mismo pago RECHAZADO: esa clave ya tiene resultado
pagar "$TS" $K-n V-demo03 tok_test           # 201 APROBADO: el intento nuevo lleva una clave nueva
pagar "$TS" $K-v V-demo07 ""                 # 400 datos-invalidos: falta el token; no se cobró y la clave queda libre
pagar "$TS" $K-s V-demo04 tok_sin_respuesta  # 504 sin-respuesta; no se guarda nada
pagar "$TU" $K-u V-demo05 tok_test           # 403: un comprador no puede cobrar (sin token, 401)
curl -s -H "Authorization: Bearer $TS" $P/P-xxxxxxxx   # 200; reemplazar por el pagoId del primer 201
# Paso T6: se vacía el pool de conexiones y un cobro nuevo se tiene que seguir guardando.
NOPAUSE=true servidor/target/server/bin/jboss-cli.bat --connect --command="/subsystem=datasources/data-source=PalcoDS:flush-all-connection-in-pool"
pagar "$TS" $K-p V-demo06 tok_test           # 201
```
