# Plan A2 · mensajeria — PAGO_CONFIRMADO por cola y tópico

- **Dueño:** Julian Zuchowicki. **Rama:** `feat/mensajeria` según `PLAN_AGENTES.md`; hoy se trabaja en el árbol actual y commitea solo Santiago, un commit por paso.
- **Toca:** `backend/common` (paquete nuevo `com.palco.common.mensajeria` y una dependencia de test), `backend/servicios/notificaciones`, `backend/servidor/src/main/wildfly/configurar.cli`, el contrato del mensaje y `docs/notas/mensajeria.md`.
- **Depende de:** el paso transversal T2 (token de rol `servicio`), solo para la aceptación del paso 7.
- **Dependen de este plan:** A1 (publica el mensaje y consume la cola), A4 (evidencia), B1 y C1.

Los nombres son los de `docs/planes/00-contratos-compartidos.md` ("los contratos"); si difieren de este plan, vale ese archivo.

## Qué se construye

Al confirmarse un pago, Ventas publica `PAGO_CONFIRMADO` en dos destinos del broker (el servidor de mensajes, ActiveMQ Artemis dentro de WildFly): una cola, que entrega cada mensaje a un solo consumidor, y un tópico, que entrega una copia a cada suscriptor.

**Para el 19/10**
- En `common`: el mensaje, el publicador y las constantes con los nombres JNDI (el nombre con que el servidor publica cada recurso).
- En `notificaciones`: dos MDB (Message-Driven Bean, el componente que consume mensajes) suscriptos al tópico. `AvisoDeCompraMDB` es no durable y escribe el aviso al comprador en el log. `AuditoriaDeComprasMDB` es durable (el broker le guarda los mensajes mientras está detenido) e inserta una fila por mensaje. Además, tres endpoints de prueba y la consulta de auditoría.
- En `configurar.cli`: 5 entregas separadas por 2 segundos y después la DLQ (la cola de los mensajes que agotaron sus intentos).

El consumidor de la cola y la publicación real son de A1.

**Para el 09/11:** email real (el mensaje trae `compradorId`, no el email: habrá que pedírselo a Usuarios), `enviarRecordatorio`, `enviarAlertaCancelacion`, el diagrama de secuencia y la decisión sobre el DNI en el tópico.

## Decisiones para aprobar

Todas están pendientes de aprobación de Julian, que las defiende. El plan usa la recomendada.

**1. El mensaje es un `record` con `timestamp` de tipo `Instant`.**
Alternativas: una clase con getters y setters (más código y un objeto modificable) o `timestamp` como `String` (sin control de formato). Verificado hoy con JDK 25 contra Yasson, la implementación de JSON-B (el conversor estándar entre objetos y JSON), en la versión 3.0.4 de WildFly 41 y en la 3.0.5 de los tests: las dos leen y escriben records anidados sin configuración, con el instante como `2026-10-16T21:30:00Z`. La prueba mostró tres trampas: un campo que falta queda en `null` o `0` sin dar error, el texto `null` devuelve `null`, y un método que empieza con `get` o `is` sale como campo. Por eso `desdeJson()` termina llamando a `validar()` y los métodos auxiliares son `aJson()`, `desdeJson()` y `validar()`.
Defensa: "es un dato que no cambia, y el repo ya usa records en los DTO (los objetos que entran y salen de la API)".

**2. El publicador es un Session Bean `@Stateless` en `common`, con `@TransactionAttribute(MANDATORY)`.**
Un Session Bean es un componente de negocio que administra el servidor; `@Stateless` significa que no guarda nada entre llamadas. `PublicadorPagoConfirmadoJms` inyecta `@Inject @JMSConnectionFactory(DestinosJms.FABRICA_XA) JMSContext` y los dos destinos con `@Resource(lookup = …)`:

```java
mensaje.validar();
String cuerpo = mensaje.aJson();
JMSProducer productor = contexto.createProducer()
        .setProperty(DestinosJms.PROPIEDAD_TIPO_EVENTO, DestinosJms.PAGO_CONFIRMADO);
productor.send(cola, cuerpo);     // TextMessage; la entrega persistente es el valor por defecto
productor.send(topico, cuerpo);   // el mismo texto
```

