# Plan A1 · ventas-core — compra con estado, pago y emisión de entradas

- **Dueño:** Sebastian Deya. **Rama:** `feat/ventas-core` es el nombre previsto; hoy se trabaja en el árbol actual, y la rama y los commits los maneja Santiago, uno por paso de este plan.
- **Toca:** `backend/servicios/ventas/` y la nota `docs/notas/ventas-core.md`.
- **Depende de:** los pasos previos T0 (Mockito en el pom padre), T1 (errores comunes) y T2 (token de rol `servicio`), y de A2 (publicador de mensajes). Están en `docs/planes/00-contratos-compartidos.md`, que acá se cita como "contratos §n" y que manda si este plan dice otra cosa.
- **Dependen de este plan:** A4 (evidencia del 23/10), B1 (reemplaza el cobro simulado), B2, B3, C1, C2 y C3.
- **Fechas:** son las de la comisión de los viernes (contratos §1). El viernes 23/10 es la fecha de trabajo de la Entrega Parcial N.º 2 y falta confirmarla con la cátedra; la consigna, escrita para la comisión del lunes, dice 19/10. El 13/11 es la entrega obligatoria con defensa.

## Qué se construye

**Para el 23/10**
- La compra en dos llamadas, como la OpenAPI (`docs/api/openapi.yaml`, el contrato REST del grupo): `POST /api/v1/ventas` reserva el cupo 10 minutos y deja la venta `EN_CHECKOUT`; `POST /api/v1/ventas/{ventaId}/pagos` cobra con un simulador local, la deja `CONFIRMADA`, crea las entradas y publica el mensaje `PAGO_CONFIRMADO`.
- `GET` y `DELETE /api/v1/ventas/{ventaId}` y `GET /api/v1/entradas/{entradaId}`.
- `ServicioDeVentas` como `@Stateful` (componente de negocio que el servidor mantiene vivo durante una conversación), una instancia por compra, con su ciclo de vida a la vista en el log.
- `VencimientoDeReservas`: un proceso por minuto que vence las reservas y devuelve el cupo.
- `EmisionDeEntradasMDB`: el MDB (Message-Driven Bean, componente que consume mensajes) de la cola `cola.emision-entradas`. Pasa las entradas a `VALIDA` y la venta a `EMITIDA`.
- Esquema `ventas` con Flyway (la herramienta de migraciones), `Idempotency-Key` (la clave que manda el cliente para que repetir un pedido no repita su efecto) con efecto real y tests con Mockito (librería de dobles de prueba).

**Queda para el 13/11:** cobro contra el servicio de Pagos (B1) y Mercado Pago; factura SOAP (C1; hasta entonces `facturaEstado` vale `NO_APLICA`); pago pendiente con 202; reembolso, conciliación de cobros sin resultado y reenvío de mensajes (C3); reventa (C2); ETag (etiqueta de versión de una respuesta) y actualización del YAML (C4).

## Decisiones para aprobar

Se implementan con la opción recomendada y quedan pendientes de aprobación de Sebastian. Entre paréntesis va el número de propuesta de contratos §11.

### 1. Cómo se ata la instancia stateful al usuario (P1)

La identidad viaja en un JWT (token firmado) y hay 8 WAR (archivos desplegables) separados.

| Alternativa | Costo |
|---|---|
| (a) Bean `@SessionScoped` con sesión HTTP | Una cookie como segunda identidad junto al token. Vale solo dentro del WAR de Ventas y obliga al frontend, que corre en otro origen, a mandar credenciales |
| (b) Índice de instancias por `ventaId`, validado contra el `sub` del token (el id del usuario) | El estado vive en la memoria de un servidor: redesplegar Ventas corta las compras en curso |
| (c) Sin stateful, todo el checkout en la base | Es lo más robusto, pero el sistema queda sin el componente stateful que exige la consigna |

