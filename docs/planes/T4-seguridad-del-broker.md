# T4 · Permisos por destino en el broker (SEC-I02)

- **Estado:** propuesta, a decidir por el grupo. No hay nada aplicado.
- **Criterio:** SEC-I02 (matriz de seguridad de la clase 11), hoy NO CUMPLE, prioridad media. Responsable:
  Julian Zuchowicki.
- **Qué pide:** con la identidad del consumidor de Notificaciones, publicar en `cola.emision-entradas` tiene que
  fallar con error de autorización, y el intento tiene que quedar registrado.

## 1. Situación actual (comprobada el 16/10)

Fuentes: `backend/servidor/src/main/wildfly/configurar.cli`, el `standalone.xml` del servidor armado
(`backend/servidor/target/server/standalone/configuration/`, leído sin modificar) y consultas de solo lectura al
WildFly en marcha (`jboss-cli`: `read-resource`, `read-attribute`, `read-resource-description`,
`read-operation-description`). Versión del servidor: `product-version` = 41.0.1.Final.

- `configurar.cli` crea la cola y el tópico y ajusta los reintentos, pero **no define usuarios ni
  `security-setting`**.
- El servidor armado tiene la configuración de fábrica: `security-setting name="#"` con
  `role name="guest" send="true" consume="true" create-non-durable-queue="true" delete-non-durable-queue="true"`
  (`standalone.xml:408-410`; el `read-resource` del rol da lo mismo, con `create-durable-queue`, `delete-durable-queue`
  y `manage` en `false`). Cualquier conexión con rol `guest` puede publicar y consumir en cualquier destino.
- La seguridad del broker está activa (`security-enabled` = `true`) y autentica contra el dominio Elytron
  `ApplicationDomain` (`elytron-domain` = `ApplicationDomain`), que usa el `properties-realm` `ApplicationRealm`
  (`application-users.properties` y `application-roles.properties`) con `role-decoder="groups-to-roles"`: los
  grupos de un usuario son sus roles en el broker (`standalone.xml:224-238`).
- Todos los servicios usan la misma fábrica: `pooled-connection-factory name="activemq-ra"`, con entradas
  `java:/JmsXA` y `java:jboss/DefaultJMSConnectionFactory`, conector `in-vm`, XA, **sin usuario**
  (`standalone.xml:433`). Los MDB también la usan: es el adaptador por defecto del subsistema EJB
  (`resource-adapter-name="${ejb.resource-adapter-name:activemq-ra.rar}"`, `standalone.xml:173`).
- Además existe `RemoteConnectionFactory` sobre el `http-connector` (`standalone.xml:432`), expuesta por el
  listener HTTP en la interfaz `public` (`127.0.0.1` por defecto, `standalone.xml:480-481`). En el repo nadie la usa
  (búsqueda de `RemoteConnectionFactory` en `backend/` y `scripts/`, fuera de `target/`: sin resultados).
- Las direcciones reales de los destinos, contra las que se comparan los `security-setting`, llevan el prefijo de
  compatibilidad: `queue-address` = `jms.queue.cola.emision-entradas` y `topic-address` =
  `jms.topic.topico.compra-confirmada`. La guía de WildFly 41 lo explica: WildFly sigue anteponiendo `jms.queue.` y
  `jms.topic.` y corre Artemis en modo compatible (Admin Guide, 7.15.13).

### Quién usa cada destino hoy

| Código | Servicio | Destino | Operación |
|---|---|---|---|
| `PublicadorPagoConfirmadoJms` (en `common`, `java:/JmsXA`), llamado por `ServicioDeVentas` | Ventas | cola y tópico | envía |
| `EmisionDeEntradasMDB` | Ventas | `cola.emision-entradas` | consume |
| `AvisoDeCompraMDB` (suscripción no durable) | Notificaciones | `topico.compra-confirmada` | consume, crea y borra su cola no durable |
| `AuditoriaDeComprasMDB` (durable, `clientId` `palco-auditoria`, suscripción `compras-confirmadas`) | Notificaciones | `topico.compra-confirmada` | consume, crea su cola durable |
| `PruebasDeMensajeria` (endpoints `/api/notificaciones/pruebas/*`, rol `servicio`) | Notificaciones | cola y tópico (usa el publicador real) y tópico (mensaje inválido) | envía |