- Con `MANDATORY`, quien llama ya tiene que estar en una transacción; si no, el servidor lanza `EJBTransactionRequiredException`. Con `REQUIRED` se abriría una propia y el mensaje saldría aunque la venta falle.
- Plan B: un bean CDI (la inyección de dependencias de Jakarta EE) con `@Transactional(MANDATORY)`. No es la primera opción porque la materia trabaja con Session Beans.
- `common` es un jar dentro de cada WAR (el archivo desplegable de un servicio). No hace falta `beans.xml`: alcanza con la anotación, como ya pasa con `HealthResource`. Tampoco faltan dependencias: `jakarta.jakartaee-api` 11 trae JMS (la API de mensajería), Session Beans y JSON-B. Solo se agrega `yasson` para el test.

Defensa: "el publicador no abre transacciones: si la venta se deshace, el mensaje no sale".

**3. Garantía transaccional (propuesta P3 de los contratos).**
`PalcoDS`, la base, no es XA (el protocolo del commit en dos fases). En la transacción JTA (la API de transacciones) el broker entra como recurso XA y la base como último recurso: una excepción no deja ni datos ni mensaje, pero una caída entre los dos commits puede dejar uno sin el otro. Alternativas: base XA (cambia `servidor`, sin verificar contra Supabase) o una tabla de salida con un proceso que publica (más código). Se recomienda no tocar la base y que los consumidores sean idempotentes (repetir la entrega no repite el efecto).
Defensa: "transacción JTA declarativa con último recurso, no commit en dos fases".

**4. Aviso y auditoría van separados dentro del mismo WAR.**
`AvisoDeCompraMDB` delega en `INotificaciones` (`ServicioDeNotificaciones`) y `AuditoriaDeComprasMDB` en `IAuditoria` (`ServicioDeAuditoria`). Alternativas: una sola interfaz (mezcla responsabilidades y cambia las operaciones de la Entrega Parcial 1) o un WAR propio (un noveno despliegue).
Defensa: "cada suscriptor recibe su copia sin conocer al otro".

**5. Suscripción durable con `clientId`, `subscriptionName` y una sola sesión.**

```java
@MessageDriven(name = "AuditoriaDeCompras", activationConfig = {
    @ActivationConfigProperty(propertyName = "destinationLookup", propertyValue = DestinosJms.TOPICO_COMPRA),
    @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "jakarta.jms.Topic"),
    @ActivationConfigProperty(propertyName = "messageSelector", propertyValue = DestinosJms.SELECTOR_PAGO_CONFIRMADO),
    @ActivationConfigProperty(propertyName = "subscriptionDurability", propertyValue = "Durable"),
    @ActivationConfigProperty(propertyName = "clientId", propertyValue = "palco-auditoria"),
    @ActivationConfigProperty(propertyName = "subscriptionName", propertyValue = "compras-confirmadas"),
    @ActivationConfigProperty(propertyName = "maxSession", propertyValue = "1") })
```

`AvisoDeCompra` lleva solo las tres primeras.
- Nombres comprobados hoy en el adaptador del servidor (`artemis-jakarta-ra-2.54.0`) con `javap`, la herramienta del JDK que muestra una clase compilada: `Durable` va con esa mayúscula exacta, `clientId` y `clientID` son equivalentes, `destinationType` solo admite `jakarta.jms.Topic` o `jakarta.jms.Queue`, y `subscriptionName` es obligatorio en una durable.
- Con un pool de varias instancias, todas las sesiones del MDB leen de la misma cola interna, `palco-auditoria.compras-confirmadas`: cada mensaje llega a una sola instancia. `shareSubscriptions` solo hace falta si otro MDB u otro servidor repite el par.
- `maxSession` = `1` da orden de llegada. La alternativa, las 15 sesiones por defecto, es más rápida y sin orden.

