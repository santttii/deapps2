# ADR-004 · Publicación y suscripción para PAGO_CONFIRMADO (Observer entre servicios)

**Estado:** Aceptada · implementada.

**Contexto.** Cuando se aprueba un pago pasan cuatro cosas: emitir las entradas (una sola vez), avisar al comprador, auditar y facturar. Ninguna hace falta para responderle al comprador que pagó.

**Decisión.** Ventas publica el evento `PAGO_CONFIRMADO` dentro de la transacción de la confirmación (`PublicadorPagoConfirmadoJms`, `MANDATORY`, `java:/JmsXA`) a dos destinos: la cola `cola.emision-entradas` (un consumidor: la emisión) y el tópico `topico.compra-confirmada` (aviso, auditoría durable y facturación durable). Cada consumidor filtra con un selector por `tipoEvento`. Los que escriben en su base (emisión, auditoría y facturación) son idempotentes; el aviso no escribe nada y no reintenta: si el email no sale, queda en el log.

**Consecuencias.**
- (+) Facturación se sumó como tercer suscriptor sin cambiar una línea de Ventas: el productor no conoce a sus observadores (OCP a nivel sistema).
- (+) El mensaje sale solo si la venta se confirma.
- (−) El broker puede entregar dos veces: cada consumidor que escribe en su base necesita su propia idempotencia (`UPDATE` condicional en la emisión, `JMSMessageID` en auditoría, restricción única en facturación).
- (−) Más piezas que operar: suscripciones durables y DLQ.

**Alternativas descartadas.** Que Ventas llame por REST a cada servicio durante la compra (acopla la compra a la disponibilidad de todos). Una tabla de salida (*outbox*), evaluada y descartada: la publicación transaccional con `JmsXA` cubre el caso "guardé pero no publiqué" sin una tabla y un relay más.