**Recomendada: (b).**
- `ComprasEnCurso` (`@ApplicationScoped`, sin transacción) es la fachada: los recursos REST hablan solo con ella. Crea cada instancia buscándola por su nombre JNDI (el nombre con que el servidor publica un componente), `java:module/ServicioDeVentas!com.palco.ventas.negocio.IVentas`, la guarda en un mapa por `ventaId` y antes de usarla comprueba en la base que el `sub` sea el comprador; si no lo es, responde 404. El recurso no inyecta `IVentas` porque se crea de nuevo en cada pedido.
- La instancia guarda lo que todavía no está en la base: los titulares, además del `ventaId`. La venta, sus ítems, el vencimiento y las claves se guardan en la primera llamada.
- `IVentas`: `VentaDto iniciarCompra(String compradorId, NuevaVentaDto nueva, String claveIdempotencia)`, `VentaDto confirmarCompra(NuevoPagoDto pago, String claveIdempotencia)` y `boolean cancelarCompra()`, que devuelve si la venta pasó a `CANCELADA`. `agregarEntrada`, de la Entrega Parcial 1, se funde en `iniciarCompra`.
- Ciclo de vida: `@PostConstruct` y `@PreDestroy` escriben en el log, y el segundo además quita la referencia del mapa, dentro de un `try/catch`; `@StatefulTimeout(value = MINUTOS_RESERVA, unit = MINUTES)`; `@Remove(retainIfException = true)` en `confirmarCompra` y `cancelarCompra`; `@AccessTimeout(0)`, para que una llamada simultánea falle al instante.
- El bean lanza solo excepciones de `ErroresDeNegocio`: con ellas la instancia sobrevive y un pago rechazado se reintenta; con cualquier otra el servidor la descarta.
- Referencias que no sirven: ante `NoSuchEJBException`, `ComprasEnCurso` quita la entrada del mapa y responde 409 `reserva-vencida`, igual que cuando no hay instancia; ante `ConcurrentAccessException`, 409 `operacion-en-curso`; si `iniciarCompra` falla, llama a `cancelarCompra()` para que el servidor libere esa instancia.

**Cómo se defiende:** "La reserva de 10 minutos es el caso que la consigna da como ejemplo de stateful. La instancia es el único lugar donde viven los titulares hasta que se paga, y el servidor la crea y la destruye. Nosotros solo la ubicamos por el id de la venta y verificamos al dueño con el token; la Venta está en la base y se accede por URL."

### 2. Quién vence la reserva (P2)

- (a) El `@PreDestroy` marca la venta y llama a Eventos. Costo: la especificación (Jakarta Enterprise Beans 4.0, "Missed PreDestroy Calls") avisa que puede no ejecutarse y recomienda una limpieza periódica. Además el timeout mide inactividad y `venceReserva` es una hora fija: quedan dos relojes.
- (b) Un barrido: `VencimientoDeReservas`, un `@Singleton` (una sola instancia en el servidor) con el temporizador `@Schedule(hour = "*", minute = "*", persistent = false)`. Costo: un componente más, y la reserva puede durar hasta un minuto de más.

**Recomendada: (b).**
- Regla: solo libera cupo quien cambió una fila con su propio `UPDATE` condicional, y recién después de que ese cambio quedó confirmado. Las transiciones son las de contratos §7.3; cancelar y vencer exigen además que la venta no tenga `clave_pago`.
- El barrido corre sin transacción propia (`NOT_SUPPORTED`): cierra cada venta en la transacción del repositorio y después llama a Eventos. Si Eventos no responde, deja un `WARNING` con venta, sector y cantidad.
- Cancelar: `cancelarCompra()` hace el `UPDATE` a `CANCELADA` y, si cambió una fila, `ComprasEnCurso` libera el cupo cuando vuelve. Si la instancia se perdió, `ComprasEnCurso` hace el mismo `UPDATE` por el repositorio. Una venta ya cancelada o vencida responde 204; una paga, 409 `venta-ya-paga`.
- El "ahora" sale de un campo `Clock reloj = Clock.systemUTC()` y viaja como parámetro (contratos §7.1).

**Cómo se defiende:** "La reserva vence por una hora guardada en la base y un proceso por minuto la libera. El servidor descarta por su cuenta la conversación abandonada."

### 3. Venta y mensaje en la misma transacción (P3)

En el servidor armado, `PalcoDS` es un `<datasource>` común y la fábrica `java:/JmsXA` es XA (el protocolo de commit en dos fases).