Defensa: "durable es una cola con nombre fijo que el broker conserva; la no durable es temporal".

**6. La auditoría es idempotente por `JMSMessageID`.**
Es la clave primaria de `registro_auditoria` y no cambia cuando el broker reentrega: `ServicioDeAuditoria.registrar` busca con `em.find` y no inserta si la fila existe. Alternativa: clave única por `venta_id`, que escondería una doble publicación.
Defensa: "auditoría registra una vez cada mensaje recibido; no publicar dos veces es responsabilidad de Ventas".

**7. Mensaje inválido: auditoría lo manda a la DLQ y aviso lo descarta.**
Si no es un `TextMessage` o `desdeJson()` lanza `MensajeInvalido`, `AuditoriaDeComprasMDB` llama a `MessageDrivenContext.setRollbackOnly()` y el broker lo reentrega hasta pasarlo a la DLQ. `AvisoDeCompraMDB` lo descarta con un `WARNING`, así queda una sola copia. En `configurar.cli`:

```
/subsystem=messaging-activemq/server=default/address-setting=#:write-attribute(name=max-delivery-attempts,value=5)
/subsystem=messaging-activemq/server=default/address-setting=#:write-attribute(name=redelivery-delay,value=2000)
```

`dead-letter-address` no hace falta: `standalone.xml` ya trae `jms.queue.DLQ`. Alternativas: lanzar una excepción (una traza por intento), una política por destino (más líneas) o los valores de fábrica (10 entregas sin espera).
Defensa: "auditoría no puede perder eventos y un aviso sí".

**8. Endpoints de prueba con rol `servicio`, sin interruptor y con el publicador real.**
Son los cuatro de la sección 4.8. `PruebasDeMensajeria` (`@Stateless`) abre la transacción y publica un ejemplo con `ventaId` `prueba-` más 8 caracteres. Alternativas: aceptar un rol humano (un organizador podría publicar pagos falsos), un interruptor por variable de entorno (obliga a reiniciar y a tocar archivos ajenos) o publicar solo en el tópico (no probaría el publicador). Hoy nadie emite tokens de rol `servicio`: los da T2. Sin T2 se verifica hasta el paso 6 y A2 queda implementada pero no aceptada; el rol no se relaja.
Defensa: "solo los llama otro servicio autenticado; son de desarrollo y no están en la OpenAPI".

**9. El durable se demuestra con `stop-delivery` y `start-delivery`.**
Se detiene solo `AuditoriaDeCompras`, se publican dos mensajes y se lo arranca. Alternativas: quitar el WAR (detiene también el endpoint que publica) o reiniciar WildFly (vacía la base).
Defensa: "el broker le guardó los mensajes y se los entregó al volver".

## Archivos

**`backend/common`**
- `pom.xml` (modificar): `org.eclipse:yasson`, sin versión; el alcance de test viene del pom padre.
- `src/main/java/com/palco/common/mensajeria/`: `PagoConfirmadoMensaje` (record con `aJson()`, `desdeJson()` y `validar()`), `MensajeInvalido` (con `@ApplicationException(rollback = true)`), `DestinosJms` (constantes), `PublicadorPagoConfirmado` (interfaz `@Local`) y `PublicadorPagoConfirmadoJms`.
- `src/test/java/com/palco/common/mensajeria/PagoConfirmadoMensajeTest.java`: ida y vuelta, ejemplo del contrato, `timestamp` con `-03:00`, campo desconocido y los casos inválidos de la sección 4.3 (texto nulo, vacío, `null` o no JSON; campos faltantes; entrada nula; DNI mal formado).

