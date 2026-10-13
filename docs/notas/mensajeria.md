# A2 — Mensajería: `PAGO_CONFIRMADO` por cola y tópico

Nota para la defensa oral. Dueño de la tarea: Julian Zuchowicki. Cerrada y probada en vivo el 13/10/2026.

## Qué se construyó

Cuando se confirma un pago, Ventas publica el mensaje `PAGO_CONFIRMADO` en el broker (el servidor de mensajes, ActiveMQ Artemis, que viene dentro de WildFly). Lo manda a una cola, que entrega cada mensaje a un solo consumidor (la emisión de entradas, de la tarea A1), y a un tópico, que entrega una copia a cada suscriptor.

- **En `common`, paquete `mensajeria`:** el mensaje (`PagoConfirmadoMensaje`) con su validación, los nombres de los destinos (`DestinosJms`) y el publicador (`PublicadorPagoConfirmadoJms`).
- **En `notificaciones`:** dos MDB (Message-Driven Bean, el componente que consume mensajes) suscriptos al tópico. `AvisoDeCompraMDB` escribe en el log el aviso al comprador y `AuditoriaDeComprasMDB` guarda una fila por mensaje en `registro_auditoria`. Hay además tres endpoints de prueba y `GET /api/notificaciones/auditoria`, todos con rol `servicio`.
- **En `configurar.cli`:** un mensaje que falla se entrega hasta 5 veces, con 2 segundos entre una y otra, y después pasa a la DLQ (la cola de mensajes fallidos).

## Decisiones: qué se eligió, contra qué alternativa y por qué

1. **El mensaje es un `record` con `timestamp` de tipo `Instant`, y `desdeJson()` siempre valida.** La alternativa era una clase con setters, o la fecha como texto. Es un dato que no cambia, y JSON-B (el conversor entre objetos y JSON) no avisa si falta un campo: lo deja en `null` o en 0.
2. **El publicador es `@Stateless`, con transacción `MANDATORY`, y manda por `java:/JmsXA`.** Con `REQUIRED` abriría una transacción propia y el mensaje saldría aunque la venta fallara después.
3. **La base no es XA y entra a la transacción como último recurso: no es un commit en dos fases.** Las alternativas eran una base XA (sin probar contra Supabase) o una tabla de salida con un proceso que publica (más código). Por eso los consumidores toleran una entrega repetida.
4. **Aviso y auditoría son dos MDB con dos interfaces (`INotificaciones` e `IAuditoria`) en el mismo WAR.** Una sola interfaz mezclaba responsabilidades, y un WAR aparte sumaba un noveno despliegue.
5. **Auditoría es durable y el aviso no.** El broker le guarda los mensajes en una cola con nombre fijo (`clientId` `palco-auditoria` más `subscriptionName` `compras-confirmadas`) mientras el MDB está detenido. Auditoría no puede perder eventos; un aviso viejo no le sirve a nadie. Usa una sola sesión (`maxSession` en 1): procesa los mensajes de a uno, en el orden en que el broker los entrega. Las 15 por defecto son más rápidas, pero insertarían en cualquier orden. Ese orden no queda guardado: cada fila lleva solo la hora en que se registró, truncada a segundos.
6. **La clave de `registro_auditoria` es el `JMSMessageID`.** Ese id no cambia cuando el broker reentrega: la reentrega encuentra la fila y no inserta. Una clave única por `venta_id` escondería que Ventas publicó dos veces. El `INSERT` se fuerza con `em.flush()` dentro de `registrar`: si la base lo rechaza, la falla sale ahí y el MDB no llega a escribir en el log que procesó el mensaje.
7. **Ante un mensaje inválido, auditoría llama a `setRollbackOnly()` y el aviso lo descarta con un `WARNING`.** Lanzar una excepción dejaba una traza por intento, y reintentar en los dos dejaba dos copias en la DLQ.
8. **5 entregas cada 2 segundos, para todas las direcciones.** De fábrica son 10 seguidas y sin espera: una falla pasajera, como la base que tarda, agotaría los intentos en un instante.
9. **Los endpoints de prueba piden rol `servicio` y usan el publicador real.** Con un rol humano un organizador podría publicar pagos falsos, y un interruptor por variable de entorno obligaba a reiniciar el servidor.