El último renglón choca con la regla "Notificaciones solo consume": los escenarios 11, 12 y 13 de
`scripts/pruebas/escenarios.mjs` publican a través de esos endpoints. La propuesta lo resuelve con una identidad
aparte para las pruebas (sección 2).

## 2. Propuesta

**Un usuario por servicio, un rol por usuario y permisos solo donde hacen falta. Nada en `#`.**

| Usuario (grupo = rol) | `jms.queue.cola.emision-entradas` | `jms.topic.topico.compra-confirmada` |
|---|---|---|
| `palco-ventas` (`ventas`) | send, consume | send |
| `palco-notificaciones` (`notificaciones`) | nada | consume, create-durable-queue, create-non-durable-queue, delete-non-durable-queue |
| `palco-pruebas` (`pruebas`), **solo en desarrollo** | send | send |

- Notificaciones necesita `create-durable-queue` por la suscripción durable de auditoría y los permisos no durables
  por la del aviso. No recibe `delete-durable-queue`: borrar la suscripción de auditoría no es parte de su trabajo.
- La DLQ y la ExpiryQueue no reciben roles: el broker mueve ahí los mensajes por su cuenta, y los escenarios las
  leen por la consola de administración (`jboss-cli`, `ManagementRealm`), que no pasa por estos permisos.
- `palco-pruebas` existe para que los endpoints de prueba sigan funcionando sin darle permisos de envío al
  consumidor de Notificaciones. Con eso, la prueba del criterio se hace con la identidad `palco-notificaciones`.
  Alternativa: llevar los endpoints de prueba a Ventas; es más limpio pero toca dos servicios y sus tests.
- Se quita el rol `guest` de `#` (lo que no tiene permiso explícito queda denegado) y se borra
  `RemoteConnectionFactory`, que nadie usa.

### Líneas para `configurar.cli`

Sintaxis comprobada con `read-operation-description(name=add)` del recurso `security-setting=#/role=guest`
(parámetros `send`, `consume`, `create-durable-queue`, `create-non-durable-queue`, `delete-durable-queue`,
`delete-non-durable-queue`, `manage`, todos booleanos con `default => false`) y con `read-resource-description` de
`pooled-connection-factory=activemq-ra` (`connectors` y `entries` obligatorios; `transaction` con
`default => "xa"`; `user` y `password` admiten expresiones, `expressions-allowed => true`, y `password` tiene como
alternativa `credential-reference`).

```
# --- Seguridad del broker (SEC-I02) ------------------------------------------------
# Nada permitido por defecto: cada destino dice quién envía y quién consume.
/subsystem=messaging-activemq/server=default/security-setting=#/role=guest:remove

/subsystem=messaging-activemq/server=default/security-setting=jms.queue.cola.emision-entradas:add
/subsystem=messaging-activemq/server=default/security-setting=jms.queue.cola.emision-entradas/role=ventas:add(send=true, consume=true)
/subsystem=messaging-activemq/server=default/security-setting=jms.queue.cola.emision-entradas/role=pruebas:add(send=true)

/subsystem=messaging-activemq/server=default/security-setting=jms.topic.topico.compra-confirmada:add
/subsystem=messaging-activemq/server=default/security-setting=jms.topic.topico.compra-confirmada/role=ventas:add(send=true)
/subsystem=messaging-activemq/server=default/security-setting=jms.topic.topico.compra-confirmada/role=notificaciones:add(consume=true, create-durable-queue=true, create-non-durable-queue=true, delete-non-durable-queue=true)
/subsystem=messaging-activemq/server=default/security-setting=jms.topic.topico.compra-confirmada/role=pruebas:add(send=true)

# Una fábrica por servicio, cada una con su usuario. Las claves salen del entorno (backend/.env, no se commitea).
/subsystem=messaging-activemq/server=default/pooled-connection-factory=palco-ventas:add(connectors=[in-vm], entries=[java:/jms/fabrica/ventas], transaction=xa, user=palco-ventas, password=${env.PALCO_JMS_CLAVE_VENTAS})
/subsystem=messaging-activemq/server=default/pooled-connection-factory=palco-notificaciones:add(connectors=[in-vm], entries=[java:/jms/fabrica/notificaciones], transaction=xa, user=palco-notificaciones, password=${env.PALCO_JMS_CLAVE_NOTIFICACIONES})
/subsystem=messaging-activemq/server=default/pooled-connection-factory=palco-pruebas:add(connectors=[in-vm], entries=[java:/jms/fabrica/pruebas], transaction=xa, user=palco-pruebas, password=${env.PALCO_JMS_CLAVE_PRUEBAS})

# Nadie la usa y es la única entrada al broker desde fuera del proceso.
/subsystem=messaging-activemq/server=default/connection-factory=RemoteConnectionFactory:remove
```

