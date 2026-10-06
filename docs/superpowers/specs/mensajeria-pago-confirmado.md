# Contrato de mensajería — `PAGO_CONFIRMADO`

Documenta el mensaje que publica `ServicioDeVentas` cuando se aprueba un pago,
según el taller de escenario de mensajería. Cubre el pendiente de la Fase 6 del
[`TODO.md`](../TODO.md): *Documentar el contrato en `docs/`*.

## Cuándo se publica

Cuando el pago de una venta queda aprobado. El mismo mensaje se publica en dos
canales, porque se usan para cosas distintas:

```
ServicioDeVentas ──► cola.emision-entradas ──► Módulo de emisión (consumidor único)
                 └─► topico.compra-confirmada ─┬► ServicioDeNotificaciones
                                               └► Auditoría / Historial (durable)
```

| Canal                      | Tipo   | Consumidores                                  | Para qué                                    |
| -------------------------- | ------ | --------------------------------------------- | ------------------------------------------- |
| `cola.emision-entradas`    | Cola   | Módulo de emisión (MDB), uno solo por mensaje | Emitir las entradas exactamente una vez     |
| `topico.compra-confirmada` | Tópico | Notificaciones (no durable), Auditoría (durable) | Que todos los interesados se enteren del hecho |

Los dos destinos ya están creados en `backend/servidor/src/main/wildfly/configurar.cli`.

## Formato

- Tipo de mensaje JMS: `TextMessage` con el cuerpo en JSON.
- El cuerpo es **el mismo** en la cola y en el tópico.
- Serialización con JSON-B, con la clase del mensaje en el módulo `common`.

### Propiedades del mensaje

| Propiedad    | Valor             | Uso                                                               |
| ------------ | ----------------- | ----------------------------------------------------------------- |
| `tipoEvento` | `PAGO_CONFIRMADO` | Los MDB filtran con `messageSelector = "tipoEvento = 'PAGO_CONFIRMADO'"` |

### Cuerpo

| Campo                     | Tipo     | Descripción                                   |
| ------------------------- | -------- | --------------------------------------------- |
| `ventaId`                 | string   | Id de la venta                                |
| `compradorId`             | string   | Id del usuario que compró                     |
| `eventoId`                | string   | Id del evento                                 |
| `entradas`                | array    | Una por entrada de la venta                   |
| `entradas[].entradaId`    | string   | Id de la entrada                              |
| `entradas[].sector`       | string   | Sector de la entrada                          |
| `entradas[].titular`      | objeto   | Titular nominativo de la entrada              |
| `entradas[].titular.nombre` | string | Nombre y apellido                             |
| `entradas[].titular.dni`  | string   | DNI, 7 u 8 dígitos                            |
| `montoTotal`              | número   | Total cobrado, en pesos enteros (`long`)      |
| `timestamp`               | string   | Momento de la confirmación                    |

### Ejemplo

Los ids usan el formato de ejemplo del taller (`V-`, `U-`, `E-`, `T-`).

```json
{
  "ventaId": "V-00231",
  "compradorId": "U-00789",
  "eventoId": "E-00042",
  "entradas": [
    {
      "entradaId": "T-000981",
      "sector": "Platea B",
      "titular": { "nombre": "Martina Suárez", "dni": "30111222" }
    }
  ],
  "montoTotal": 30250,
  "timestamp": "2026-10-16T18:30:00-03:00"
}
```

## Garantías esperadas

- **Envío transaccional**: el mensaje se publica dentro de la misma transacción
  JTA que guarda la venta, para que no quede una venta sin mensaje ni al revés.
- **Emisión idempotente**: si el broker reentrega el mensaje, las entradas no se
  emiten dos veces.
- **Reintentos y DLQ**: un mensaje que falla se reintenta y, si sigue fallando,
  termina en la cola de mensajes fallidos.
- **Auditoría durable**: si el suscriptor de auditoría está caído, al volver
  recibe los mensajes pendientes. Notificaciones es no durable: se acepta perder
  un aviso.

## Pendientes

- Formato de ids: definir si `V-00231` y similares son los ids reales o códigos
  públicos aparte del id interno (Fase 1).
- DNI en el tópico: definir si viaja completo o solo los ids (Fase 12).
- Formato exacto de `timestamp`.
