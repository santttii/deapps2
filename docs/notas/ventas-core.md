# Ventas core (tarea A1)

Nota para la defensa oral. A cargo de Sebastian Deya. Cerrada el 13/10/2026 y corregida ese mismo día después de la revisión (decisión 10): lo que figura como probado se corrió ese día en el WildFly de desarrollo. Los pasos T y las decisiones P son los de `docs/planes/00-contratos-compartidos.md`.

## Qué se construyó

`ServicioDeVentas` vende las entradas en dos llamadas, con el cupo reservado 10 minutos entre una y otra. La API cuelga de `/api/v1` y pide el rol `user`; una venta ajena responde 404, igual que una que no existe.

- `POST /ventas` reserva el cupo en Eventos y deja la venta `EN_CHECKOUT`. `POST /ventas/{id}/pagos` cobra, la deja `CONFIRMADA`, crea una entrada por titular y publica `PAGO_CONFIRMADO`. Los dos exigen `Idempotency-Key` con un UUID. Además, `GET` y `DELETE /ventas/{id}` y `GET /entradas/{id}`.
- Cuatro componentes: `ServicioDeVentas` (`@Stateful`, una instancia por compra), `ComprasEnCurso` (la fachada que usan los recursos REST), `VencimientoDeReservas` (un barrido por minuto) y `EmisionDeEntradasMDB` (consume la cola: deja las entradas `VALIDA` y la venta `EMITIDA`).
- El esquema `ventas` (tablas `venta`, `venta_item` y `entrada`) y 187 tests con Mockito, que corren sin servidor (parte de ellos, de la integración con Pagos, tarea B1).

## Decisiones: qué se eligió, contra qué alternativa y por qué