- (a) Dejarlo así y describir la garantía real. Costo: la ventana de la caída, abajo.
- (b) Convertir la base en XA. Costo: cambia `configurar.cli`, obliga a rearmar el servidor y no está verificado contra Supabase.
- (c) Tabla de salida y un proceso que publica. Costo: más código y otro proceso que defender.

**Recomendada: (a).** `confirmarCompra` lleva escrito `@TransactionAttribute(REQUIRED)` y el publicador `MANDATORY`: es una transacción JTA (la API de transacciones) declarativa. La garantía que sigue sale de leer la configuración y el código del servidor; qué se prueba y qué no está en Riesgos.
- El broker (servidor de mensajes) participa como recurso XA y la base como último recurso. No es un commit en dos fases.
- Con una excepción, o si falla el commit de la base, no queda ni venta confirmada ni mensaje.
- La base confirma antes que el broker: el MDB no recibe el mensaje de una venta sin confirmar.
- Si el servidor se cae entre los dos commits, queda una venta `CONFIRMADA` con entradas `PENDIENTE_EMISION` y sin mensaje. Se ve en la base; el reenvío es de C3.
- La transacción cubre solo el tercer paso del flujo: reservar y cobrar quedan afuera (tabla de contratos §4.7, que va a la nota).

Cómo paga `ComprasEnCurso`:
1. Busca la venta. Ajena o inexistente: 404. Ya paga: con la misma clave devuelve la venta; con otra, 409 `venta-ya-paga`. Cancelada, con la reserva vencida o sin instancia: 409 `reserva-vencida` y nada más.
2. Guarda `clave_pago` en su propia transacción, con un `UPDATE` que exige `EN_CHECKOUT`, reserva vigente y `clave_pago` vacía o igual. Si no cambió ninguna fila, relee y responde 409 `reserva-vencida` u `operacion-en-curso`.
3. Llama a `confirmarCompra`, que valida estado y vencimiento (solo lee), cobra con `Cobros`, pasa la venta a `CONFIRMADA`, crea las entradas y llama a `publicar` una sola vez.
4. Si el cobro fue rechazado, borra `clave_pago` y responde 402. Ante cualquier otro error la deja: con un 504 el comprador reintenta con la misma clave.

`clave_pago` existe porque, según el código del servidor, `@Remove` saca la instancia antes del commit: si el commit falla después de cobrar, sin esa marca no quedaría rastro del intento. Por eso una venta `EN_CHECKOUT` con esa marca queda para la conciliación de C3.

**Cómo se defiende:** "Es una transacción declarativa con el broker como recurso XA y la base como último recurso. Reservar se compensa a mano y cobrar se protege con la clave de idempotencia. Sabemos qué ventana queda, y por eso el consumidor es idempotente."

### 4. Entradas y MDB de emisión (P4)

- (a) El pago crea las entradas en `PENDIENTE_EMISION` y el MDB las emite. Costo: el pago escribe una tabla más.
- (b) Las crea el MDB, como dice el brief. Costo: el mensaje no podría llevar `entradaId` y el `GET` de la entrada daría 404 apenas vuelve el pago.
- (c) Tabla de mensajes ya procesados. Costo: otra tabla para el mismo efecto.

**Recomendada: (a).**
- El MDB es delgado: convierte el mensaje y llama a `EntradaRepository.emitirPendientes(ventaId, ahora)` y a `VentaRepository.marcarEmitida(ventaId)`. Activación y casos de error: contratos §4.4 y §4.5.
- La idempotencia está en la base: un `UPDATE` por entrada, `where id = ? and estado = 'PENDIENTE_EMISION'`, cada uno con su `codigo_qr` (un UUID; la columna es única). Si el broker reentrega, no cambia ninguna fila y el mensaje se confirma.
- El consumidor único lo da la cola: cada mensaje va a un solo consumidor.

**Cómo se defiende:** "Si el mensaje llega dos veces, el segundo `UPDATE` no encuentra nada pendiente. El consumidor está en Ventas porque Ventas es dueño de las entradas; la cola separa en el tiempo, con reintentos y DLQ (la cola de mensajes fallidos), y el cruce entre componentes lo muestra el tópico."

