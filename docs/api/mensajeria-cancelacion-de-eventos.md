# Contrato de mensajería — `EVENTO_CANCELADO` y `COMPRA_ANULADA`

Documenta los dos mensajes del segundo proceso asincrónico de Palco: la cancelación de un evento. El de la
compra (`PAGO_CONFIRMADO` y `REVENTA_CONFIRMADA`) está en
[`mensajeria-pago-confirmado.md`](mensajeria-pago-confirmado.md) y no cambia: los dos procesos usan destinos
distintos y no comparten consumidores.

## Cuándo se publica

Son dos hechos distintos, y por eso dos mensajes:

- **`EVENTO_CANCELADO`**, uno por evento. Lo publica Eventos (`ServicioDeEventos.cancelarEvento`) cuando el
  organizador dueño cancela el evento con `POST /api/eventos/{eventoId}/cancelacion`, en la misma transacción en
  que lo marca como cancelado. Lleva el evento y nada de las ventas: quién compró qué lo sabe Ventas.
- **`COMPRA_ANULADA`**, uno por compra. Lo publica Ventas (`CancelacionDeEventos.anular`) por cada compra pagada
  del evento, en la transacción en que la anula, después de pedirle el reembolso a Pagos. Es el que trae a quién
  avisarle y cuánto se le devolvió.

```
ServicioDeEventos ──► topico.eventos-cancelados ──► CancelacionDeEventoMDB (Ventas, durable)        EVENTO_CANCELADO
                                                      │ por cada compra pagada: la anula, anula sus entradas
                                                      │ y pide el reembolso (REST: POST /api/v1/pagos/{pagoId}/reembolso)
                                                      ▼
CancelacionDeEventos (Ventas) ──► topico.eventos-cancelados ─┬► AvisoDeCancelacionMDB (Notificaciones, no durable)
                                                             └► AuditoriaDeCancelacionesMDB (Notificaciones, durable)
                                                                                                    COMPRA_ANULADA
```

| Canal                       | Tipo   | Nombre JNDI                         | Mensajes                              | Para qué |
| --------------------------- | ------ | ----------------------------------- | ------------------------------------- | -------- |
| `topico.eventos-cancelados` | Tópico | `java:/jms/topic/eventosCancelados` | `EVENTO_CANCELADO` y `COMPRA_ANULADA` | Que cada interesado en la cancelación reciba su copia |

El tópico lo crea `backend/servidor/src/main/wildfly/configurar.cli`. Es un tópico y no una cola porque el mismo
hecho les interesa a varios consumidores, y es uno aparte de `topico.compra-confirmada` porque es otro proceso,
con otros suscriptores y otro cuerpo: si fueran al mismo, cada consumidor tendría que filtrar los tipos del otro.
Se publica siempre con la fábrica de conexiones transaccional, `java:/JmsXA`.

### Consumidores

| Consumidor                    | Servicio       | Recibe             | Suscripción | `clientId` / `subscriptionName`                    | Qué hace |
| ----------------------------- | -------------- | ------------------ | ----------- | -------------------------------------------------- | -------- |
| `CancelacionDeEventoMDB`      | Ventas         | `EVENTO_CANCELADO` | Durable     | `ventas-cancelaciones` / `ventas-cancelaciones`    | Anula las compras pagadas del evento y sus entradas, pide los reembolsos y publica un `COMPRA_ANULADA` por compra (`CancelacionDeEventos`) |
| `AvisoDeCancelacionMDB`       | Notificaciones | `COMPRA_ANULADA`   | No durable  | —                                                  | Le manda el email al comprador (`ServicioDeNotificaciones.enviarAlertaCancelacion`) |
| `AuditoriaDeCancelacionesMDB` | Notificaciones | `COMPRA_ANULADA`   | Durable, `maxSession = 1` | `palco-auditoria-cancelaciones` / `compras-anuladas` | Guarda una fila de auditoría por compra anulada (`ServicioDeAuditoria.registrarAnulacion`) |

`EVENTO_CANCELADO` lo consume solo Ventas. La auditoría anota las anulaciones y no la cancelación: su fila
necesita venta, comprador y monto, y `EVENTO_CANCELADO` no los trae; el rastro de una cancelación es el conjunto
de filas de sus compras. `DestinosJms.SELECTOR_CANCELACIONES` (los dos tipos) está definido, pero hoy ningún
consumidor lo usa.