1. **El stateful se ubica por el id de la venta (P1).** `ComprasEnCurso` (`@ApplicationScoped`) pide una instancia por JNDI, la guarda en un mapa por `ventaId` y antes de usarla comprueba en la base que el `sub` del token sea el comprador. La instancia guarda lo que todavía no está en la base, los titulares, y lo que hace falta para cobrar sin leer la base: el total y el vencimiento. Un bean `@SessionScoped` sumaba una cookie como segunda identidad, y resolver todo en la base dejaba al sistema sin el stateful que pide la consigna.
2. **Una sola llamada a la vez por compra, frenada en la fachada.** El plan era dejarlo en `@AccessTimeout(0)`. En WildFly 41 esa llamada rechazada hace además que el contenedor descarte la instancia: un doble clic dejaba la compra sin poder pagarse. Por eso `ComprasEnCurso` anota qué compras tienen una llamada en curso y responde 409 `operacion-en-curso` antes de llegar al bean.
3. **La reserva la vence un barrido, no el `@PreDestroy` (P2).** `VencimientoDeReservas` es un `@Singleton` con `@Schedule(persistent = false)` que mira la hora guardada en `vence_reserva`. `@PreDestroy` puede no ejecutarse (lo vimos: ante una excepción inesperada no corrió) y el timeout mide inactividad, no una hora fija; por eso solo escribe en el log y quita la referencia. El costo: la reserva dura hasta un minuto de más.
4. **Cada cambio de estado es un `UPDATE` con la condición adentro** (`where estado = 'EN_CHECKOUT'`) y solo sigue quien cambió la fila. Leer y después escribir dejaría que pagar, cancelar y vencer se pisen. De ahí la regla del cupo: lo devuelve a Eventos solo quien cerró la venta, con ese cambio ya confirmado.
5. **Confirmar, crear las entradas y publicar van en una transacción JTA declarativa (P3):** `REQUIRED` en `confirmarCompra` y `MANDATORY` en el publicador. El broker entra como recurso XA y la base como último recurso; no es un commit en dos fases. Una base XA no está verificada contra Supabase, y una tabla de salida con un proceso que publica era más código.
6. **Antes de cobrar se anota el intento en la venta (`clave_pago`),** con un `UPDATE` que se confirma en el momento. Sin esa marca, el barrido podría liberar el cupo de una venta que se está cobrando, y un cobro seguido de una falla no dejaría rastro. Se quita solo cuando se sabe que no se cobró; ante un 504 o una falla inesperada se deja.
7. **Las entradas nacen con el pago, en `PENDIENTE_EMISION`, y el MDB las emite (P4).** Si las creara el MDB, el mensaje no podría llevar `entradaId` y el `GET` de la entrada daría 404 apenas vuelve el pago. El MDB es idempotente por la base: un `UPDATE` por entrada que exige `PENDIENTE_EMISION`, en lugar de una tabla de mensajes procesados.
8. **`Idempotency-Key` con dos columnas, `clave_idempotencia` y `clave_pago`.** Repetir la clave devuelve la venta como está hoy, sin reservar ni cobrar de nuevo. Si dos pedidos con la misma clave llegan a la vez, los dos reservan, la restricción única `(comprador_id, clave_idempotencia)` deja guardar a uno solo y el otro devuelve su cupo y responde con la venta del primero. Una tabla clave → respuesta obligaba a guardar respuestas. Repetir la clave con otro pedido (otro evento, otro sector u otra cantidad) responde 409 `clave-reutilizada` en lugar de la venta vieja: devolver una compra de 1 entrada a quien pidió 3 le haría creer que reservó 3. Se comparan el evento y los renglones en orden, que es lo que está en la base; los titulares no, porque viven en la instancia (14/10).
11. **Una compra que perdió su instancia se cancela cuando el comprador intenta pagarla (14/10).** Sin la instancia no están los titulares y no se puede pagar; la respuesta es 409 `reserva-vencida`, que invita a empezar de nuevo. Antes la venta seguía `EN_CHECKOUT` y retenía el cupo hasta el barrido, así que la compra nueva podía encontrar el sector agotado por la propia compra perdida. Ahora se cancela con el mismo `UPDATE` condicional de `cancelar` y, si ese pedido cambió la fila, devuelve el cupo. Vale igual cuando la instancia desaparece en medio del pago (`NoSuchEJBException`), después de quitar la marca de ese intento. Si la venta tiene la marca de un pago sin resultado no se cancela: ese caso sigue siendo `operacion-en-curso`.
12. **Un evento que ya empezó no se vende (14/10).** La regla vive en Eventos: rechaza la reserva con 409 `evento-finalizado`. Eventos también responde 409 cuando no hay cupo, así que `ClienteDeEventos` ahora lee el `type` del cuerpo del 409 y responde al comprador "Ese evento ya empezó o terminó" con `evento-finalizado`; cualquier otro 409 sigue siendo `cupo-agotado`. Mirar solo el código hubiera dicho "no quedan entradas" de un evento que ya pasó.
9. **El cobro va detrás de la interfaz `Cobros`.** A1 la implementó con `CobrosFake` y B1 conectó el servicio de Pagos sin tocar el bean (`docs/notas/integracion.md`). Llamar al simulador desde el bean obligaba a reescribirlo después.
10. **Mientras se espera a otro servicio no se retiene una conexión de la base.** Los 8 servicios comparten un pool de 10 conexiones, y el servicio al que se llama necesita una para contestar. Por eso `iniciarCompra` reserva primero y guarda la venta al final, de una sola vez y sin transacción propia (`NOT_SUPPORTED`: esa escritura corre en la transacción del repositorio), y `confirmarCompra` cobra con el total que recuerda la instancia y recién después toca la base. El plan guardaba la cabecera antes de reservar: con 80 compras a la vez se abrieron 28, 52 dieron 504 y quedaron 20 lugares tomados sin venta. Con el orden nuevo las 80 responden 201 en menos de medio segundo, y 60 pagos a la vez responden 201 en poco más de medio segundo (`docs/entrega3/evidencia/compras-simultaneas.txt`). Agrandar el pool solo corría el límite.

