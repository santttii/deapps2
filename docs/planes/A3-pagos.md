# Plan A3 · pagos — Pago con pasarela simulada detrás de una interfaz propia

- **Dueña:** Valentina Frisoli. **Rama:** `feat/pagos` según `PLAN_AGENTES.md`; se trabaja en el árbol actual y los commits los hace solo Santiago, uno por paso.
- **Toca:** `backend/servicios/pagos/` y la nota `docs/notas/pagos.md`. El prototipo en Figma es tarea de Valentina y no se planifica acá.
- **Depende de:** los pasos transversales T0 y T1 para compilar, y T2 (token de rol `servicio`) para los `curl`.
- **Dependen de este plan:** las tareas B1, C2 y C3 de las olas siguientes. A1 no, porque cobra con su `CobrosFake`: este plan no bloquea el 23/10.

Los nombres son los de `docs/planes/00-contratos-compartidos.md` ("contratos"), que manda si hay diferencias.

Las fechas son las de la comisión de los viernes (sección 1 de contratos). El viernes 23/10 es la fecha de trabajo de la Entrega Parcial N.º 2 y falta confirmarla con la cátedra; la consigna, escrita para la comisión del lunes, dice 19/10. El 13/11 es la entrega obligatoria con defensa. Lo que este plan deja "para el 13/11" es lo que no entraba en el checkpoint: en `docs/PLAN_AGENTES.md` pasó a la tarea D1, con el adaptador de Mercado Pago hasta el 08/11 y el webhook hasta el 19/11, y entra en la entrega del 13/11 solo si el 08/11 está cerrado.

## Qué se construye

Hoy el WAR (el archivo desplegable del servicio) de pagos tiene solo `PagosApplication`.

**Para el 23/10:** `POST /api/v1/pagos`, `GET /api/v1/pagos/{pagoId}`, la tabla `pagos.pago` y sus tests. Ventas pide un cobro y Pagos lo resuelve con `PagosFake`, una pasarela (el servicio externo que cobra) simulada. El pedido es idempotente: repetirlo no repite el cobro.

**Para el 13/11:** el webhook (el aviso HTTP que la pasarela le manda a nuestra API), el reembolso y `MercadoPagoPagos`, diseñados en las decisiones 5 y 6.

Respecto de la guía de la tarea (`docs/PLAN_AGENTES.md`) cambian tres cosas: qué interfaz tiene las dos implementaciones (decisión 1), el alcance (decisión 5) y la ruta, de `/api/pagos` a `/api/v1/pagos` (propuesta P7 de contratos).

## Decisiones para aprobar

Se implementan con la recomendada y quedan pendientes de aprobación de Valentina.

**1. Interfaz y patrón (propuesta P9).**
- (a) `PagosFake` y `MercadoPagoPagos` implementan `IPagos`, como dice la guía de la tarea: cada una tendría que guardar el pago y deduplicar.
- (b) `ServicioDePagos` implementa `IPagos`, y las dos implementaciones elegibles implementan `PasarelaDePagos`.
- **Recomendada: (b).** Guardar y deduplicar se escribe una sola vez, y coincide con la Entrega Parcial 1.
- **Patrón.** `MercadoPagoPagos` va a ser un Adapter: traduce la API de Mercado Pago a `PasarelaDePagos`. `PagosFake` es una implementación simulada y no adapta nada. `FabricaDePasarela` es la Factory que elige cuál se crea. Que sean intercambiables es la forma de un Strategy, pero lo que se resuelve es una interfaz ajena incompatible: por eso se cuenta como Adapter. El 23/10 hay interfaz y Factory; el Adapter figura "diseñado, pendiente".
- **Defensa:** "el servicio decide y guarda; la pasarela solo habla con el exterior".