### Los usuarios

`configurar.cli` no crea usuarios del `ApplicationRealm`: viven en `application-users.properties` y
`application-roles.properties` del servidor armado, que se regeneran al rearmar. Se agregan con `add-user`, cuyas
opciones se comprobaron con `bin/add-user.sh --help` (`-a` usuario de aplicación, `-u`, `-p`, `-g` grupos separados
por coma); la guía (Admin Guide, "Non-Interactive Mode") documenta la forma `./add-user.sh -a {username} {password}`.
Propuesta: que `backend/dev.sh`, que ya carga `backend/.env`, ejecute antes de arrancar:

```
servidor/target/server/bin/add-user.sh -a -u palco-ventas -p "$PALCO_JMS_CLAVE_VENTAS" -g ventas
servidor/target/server/bin/add-user.sh -a -u palco-notificaciones -p "$PALCO_JMS_CLAVE_NOTIFICACIONES" -g notificaciones
servidor/target/server/bin/add-user.sh -a -u palco-pruebas -p "$PALCO_JMS_CLAVE_PRUEBAS" -g pruebas
```

Falta comprobar qué hace `add-user` si el usuario ya existe (hay que poder correr `dev.sh` muchas veces). Fuera de
desarrollo, `palco-pruebas` y su fábrica no se crean.

### Cómo llegan las credenciales al código

- **Publicador (`java:/JmsXA` hoy).** `PublicadorPagoConfirmadoJms` está en `common` y usa la constante
  `DestinosJms.FABRICA_XA = "java:/JmsXA"`, que es global: con ella todos los WAR comparten identidad. Propuesta:
  que la constante pase a un nombre del módulo (`java:comp/env/jms/FabricaPalco`) y que cada WAR lo apunte a su
  fábrica en su `web.xml` con un `resource-ref` cuyo `lookup-name` sea `java:/jms/fabrica/ventas` (o
  `.../pruebas` en Notificaciones, para `PruebasDeMensajeria`). **Toca `backend/common`**: hay que acordarlo con
  quien lo mantiene. La clave no aparece en el código: la tiene la fábrica.
- **MDB.** La guía de WildFly 41 (7.15.3) dice que una `pooled-connection-factory` es la fachada de los conectores
  de entrada y de salida del adaptador de Artemis, que un MDB puede usar una distinta con `@ResourceAdapter`, y que
  la fábrica "can be configured to use specific security credentials via the user and password attributes". En
  7.15.13 muestra la forma: `@ResourceAdapter("nombre-de-la-fabrica")` (`org.jboss.ejb3.annotation`). Entonces
  `EmisionDeEntradasMDB` llevaría `@ResourceAdapter("palco-ventas")`, y `AvisoDeCompraMDB` y
  `AuditoriaDeComprasMDB`, `@ResourceAdapter("palco-notificaciones")`. Hace falta la dependencia
  `org.jboss.ejb3:jboss-ejb3-ext-api` con `scope` `provided`.
- **Sin comprobar todavía:** que el conector de entrada del MDB use el `user` y `password` de la fábrica. La guía
  lo menciona para el caso de un servidor remoto. Si no los usa, la otra vía son las propiedades de activación
  `user` y `password` del MDB, puestas en `jboss-ejb3.xml` para no escribir la clave en una anotación: WildFly no
  reemplaza expresiones en anotaciones (`annotation-property-replacement`, `default => false`) pero sí en los
  descriptores propios de JBoss (`jboss-descriptor-property-replacement`, `default => true`); los dos datos salen de
  `read-resource-description` del subsistema `ee`.