Reventa no está suscripta al tópico (ver [Pendientes](#pendientes)).

## Clases

En el módulo `common`, paquete `com.palco.common.mensajeria`:

| Clase | Qué es |
| ----- | ------ |
| `EventoCanceladoMensaje` | El mensaje `EVENTO_CANCELADO`, como `record`. `aJson()` lo convierte a texto, `desdeJson(String)` lo lee y lo valida, `validar()` comprueba que esté completo |
| `CompraAnuladaMensaje` | El mensaje `COMPRA_ANULADA`, con los mismos tres métodos |
| `MensajeInvalido` | La excepción que lanzan `desdeJson` y `validar` (la misma de `PAGO_CONFIRMADO`) |
| `DestinosJms` | `TOPICO_CANCELACIONES`, los nombres `EVENTO_CANCELADO` y `COMPRA_ANULADA`, los selectores y `tipoDeCancelacionDe`, que lee y controla la propiedad `tipoEvento` |
| `PublicadorDeCancelaciones` | La interfaz para publicar: `publicarEventoCancelado(mensaje)` y `publicarCompraAnulada(mensaje)` |
| `PublicadorDeCancelacionesJms` | Su implementación: un Session Bean `@Stateless` con transacción `MANDATORY` |

Los consumidores están en el paquete `mensajeria` de cada servicio y delegan en su capa de negocio, como un
recurso REST: Ventas (`CancelacionDeEventoMDB` → `negocio/CancelacionDeEventos`) y Notificaciones
(`AvisoDeCancelacionMDB` → `INotificaciones`, `AuditoriaDeCancelacionesMDB` → `IAuditoria`).

## Formato

- Tipo de mensaje JMS: `TextMessage` con el cuerpo en JSON, con entrega persistente.
- Serialización con JSON-B, que escribe los campos en orden alfabético y **omite los que son null**: un evento
  cancelado sin motivo viaja sin el campo `motivo`.
- Los dos mensajes van al mismo tópico; los distingue la propiedad `tipoEvento`.

### Propiedades del mensaje

| Propiedad    | Valor              | Lo publica | Selector de quien lo recibe |
| ------------ | ------------------ | ---------- | --------------------------- |
| `tipoEvento` | `EVENTO_CANCELADO` | Eventos    | `tipoEvento = 'EVENTO_CANCELADO'` (`DestinosJms.SELECTOR_EVENTO_CANCELADO`): Ventas |
| `tipoEvento` | `COMPRA_ANULADA`   | Ventas     | `tipoEvento = 'COMPRA_ANULADA'` (`DestinosJms.SELECTOR_COMPRA_ANULADA`): aviso y auditoría |

El selector de Ventas es lo que evita que se escuche a sí misma: los `COMPRA_ANULADA` que publica van al mismo
tópico del que consume. Los dos consumidores de Notificaciones vuelven a comprobar la propiedad con
`DestinosJms.tipoDeCancelacionDe`, que acepta solo estos dos valores: un mensaje sin un `tipoEvento` conocido es
inválido.

Como en el tópico de compras, cambiar el selector de una suscripción durable hace que el broker la borre y la
vuelva a crear (así lo define JMS), y los mensajes pendientes en ella se pierden. Un cambio de selector se
despliega con las suscripciones sin pendientes.

### Cuerpo de `EVENTO_CANCELADO`

| Campo       | Tipo             | Descripción |
| ----------- | ---------------- | ----------- |
| `eventoId`  | string           | Id del evento cancelado |
| `titulo`    | string           | Título del evento, para el aviso: ni Ventas ni Notificaciones lo tienen |
| `motivo`    | string, opcional | Lo que escribió el organizador al cancelar, hasta 500 caracteres; no viaja si no dio motivo |
| `timestamp` | string           | Instante de la cancelación, en UTC con `Z` (`2026-11-05T14:20:31.512Z`): no se trunca a segundos |

### Cuerpo de `COMPRA_ANULADA`

| Campo         | Tipo             | Descripción |
| ------------- | ---------------- | ----------- |
| `ventaId`     | string           | Id de la venta anulada |
| `compradorId` | string           | Id del usuario que había comprado (el claim `sub` de su token). Notificaciones le pide el email a Usuarios con `GET /api/usuarios/{usuarioId}` |
| `eventoId`    | string           | Id del evento cancelado |
| `titulo`      | string           | Título del evento, copiado de `EVENTO_CANCELADO` |
| `motivo`      | string, opcional | El motivo de la cancelación, copiado de `EVENTO_CANCELADO` |
| `monto`       | número           | Lo que había pagado, con el cargo de servicio, en pesos enteros (`long`) |
| `reembolsado` | booleano         | Si Pagos ya devolvió ese monto. Puede ser false: la compra se anula igual (ver [Garantías](#garantías)) |
| `timestamp`   | string           | Instante de la anulación, en UTC con `Z`; tampoco se trunca |

Los ids tienen el formato real del sistema, hasta 40 caracteres: `V-` y 8 caracteres de un UUID para las ventas,
`user-…` para los usuarios y `ev-…` para los eventos. Un campo que el lector no conoce se ignora, así que agregar
campos no rompe a los consumidores.

### Ejemplos

`EVENTO_CANCELADO`:

```json
{
  "eventoId": "ev-6",
  "motivo": "Suspendido por lluvia.",
  "timestamp": "2026-11-05T14:20:31.512Z",
  "titulo": "Marea Baja + invitados"
}
```

`COMPRA_ANULADA`, uno por cada compra pagada de ese evento:

```json
{
  "compradorId": "user-titular",
  "eventoId": "ev-6",
  "monto": 33000,
  "motivo": "Suspendido por lluvia.",
  "reembolsado": true,
  "timestamp": "2026-11-05T14:20:31.874Z",
  "titulo": "Marea Baja + invitados",
  "ventaId": "V-3f9a1c2b"
}
```

### Qué se rechaza

`desdeJson` lanza `MensajeInvalido` si el texto es nulo, está vacío, es `null` o no es JSON, y también si el
mensaje no pasa `validar()`. Igual que en `PAGO_CONFIRMADO`, el texto se lee primero con JSON-P, porque con un
JSON cortado JSON-B puede no terminar nunca.

- `EVENTO_CANCELADO` exige `eventoId` no vacío y de hasta 40 caracteres, `titulo` no vacío y `timestamp` presente.
- `COMPRA_ANULADA` exige `ventaId`, `compradorId` y `eventoId` no vacíos y de hasta 40 caracteres, `titulo` no
  vacío, `monto` mayor que 0 y `timestamp` presente.
- `motivo` es opcional en los dos, y `reembolsado` no se controla.

El publicador llama a `validar()` antes de mandar: un mensaje incompleto no llega al broker.

## Garantías

- **Cancelar y publicar, en una sola transacción.** `ServicioDeEventos.cancelarEvento` corre en una transacción
  JTA declarativa (`REQUIRED`, la de un `@Stateless`) y el publicador es `MANDATORY`. Si la marca del evento se
  deshace, el mensaje no sale y nadie reembolsa; si publicar falla, el evento no queda cancelado. Valen las
  mismas condiciones que para `PAGO_CONFIRMADO`: el broker participa como recurso XA y la base como último
  recurso, no es un commit en dos fases.
- **Una transacción por compra.** Ventas recibe `EVENTO_CANCELADO` en una transacción (`REQUIRED` en `onMessage`),
  pero anula cada compra en la suya (`REQUIRES_NEW` en `CancelacionDeEventos.anular`): la venta pasa a
  `CANCELADA`, sus entradas `PENDIENTE_EMISION`, `VALIDA` y `PUBLICADA` pasan a `ANULADA` y se publica su
  `COMPRA_ANULADA`, todo junto. Un fallo en la última compra no deshace las anteriores. Las entradas `USADA` y
  `VENDIDA` no se tocan: con una ya entró alguien y la otra es de otro comprador, que tiene la suya.
- **El reembolso no bloquea la anulación.** Va antes de publicar, porque el aviso dice si el dinero volvió. Si Pagos
  no responde, la compra se anula igual, el mensaje sale con `reembolsado` en false y queda una advertencia en el
  log con el `pagoId`; el email le dice al comprador que la devolución está en trámite. El reembolso es una
  llamada REST que confirma en el acto: un rollback posterior no lo deshace, y Pagos devuelve un pago una sola vez.
- **Idempotencia.**
  - Eventos: cancelar un evento ya cancelado responde 409 `evento-cancelado`, así que no sale un segundo
    `EVENTO_CANCELADO`.
  - Ventas: la anulación es un `UPDATE` condicional (la venta tiene que seguir `CONFIRMADA` o `EMITIDA`). Si el
    broker reentrega `EVENTO_CANCELADO`, no cambia ninguna fila, no se pide otro reembolso y no se vuelve a
    publicar `COMPRA_ANULADA`.
  - Auditoría: la fila se guarda con el `JMSMessageID`; una reentrega del mismo mensaje no inserta otra.
- **Reintentos y DLQ.** Como en todas las colas y suscripciones del servidor, un mensaje que falla se entrega hasta
  5 veces, con 2 segundos entre una y otra, y después pasa a `java:/jms/queue/DLQ`. Ventas y la auditoría deshacen
  la transacción ante un mensaje inválido o un error, así que el broker reintenta; un reintento de Ventas solo
  retoma las compras que faltaban.
- **Durabilidad.** La suscripción de Ventas y la de la auditoría son durables: si el servicio está detenido, al
  volver recibe lo pendiente. El aviso al comprador es no durable, igual que el de la compra: si Notificaciones
  está caído ese email se pierde, y un mensaje que no se entiende se descarta con una advertencia en lugar de
  reintentarse. La copia que no se pierde es la de auditoría.

## Pendientes

- **Reembolsos sin respuesta.** Una compra anulada con `reembolsado` en false no tiene hoy un proceso que vuelva a
  pedir su reembolso: una reentrega de `EVENTO_CANCELADO` la encuentra ya `CANCELADA` y no la toca. Se resuelve a
  mano con el `pagoId` del log (`POST /api/v1/pagos/{pagoId}/reembolso`, rol `servicio`).
- **Reventa no escucha la cancelación.** Una publicación `ACTIVA` de una entrada que quedó `ANULADA` sigue en el
  listado. Si alguien la compra, Ventas rechaza la transferencia porque la entrada ya no está `PUBLICADA`: la
  publicación queda `ANULADA` y Reventa devuelve el cobro.