## Qué pasa si falla cada paso

| Paso | Si falla |
|---|---|
| Reservar el cupo (REST a Eventos, que confirma en el acto) | 409 `cupo-agotado`, 422 `sector-inexistente` o 504. Ventas devuelve los sectores que ya habían respondido; la venta todavía no se guardó. Si falla el guardado, devuelve todos |
| Cobrar (es externo: un rollback no lo deshace) | 402 `pago-rechazado` o 504 `sin-respuesta`. La venta sigue `EN_CHECKOUT` y la instancia, viva: tras un 402 se paga con otra clave; tras un 504, con la misma |
| Confirmar, crear entradas y publicar (transacción JTA) | 500 y rollback: ni venta confirmada, ni entradas, ni mensaje. La venta queda con `clave_pago` |
| Emitir (MDB, en la transacción que recibe el mensaje) | El mensaje vuelve al broker: 5 entregas cada 2 segundos y después la DLQ. La venta queda `CONFIRMADA`, nunca a medias |

Los `type` de los errores: `datos-invalidos` (400), `no-autenticado` (401), `pago-rechazado` (402), `identidad-no-verificada` (403), `no-encontrado` (404), `cupo-agotado`, `evento-finalizado`, `clave-reutilizada`, `reserva-vencida`, `venta-ya-paga` y `operacion-en-curso` (409), `maximo-de-entradas`, `titulares-no-coinciden`, `dni-repetido` y `sector-inexistente` (422) y `sin-respuesta` (504). Los repositorios tienen dos más (`clave-repetida` y `entrada-repetida`) como última red ante una clave repetida en la base; nunca salieron por la API.

## Un 409 de Pagos no siempre quiere decir "no se cobró" (14/10)

Pagos responde 409 con dos `type` distintos y `ClienteDePagos` los trataba igual, como un rechazo sin pago:

- `clave-reutilizada`: la clave ya cobró otra venta u otro monto. No se cobró este pedido. Sigue saliendo como 402 `pago-rechazado` y se borra `clave_pago`.
- `pago-duplicado`: dos pedidos con la misma clave chocaron al guardarse y Pagos no pudo releer el que ganó. Pasa, por ejemplo, con el reintento que sigue a un 504 mientras el pedido original todavía está en curso. Ese otro pedido sí pudo haber cobrado: leerlo como rechazo borraba `clave_pago` y dejaba un cobro hecho sin venta.

Ahora, ante un 409 que no sea `clave-reutilizada`, Ventas repite el pedido una vez con la misma clave. Pagos no cobra de nuevo: devuelve el pago que guardó y la venta se confirma con ese `pagoId`. Si vuelve a chocar, sale como 504 `sin-respuesta` y la venta queda con `clave_pago`, igual que cuando no se sabe si se cobró. Un 409 sin un `type` legible se trata como `pago-duplicado`, porque ante la duda no se puede decir "no se cobró".

No cambia el contrato de Pagos: Ventas lee el `type` que Pagos ya manda en su error (Problem Details). Se eligió repetir el pedido, y no consultar el pago, porque Pagos no tiene una consulta por clave (`GET /pagos/{id}` pide el id, que Ventas no tiene) y la misma clave ya da esa respuesta. Lo cubren tres tests de `ClienteDePagosTest`: el duplicado seguido del pago guardado, el duplicado que se repite y el 409 sin `type`.

## La clave que recibe Pagos va atada a la venta (14/10)

En Ventas la clave de pago es única por comprador; en Pagos, en todo el servicio (`pago_clave_idempotencia_unica`). Cuando la clave viajaba tal cual, un comprador que mandaba la misma `Idempotency-Key` que ya había usado otro recibía 402 "El pago fue rechazado (clave_reutilizada). Podés probar con otro medio de pago.": lo mandaba a cambiar la tarjeta cuando el problema era la clave, y le confirmaba que esa clave existía en una venta ajena.