### 5. Modelo, ids y fechas (P5)

Ids: (a) prefijo más 8 caracteres de un UUID, asignado en código; (b) número de la base mostrado como `V-00001`, que suma un mapeo y vuelve a 1 en cada reinicio; (c) numeración que calce con los ejemplos, que coincide una sola vez. **Recomendada: (a)**, con `V-` y `T-`, como ya hacen Eventos y Usuarios. Los `V-00231` y `T-000981` de la OpenAPI eran ejemplos.

| Tabla | Columnas | Únicos |
|---|---|---|
| `venta` | `id`, `comprador_id`, `evento_id`, `estado`, `factura_estado`, `subtotal`, `cargo_servicio`, `monto_total`, `vence_reserva`, `clave_idempotencia`, `clave_pago`, `pago_id`, `creada`, `confirmada` | `(comprador_id, clave_idempotencia)` |
| `venta_item` | `id`, `venta_id`, `sector_id`, `sector`, `precio_unitario`, `cantidad`, `orden` | `(venta_id, sector_id)` |
| `entrada` | `id`, `venta_id`, `sector_id`, `sector`, `titular_nombre`, `titular_dni`, `precio_pagado`, `estado`, `codigo_qr`, `emitida` | `(venta_id, titular_dni)`; `codigo_qr` |

- Tipos, fechas y estados siguen contratos §7.1 y §7.3: ids `varchar(40)`, montos `bigint` (`long` en Java), fechas `timestamp` en UTC publicadas con `Z`, y los estados sin `check` de lista (contratos, paso T6: en este servidor se rompen; los valida el enumerado de Java). Solo SQL estándar, para que corra en H2 y en PostgreSQL.
- Los titulares se asignan a los sectores en el orden de `items`. El token de la tarjeta no se guarda.

**Cómo se defiende:** "Usamos la convención de ids que ya funcionaba en el repo. Lo que no puede fallar (una venta por clave, un DNI por venta, un código por entrada) lo garantiza la base con restricciones únicas."

### 6. Qué se adelanta de la Ola 3 (P7 y P8)

- **`Idempotency-Key`.** Obligatoria y con formato UUID; si no, 400. Dos columnas: `clave_idempotencia` y `clave_pago`. Repetir la clave devuelve el estado actual de la venta, sin reservar ni cobrar otra vez. Se descartan una tabla clave → respuesta (hay que guardar respuestas) y aceptar la cabecera sin usarla (obliga a reabrir este código).
- **Problem Details** (formato estándar de errores, RFC 9457). Lo hace T1 en `common`; Ventas solo lanza excepciones de `ErroresDeNegocio` con su `tipo`.
- **`/api/v1`.** El context root (la ruta de la que cuelga el WAR) pasa a `/api/v1`, con los recursos `ventas` y `entradas`.
- **`GET /api/v1/entradas/{entradaId}`.** Entra ahora: lo usan el request 3 de Postman, B2 y la evidencia de A4. Solo responde al comprador.
- **`_links`.** `self` siempre; `pagar` y `cancelar` solo en `EN_CHECKOUT`. Los arma el recurso.

**Cómo se defiende:** "Con dos columnas cumplimos lo que dijimos en la clase 10: reintentar no reserva ni cobra dos veces."

### 7. Llamadas a otros servicios y simuladores

| Llamada | Para el 23/10 |
|---|---|
| Eventos: reservar y liberar cupo (contratos §5.2) | Real, con `ClienteDeEventos` y `TokenDeServicio`. Sin T2 no funciona |
| Usuarios: `GET /api/usuarios/yo` (§5.3) | Real. La hace `ComprasEnCurso` con el token del comprador, antes de abrir la transacción. 403 `identidad-no-verificada` |
| Pagos | Simulado: interfaz `Cobros` con `CobrosFake` y los tokens de §5.4. B1 la reemplaza |
| Publicador de A2 | Real. A1 no crea ninguna interfaz provisoria |

Orden dentro de `iniciarCompra`:
1. Valida sin tocar nada: máximo de entradas, un titular por entrada y DNI sin repetir.
2. Guarda la cabecera de la venta con la clave (`persist` y `flush`). Una clave repetida falla acá, antes de reservar, y `ComprasEnCurso` devuelve la venta que ya existía.
3. Reserva sector por sector; la respuesta trae nombre y precio.
4. Completa ítems y montos con `ReglasDeNegocio.cargoServicio`.

