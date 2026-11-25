# Contrato de mensajería: `PAGO_CONFIRMADO`

Documenta el mensaje que publica `ServicioDeVentas` cuando se aprueba un pago,
según el taller de escenario de mensajería.

**Actualización del 23/10/2026:** se sumó el evento `REVENTA_CONFIRMADA` (ver más abajo).

**Actualización del 24/11/2026:** el titular de cada entrada viaja sin su DNI y la auditoría ya no lo guarda
(DEF-37, ver [Datos personales](#datos-personales)).

**Estado al 25/11/2026.** El mensaje, su validación, las constantes con los
nombres y el publicador están en `backend/common`, con tests. Ventas publica el
mensaje en cada pago aprobado y los cuatro consumidores funcionan:
`EmisionDeEntradasMDB` (cola, en Ventas), `AvisoDeCompraMDB` y
`AuditoriaDeComprasMDB` (tópico, en Notificaciones) y `FacturacionDeComprasMDB`
(tópico, en Facturación). Cada garantía de más abajo dice con qué se prueba. Una
caída del servidor entre el commit de la base y el del broker es el caso que la
base como último recurso no cubre: está explicado en "Garantías".

Evidencia: los escenarios 7 a 15 y 17 de `scripts/pruebas/escenarios.mjs`, que
corre el job "Integración" de `.github/workflows/ci.yml` en cada push (en verde
sobre `12a08f2`, el 25/11); su salida del 19/11, todos en verde, en
`docs/testing/evidencia/checkpoint-3/escenarios.txt`; la evidencia de la Entrega
Parcial N.º 2 en `docs/entrega3/evidencia/`, y el broker apagado y el contenedor
recreado con un mensaje pendiente en `docs/testing/evidencia/checkpoint-3/broker-apagado.txt`
(10 de 10) y `broker-recreado.txt` (16 de 16).

## Cuándo se publica

Cuando el pago de una venta queda aprobado. El mismo mensaje se publica en dos
canales, porque se usan para cosas distintas:

```
ServicioDeVentas ──► cola.emision-entradas ──► EmisionDeEntradasMDB (Ventas, consumidor único)
                 └─► topico.compra-confirmada ─┬► AvisoDeCompraMDB (Notificaciones, no durable)
                                               ├► AuditoriaDeComprasMDB (Notificaciones, durable)
                                               └► FacturacionDeComprasMDB (Facturación, durable)
```

| Canal                      | Tipo   | Nombre JNDI                        | Consumidores                                  | Para qué                                    |
| -------------------------- | ------ | ---------------------------------- | --------------------------------------------- | ------------------------------------------- |
| `cola.emision-entradas`    | Cola   | `java:/jms/queue/emisionEntradas`  | Emisión de entradas (MDB), uno solo por mensaje | Emitir las entradas exactamente una vez     |
| `topico.compra-confirmada` | Tópico | `java:/jms/topic/compraConfirmada` | Aviso al comprador (no durable), Auditoría (durable), Facturación (durable) | Que todos los interesados se enteren del hecho |

Los dos destinos están creados en `backend/servidor/src/main/wildfly/configurar.cli`.

### Segundo tipo de evento: `REVENTA_CONFIRMADA` (desde el 23/10/2026)

Cuando se compra una entrada en la reventa oficial, Ventas (`ReventaDeEntradas.transferir`)
publica `REVENTA_CONFIRMADA` **solo en el tópico**, en la misma transacción en la que crea la
venta nueva del comprador y emite su entrada. No va a la cola: la entrada ya quedó emitida en
la transferencia. El cuerpo es el mismo que el de `PAGO_CONFIRMADO`, con la venta nueva.

```
ReventaDeEntradas (Ventas) ──► topico.compra-confirmada ─┬► AvisoDeCompraMDB (aviso "en la reventa oficial")
                                                         ├► AuditoriaDeComprasMDB (fila con tipo_evento REVENTA_CONFIRMADA)
                                                         └► FacturacionDeComprasMDB (factura de la venta nueva)
```

Es el uso de `tipoEvento` que previó el taller de mensajería: que un mismo tópico agrupe más de
un tipo de evento y cada consumidor elija cuáles recibe con su selector.
Se publica siempre con la fábrica de conexiones transaccional, `java:/JmsXA`.

## Clases (módulo `common`, paquete `com.palco.common.mensajeria`)

| Clase | Qué es |
| ----- | ------ |
| `PagoConfirmadoMensaje` | El mensaje, como `record`. `aJson()` lo convierte a texto, `desdeJson(String)` lo lee y lo valida, `validar()` comprueba que esté completo |
| `MensajeInvalido` | La excepción que lanzan `desdeJson` y `validar` |
| `DestinosJms` | Las constantes con los nombres JNDI, la propiedad `tipoEvento` y el selector |
| `PublicadorPagoConfirmado` | La interfaz para publicar: `publicar(mensaje)` manda `PAGO_CONFIRMADO` a la cola y al tópico; `publicarReventa(mensaje)` manda `REVENTA_CONFIRMADA` solo al tópico |
| `PublicadorPagoConfirmadoJms` | Su implementación: un Session Bean `@Stateless` con transacción `MANDATORY` |

## Formato

- Tipo de mensaje JMS: `TextMessage` con el cuerpo en JSON, con entrega persistente.
- El cuerpo es **el mismo** en la cola y en el tópico.
- Serialización con JSON-B, que escribe los campos en orden alfabético.

### Propiedades del mensaje

| Propiedad    | Valor                | Dónde se publica | Quién lo recibe |
| ------------ | -------------------- | ---------------- | --------------- |
| `tipoEvento` | `PAGO_CONFIRMADO`    | Cola y tópico    | La emisión (`tipoEvento = 'PAGO_CONFIRMADO'`) y los tres suscriptores del tópico |
| `tipoEvento` | `REVENTA_CONFIRMADA` | Solo el tópico   | Los tres suscriptores del tópico |

Los suscriptores del tópico filtran con
`messageSelector = "tipoEvento IN ('PAGO_CONFIRMADO', 'REVENTA_CONFIRMADA')"`
(`DestinosJms.SELECTOR_COMPRA_CONFIRMADA`) y leen la propiedad con `DestinosJms.tipoEventoDe`.
Un mensaje sin un `tipoEvento` conocido es inválido.

Cambiar el selector de una suscripción durable hace que el broker la borre y la vuelva a crear
(así lo define JMS): los mensajes que estuvieran pendientes en ella se pierden. Por eso un cambio
de selector se despliega con las suscripciones al día (sin pendientes), como el del 23/10.

### Cuerpo

| Campo                     | Tipo     | Descripción                                   |
| ------------------------- | -------- | --------------------------------------------- |
| `ventaId`                 | string   | Id de la venta                                |
| `compradorId`             | string   | Id del usuario que compró (el claim `sub` de su token) |
| `eventoId`                | string   | Id del evento                                 |
| `entradas`                | array    | Una por entrada de la venta, entre 1 y 6      |
| `entradas[].entradaId`    | string   | Id de la entrada                              |
| `entradas[].sector`       | string   | Nombre del sector de la entrada, no su id     |
| `entradas[].titular`      | objeto   | Titular nominativo de la entrada, sin su DNI  |
| `entradas[].titular.nombre` | string | Nombre y apellido                             |
| `montoTotal`              | número   | Total cobrado con el cargo de servicio, en pesos enteros (`long`) |
| `timestamp`               | string   | Instante de la confirmación, en UTC y truncado a segundos: `2026-10-16T21:30:00Z` |

Los ids son los reales del sistema: un prefijo, un guion y 8 caracteres de un
UUID (`V-` para ventas, `T-` para entradas), de hasta 40 caracteres. Los de
usuarios y eventos conservan el formato que ya tenían (`user-…`, `ev-…`).

Quien lee acepta también un `timestamp` con otra zona (`2026-10-16T18:30:00-03:00`)
y lo interpreta como el mismo instante; quien publica siempre escribe `Z`.
Un campo que el lector no conoce se ignora, así que agregar campos no rompe a
los consumidores.

### Ejemplo

El test `PagoConfirmadoMensajeTest` lee este bloque del archivo: si el ejemplo
y la clase dejan de coincidir, el build falla.

```json
{
  "compradorId": "user-titular",
  "entradas": [
    { "entradaId": "T-9c41d07e", "sector": "Platea B", "titular": { "nombre": "Ana Gómez" } },
    { "entradaId": "T-51b7a2f0", "sector": "Platea B", "titular": { "nombre": "Luis Pérez" } }
  ],
  "eventoId": "ev-1",
  "montoTotal": 60500,
  "timestamp": "2026-10-16T21:30:00Z",
  "ventaId": "V-3f9a1c2b"
}
```

### Qué se rechaza

`desdeJson` lanza `MensajeInvalido` si el texto es nulo, está vacío, es `null`
o no es JSON, y también si el mensaje no pasa `validar()`, que exige:

- `ventaId`, `compradorId`, `eventoId` y cada `entradaId` no vacíos y de hasta 40 caracteres;
- entre 1 y 6 entradas (`ReglasDeNegocio.MAX_ENTRADAS_POR_ORDEN`), ninguna nula;
- cada titular con nombre (el DNI ya no se lee: si un mensaje viejo lo trae, no se controla);
- `montoTotal` mayor que 0;
- `timestamp` presente.

El publicador llama a `validar()` antes de mandar: un mensaje incompleto no
llega al broker.

## Garantías

- **Una sola transacción para confirmar y publicar.** Confirmar la venta, crear
  las entradas y publicar el mensaje ocurren en una transacción JTA declarativa:
  `REQUIRED` en `ServicioDeVentas.confirmarCompra` y `MANDATORY` en el
  publicador, que por eso no puede publicar fuera de una transacción.
- **No es un commit en dos fases.** El broker participa como recurso XA. La base
  no es XA y participa como último recurso.
- **Si hay una excepción o falla el commit de la base**, no queda ni venta
  confirmada ni mensaje. Lo prueban el escenario 13 (una transacción que se
  deshace después de publicar: ningún mensaje en la cola, el tópico ni la DLQ)
  y el 14 (Ventas falla después del cobro: la venta sigue `EN_CHECKOUT`, no sale
  ningún mensaje y la conciliación la termina con ese mismo pago).
- **Si el servidor se cae entre el commit de la base y el del broker**, queda
  una venta `CONFIRMADA` con entradas `PENDIENTE_EMISION` y sin mensaje. Es un
  estado visible y es el costo de tener la base como último recurso en lugar de
  un datasource XA: la consigna pide transacciones declarativas, no un commit en
  dos fases, y la conciliación de pagos solo toma ventas `EN_CHECKOUT`. Una tabla
  de salida (*outbox*) lo cerraría (ADR-004 y sección 7.12 del documento único).
- **La transacción cubre solo ese paso.** Reservar el cupo es una llamada REST
  a Eventos que confirma en el acto, y el cobro es externo: ninguno de los dos
  se deshace con un rollback.
- **Un mensaje por pago**: un mensaje a la cola y una copia por suscripción del
  tópico, con el `tipoEvento` y el cuerpo de este contrato (escenario 7); el
  aviso y la auditoría reciben cada uno la suya (escenario 9).
- **Emisión idempotente**: si el broker reentrega el mensaje, las entradas no se
  emiten dos veces (escenario 8 y `EmisionEnH2Test`).
- **Consumidor detenido**: con la emisión detenida, el mensaje espera en la cola
  y la entrada se emite al volver (escenario 10, el del taller).
- **Reintentos y DLQ**: un mensaje que falla se entrega hasta 5 veces, con 2
  segundos entre una y otra, y después pasa a la cola de mensajes fallidos (`DLQ`)
  sin trabar a los demás (escenario 12).
- **Auditoría durable**: si el suscriptor de auditoría está detenido, al volver
  recibe los mensajes pendientes (escenario 11). El aviso al comprador es no
  durable: se acepta perder un aviso.
- **Entrega persistente**: un mensaje pendiente sobrevive a apagar el broker,
  que es apagar el servidor (`scripts/pruebas/broker-apagado.mjs`, que también
  corre en el job "Integración"), y en el compose también a recrear el
  contenedor, porque el journal va en el volumen `datos-servidor` (ADR-005).
- **Facturación una sola vez**: la compra se factura por el tópico, con una sola
  factura por venta aunque el mensaje llegue dos veces (escenario 15 y
  `FacturacionDeComprasMDBTest#elMismoMensajeEntregadoDosVecesNoFacturaDosVeces`).
  `REVENTA_CONFIRMADA` llega a los tres suscriptores (escenario 17).

## Datos personales

Desde el 24/11/2026 (DEF-37, criterio SEC-D05: minimizar los datos personales, Ley 25.326) cada titular viaja
solo con su nombre. Lo que ningún consumidor necesita no viaja, y el DNI no lo necesita ninguno:

| Consumidor | Qué usa del mensaje | DNI |
| ---------- | ------------------- | --- |
| `EmisionDeEntradasMDB` (Ventas) | `ventaId`: las entradas, con su titular, las lee de su base | No lo usa |
| `AvisoDeCompraMDB` (Notificaciones) | Comprador, venta, evento, monto y, de cada entrada, el sector y el nombre del titular | No lo usa: el email nombra a cada titular y nunca llevó su DNI |
| `AuditoriaDeComprasMDB` (Notificaciones) | Venta, comprador, evento, monto y el cuerpo | No lo usa |
| `FacturacionDeComprasMDB` (Facturación) | Venta, comprador, evento, monto y `timestamp` | No lo usa: la factura B a consumidor final no identifica al comprador, y el pedido del CAE a ARCA no tiene documento |

El DNI queda solo en Ventas, en la entrada que el titular presenta en la puerta. El nombre sigue viajando porque
el aviso lo usa. `REVENTA_CONFIRMADA` tiene el mismo cuerpo, así que vale lo mismo; los mensajes de la cancelación
(`mensajeria-cancelacion-de-eventos.md`) no llevan titulares.

- **Mensajes publicados antes.** Hasta el 23/11 el mensaje traía `entradas[].titular.dni`. Si queda alguno en la
  cola, en una suscripción durable o en la DLQ, los cuatro consumidores lo procesan igual: el campo se ignora como
  cualquier otro que el contrato no define, y su valor ya no se controla. Al revés no anda (un consumidor anterior
  al cambio rechazaría un mensaje sin DNI), pero no hace falta: todos los WAR salen del mismo build y se despliegan
  juntos.
- **Auditoría.** `ServicioDeAuditoria` guarda el cuerpo tal como llegó, menos el `dni` de cada titular: un mensaje
  publicado antes queda guardado igual que uno nuevo. Las filas que ya estaban las limpia, al desplegar, la
  migración V3 de Notificaciones (`V3__sacar_el_dni_de_la_auditoria.sql`). La API de auditoría sigue sin devolver
  el cuerpo.
- **Idempotencia.** No cambia: la emisión, la auditoría (por `JMSMessageID`) y la factura (por `ventaId`) se
  deciden con datos que el mensaje sigue trayendo.
