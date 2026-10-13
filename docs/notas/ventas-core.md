# Ventas core (tarea A1)

Nota para la defensa oral. A cargo de Sebastian Deya. Cerrada el 13/10/2026 y corregida ese mismo día después de la revisión (decisión 10): lo que figura como probado se corrió ese día en el WildFly de desarrollo. Los pasos T y las decisiones P son los de `docs/planes/00-contratos-compartidos.md`.

## Qué se construyó

`ServicioDeVentas` vende las entradas en dos llamadas, con el cupo reservado 10 minutos entre una y otra. La API cuelga de `/api/v1` y pide el rol `user`; una venta ajena responde 404, igual que una que no existe.

- `POST /ventas` reserva el cupo en Eventos y deja la venta `EN_CHECKOUT`. `POST /ventas/{id}/pagos` cobra, la deja `CONFIRMADA`, crea una entrada por titular y publica `PAGO_CONFIRMADO`. Los dos exigen `Idempotency-Key` con un UUID. Además, `GET` y `DELETE /ventas/{id}` y `GET /entradas/{id}`.
- Cuatro componentes: `ServicioDeVentas` (`@Stateful`, una instancia por compra), `ComprasEnCurso` (la fachada que usan los recursos REST), `VencimientoDeReservas` (un barrido por minuto) y `EmisionDeEntradasMDB` (consume la cola: deja las entradas `VALIDA` y la venta `EMITIDA`).
- El esquema `ventas` (tablas `venta`, `venta_item` y `entrada`) y 168 tests con Mockito, que corren sin servidor (16 son de la integración con Pagos, tarea B1).

## Decisiones: qué se eligió, contra qué alternativa y por qué

1. **El stateful se ubica por el id de la venta (P1).** `ComprasEnCurso` (`@ApplicationScoped`) pide una instancia por JNDI, la guarda en un mapa por `ventaId` y antes de usarla comprueba en la base que el `sub` del token sea el comprador. La instancia guarda lo que todavía no está en la base, los titulares, y lo que hace falta para cobrar sin leer la base: el total y el vencimiento. Un bean `@SessionScoped` sumaba una cookie como segunda identidad, y resolver todo en la base dejaba al sistema sin el stateful que pide la consigna.
2. **Una sola llamada a la vez por compra, frenada en la fachada.** El plan era dejarlo en `@AccessTimeout(0)`. En WildFly 41 esa llamada rechazada hace además que el contenedor descarte la instancia: un doble clic dejaba la compra sin poder pagarse. Por eso `ComprasEnCurso` anota qué compras tienen una llamada en curso y responde 409 `operacion-en-curso` antes de llegar al bean.
3. **La reserva la vence un barrido, no el `@PreDestroy` (P2).** `VencimientoDeReservas` es un `@Singleton` con `@Schedule(persistent = false)` que mira la hora guardada en `vence_reserva`. `@PreDestroy` puede no ejecutarse (lo vimos: ante una excepción inesperada no corrió) y el timeout mide inactividad, no una hora fija; por eso solo escribe en el log y quita la referencia. El costo: la reserva dura hasta un minuto de más.
4. **Cada cambio de estado es un `UPDATE` con la condición adentro** (`where estado = 'EN_CHECKOUT'`) y solo sigue quien cambió la fila. Leer y después escribir dejaría que pagar, cancelar y vencer se pisen. De ahí la regla del cupo: lo devuelve a Eventos solo quien cerró la venta, con ese cambio ya confirmado.
5. **Confirmar, crear las entradas y publicar van en una transacción JTA declarativa (P3):** `REQUIRED` en `confirmarCompra` y `MANDATORY` en el publicador. El broker entra como recurso XA y la base como último recurso; no es un commit en dos fases. Una base XA no está verificada contra Supabase, y una tabla de salida con un proceso que publica era más código.
6. **Antes de cobrar se anota el intento en la venta (`clave_pago`),** con un `UPDATE` que se confirma en el momento. Sin esa marca, el barrido podría liberar el cupo de una venta que se está cobrando, y un cobro seguido de una falla no dejaría rastro. Se quita solo cuando se sabe que no se cobró; ante un 504 o una falla inesperada se deja.
7. **Las entradas nacen con el pago, en `PENDIENTE_EMISION`, y el MDB las emite (P4).** Si las creara el MDB, el mensaje no podría llevar `entradaId` y el `GET` de la entrada daría 404 apenas vuelve el pago. El MDB es idempotente por la base: un `UPDATE` por entrada que exige `PENDIENTE_EMISION`, en lugar de una tabla de mensajes procesados.
8. **`Idempotency-Key` con dos columnas, `clave_idempotencia` y `clave_pago`.** Repetir la clave devuelve la venta como está hoy, sin reservar ni cobrar de nuevo. Si dos pedidos con la misma clave llegan a la vez, los dos reservan, la restricción única `(comprador_id, clave_idempotencia)` deja guardar a uno solo y el otro devuelve su cupo y responde con la venta del primero. Una tabla clave → respuesta obligaba a guardar respuestas.
9. **El cobro va detrás de la interfaz `Cobros`.** A1 la implementó con `CobrosFake` y B1 conectó el servicio de Pagos sin tocar el bean (`docs/notas/integracion.md`). Llamar al simulador desde el bean obligaba a reescribirlo después.
10. **Mientras se espera a otro servicio no se retiene una conexión de la base.** Los 8 servicios comparten un pool de 10 conexiones, y el servicio al que se llama necesita una para contestar. Por eso `iniciarCompra` reserva primero y guarda la venta al final, de una sola vez y sin transacción propia (`NOT_SUPPORTED`: esa escritura corre en la transacción del repositorio), y `confirmarCompra` cobra con el total que recuerda la instancia y recién después toca la base. El plan guardaba la cabecera antes de reservar: con 80 compras a la vez se abrieron 28, 52 dieron 504 y quedaron 20 lugares tomados sin venta. Con el orden nuevo las 80 responden 201 en menos de medio segundo, y 60 pagos a la vez responden 201 en poco más de medio segundo (`docs/entrega3/evidencia/compras-simultaneas.txt`). Agrandar el pool solo corría el límite.

