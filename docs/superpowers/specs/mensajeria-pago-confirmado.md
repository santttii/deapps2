# Contrato de mensajería — `PAGO_CONFIRMADO`

Documenta el mensaje que publica `ServicioDeVentas` cuando se aprueba un pago,
según el taller de escenario de mensajería. Cubre el pendiente de la Fase 6 del
[`TODO.md`](../../../TODO.md): *Documentar el contrato en `docs/`*.

**Estado al 14/10/2026.** El mensaje, su validación, las constantes con los
nombres y el publicador están en `backend/common`, con tests. Ventas publica el
mensaje en cada pago aprobado y los tres consumidores funcionan:
`EmisionDeEntradasMDB` (cola, en Ventas) y `AvisoDeCompraMDB` y
`AuditoriaDeComprasMDB` (tópico, en Notificaciones). La garantía transaccional
de más abajo está probada para la transacción que se deshace (el mensaje no
sale); la caída del servidor entre el commit de la base y el del broker no se
probó. Evidencia: `docs/entrega3/evidencia/`.

## Cuándo se publica

Cuando el pago de una venta queda aprobado. El mismo mensaje se publica en dos
canales, porque se usan para cosas distintas:

```
ServicioDeVentas ──► cola.emision-entradas ──► EmisionDeEntradasMDB (Ventas, consumidor único)
                 └─► topico.compra-confirmada ─┬► AvisoDeCompraMDB (Notificaciones, no durable)
                                               └► AuditoriaDeComprasMDB (Notificaciones, durable)
```

| Canal                      | Tipo   | Nombre JNDI                        | Consumidores                                  | Para qué                                    |
| -------------------------- | ------ | ---------------------------------- | --------------------------------------------- | ------------------------------------------- |
| `cola.emision-entradas`    | Cola   | `java:/jms/queue/emisionEntradas`  | Emisión de entradas (MDB), uno solo por mensaje | Emitir las entradas exactamente una vez     |
| `topico.compra-confirmada` | Tópico | `java:/jms/topic/compraConfirmada` | Aviso al comprador (no durable), Auditoría (durable) | Que todos los interesados se enteren del hecho |

Los dos destinos ya están creados en `backend/servidor/src/main/wildfly/configurar.cli`.
Se publica siempre con la fábrica de conexiones transaccional, `java:/JmsXA`.

## Clases (módulo `common`, paquete `com.palco.common.mensajeria`)

| Clase | Qué es |
| ----- | ------ |
| `PagoConfirmadoMensaje` | El mensaje, como `record`. `aJson()` lo convierte a texto, `desdeJson(String)` lo lee y lo valida, `validar()` comprueba que esté completo |
| `MensajeInvalido` | La excepción que lanzan `desdeJson` y `validar` |
| `DestinosJms` | Las constantes con los nombres JNDI, la propiedad `tipoEvento` y el selector |
| `PublicadorPagoConfirmado` | La interfaz para publicar: `void publicar(PagoConfirmadoMensaje mensaje)` |
| `PublicadorPagoConfirmadoJms` | Su implementación: un Session Bean `@Stateless` con transacción `MANDATORY` |

## Formato

- Tipo de mensaje JMS: `TextMessage` con el cuerpo en JSON, con entrega persistente.
- El cuerpo es **el mismo** en la cola y en el tópico.
- Serialización con JSON-B, que escribe los campos en orden alfabético.

### Propiedades del mensaje

| Propiedad    | Valor             | Uso                                                               |
| ------------ | ----------------- | ----------------------------------------------------------------- |
| `tipoEvento` | `PAGO_CONFIRMADO` | Los MDB filtran con `messageSelector = "tipoEvento = 'PAGO_CONFIRMADO'"` |

### Cuerpo

| Campo                     | Tipo     | Descripción                                   |
| ------------------------- | -------- | --------------------------------------------- |
| `ventaId`                 | string   | Id de la venta                                |
| `compradorId`             | string   | Id del usuario que compró (el claim `sub` de su token) |
| `eventoId`                | string   | Id del evento                                 |
| `entradas`                | array    | Una por entrada de la venta, entre 1 y 6      |
| `entradas[].entradaId`    | string   | Id de la entrada                              |
| `entradas[].sector`       | string   | Nombre del sector de la entrada, no su id     |
| `entradas[].titular`      | objeto   | Titular nominativo de la entrada              |
| `entradas[].titular.nombre` | string | Nombre y apellido                             |
| `entradas[].titular.dni`  | string   | DNI, 7 u 8 dígitos                            |
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
    { "entradaId": "T-9c41d07e", "sector": "Platea B", "titular": { "dni": "30123456", "nombre": "Ana Gómez" } },
    { "entradaId": "T-51b7a2f0", "sector": "Platea B", "titular": { "dni": "31234567", "nombre": "Luis Pérez" } }
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
- cada titular con nombre y con un DNI de 7 u 8 dígitos, sin puntos (`ReglasDeNegocio.esDniValido`,
  la misma regla que aplica el registro de usuarios);
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
  confirmada ni mensaje.
- **Si el servidor se cae entre el commit de la base y el del broker**, queda
  una venta `CONFIRMADA` con entradas `PENDIENTE_EMISION` y sin mensaje. Es un
  estado visible; el reenvío queda para más adelante (tarea C3). Este caso no
  se probó.
- **La transacción cubre solo ese paso.** Reservar el cupo es una llamada REST
  a Eventos que confirma en el acto, y el cobro es externo: ninguno de los dos
  se deshace con un rollback.
- **Emisión idempotente**: si el broker reentrega el mensaje, las entradas no se
  emiten dos veces.
- **Reintentos y DLQ**: un mensaje que falla se entrega hasta 5 veces, con 2
  segundos entre una y otra, y después pasa a la cola de mensajes fallidos (`DLQ`).
- **Auditoría durable**: si el suscriptor de auditoría está detenido, al volver
  recibe los mensajes pendientes. El aviso al comprador es no durable: se acepta
  perder un aviso.

## Pendientes

- DNI en el tópico: definir si viaja completo o solo los ids (Fase 12). Hoy viaja completo.