- `ClienteDePagos` le manda a Pagos `<ventaId>:<clave>` (por ejemplo `V-3f9a1c2b:5d3c1f0e-…`). Dos ventas distintas no pueden compartir clave en Pagos, y el mismo cobro repetido lleva siempre la misma: el reintento con la misma clave después de un 504 y la repetición ante un 409 `pago-duplicado` siguen recibiendo el pago que Pagos ya guardó. Entra en las 80 posiciones de Pagos (10 del id de la venta, 1 de los dos puntos y 36 del UUID). No hace falta migración: `clave_pago` en Ventas guarda la clave del comprador, como antes.
- Si igual llega un `clave_reutilizada` (la misma clave con otro monto), el 402 dice que la clave de ese intento no es válida y pide reintentar con una `Idempotency-Key` nueva. No menciona otra venta ni otro medio de pago. El motivo quedó como constante en `ResultadoDeCobro`, para que la compra lo reconozca sin depender de `ClienteDePagos`.

Se eligió derivar la clave en Ventas, y no cambiar la restricción de Pagos a (venta, clave), porque el arreglo queda en el servicio que tiene el problema y no toca la base ni el contrato de Pagos. Lo cubren `dosCompradoresConLaMismaClaveNoChocanEnPagos` y `elMismoCobroRepetidoLlegaAPagosConLaMismaClave` (`ClienteDePagosTest`, con un doble que aplica la regla de unicidad de Pagos) y `siPagosNoAceptaLaClaveSePideUnaClaveNuevaYNoOtroMedioDePago` (`ServicioDeVentasTest`).

## La emisión idempotente, probada contra la base (14/10)

Que el segundo `PAGO_CONFIRMADO` de una venta no emita de nuevo depende de los UPDATE condicionales de `EntradaRepositoryJpa.emitirPendientes` y `VentaRepositoryJpa.marcarEmitida`. `EmisionDeEntradasMDBTest` usa repositorios simulados, así que esa garantía no tenía ningún test contra una base. `EmisionEnH2Test` la prueba con Hibernate sobre H2 en modo PostgreSQL (las mismas opciones del datasource de desarrollo y el esquema de la migración V1): arma una venta confirmada con dos entradas, emite dos veces y comprueba que la segunda no cambia filas (0 entradas, `marcarEmitida` en falso), que los `codigo_qr` son los de la primera emisión y que la venta sigue `EMITIDA`. H2 e Hibernate entran solo con alcance `test` (las versiones de WildFly 41): no viajan en el WAR.

## Qué quedó pendiente o simulado y por qué

- **La pasarela es simulada y no se factura.** Desde B1 cobra el servicio de Pagos, con su pasarela simulada, que decide por el token: `tok_rechazado` rechaza, `tok_sin_respuesta` no contesta y cualquier otro aprueba. `CobrosFake` queda para trabajar sin Pagos (`palco.ventas.cobros=fake`); con él, una clave de pago usada en dos ventas paga las dos. `facturaEstado` vale `NO_APLICA` hasta C1 (SOAP, para el 13/11).
- **Una venta con `clave_pago` y sin resultado queda `EN_CHECKOUT` con su cupo** hasta la conciliación de C3 (se presenta el 13/11). La prueba de la excepción forzada dejó dos así. Detalle y procedimiento manual en "Límite: un cobro sin resultado sin instancia", más abajo.
- **Redesplegar Ventas corta las compras en curso** (el índice y los titulares viven en memoria): se pueden consultar y cancelar, pagar responde 409 `reserva-vencida` y el barrido las vence.
- **Si Eventos no responde al devolver el cupo** la devolución no se reintenta: queda un `WARNING` con venta, evento, sector y cantidad, y ese lugar queda sin vender hasta C3. Detalle en "Límite: la devolución de cupo no se reintenta", más abajo. Si no responde al reservar, no se sabe si la reserva se aplicó y no se devuelve: el `WARNING` dice evento, sector y cantidad.
- **Dos pedidos simultáneos con la misma clave reservan los dos** (decisión 8). Si en el sector queda lugar para uno solo, el que pierde recibe 409 `cupo-agotado`; al repetirlo recibe la venta.
- **Sin probar:** más de 80 compras o de 60 pagos a la vez; los pagos simultáneos con la versión anterior (frenan el servidor mientras duran); una caída entre el commit de la base y el del broker; Eventos o Usuarios caídos (solo con tests); el arranque en frío de WildFly; la migración contra PostgreSQL. La excepción forzada y el mensaje repetido se probaron con un WAR temporal que no quedó en el repo.
- **Falta el visto bueno del grupo** a lo que se aparta del plan: la columna `entrada.orden`, las decisiones 2 y 10, que un nombre con saltos de línea o caracteres de control responda 400, que cancelar responda `operacion-en-curso` si hay un pago en curso, que un sector repetido responda 400 y `@DependsOn("MigracionesVentas")` en el barrido.