## Qué quedó pendiente o simulado y por qué

- **El aviso es una línea en el log, no un email.** El mensaje trae `compradorId` y no el email: para el 13/11 hay que pedírselo a Usuarios. `enviarRecordatorio` y `enviarAlertaCancelacion` todavía no existen.
- **Al 13/10 ninguna compra real publica:** eso es de A1. Los mensajes salen de los endpoints de prueba, con un `ventaId` que empieza con `prueba-`.
- **No se probó en vivo:** una caída del servidor entre el commit de la base y el del broker, la reentrega de un mensaje válido y un `INSERT` rechazado por la base (los dos tienen test, con dobles), y el SQL contra PostgreSQL.
- **Un JSON cortado puede dejar colgado a un consumidor.** `PagoConfirmadoMensaje.desdeJson` no termina si el texto se corta dentro de un campo que el mensaje no conoce (por ejemplo `{"a":{`): la librería que lee el JSON queda en un bucle en lugar de dar error. Se reprodujo fuera del servidor, con los mismos jar (Yasson 3.0.4 y Parsson 1.1.9). Hoy nadie publica un texto así: Ventas manda `aJson()` y la prueba, un texto fijo. La corrección va en `common`, porque `desdeJson` la usan los tres MDB: leer antes el texto con JSON-P, que para esos textos da error al instante. Está pedida y todavía no se hizo.
- **El listado de auditoría no ordena dentro del mismo segundo.** `recibido_en` se guarda truncada a segundos y el desempate por `mensajeId` solo sirve para que la respuesta sea siempre la misma. Se nota cuando auditoría estuvo detenida: al volver registra lo pendiente de corrido, y varias filas quedan con la misma hora (en la prueba del suscriptor detenido, las dos). Guardar el orden real pide una columna más, en una migración V2.
- **Las dos líneas de `configurar.cli` no corrieron en un armado limpio**, porque exige detener WildFly. Se aplicaron con `jboss-cli` sin reiniciar y siguieron vigentes después de un reinicio.
- **La suscripción durable se crea sin dar permisos**, porque las conexiones internas del servidor saltean ese control. Con un broker externo habría que darle `create-durable-queue` al rol `guest`.
- **El DNI del titular viaja completo** y queda en la columna `cuerpo`; la consulta de auditoría no lo devuelve. La decisión es de la Fase 12.
- **Cada prueba deja restos:** una copia en `cola.emision-entradas`, que el MDB de Ventas tiene que descartar, y cada inválido, un mensaje en la `DLQ`. Antes de una demo se vacían con `remove-messages`.

## Las cinco preguntas más probables de la cátedra

1. **¿Por qué una cola y además un tópico?** La cola entrega cada mensaje a un solo consumidor, que es lo que hace falta para emitir las entradas una sola vez. El tópico entrega una copia a cada suscriptor: aviso y auditoría reciben lo mismo sin conocerse, y sumar un tercero no obliga a tocar Ventas.
2. **¿Qué pasa si Notificaciones está caído cuando alguien compra?** La compra no se entera, porque Ventas publica y sigue. Al volver, auditoría recibe todo lo pendiente, porque su suscripción es durable; el aviso de ese rato se pierde, y es una decisión. Lo probamos deteniendo solo ese MDB: el broker guardó los 2 mensajes y, al reanudarlo, aparecieron las dos filas.
3. **¿Qué pasa si un consumidor falla al procesar un mensaje?** La transacción del MDB se deshace y el mensaje vuelve al broker, que lo reentrega hasta 5 veces, cada 2 segundos, y después lo pasa a la DLQ, donde queda para revisarlo. Como una reentrega puede repetir un mensaje ya procesado, auditoría no inserta dos veces el mismo `JMSMessageID`.
4. **¿Cómo evitan que salga el mensaje de una venta que falló? ¿Es un commit en dos fases?** No: es una transacción JTA declarativa. El publicador exige la transacción de quien lo llama, así que el mensaje sale recién con el commit. Con una excepción no queda ni dato ni mensaje: el endpoint con falla responde 409 y los contadores del broker no cambian. Si el servidor se cae justo entre los dos commits puede quedar una venta confirmada sin mensaje; ese caso no lo probamos.
5. **¿Por qué todo es stateless, y dónde queda el estado?** Ni los MDB ni los Session Beans recuerdan nada entre un mensaje y el siguiente. Lo que tiene que durar queda en la base (las filas de auditoría) o en el broker (los mensajes pendientes), y por eso el servidor atiende con un pool de instancias.

