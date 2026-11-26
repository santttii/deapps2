# ADR-004 · Publicación y suscripción para PAGO_CONFIRMADO (Observer entre servicios)

**Estado:** Aceptada · implementada.

**Fecha:** 19/11/2026 (revisada el 25/11/2026).

**Contexto.** Cuando se aprueba un pago pasan cuatro cosas: emitir las entradas (una sola vez), avisar al comprador, auditar y facturar. Ninguna hace falta para responderle al comprador que pagó.

**Decisión.** Ventas publica el evento `PAGO_CONFIRMADO` dentro de la transacción de la confirmación (`PublicadorPagoConfirmadoJms`, `MANDATORY`, `java:/JmsXA`) a dos destinos: la cola `cola.emision-entradas` (un consumidor: la emisión) y el tópico `topico.compra-confirmada` (aviso, auditoría durable y facturación durable). Cada consumidor filtra con un selector por `tipoEvento`. Los que escriben en su base (emisión, auditoría y facturación) son idempotentes; el aviso no escribe nada y no reintenta: si el email no sale, queda en el log.

**Consecuencias.**
- (+) Facturación se sumó como tercer suscriptor sin cambiar una línea de Ventas: el productor no conoce a sus observadores (OCP a nivel sistema).
- (+) El mensaje sale solo si la venta se confirma.
- (−) El broker puede entregar dos veces: cada consumidor que escribe en su base necesita su propia idempotencia (`UPDATE` condicional en la emisión, `JMSMessageID` en auditoría, restricción única en facturación).
- (−) Más piezas que operar: suscripciones durables y DLQ.

**Alternativas descartadas.** Que Ventas llame por REST a cada servicio durante la compra (acopla la compra a la disponibilidad de todos). Una tabla de salida (*outbox*), evaluada y descartada: era una tabla y un proceso más. Con `JmsXA` el mensaje no sale si la venta no se confirma; lo que no cubre es una caída del servidor justo entre el commit de la base, que entra como último recurso, y el del broker: la venta quedaría `CONFIRMADA` sin su mensaje (documento único, 7.4 y 7.12). Se acepta porque la consigna pide transacciones declarativas, no un commit en dos fases; una base XA o una tabla de salida lo cerrarían (documento único, 7.12).