### Límite: un cobro sin resultado sin instancia

**Qué pasa.** Después de un 504 `sin-respuesta` de Pagos (o de una falla inesperada en `confirmarCompra`), la venta queda `EN_CHECKOUT` con `clave_pago`. El reintento con la misma clave solo funciona mientras viva la instancia stateful (`ComprasEnCurso.pagar`: con `clave_pago` y sin instancia en el índice lanza `pagoSinResultado`). La instancia muere a los 10 minutos sin llamadas (`@StatefulTimeout(ReglasDeNegocio.MINUTOS_RESERVA)` en `ServicioDeVentas`), al redesplegar Ventas o cuando el contenedor la descarta por una falla. Desde ahí:

- pagar con la misma clave o con otra responde 409 `operacion-en-curso` ("puede que ya se haya cobrado") y deja un `WARNING` con la venta y la clave;
- cancelar responde 409 `operacion-en-curso` (`VentaRepositoryJpa.cancelar` exige `clave_pago is null`);
- el barrido la saltea (`buscarReservasVencidas` y `vencer` también exigen `clave_pago is null`).

**Por qué es así.** Ventas no sabe si Pagos cobró. Vencerla liberaría el cupo de una compra que pudo haberse cobrado y ese lugar se podría vender dos veces. Retenerla deja, en el peor caso, lugares sin vender. Pagos no tiene consulta por clave ni por venta: `GET /api/v1/pagos/{pagoId}` pide el id del pago, que Ventas no tiene, y `GET /api/v1/pagos?ventaId=…` responde 405. Repetir el `POST /api/v1/pagos` con la misma clave tampoco sirve como consulta: si el pago no existe, cobra.

**Cómo se resuelve hoy, a mano.** Las sentencias son SQL estándar y corren en H2 y en PostgreSQL. En desarrollo la base es H2 en memoria dentro de WildFly y no acepta conexiones de afuera: ahí no se pueden correr. Se probaron contra una H2 aparte, con las migraciones de Ventas y Pagos y una venta trabada de ejemplo.

```sql
-- 1. Las ventas trabadas (el WARNING del log trae el id y la clave)
select id, comprador_id, evento_id, clave_pago, vence_reserva, monto_total
from ventas.venta
where estado = 'EN_CHECKOUT' and clave_pago is not null
order by vence_reserva;

-- 2. El cupo que retiene cada una
select i.venta_id, v.evento_id, i.sector_id, i.cantidad
from ventas.venta_item i join ventas.venta v on v.id = i.venta_id
where i.venta_id = 'V-1a2b3c4d'
order by i.orden;

-- 3. Si Pagos guardó un pago de esa venta. La clave en Pagos es '<ventaId>:<clave_pago>'
select id, estado, motivo, monto, clave_idempotencia, creado
from pagos.pago
where venta_id = 'V-1a2b3c4d'
order by creado;
```

Si la consulta 3 devuelve un pago, se confirma su estado por la API de Pagos con el token de servicio:

```bash
H=http://localhost:8080; J='Content-Type: application/json'
TS=$(curl -s -X POST -H "$J" -d '{"secreto":"palco-desarrollo"}' $H/api/usuarios/servicios/token | sed -E 's/.*"token":"([^"]+)".*/\1/')
curl -s -H "Authorization: Bearer $TS" $H/api/v1/pagos/P-xxxxxxxx      # sin token: 401; id inexistente: 404
```

Según el resultado:

- **Ningún pago con la clave `<ventaId>:<clave_pago>`, o uno `RECHAZADO`:** no se cobró. Se vence la venta y, solo si el `UPDATE` cambió una fila, se devuelve el cupo de cada renglón de la consulta 2. Son escrituras: no se corrieron.

  ```sql
  update ventas.venta set estado = 'VENCIDA'
  where id = 'V-1a2b3c4d' and estado = 'EN_CHECKOUT'
    and clave_pago = 'c0ffee00-0000-4000-8000-000000000001';   -- tiene que decir 1 fila
  ```
  ```bash
  curl -s -X POST -H "$J" -H "Authorization: Bearer $TS" -d '{"cantidad": -2}' \
    $H/api/eventos/<eventoId>/sectores/<sectorId>/cupo                # uno por renglón; cantidad negativa libera
  ```

  `clave_pago` queda como rastro: con la venta `VENCIDA`, pagar responde 409 `reserva-vencida`.
- **Un pago `APROBADO`:** se cobró. La venta no se toca y el cupo sigue tomado. Confirmarla exige crear las entradas con sus titulares (que vivían en la instancia) y publicar `PAGO_CONFIRMADO` en la misma transacción: no se hace a mano. Se resuelve con el comprador o con la conciliación de C3.

**Qué se va a hacer.** La conciliación con Pagos, tarea C3 del plan (`docs/PLAN_AGENTES.md`), que se presenta en la Entrega Obligatoria N.º 2 (13/11): preguntarle a Pagos por esa clave y cerrar la venta, confirmándola o liberando su cupo.

### Límite: la devolución de cupo no se reintenta

**Qué pasa.** Al cancelar (`ComprasEnCurso.cancelar`) o vencer (`VencimientoDeReservas.vencerReservas`), primero se confirma el cambio de estado en la base y después `ComprasEnCurso.devolverCupo` llama a `ClienteDeEventos.liberarCupo` por cada renglón. `liberarCupo` hace un solo intento (solo lo repite ante un 401, con un token de servicio nuevo). Si Eventos no responde o responde otra cosa, no lanza: deja un `WARNING` con venta, evento, sector y cantidad y devuelve `false`, que nadie mira. La venta ya está `CANCELADA` o `VENCIDA` y nada en la base dice que falta devolver, así que el barrido, que solo mira ventas `EN_CHECKOUT`, no la vuelve a tomar. Esos lugares siguen contados como vendidos en Eventos.

**Por qué se aceptó para esta entrega.** Es el lado seguro de la regla de `ClienteDeEventos`: puede quedar un lugar sin vender, nunca uno vendido dos veces. El `WARNING` trae los datos para devolverlo a mano con el mismo `POST .../cupo` con cantidad negativa del punto anterior. Cerrarlo bien necesita una migración y un cambio en el barrido, que entran en C3 (punto 3 de su objetivo).

**Qué lo resolvería.** Una marca de devolución pendiente en la venta (por ejemplo, una columna `cupo_pendiente` o una tabla de renglones por devolver), que se pone cuando `liberarCupo` devuelve `false` y que el barrido revisa en cada corrida, reintentando hasta que Eventos acepte y quitando la marca con un `UPDATE` condicional. La devolución tiene que ser segura de repetir: si Eventos aplicó el cambio pero la respuesta se perdió, un segundo intento no puede restar dos veces.

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
mvn -q -B -pl servicios/ventas package       # 187 tests en verde
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