**2. Cómo se elige la implementación.**
- (a) Un productor de CDI (la inyección de dependencias de Jakarta EE): `FabricaDePasarela`, `@ApplicationScoped`, con un método `@Produces @ApplicationScoped PasarelaDePagos` que lee la configuración y crea la clase con `new`.
- (b) `@Alternative`: se elige al empaquetar, no con una variable. (c) Un qualifier (anotación que distingue implementaciones) por clase e `Instance.select`: dos conceptos más para explicar. (d) Un `if` en `ServicioDePagos`: mezcla configuración con negocio.
- **Recomendada: (a).** El repo no tiene `beans.xml`: una clase sin anotación no es un bean y `@Inject PasarelaDePagos` tiene un solo candidato.
- **Configuración.** `palco.pagos.proveedor` (variable `PALCO_PAGOS_PROVEEDOR`), leída con `ConfigProvider.getConfig().getOptionalValue(…)` y con `fake` por defecto; con `@ConfigProperty`, una propiedad sin valor impediría el despliegue. MicroProfile Config está disponible: `backend/servidor/pom.xml` no pide la capa por nombre, pero el `standalone.xml` armado tiene el subsistema `microprofile-config-smallrye` y `common` ya trae un `microprofile-config.properties`.
- **Falla temprana.** Un valor que la fábrica no conoce (hoy, cualquiera distinto de `fake`) impide el despliegue con un mensaje claro, en vez de caer en silencio al simulador. Para eso `MigracionesPagos` llama a `pasarela.nombre()` al arrancar: el productor corre recién en el primer uso.
- **Defensa:** "una variable de entorno decide qué clase se crea; el negocio solo conoce la interfaz".

**3. Frontera con Ventas.**
- (a) El pago público sigue en Ventas (`POST /api/v1/ventas/{ventaId}/pagos`, como en la OpenAPI). Pagos expone una API interna con rol `servicio` y responde un rechazo con 201 y `estado` `RECHAZADO`. Lo único público va a ser el webhook.
- (b) Pagos responde 402: el intento no queda consultable. (c) El endpoint público vive en Pagos: contradice la OpenAPI.
- **Recomendada: (a).** **Defensa:** "para Pagos un rechazo es un resultado, no un error; el 402 se lo da Ventas al comprador".

**4. Idempotencia y transacciones.**
- (a) `clave_idempotencia` única y pasarela fuera de toda transacción de base: `ServicioDePagos` lleva `@TransactionAttribute(NOT_SUPPORTED)` y cada método de `PagoRepositoryJpa` corre su transacción corta. Se busca por clave, se cobra y se guarda; si guardar choca porque llegaron dos pedidos a la vez, se relee y se devuelve ese pago.
- (b) Una sola transacción: retiene una de las 10 conexiones de los 8 WAR mientras espera a un tercero, y tras una clave duplicada la transacción queda marcada para deshacerse y ya no se puede releer. (c) Verificar solo en Java: dos pedidos simultáneos pasan los dos.
- **Recomendada: (a).** **Defensa:** "no dejamos una transacción abierta mientras esperamos a un tercero, y el duplicado lo impide la base, no un `if`".

**5. Alcance antes del 23/10 (propuesta P10).**
- (a) Solo el pago con `PagosFake`. (b) Todo lo que lista la guía de la tarea. (c) Sumar un `MercadoPagoPagos` escrito de memoria.
- **Recomendada: (a).** El 23/10 se evalúa el proceso asincrónico, que no pasa por Pagos. Hoy la referencia oficial de `POST /v1/payments` devuelve 404 y la página de webhooks no trae la plantilla de la firma: un test contra ejemplos propios no prueba nada. Tampoco queda un esqueleto vacío.
- **`MercadoPagoPagos`, cuando se escriba.** Cliente JAX-RS (la API REST de Jakarta EE) con 2 segundos de conexión y 5 de lectura, creado fuera del constructor y cerrado al destruirse; todo código que no sea 2xx pasa a `SinRespuesta`; la traducción de estados es una función pura, probada con una respuesta copiada del sandbox (el entorno de pruebas de Mercado Pago). La credencial llega solo por `PALCO_PAGOS_MERCADOPAGO_TOKEN`.
- **Defensa:** "mostramos lo que funciona y decimos qué es simulado".

**6. Webhook (13/11).**
- **Validación.** (a) Firma HMAC (un código calculado con una clave secreta compartida) en la cabecera `x-signature`. (b) Un secreto en la URL: queda en los logs. (c) JWT, el token firmado de nuestros servicios: la pasarela no lo tiene. **Recomendada: (a)**, como figura en la clase 10. El secreto llega por `PALCO_PAGOS_WEBHOOK_SECRETO`; sin él se responde 401 a todo. Se valida antes de leer el cuerpo, y el id del pago se toma del parámetro `data.id` de la URL.
- **Idempotencia.** El id del pago en la pasarela se guarda en `id_externo`, única desde la V1. La notificación solo cambia un pago `PENDIENTE` (`update … where idExterno = :id and estado = 'PENDIENTE'`, la técnica del cupo en Eventos): la repetida no modifica ninguna fila. Una tabla de notificaciones procesadas descartaría el aviso legítimo de un cambio de estado.
- **Estados.** Se consultan con `PasarelaDePagos.consultar(idExterno)`, no se leen del cuerpo: `APROBADO`, `RECHAZADO` o `VENCIDO`; si sigue pendiente, no cambia nada.
- **Respuesta.** 200 vacío después de guardar (también si es repetida o desconocida), 400 sin `data.id`, 401 por firma y 504 si no se pudo consultar, para que la pasarela reintente. Responder antes de procesar pierde el aviso si el servidor se cae.
- **Defensa:** "se valida por firma porque la pasarela no tiene nuestro token; si llegan dos avisos, la base deja pasar uno solo".

