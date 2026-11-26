# ADR-001 · Controlador de fachada para la compra (GRASP Controlador)

**Estado:** Aceptada · implementada.

**Fecha:** 19/11/2026 (revisada el 25/11/2026).

**Contexto.** Una compra involucra al componente stateful de la compra (`ServicioDeVentas`), dos repositorios, Usuarios (identidad verificada) y Eventos (reserva y devolución de cupo). Algunos pasos quedan fuera de la transacción del stateful y el orden importa: consultar a Usuarios antes de abrir la compra, anotar el intento de pago antes de cobrar, devolver el cupo después de confirmar la cancelación. Los pedidos llegan por REST (`VentasResource`, `EntradasResource`).

**Decisión.** Los recursos JAX-RS solo traducen HTTP: validan la forma del pedido (cuerpo, `Idempotency-Key`), sacan el comprador del token, delegan y arman la respuesta (código, `Location`, `_links`). El caso de uso lo coordina `ComprasEnCurso` (`@ApplicationScoped`), que es el controlador GRASP del caso de uso "comprar" y hacia afuera funciona como fachada del subsistema de la compra.

**Consecuencias.**
- (+) La regla "el Resource no tiene lógica" se cumple: `VentasResource` no tiene reglas de la compra.
- (+) El orden de los pasos y la regla de una operación a la vez por compra están en un solo lugar y se prueban sin HTTP (`ComprasEnCursoTest`).
- (−) `ComprasEnCurso` (575 líneas) y `ServicioDeVentas` (738) son las dos clases más grandes del backend: hay riesgo de God Class. Lo que no es parte de la conversación de una compra ya se separó (`UsoDeEntradas` para la puerta, `ReventaDeEntradas` para la reventa, `CancelacionDeEventos` y `AnulacionDeCompras` para la cancelación de un evento) y esa es la regla para lo que se sume.

**Alternativas descartadas.** Lógica en el Resource (mezcla HTTP con reglas y no se prueba sin servidor). Que el Resource hable directo con el stateful (el orden de pasos fuera de la transacción quedaría repartido entre recursos).