**`backend/servicios/notificaciones`** (clases en `src/main/java/com/palco/notificaciones/`)
- `src/main/resources/META-INF/persistence.xml`: unidad `notificaciones`, como la de Eventos.
- `src/main/resources/db/migracion/V1__crear_tablas.sql`: migración Flyway (el script versionado que crea las tablas al desplegar). Tabla `registro_auditoria`: `mensaje_id varchar(100)` clave primaria; `tipo_evento`, `venta_id`, `comprador_id` y `evento_id` `varchar(40)`; `monto_total bigint`; `cuerpo varchar(4000)`; `recibido_en timestamp`. Todas `not null`, sin valores por defecto.
- `negocio/MigracionesNotificaciones.java`: llama a `BaseDeDatos.migrar(dataSource, "notificaciones")`, igual que `MigracionesEventos`.
- `dominio/RegistroDeAuditoria.java`: entidad; `recibidoEn` es un `LocalDateTime` en UTC, truncado a segundos y tomado de un `Clock`.
- `negocio/INotificaciones.java`, `ServicioDeNotificaciones.java`: `enviarConfirmacion` escribe el aviso en el log, sin el DNI.
- `negocio/IAuditoria.java`, `ServicioDeAuditoria.java`: `registrar` y `listar`.
- `negocio/PruebasDeMensajeria.java`: los tres casos de prueba.
- `mensajeria/AvisoDeCompraMDB.java`, `AuditoriaDeComprasMDB.java`: leen, delegan y escriben una línea `INFO` con el `ventaId`.
- `api/NotificacionesResource.java`, `api/Dtos.java`: los cuatro endpoints y `RegistroDeAuditoriaDto`, con `recibidoEn` como `Instant`. Sin `@Consumes`: ningún endpoint recibe cuerpo.

**Otros**
- `backend/servidor/src/main/wildfly/configurar.cli` (modificar): las dos líneas y el comentario de cabecera, que hoy dice que el script corre en cada armado.
- `docs/superpowers/specs/mensajeria-pago-confirmado.md` (modificar): formato de `timestamp`, ids de ejemplo, nombres de clases y garantía (secciones 4.3 y 4.7). Los campos no cambian.
- `docs/notas/mensajeria.md` (crear): la nota para la defensa.

Cada `@Stateless` lleva el comentario que justifica por qué no guarda estado (`CLAUDE.md`). `mensajeria/` es la excepción acordada a las tres capas: el MDB es un punto de entrada delgado, como el recurso REST.

## Contratos con las otras tareas

**Expone**
- A A1: `PublicadorPagoConfirmado.publicar(PagoConfirmadoMensaje)`. Un mensaje inválido lanza `MensajeInvalido`: la venta se deshace y el cliente recibe 500, porque es un error de quien publica.
- A A1, para `EmisionDeEntradasMDB`: `PagoConfirmadoMensaje.desdeJson(String)`, que devuelve un mensaje validado o lanza `MensajeInvalido`; `DestinosJms.COLA_EMISION` y `DestinosJms.SELECTOR_PAGO_CONFIRMADO`; y la política de reintentos, que vale para todas las colas.
- A A4: la línea `INFO` de cada MDB y `GET /api/notificaciones/auditoria`.

**Consume**
- Los nombres JNDI de la sección 4.2, `BaseDeDatos.migrar`, `ErroresDeNegocio.Conflicto` y `ReglasDeNegocio.MAX_ENTRADAS_POR_ORDEN`: existen hoy.
- De T2: `POST /api/usuarios/servicios/token` con `{"secreto": "palco-desarrollo"}`; el token dura 5 minutos.
- De A1: que `EmisionDeEntradasMDB` descarte con un aviso las ventas inexistentes (los mensajes de prueba llegan a su cola) y que publique una sola vez por venta.

A2 no toca `TODO.md` ni `CLAUDE.md`: la frase de XA y la capa `mensajeria/` las corrige Santiago en T5, la pasada final de documentos.

## Pasos

Cada paso es verificable y es un commit de Santiago.