## Archivos

Bajo `backend/servicios/pagos/`; las rutas de código son relativas al paquete `com.palco.pagos`.

| Archivo | Propósito |
|---|---|
| `pom.xml` (modificar) | Declara `microprofile-config-api`, sin versión |
| `WEB-INF/jboss-web.xml` (modificar) | Context root (la ruta de la que cuelga el WAR): `/api/v1/pagos` |
| `PagosApplication.java` (modificar) | Solo el comentario con la ruta |
| `api/Dtos.java` | `NuevoPagoDto` y `PagoDto` (lo que entra y sale de la API), con los largos que limita la tabla |
| `api/PagosResource.java` | `POST` y `GET {pagoId}`, cada uno con `@RolesAllowed("servicio")` |
| `negocio/IPagos.java` | `procesarPago(NuevoPagoDto pago, String claveIdempotencia)` y `obtener(String pagoId)` |
| `negocio/ServicioDePagos.java` | `@Stateless` (no recuerda nada entre llamadas: todo está en la base). Idempotencia y guardado |
| `negocio/MigracionesPagos.java` | Migra el esquema `pagos` y escribe en el log la pasarela activa |
| `dominio/Pago.java`, `dominio/EstadoPago.java` | Entidad y sus cinco estados |
| `datos/PagoRepository.java`, `datos/PagoRepositoryJpa.java` | `buscarPorId`, `buscarPorClave` y `guardar`, que lanza `Conflicto` solo si `BaseDeDatos.esClaveDuplicada(e)` |
| `pasarela/PasarelaDePagos.java`, `pasarela/ResultadoPasarela.java` | `cobrar(ventaId, monto, medioDePago, token, claveIdempotencia)` y `nombre()` |
| `pasarela/PagosFake.java`, `pasarela/FabricaDePasarela.java` | El simulador y la Factory |
| `META-INF/persistence.xml`, `db/migracion/V1__crear_tablas.sql` | Unidad de persistencia `pagos` y migración de Flyway (la herramienta que versiona el esquema) con la tabla `pago` |
| Tests: `PagosFakeTest`, `ServicioDePagosTest`, `PagoRepositoryEnMemoria` | Ver pasos 3 y 4 |
| `docs/notas/pagos.md` | Nota de una página para la defensa |

Para el 13/11 se suman `api/WebhookResource`, `seguridad/FirmaDeWebhook`, `pasarela/MercadoPagoPagos` y sus tests.

## Contratos con las otras tareas

**Expone**, con rol `servicio` (sección 5.4 de contratos). Es una API interna y queda fuera de `openapi.yaml` a propósito:

- `POST /api/v1/pagos`, con cabecera `Idempotency-Key` y cuerpo `{ventaId, monto, medioDePago, token}`. Responde 201 con `Location: /api/v1/pagos/{pagoId}` y `{pagoId, ventaId, monto, estado, motivo, idExterno}`; `estado` es `APROBADO` o `RECHAZADO`.
- La misma clave con la misma venta y monto devuelve 201 con el mismo pago, sin llamar otra vez a la pasarela.
- Errores: 400 datos inválidos o falta la clave; 401; 403 otro rol; 409 clave usada con otra venta u otro monto; 504 la pasarela no respondió y no se guardó nada.
- `GET /api/v1/pagos/{pagoId}`: 200 o 404.
- `PagosFake`: `tok_rechazado` da `RECHAZADO` con motivo `fondos_insuficientes`; `tok_sin_respuesta` lanza `SinRespuesta` (504); cualquier otro, como `tok_test`, da `APROBADO`. El id externo es `fake-` más la clave: el mismo pedido da siempre el mismo resultado.
- Tabla `pago`: `id` (`P-` más 8 caracteres de UUID, asignado en código), `venta_id`, `monto`, `medio_de_pago`, `proveedor`, `estado`, `motivo`, `clave_idempotencia`, `id_externo`, `creado` y `actualizado` (UTC, desde un `Clock`). Únicos: `clave_idempotencia` e `id_externo`. La columna `estado` no lleva `check` de lista (contratos, paso T6: en este servidor se rompen); sus cinco valores los valida el enumerado de Java. El token de la tarjeta no se guarda.
- Pagos garantiza un pago por clave; un solo pago por venta lo garantiza Ventas. Un pago no se deshace con un rollback de Ventas: se reintenta con la misma clave o, más adelante, se reembolsa.