Si una reserva falla, o si algo falla después de la primera, devuelve los sectores que ya habían respondido 200 y relanza la excepción; el rollback borra la cabecera. Un sector cuya llamada no respondió no se devuelve, porque no se sabe si se aplicó. Ventas no valida la fecha del evento.

**Cómo se defiende:** "Entre servicios no hay transacción: cada llamada confirma por su cuenta. Ante la duda queda un lugar sin vender, nunca uno vendido dos veces. El cobro va detrás de una interfaz, así que conectar Pagos no toca el bean."

### 8. Dónde se declara Mockito (T0)

(a) Con versión en el pom de Ventas: no sale del módulo, pero el repo fija las versiones solo en el pom padre. (b) T0 agrega `mockito-core` 5.24.0 al pom padre y Ventas lo declara sin versión. (c) Dobles escritos a mano. **Recomendada: (b).** No se usa `mockito-junit-jupiter`, que trae JUnit 5.13.4 cuando el repo usa 6.1.3: los dobles se crean con `Mockito.mock(...)`.

**Cómo se defiende:** "Las versiones se fijan en un solo lugar del repo, y probamos las reglas del bean sin servidor, simulando sus dependencias."

## Archivos

Rutas relativas a `backend/servicios/ventas/`; las clases cuelgan de `src/main/java/com/palco/ventas/`. Cada componente dice en su comentario de clase por qué es stateful o stateless.

- `pom.xml` (modificar): declara `org.mockito:mockito-core` sin versión.
- `src/main/webapp/WEB-INF/jboss-web.xml` (modificar): context root `/api/v1`.
- `VentasApplication.java` (modificar): solo el comentario.
- `src/main/resources/META-INF/persistence.xml`: unidad `ventas` sobre `PalcoDS`, esquema `ventas`.
- `src/main/resources/db/migracion/V1__crear_tablas.sql`: las tres tablas.
- `api/Dtos.java`: DTO (objetos que entran o salen de la API) con sus validaciones. Sin `@Max` en `cantidad`: el máximo es una regla de negocio y responde 422.
- `api/VentasResource.java`: las cuatro operaciones de `ventas`; arma `Location` y `_links`.
- `api/EntradasResource.java`: `GET` de la entrada.
- `negocio/IVentas.java`, `negocio/ServicioDeVentas.java`: la conversación de compra y su bean stateful.
- `negocio/ComprasEnCurso.java`: fachada e índice de instancias. La búsqueda JNDI va en un campo `Supplier<IVentas>` que el test reemplaza.
- `negocio/VencimientoDeReservas.java`: el barrido.
- `negocio/MigracionesVentas.java`: corre Flyway al desplegar, como `MigracionesEventos`.
- `negocio/externos/ClienteDeEventos.java`, `ClienteDeUsuarios.java`: clientes REST `@ApplicationScoped`, según contratos §5.1.
- `negocio/externos/Cobros.java`, `ResultadoDeCobro.java`, `CobrosFake.java`: el cobro visto desde Ventas y su simulador, también `@ApplicationScoped`.
- `datos/VentaRepository.java`, `VentaRepositoryJpa.java`: guardar, buscar y los `UPDATE` condicionales de la venta. Las búsquedas devuelven cargado lo que el DTO necesita, porque `ComprasEnCurso` las usa fuera de una transacción, y después de un `UPDATE` la venta se vuelve a leer antes de armar la respuesta.
- `datos/EntradaRepository.java`, `EntradaRepositoryJpa.java`: crear, buscar y emitir entradas.
- `dominio/Venta.java`, `VentaItem.java`, `Entrada.java`: las entidades.
- `dominio/EstadoVenta.java`, `EstadoFactura.java`, `EstadoEntrada.java`: los estados de contratos §7.3.
- `mensajeria/EmisionDeEntradasMDB.java`: el consumidor de la cola.
- Tests: `ServicioDeVentasTest` (reglas, total, devolución del cupo, pago rechazado, reserva vencida, un solo `publicar`), `ComprasEnCursoTest` (clave repetida, venta ajena, identidad no verificada y las referencias que no sirven), `VencimientoDeReservasTest` y `EmisionDeEntradasMDBTest` (delega, y no falla si no hay nada pendiente).
- `docs/notas/ventas-core.md`: las decisiones con palabras de Sebastian, la tabla de fallas por paso, las líneas reales del log, los `type` usados y cinco preguntas probables con su respuesta.