1. `common`: `pom.xml`, `PagoConfirmadoMensaje`, `MensajeInvalido` y el test. `mvn -B test -pl common` da BUILD SUCCESS y suma tests a los 5 de hoy.
2. `common`: `DestinosJms`, la interfaz y el bean publicador. Compila.
3. `notificaciones`: `persistence.xml`, migración, entidad y `MigracionesNotificaciones`. El SQL se cierra antes del primer despliegue: si se edita después, Flyway lo rechaza y el WAR no despliega hasta reiniciar WildFly. Redesplegar; `/health` da 200.
4. `notificaciones`: interfaces, beans y los dos MDB. Redesplegar; el tópico muestra dos suscripciones y la durable tiene un consumidor.
5. `notificaciones`: recurso, DTO y `PruebasDeMensajeria`. Redesplegar; un `POST` sin token da 401 (hoy da 404).
6. `configurar.cli` y armado limpio (generar el servidor de cero): detener WildFly, `mvn -B clean install` y arrancar. Comprueba que las dos líneas funcionan dentro del script y que los 8 WAR despliegan con el `common` nuevo. Borra los mensajes guardados, vacía la base y cambia la clave de los tokens.
7. Aceptación con el token de T2: publicación, caso con falla, mensaje inválido hasta la DLQ y suscriptor detenido.
8. Actualizar el contrato del mensaje y escribir `docs/notas/mensajeria.md`, con las dos líneas de `jboss-cli` para los servidores ya armados.

## Cómo se verifica

Los valores se comparan antes y después, nunca como número fijo: los mensajes sobreviven a un reinicio y la base H2 de desarrollo no. Antes de una demo se vacían `cola.emision-entradas` y `DLQ` con `remove-messages`.

Git Bash, desde `backend/`:

```bash
export JAVA_HOME=/c/Users/santi/.jdks/temurin-25
D=servidor/target/server/standalone; W=$D/deployments/palco-notificaciones.war
N=localhost:8080/api/notificaciones
mvn -B test -pl common                          # paso 1: BUILD SUCCESS
./redesplegar.sh notificaciones                 # pasos 3 a 5
until [ $W.deployed -nt $W ] || [ -e $W.failed ]; do sleep 2; done
ls $D/deployments | grep -c failed              # 0
curl -s -o /dev/null -w '%{http_code}\n' $N/health                                  # 200
# paso 6, después del armado limpio (Ventas y Pagos cambian de ruta cuando entren A1 y A3):
for s in usuarios eventos ventas pagos validacion reventa notificaciones facturacion; do
  curl -s -o /dev/null -w "$s %{http_code}\n" localhost:8080/api/$s/health; done    # ocho 200
grep -c "session bean named 'PublicadorPagoConfirmadoJms'" $D/log/server.log        # 8 por cada arranque
# paso 7 (si pasan más de 5 minutos, pedir otro token):
T=$(curl -s -X POST -H 'Content-Type: application/json' -d '{"secreto":"palco-desarrollo"}' \
  localhost:8080/api/usuarios/servicios/token | sed -E 's/.*"token":"([^"]+)".*/\1/')
A="Authorization: Bearer $T"
curl -s -o /dev/null -w '%{http_code}\n' -X POST $N/pruebas/pago-confirmado         # 401, sin token
curl -s -X POST -H "$A" $N/pruebas/pago-confirmado     # 202 y el mensaje; anotar su ventaId
grep prueba-xxxxxxxx $D/log/server.log                 # con ese ventaId: una línea INFO de cada MDB
curl -s -H "$A" $N/auditoria                           # trae una fila con ese ventaId
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "$A" $N/pruebas/pago-confirmado-con-falla  # 409; ni fila ni línea nuevas
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "$A" $N/pruebas/mensaje-invalido           # 202; 5 WARNING de auditoría, uno cada 2 s
```

PowerShell, desde la raíz del repo (en Git Bash la barra inicial de cada comando se convierte en una ruta):