## Cómo probarlo a mano

Con WildFly levantado, desde la raíz del repo. Los 22 tests del módulo corren con `mvn -B -f backend/pom.xml -pl servicios/notificaciones package` (JDK 25). En Git Bash:

```bash
N=localhost:8080/api/notificaciones; L=backend/servidor/target/server/standalone/log/server.log
T=$(curl -s -X POST -H 'Content-Type: application/json' -d '{"secreto":"palco-desarrollo"}' \
  localhost:8080/api/usuarios/servicios/token | sed -E 's/.*"token":"([^"]+)".*/\1/')
A="Authorization: Bearer $T"                                # el token dura 5 minutos: ante un 401, pedir otro
curl -s -o /dev/null -w '%{http_code}\n' -X POST $N/pruebas/pago-confirmado   # 401: sin token no publica nadie
curl -s -X POST -H "$A" $N/pruebas/pago-confirmado          # 1) 202 y el mensaje: copiar su ventaId (prueba-xxxxxxxx)
grep prueba-xxxxxxxx $L                                     #    tres líneas INFO: el aviso, AvisoDeCompra y AuditoriaDeCompras
curl -s -H "$A" $N/auditoria                                #    trae la fila de ese ventaId
curl -s -i -X POST -H "$A" $N/pruebas/pago-confirmado-con-falla   # 2) 409: no aparece ni una línea ni una fila nueva
curl -s -i -X POST -H "$A" $N/pruebas/mensaje-invalido            # 3) 202; esperar 10 segundos
grep -E 'inválido|AMQ222149' $L | tail -7                   #    1 WARNING del aviso, 5 de auditoría cada 2 s y el pase a la DLQ
```

En PowerShell (en Git Bash la barra inicial de cada operación se convierte en una ruta):

```powershell
$env:NOPAUSE = 'true'; $env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-25"   # sin NOPAUSE, jboss-cli espera una tecla
$cli = "backend\servidor\target\server\bin\jboss-cli.bat"; $srv = "/subsystem=messaging-activemq/server=default"
$mdb = "/deployment=palco-notificaciones.war/subsystem=ejb3/message-driven-bean=AuditoriaDeCompras"
$pend = "$srv/jms-topic=topico.compra-confirmada:count-messages-for-subscription(client-id=palco-auditoria,subscription-name=compras-confirmadas)"
& $cli --connect --command="$srv/address-setting=#:read-resource(include-defaults=true)"   # max-delivery-attempts 5 y redelivery-delay 2000
& $cli --connect --command="$srv/jms-queue=DLQ:count-messages"   # uno más por cada mensaje inválido
& $cli --connect --command="${mdb}:stop-delivery"                # 4) después, dos POST pago-confirmado: hay aviso y no hay fila
& $cli --connect --command="$pend"                               #    2: el broker los guarda
& $cli --connect --command="${mdb}:start-delivery"               #    GET auditoria suma las dos filas
& $cli --connect --command="/subsystem=datasources/data-source=PalcoDS:flush-all-connection-in-pool"   # 5) otro POST: la fila aparece igual
```

Un servidor armado antes de este cambio no tiene los reintentos, porque `configurar.cli` corre solo al armarlo. Se le aplican así, sin reiniciar:

```powershell
& $cli --connect --command="$srv/address-setting=#:write-attribute(name=max-delivery-attempts,value=5)"
& $cli --connect --command="$srv/address-setting=#:write-attribute(name=redelivery-delay,value=2000)"
```