## Qué pasa si falla cada paso

| Paso | Si falla |
|---|---|
| Reservar el cupo (REST a Eventos, que confirma en el acto) | 409 `cupo-agotado`, 422 `sector-inexistente` o 504. Ventas devuelve los sectores que ya habían respondido; la venta todavía no se guardó. Si falla el guardado, devuelve todos |
| Cobrar (es externo: un rollback no lo deshace) | 402 `pago-rechazado` o 504 `sin-respuesta`. La venta sigue `EN_CHECKOUT` y la instancia, viva: tras un 402 se paga con otra clave; tras un 504, con la misma |
| Confirmar, crear entradas y publicar (transacción JTA) | 500 y rollback: ni venta confirmada, ni entradas, ni mensaje. La venta queda con `clave_pago` |
| Emitir (MDB, en la transacción que recibe el mensaje) | El mensaje vuelve al broker: 5 entregas cada 2 segundos y después la DLQ. La venta queda `CONFIRMADA`, nunca a medias |

Los `type` de los errores: `datos-invalidos` (400), `no-autenticado` (401), `pago-rechazado` (402), `identidad-no-verificada` (403), `no-encontrado` (404), `cupo-agotado`, `reserva-vencida`, `venta-ya-paga` y `operacion-en-curso` (409), `maximo-de-entradas`, `titulares-no-coinciden`, `dni-repetido` y `sector-inexistente` (422) y `sin-respuesta` (504). Los repositorios tienen dos más (`clave-repetida` y `entrada-repetida`) como última red ante una clave repetida en la base; nunca salieron por la API.

## Qué quedó pendiente o simulado y por qué

- **La pasarela es simulada y no se factura.** Desde B1 cobra el servicio de Pagos, con su pasarela simulada, que decide por el token: `tok_rechazado` rechaza, `tok_sin_respuesta` no contesta y cualquier otro aprueba. `CobrosFake` queda para trabajar sin Pagos (`palco.ventas.cobros=fake`); con él, una clave de pago usada en dos ventas paga las dos. `facturaEstado` vale `NO_APLICA` hasta C1 (SOAP, para el 09/11).
- **Una venta con `clave_pago` y sin resultado queda `EN_CHECKOUT` con su cupo** hasta la conciliación de C3: el barrido no la toca y no se puede pagar ni cancelar. Hoy hay dos, de la prueba de la excepción forzada.
- **Redesplegar Ventas corta las compras en curso** (el índice y los titulares viven en memoria): se pueden consultar y cancelar, pagar responde 409 `reserva-vencida` y el barrido las vence.
- **Si Eventos no responde al devolver el cupo** queda un `WARNING` con venta, sector y cantidad, y ese lugar queda sin vender hasta C3. Si no responde al reservar, no se sabe si la reserva se aplicó y no se devuelve: el `WARNING` dice evento, sector y cantidad.
- **Dos pedidos simultáneos con la misma clave reservan los dos** (decisión 8). Si en el sector queda lugar para uno solo, el que pierde recibe 409 `cupo-agotado`; al repetirlo recibe la venta.
- **Sin probar:** más de 80 compras o de 60 pagos a la vez; los pagos simultáneos con la versión anterior (frenan el servidor mientras duran); una caída entre el commit de la base y el del broker; Eventos o Usuarios caídos (solo con tests); el arranque en frío de WildFly; la migración contra PostgreSQL. La excepción forzada y el mensaje repetido se probaron con un WAR temporal que no quedó en el repo.
- **Falta el visto bueno del grupo** a lo que se aparta del plan: la columna `entrada.orden`, las decisiones 2 y 10, que un nombre con saltos de línea o caracteres de control responda 400, que cancelar responda `operacion-en-curso` si hay un pago en curso, que un sector repetido responda 400 y `@DependsOn("MigracionesVentas")` en el barrido.