```powershell
$env:NOPAUSE = 'true'; $env:JAVA_HOME = 'C:\Users\santi\.jdks\temurin-25'   # sin NOPAUSE, jboss-cli espera una tecla
$cli = "backend\servidor\target\server\bin\jboss-cli.bat"
$srv = "/subsystem=messaging-activemq/server=default"
$top = "$srv/jms-topic=topico.compra-confirmada"
$mdb = "/deployment=palco-notificaciones.war/subsystem=ejb3/message-driven-bean=AuditoriaDeCompras"
$pend = "count-messages-for-subscription(client-id=palco-auditoria,subscription-name=compras-confirmadas)"
& $cli --connect --command="$srv/address-setting=#:read-resource(include-defaults=true)"   # paso 6: 5 y 2000
& $cli --connect --command="${top}:list-all-subscriptions"       # dos; la durable, con un consumidor
& $cli --connect --command="$srv/jms-queue=cola.emision-entradas:read-attribute(name=messages-added)"   # no cambia con el caso con falla
& $cli --connect --command="$srv/jms-queue=DLQ:count-messages"   # uno más, unos 12 segundos después del inválido
& $cli --connect --command="${mdb}:stop-delivery"
& $cli --connect --command="${mdb}:read-attribute(name=delivery-active)"   # false
# dos POST pago-confirmado: el log muestra los dos avisos y GET auditoria no cambia
& $cli --connect --command="${top}:$pend"                        # dos más que antes
& $cli --connect --command="${mdb}:start-delivery"               # GET auditoria suma las dos filas
```

## Riesgos y dudas abiertas

- **Nada de la mensajería se ejecutó todavía en este servidor**: lo anterior sale de leer la configuración y el código del adaptador. Los pasos 6 y 7 comprueban:
  - Un Session Bean dentro del jar de `common`, desplegado en los 8 WAR; nunca hubo uno ahí. Si falla, plan B de la decisión 2.
  - Que el mensaje no salga si la transacción se deshace.
  - La creación de la suscripción durable: el rol `guest` del broker no tiene ese permiso, aunque las conexiones internas lo saltean. Si falla, se agrega a `configurar.cli`: `/subsystem=messaging-activemq/server=default/security-setting=#/role=guest:write-attribute(name=create-durable-queue,value=true)`.
  - `stop-delivery`, `start-delivery`, la reentrega cada 2 segundos y el pase a la DLQ. Si difieren, se ajusta la demo.
  - Las dos líneas dentro de `configurar.cli`. Si fallan, se aplican con `jboss-cli` y se documenta.
- **Los comandos de "Cómo se verifica" no se corrieron**: esta etapa no permite compilar ni redesplegar. Las operaciones de `jboss-cli` existen en el servidor, pero la espera del redespliegue y la extracción del token están sin probar.
- **Token de servicio.** Sin T2 no hay aceptación: `jboss-cli` no tiene una operación para enviar mensajes.
- **Un MDB que no logra activarse no hace fallar el despliegue**: el adaptador reintenta sin fin y `/health` sigue en 200. Tras cada redespliegue se mira `list-all-subscriptions` y el log.
- **Cambiar el selector** (el filtro `tipoEvento = 'PAGO_CONFIRMADO'`) **o el tópico del MDB durable** hace que el adaptador borre y recree la suscripción: se pierden los pendientes.
- **`configurar.cli` no se reaplica** si `backend/servidor/target/server` ya existe: cada integrante corre las dos líneas con `jboss-cli`, o detiene WildFly y rearma. Nunca `mvn clean` con WildFly levantado.
- **Caída entre los dos commits:** nadie la probó, y así se dice en la defensa.
- **El publicador queda desplegado en los 8 WAR** aunque solo lo use Ventas. Si la cátedra lo objeta, en la Ola 3 el bean se muda a Ventas.
- **El DNI viaja completo** en el tópico y queda en la columna `cuerpo`. Se acepta para el 19/10 (Fase 12 del `TODO.md`).
- **Detalles que este plan fija y los contratos no dicen**, a confirmar por el grupo:
  - `desdeJson()` llama a `validar()`.
  - El mensaje inválido de prueba lleva `tipoEvento`; sin esa propiedad el selector lo filtra.
  - `ServicioDeAuditoria` toma la hora de un `Clock` propio, porque `registrar` no la recibe.
  - `sector` y el nombre del titular no tienen tope de largo. Un cuerpo de 6 entradas ronda los 900 caracteres; si alguno pasara de 4000, el insert falla y el mensaje termina en la DLQ.