**Consume:** la versión que fija T0; de T1, `SinRespuesta`, `Conflicto(tipo, mensaje)` y `esClaveDuplicada` (Pagos no crea excepción ni manejador de errores propio); de T2, `POST /api/usuarios/servicios/token`.

**Pide a Santiago (T4 y T5):** `PALCO_PAGOS_PROVEEDOR` en `backend/.env.example`, las dos rutas en el README y, si el grupo quiere ver Pagos en Postman, dos requests a `POST /v1/pagos` con `tokenServicio`; hoy ninguno llega a este WAR.

## Pasos

Para el 23/10:

1. `pom.xml`, `jboss-web.xml` y el comentario. Redesplegar: `/api/v1/pagos/health` da 200.
2. Migración (el SQL se cierra antes de desplegar), `persistence.xml`, `MigracionesPagos`, `Pago` y `EstadoPago`. Redesplegar: health 200 y ningún archivo `.failed`.
3. `PasarelaDePagos`, `ResultadoPasarela`, `PagosFake` y `PagosFakeTest`: aprueba, rechaza, no responde y repite el resultado.
4. `PagoRepository`, su implementación, `IPagos`, `ServicioDePagos` y `ServicioDePagosTest`: la misma clave devuelve el mismo pago con una sola llamada a la pasarela; con otro monto da `Conflicto`; un choque al guardar devuelve el pago que ganó; `tok_sin_respuesta` no guarda nada. El doble de prueba `PagoRepositoryEnMemoria` rechaza la clave repetida como la base: por eso se escribe a mano y no con Mockito.
5. `FabricaDePasarela`, `Dtos` y `PagosResource`. Redesplegar: el log nombra la pasarela activa; sin token da 401 y con el de `titular@palco.test`, 403.
6. Con T2, los `curl` de la sección siguiente.
7. `docs/notas/pagos.md`, con las palabras de Valentina: decisiones 1 a 5 y cinco preguntas probables.

Después del 23/10:

8. `FirmaDeWebhook`, `confirmarPago` y `WebhookResource`. Tests: firma válida, alterada y sin secreto; dos `confirmarPago` seguidos cambian el estado una sola vez.
9. Reembolso: `UPDATE` condicional de `APROBADO` a `REEMBOLSADO` y relectura en otra llamada al repositorio, porque ese `UPDATE` no actualiza una entidad ya cargada.
10. `MercadoPagoPagos`, con credenciales de prueba.

## Cómo se verifica

En Git Bash, con un Maven por vez y sin `mvn clean` mientras WildFly esté levantado:

```bash
export JAVA_HOME=/c/Users/santi/.jdks/temurin-25
cd "/c/Users/santi/OneDrive/Documentos/Facultad/Desarrollo de Aplicaciones II/TP Integrador/backend"
mvn -q -B test -pl servicios/pagos -am     # los 5 tests de common y los nuevos de pagos
./redesplegar.sh pagos
sleep 8    # el despliegue anterior sigue respondiendo hasta que WildFly toma el archivo nuevo
for i in $(seq 30); do curl -sf localhost:8080/api/v1/pagos/health && break; sleep 2; done
# {"servicio":"pagos","baseDeDatos":"ok","estado":"ok"}
ls servidor/target/server/standalone/deployments | grep failed    # no tiene que listar nada
```

Al cerrar la tarea, `mvn -q -B test` sobre todo el backend: los 11 tests actuales más los nuevos. Sin T2 se comprueba hasta acá:

```bash
P=localhost:8080/api/v1/pagos; J='Content-Type: application/json'
codigo() { curl -s -o /dev/null -w '%{http_code}\n' "$@"; }
codigo -X POST $P -H "$J" -d '{}'                                    # 401
TU=$(curl -s -X POST localhost:8080/api/usuarios/login -H "$J" \
  -d '{"email":"titular@palco.test","password":"palco1234"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
codigo -X POST $P -H "$J" -H "Authorization: Bearer $TU" -d '{}'     # 403
```

Con T2, el pago aprobado y el rechazado. `ventaId` puede ser cualquier texto: Pagos no lee tablas de Ventas.