## Las cinco preguntas más probables

1. **¿Por qué `ServicioDeVentas` es stateful y quién maneja su ciclo de vida?** Una compra es una conversación de dos pedidos y la instancia recuerda a nombre de quién va cada entrada hasta que se paga; con un stateless el pago podría caer en otra instancia del pool. El ciclo lo maneja el contenedor: la crea con la búsqueda JNDI (`@PostConstruct`) y la destruye con `@Remove` al pagar o cancelar, o por `@StatefulTimeout` a los 10 minutos sin uso. Cada paso queda en el log, con el número de instancia (al final hay líneas reales).
2. **¿Qué pasa si algo falla a mitad de reservar, cobrar y confirmar?** La transacción JTA cubre el tercer paso; los otros dos se protegen como dice la tabla. Forzamos una excepción después de publicar: el pago respondió 500, la venta siguió `EN_CHECKOUT` sin entradas y la cola y el tópico no cambiaron. Una caída entre el commit de la base y el del broker dejaría una venta `CONFIRMADA` sin mensaje; eso no lo probamos.
3. **¿Por qué reservar y cobrar son sincrónicos y la emisión asincrónica?** El comprador necesita saber en el momento si hay lugar y si el pago pasó. El código QR puede llegar después: así el pago no espera a la emisión ni falla con ella. Va por una cola porque cada mensaje tiene que recibirlo un solo consumidor.
4. **¿Qué pasa si el mensaje llega dos veces o el consumidor falla?** La segunda vez el `UPDATE` no encuentra nada pendiente y el mensaje se confirma sin error: con 8 copias a la vez hubo una emisión y 7 líneas "es un mensaje repetido". Si el consumidor falla, su transacción se deshace entera y el broker reentrega.
5. **¿Cómo evitan vender dos veces un lugar o cobrar dos veces?** El cupo es de Eventos y se reserva antes de cobrar. Repetir un pedido con la misma clave no cobra de nuevo ni deja una segunda reserva: veinte `POST` simultáneos con la misma clave dejaron una sola venta y las vendidas subieron 1, y ocho pagos simultáneos con claves distintas dieron un 201 y siete 409 `operacion-en-curso`.

## Cómo probarlo a mano

En Git Bash, desde `backend/`, con WildFly levantado; cada comentario dice lo que tiene que salir. Abajo van cuatro líneas reales del log del 13/10 (sin la fecha y con la clase abreviada): una emisión y una compra que nadie pagó.