## Contratos con las otras tareas

**Expone**
- La API de contratos §6, con sus JSON `Venta` y `Entrada`. Agrega un caso: `DELETE` responde 409 `operacion-en-curso` si la venta tiene `clave_pago` o hay otra llamada en curso. Las diferencias con la OpenAPI y con la clase 10 son las de contratos §8.3.
- `com.palco.ventas.negocio.externos.Cobros` y `ResultadoDeCobro` (§5.5), para B1.
- Un `PagoConfirmadoMensaje` por venta confirmada (§4.3) y una línea `INFO` del MDB por mensaje, para A4.
- Las tablas de §7.2. Para C3: las ventas `EN_CHECKOUT` con `clave_pago` y las `CONFIRMADA` que no pasan a `EMITIDA`.

**Consume**
- T1: `ErroresDeNegocio.PagoRechazado`, `SinPermiso`, `ReglaIncumplida`, `SinRespuesta`, `Conflicto(tipo, mensaje)` y `BaseDeDatos.esClaveDuplicada`.
- T2: `com.palco.common.seguridad.TokenDeServicio` (`obtener`, `invalidar`, `URL_INTERNA`).
- A2: `PublicadorPagoConfirmado`, `PagoConfirmadoMensaje`, `MensajeInvalido` y `DestinosJms`.
- Lo que ya existe: `ReglasDeNegocio`, `Sesion.usuarioId`, `BaseDeDatos.migrar` y los endpoints de §5.2 y §5.3.
- Pide a Santiago, sin hacerlo: los cambios de la colección de Postman (T4) y de los documentos (T5).

## Pasos

Cada paso termina con su verificación y es un punto de commit. El calendario de trabajo es el de `docs/PLAN_AGENTES.md`: el que este plan tomaba de contratos §10 apuntaba al lunes 19/10 y se quitó.

0. Previos en el árbol: T0, T1, T2 y A2, con los 8 `health` en 200. Sin T0 ni T1 no se puede hacer el paso 3, sin T2 no se puede verificar el 4 y sin A2 no compila el 5.
1. Context root, `persistence.xml`, `MigracionesVentas` y la V1, con el SQL cerrado antes del primer despliegue. `GET /api/v1/health` da 200 e informa `v1`; `/api/ventas/health` deja de existir.
2. Entidades y repositorios. Compila y despliega sin archivos `.failed`.
3. Mockito: `mvn -q dependency:tree -pl servicios/ventas -Dincludes=org.junit.jupiter,org.junit.platform` muestra todo en 6.1.3. Un test mínimo con `Mockito.mock(Cobros.class)` y después los de reglas.
4. `iniciarCompra`, `cancelarCompra`, los dos clientes, `ComprasEnCurso` y `VentasResource`. Con `curl`: 201, cupo más 2, `DELETE` 204, cupo de vuelta, y el `@PostConstruct` y el `@PreDestroy` en el log.
5. `confirmarCompra` con `CobrosFake` y el publicador; `EntradasResource`. El pago da 201 y el contador de la cola sube 1. Una vez y a mano, la prueba de la excepción forzada.
6. `EmisionDeEntradasMDB`. La entrada queda `VALIDA` y la venta `EMITIDA`. **Acá se toma la primera evidencia para A4.**
7. Idempotencia de los dos `POST`, 402 con reintento y los casos de error de la tabla de abajo.
8. `VencimientoDeReservas`: una compra sin pagar y 11 minutos de espera, una sola vez.
9. La nota y el reporte.

## Cómo se verifica

Desde Git Bash, con WildFly levantado:

```bash
export JAVA_HOME=/c/Users/santi/.jdks/temurin-25
cd "/c/Users/santi/OneDrive/Documentos/Facultad/Desarrollo de Aplicaciones II/TP Integrador/backend"
mvn -q -B test -pl servicios/ventas -am
./redesplegar.sh ventas && sleep 8
until curl -sf localhost:8080/api/v1/health >/dev/null; do sleep 2; done
ls servidor/target/server/standalone/deployments/*.failed 2>/dev/null   # no tiene que listar nada

vendidas() { curl -s localhost:8080/api/eventos/atletico-norte-racing-sur | sed -E 's/.*"id":"sec-1-4"[^}]*"vendidas":([0-9]+).*/\1/'; }
cola() { NOPAUSE=true servidor/target/server/bin/jboss-cli.bat --connect --command="/subsystem=messaging-activemq/server=default/jms-queue=cola.emision-entradas:read-attribute(name=messages-added)" | tr -d '\r' | sed -n 's/.*"result" => \([0-9]*\)L*.*/\1/p'; }
uuid() { node -e "console.log(crypto.randomUUID())"; }

T=$(curl -s -X POST localhost:8080/api/usuarios/login -H 'Content-Type: application/json' \
  -d '{"email":"titular@palco.test","password":"palco1234"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
A=(-H "Authorization: Bearer $T" -H 'Content-Type: application/json')
CUERPO='{"eventoId":"ev-1","items":[{"sectorId":"sec-1-4","cantidad":2}],"titulares":[{"nombre":"Ana Gomez","dni":"30123456"},{"nombre":"Luis Perez","dni":"31234567"}]}'

V0=$(vendidas); C0=$(cola); K=$(uuid)
R=$(curl -si -X POST localhost:8080/api/v1/ventas "${A[@]}" -H "Idempotency-Key: $K" -d "$CUERPO"); echo "$R"
V=$(printf '%s' "$R" | grep -i '^location:' | tr -d '\r' | sed 's#.*/##')
P=$(curl -s -X POST localhost:8080/api/v1/ventas/$V/pagos "${A[@]}" -H "Idempotency-Key: $(uuid)" \
  -d '{"medioDePago":"tarjeta","token":"tok_test"}'); echo "$P"
E=$(printf '%s' "$P" | grep -o '"id":"T-[0-9a-f]*"' | head -1 | cut -d'"' -f4)
sleep 3; curl -s localhost:8080/api/v1/entradas/$E "${A[@]}"; curl -s localhost:8080/api/v1/ventas/$V "${A[@]}"
echo "cupo: $V0 -> $(vendidas)   cola: $C0 -> $(cola)"
```

Resultado esperado:
- Tests en verde y health 200 con `"servicio":"v1"`.
- Primer `POST`: 201, `Location` terminado en `/api/v1/ventas/V-…`, `EN_CHECKOUT` y `montoTotal` 60500.
- Pago: `CONFIRMADA` con dos entradas. Después, la entrada en `VALIDA` con `codigoQr` y la venta en `EMITIDA`.
- El cupo sube 2 y la cola sube 1. En `servidor/target/server/standalone/log/server.log` aparecen el `@PostConstruct` y el `@PreDestroy` de `ServicioDeVentas` y la línea `INFO` del MDB con el `ventaId`, sin errores después del pago ni de una cancelación.

Las funciones `vendidas` y `cola` ya se corrieron hoy contra el servidor (dieron 1440 y 0); el resto del guion todavía no. El `sleep 8` está porque el servidor revisa los despliegues cada 5 segundos (`scan-interval` en `standalone.xml`); hasta que detecta el cambio, el health lo puede contestar el WAR anterior.

Casos que se prueban aparte, cada uno con una venta y una clave nuevas:

| Caso | Esperado |
|---|---|
| Repetir el primer `POST` con la misma `$K` | 201 con el mismo `id`; el cupo no cambia |
| Pagar con `tok_rechazado` | 402 `pago-rechazado`; pagar después con `tok_test` da 201 |
| Comprar `sec-1-3`, que está agotado | 409 `cupo-agotado` |
| `cantidad` 7, o el mismo DNI dos veces | 422 `maximo-de-entradas` o `dni-repetido` |
| Sin `Idempotency-Key` | 400 |
| `DELETE` de una venta sin pagar | 204 y `vendidas` vuelve a su valor; repetirlo da 204 |
| No pagar y esperar 11 minutos | `VENCIDA`, cupo devuelto, la línea del barrido y el `@PreDestroy` por timeout |
| Redesplegar Ventas con una compra abierta y pagar | 409 `reserva-vencida`, sin errores en el log; la venta vence sola |
| `throw new IllegalStateException()` después de `publicar`, con una venta de 1 entrada | 500, la venta sigue `EN_CHECKOUT` y la cola no cambia. Se anota en la nota y se saca el `throw`. Esa venta queda con el cupo tomado hasta reiniciar |
| `POST /api/notificaciones/pruebas/pago-confirmado`, de A2 | El MDB avisa que la venta no existe y la DLQ no crece |

Los requests 1, 2 y 3 de la carpeta 3 de Postman pasan cuando Santiago aplique T4.

## Riesgos y dudas abiertas

**Leído en el código del servidor, sin ejecutar.** Cada punto se comprueba en el paso que se indica.
- El ciclo de vida del stateful: instancia nueva por cada búsqueda JNDI, timeout por inactividad que llama a `@PreDestroy`, descarte ante una excepción que no es de negocio, `NoSuchEJBException` tras el `@Remove` y fallo inmediato con `@AccessTimeout(0)`. Pasos 4, 5 y 8. Si no se cumple, se revisa la decisión 1.
- `@Schedule(persistent = false)` dentro del WAR. Paso 8. Si falla, se revisa la decisión 2.
- Que el mensaje no salga cuando la transacción se deshace. Paso 5. La caída entre los dos commits no la prueba nadie y se cuenta como "no probada".
- La convivencia de `/api/v1` con `/api/v1/pagos`. Se ve cuando despliegue A3; si falla, el que vuelve a su ruta vieja es Pagos.
- Mockito 5.24.0 con Java 25 y JUnit 6.1.3. Paso 3. Si falla, dobles escritos a mano.
- El SQL de la V1: un revisor probó las restricciones en H2 por JDBC, no con Hibernate ni contra PostgreSQL.
- Dos pedidos simultáneos con la misma clave: sin probar con dos conexiones. En el peor caso el segundo recibe 500; nunca hay una segunda reserva.
- `@PreDestroy` al detener el WAR con compras abiertas: por eso va dentro de un `try/catch`.

**Lo que no se puede mostrar en vivo**
- El 403 `identidad-no-verificada` y el 404 de una venta ajena se prueban solo con dobles: hay una sola cuenta de prueba con rol `user` y tiene la identidad verificada.
- La reentrega de una venta ya emitida: los endpoints de prueba de A2 publican un mensaje fijo. La condición del `UPDATE` la probó un revisor en H2; en vivo solo se ve el caso de la venta que no existe.

**Límites que se aceptan para el 23/10**
- Los de contratos §12 que nacen acá: redesplegar Ventas corta las compras en curso; puede quedar cupo tomado sin venta si el servidor se cae entre la reserva y el commit o si Eventos no responde al liberar; y hay lugar para unas 5 compras simultáneas, porque cada una retiene una conexión de la base mientras llama a Eventos.
- Una venta con `clave_pago` y sin resultado queda `EN_CHECKOUT`, con su cupo, hasta la conciliación de C3. Con el simulador solo pasa con `tok_sin_respuesta` o con una falla inesperada después de cobrar.
- La misma clave con otro cuerpo devuelve la venta original: no se compara el cuerpo.
- Ver el vencimiento lleva 11 minutos: `MINUTOS_RESERVA` vive en `common` y no se toca.

**Para que decida el grupo**
- `DELETE` con 409 `operacion-en-curso` y la firma `boolean cancelarCompra()` no están en contratos §6 ni §7.3: hay que sumarlos ahí o corregir este plan.
- Pagar una venta `CANCELADA` responde 409 `reserva-vencida`, sin un `type` propio.
- La emisión no tiene un componente de negocio propio: queda entre el MDB y `EntradaRepository`, porque contratos §7.4 no lista otro.
- La línea de espera de contratos §10 no alcanza a partir del segundo redespliegue; acá se le suma el `sleep 8`.