```bash
TS=$(curl -s -X POST localhost:8080/api/usuarios/servicios/token -H "$J" \
  -d '{"secreto":"palco-desarrollo"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')   # vence a los 5 minutos
clave() { node -e "console.log(crypto.randomUUID())"; }
pagar() { curl -s -i -X POST $P -H "Authorization: Bearer $TS" -H "Idempotency-Key: $3" -H "$J" \
  -d "{\"ventaId\":\"$1\",\"monto\":60500,\"medioDePago\":\"tarjeta\",\"token\":\"$2\"}"; }
K=$(clave)
pagar V-prueba01 tok_test "$K"                  # 201, Location y "estado":"APROBADO"
pagar V-prueba01 tok_test "$K"                  # 201 con el mismo pagoId
pagar V-prueba02 tok_test "$K"                  # 409: la clave ya se usó con otra venta
pagar V-prueba03 tok_rechazado "$(clave)"       # 201, "estado":"RECHAZADO", "motivo":"fondos_insuficientes"
pagar V-prueba04 tok_sin_respuesta "$(clave)"   # 504, application/problem+json
K2=$(clave); pagar V-prueba05 tok_test "$K2" & pagar V-prueba05 tok_test "$K2" & wait   # dos 201, un solo pagoId
ID=P-xxxxxxxx                                   # el pagoId que devolvió el primer 201
curl -s -H "Authorization: Bearer $TS" $P/$ID   # 200 con ese pago
codigo -H "Authorization: Bearer $TS" $P/P-noexiste   # 404
```

Cuando Ventas ya esté en `/api/v1`, `/api/v1/pagos/health` tiene que seguir diciendo `pagos`.

## Riesgos y dudas abiertas

- **Sin T2 no hay `curl` de aprobado y rechazado**, la aceptación que pide la guía de la tarea. Hasta entonces la tarea se cierra con los pasos 1 a 5 y esos `curl` se informan como no corridos. No se abre el pago al rol `user` para esquivarlo.
- **Sin ejecutar en este servidor:** `ConfigProvider` desde código propio (si falla, `System.getenv("PALCO_PAGOS_PROVEEDOR")`); el productor de CDI, porque el repo todavía no tiene ninguno; `/api/v1/pagos` conviviendo con Ventas en `/api/v1` (si falla, Pagos vuelve a `/api/pagos`); la V1 sobre H2; `redesplegar.sh` en esta máquina. Los dos `curl` en paralelo pueden no llegar a chocar: ese caso lo prueba el test.
- **Maven necesita conexión la primera vez:** en `~/.m2` está el jar de `microprofile-config-api` 3.1.1, pero no su `.pom`.
- **La V1 no se edita después del primer despliegue:** según contratos trabaría el redespliegue hasta reiniciar WildFly, que vacía la base de todos. Los cambios van en una V2.
- **Límites aceptados.** Dos pedidos simultáneos con la misma clave llaman dos veces a la pasarela: con el simulador da igual; con Mercado Pago depende de que deduplique por `X-Idempotency-Key`, sin verificar. Si la pasarela cobra y falla el guardado, el pago queda sin registro hasta que Ventas reintente con la misma clave.
- **Mercado Pago real necesita gestiones fuera del repo:** cuenta de desarrollador, aplicación y usuarios de prueba, access token de prueba, clave secreta de webhooks y una URL pública para recibir el webhook. La API pide además `payment_method_id`, `installments` y el email del pagador, que `NuevoPago` no tiene: `medioDePago` sigue siendo `tarjeta` y los campos nuevos se discuten en B2, que genera el token de tarjeta.
- **Webhook, sin verificar:** la plantilla del texto firmado no está en la página oficial; se confirma contra el sandbox antes de escribir `FirmaDeWebhook`. Activar el secreto exige reiniciar WildFly, lo que vacía la base y cambia la clave de los tokens.
- **Falta en contratos**, antes del paso 8: cómo se entera Ventas de que un pago pendiente se resolvió (C3); los tokens del simulador que devuelven `PENDIENTE`; el catálogo de la clase 10, que dice "200 vacío (acuse inmediato)" mientras la decisión 6 responde después de guardar; y el `type` del 409, para el que se propone `clave-reutilizada`.
- **Consulta para la cátedra:** si el 13/11 una pasarela simulada y declarada como tal alcanza como integración REST con un sistema externo.

Fuente consultada hoy: [Webhooks de Mercado Pago](https://www.mercadopago.com.ar/developers/es/docs/your-integrations/notifications/webhooks).