```bash
export JAVA_HOME=/c/Users/santi/.jdks/temurin-25 NOPAUSE=true    # sin NOPAUSE, jboss-cli espera una tecla
mvn -q -B -pl servicios/ventas package       # 168 tests en verde
cp servicios/ventas/target/ventas.war servidor/target/server/standalone/deployments/palco-ventas.war; sleep 8
H=localhost:8080; V=$H/api/v1; J='Content-Type: application/json'; L=servidor/target/server/standalone/log/server.log
T=$(curl -s -X POST -H "$J" -d '{"email":"titular@palco.test","password":"palco1234"}' $H/api/usuarios/login | sed -E 's/.*"token":"([^"]+)".*/\1/')
A=(-H "Authorization: Bearer $T" -H "$J"); uuid() { node -e "console.log(crypto.randomUUID())"; }
vendidas() { curl -s $H/api/eventos/atletico-norte-racing-sur | sed -E 's/.*"id":"sec-1-4"[^}]*"vendidas":([0-9]+).*/\1/'; echo; }
cola() { servidor/target/server/bin/jboss-cli.bat --connect --command="/subsystem=messaging-activemq/server=default/jms-queue=cola.emision-entradas:read-attribute(name=messages-added)" | grep result; }
P='{"eventoId":"ev-1","items":[{"sectorId":"sec-1-4","cantidad":2}],"titulares":[{"nombre":"Ana Gomez","dni":"30123456"},{"nombre":"Luis Perez","dni":"31234567"}]}'
abrir() { curl -s -i -X POST $V/ventas "${A[@]}" -H "Idempotency-Key: ${1:-$(uuid)}" -d "$P" | tr -d '\r' | grep -i '^location:' | sed 's#.*/##'; }
pagar() { curl -s -i -X POST $V/ventas/$1/pagos "${A[@]}" -H "Idempotency-Key: $(uuid)" -d "{\"medioDePago\":\"tarjeta\",\"token\":\"$2\"}"; echo; }
ver() { curl -s $V/$1 "${A[@]}"; echo; }
curl -s $V/health; echo; vendidas; cola      # 1) Compra completa. Health con "servicio":"v1"; anotar vendidas y la cola
K=$(uuid); C=$(abrir $K); echo $C; abrir $K; vendidas    # dos veces el mismo V-xxxxxxxx (misma clave, misma venta); vendidas subió 2
pagar $C tok_test                            # 201, Location …/pagos/P-…, "estado":"CONFIRMADA" y dos entradas PENDIENTE_EMISION
ver ventas/$C; ver entradas/$(ver ventas/$C | grep -o 'T-[0-9a-f]*' | head -1)   # la venta EMITIDA; la entrada VALIDA, con codigoQr y emitida
cola; grep $C $L                             # la cola subió 1; el log: abierta, confirmada, destruye la instancia y los tres MDB
C=$(abrir); vendidas; curl -s -o /dev/null -w '%{http_code}\n' -X DELETE $V/ventas/$C "${A[@]}"   # 2) Compra cancelada: vendidas subió 2 y el DELETE da 204 (repetirlo, también)
vendidas; ver ventas/$C                      # vendidas volvió a su valor; "estado":"CANCELADA" y _links solo con self
C=$(abrir); pagar $C tok_rechazado; pagar $C tok_test; pagar $C tok_test   # 3) Errores: 402 pago-rechazado, después 201 y después 409 venta-ya-paga
curl -s -X POST $V/ventas "${A[@]}" -d "$P"; echo   # 400 datos-invalidos: falta Idempotency-Key
curl -s -X POST $V/ventas "${A[@]}" -H "Idempotency-Key: $(uuid)" -d '{"eventoId":"ev-1","items":[{"sectorId":"sec-1-4","cantidad":1}],"titulares":[{"nombre":"Ana\nGomez","dni":"30123456"}]}'; echo   # 400 datos-invalidos: el nombre va en una sola línea
C=$(abrir); echo $C                          # 4) Vencimiento: no tocarla y esperar 11 minutos
grep $C $L; ver ventas/$C; vendidas          # el @PreDestroy por timeout y la línea del barrido; "estado":"VENCIDA" y el cupo devuelto
node ../docs/entrega3/evidencia/compras-simultaneas.mjs   # 5) A la vez: 80 compras, 20 pedidos con la misma clave y 30 pagos, todos 201 (salida en compras-simultaneas.txt)
```

```
13:18:25,566 INFO [EmisionDeEntradasMDB] (Thread-167 (activemq-client-global)) EmisionDeEntradas: procesado el mensaje de la venta V-63126e66. Entradas emitidas: 2. La venta pasó a EMITIDA.
13:24:30,291 INFO [ServicioDeVentas] (default task-149) Compra V-2d21bfc0 abierta por user-titular: total 60500, reserva hasta 2026-10-13T16:34:30Z (instancia 7767fb74).
13:34:30,294 INFO [ServicioDeVentas] (SimpleStatefulSessionBeanCache - 22) Compra en curso: el contenedor destruye la instancia 7767fb74 de la venta V-2d21bfc0.
13:35:00,041 INFO [VencimientoDeReservas] (EJB default - 2) VencimientoDeReservas: la venta V-2d21bfc0 no se pagó a tiempo (la reserva valía hasta 2026-10-13T16:34:30Z). Pasó a VENCIDA y se devuelve su cupo a Eventos. Entradas a devolver: 2.
```