## 3. Cómo se verificaría

1. **Prueba del criterio (nueva).** Un endpoint de desarrollo en Notificaciones (rol `servicio`) que intenta enviar a
   `cola.emision-entradas` con la fábrica `palco-notificaciones` y responde 403 si recibe
   `JMSSecurityRuntimeException`; un escenario 14 en `scripts/pruebas/escenarios.mjs` lo llama y espera el 403, y
   comprueba que el contador de la cola no subió. "Queda registrado": el endpoint registra el intento rechazado en
   el log del servidor. Si además se quiere el registro del propio broker, hay que ver qué emite Artemis en
   WildFly 41 ante un envío sin permiso; no se comprobó.
2. **Que nada se rompa.** Los 13 escenarios de `scripts/pruebas/escenarios.mjs` contra el servidor rearmado: el 1
   y el 7 cubren el publicador de Ventas, el 8 y el 10 el MDB de emisión, el 9 y el 11 los dos suscriptores del
   tópico (el 11 la suscripción durable), y el 12 y el 13 los endpoints de prueba con `palco-pruebas`. Más la
   colección de Postman con Newman.
3. **Tests unitarios.** `mvn -q test` tiene que seguir en verde, pero `EmisionDeEntradasMDBTest`,
   `AvisoDeCompraMDBTest`, `AuditoriaDeComprasMDBTest` y `PruebasDeMensajeriaTest` usan objetos simulados
   (Mockito): no pasan por el broker y **no prueban los permisos**. La evidencia de SEC-I02 sale del punto 1.
4. Revisión de `configurar.cli` y una lectura de `security-setting` con `jboss-cli` en el servidor rearmado.

## 4. Riesgos para la Entrega Parcial N.º 2 (23/10)

- Aplicarlo exige **rearmar el servidor** (`mvn clean install` con WildFly detenido): se borran los mensajes del
  broker y, con H2 en memoria, los datos. Hay que hacerlo en todas las máquinas del grupo.
- Toca `backend/common` (fábrica del publicador), Ventas y Notificaciones (`@ResourceAdapter`, `web.xml`),
  `dev.sh` y el `.env` de cada integrante. Un error de configuración no da una excepción visible al comprar: el
  MDB no se conecta y **las entradas quedan sin emitir**, justo lo que se muestra en la demo.
- Queda un punto sin comprobar (credenciales en el conector de entrada) que solo se resuelve probando.
- A una semana del checkpoint, el riesgo de romper la demo es mayor que el de dejar el broker como está.

## 5. Alternativa recomendada: riesgo aceptado para el 23/10, aplicar para el 13/11

Para el 23/10, registrar SEC-I02 como **riesgo aceptado** con este texto:

> El broker JMS corre dentro del mismo WildFly y lo usan solo los servicios desplegados en él, por el conector
> `in-vm`. Hoy cualquier servicio puede publicar en cualquier destino (rol `guest` en `#`). El riesgo es que un
> servicio comprometido o con un error publique en un destino ajeno, por ejemplo una emisión de entradas falsa. Se
> acepta para el checkpoint del 23/10 porque el servidor escucha solo en `127.0.0.1` y no hay otros clientes del
> broker; la corrección (un usuario por servicio y permisos por destino) está planificada para el 13/11 en
> `docs/planes/T4-seguridad-del-broker.md`.

Para el 13/11 (con defensa oral), aplicarlo en una rama `feat/servidor-seguridad-broker`, idealmente cerrada antes
del 06/11 para dejar margen, empezando por comprobar el punto abierto de la sección 2 en un servidor rearmado
aparte del que usa el grupo.

## 6. A decidir por el grupo

1. Riesgo aceptado para el 23/10 y aplicación para el 13/11, o aplicarlo ya.
2. Identidad `palco-pruebas` o mover los endpoints de prueba a Ventas.
3. El cambio de `DestinosJms.FABRICA_XA` en `backend/common` (lo tiene que aprobar quien mantiene `common`).
4. Si `RemoteConnectionFactory` se borra (propuesta) o se deja.
