# ADR-003 · Adapter (y Factory) para ARCA, el sistema SOAP legado

**Estado:** Aceptada · implementada.

**Contexto.** Facturación pide el CAE a ARCA, un sistema legado que habla SOAP. En el TP ARCA es un servicio SOAP simulado en un WAR aparte (`/legado/arca`, WSDL publicado por WildFly). ARCA puede tardar, rechazar o no estar.

**Decisión.** El negocio (`ServicioDeFacturacion`) depende de una interfaz propia, `Arca`. `ArcaSoap` la implementa: traduce el pedido a la interfaz anotada `PuertoArca`, la respuesta XML (`RespuestaCaeXml`) a un resultado del dominio y cualquier `WebServiceException` (tiempo vencido, conexión rechazada, SOAP Fault) a `SinRespuesta`. `FabricaDeArca` (`@Produces`) arma el cliente con los tiempos de espera de la configuración (2000 ms de conexión y 5000 ms de lectura por defecto) y se niega a crearlo si está habilitado el lector XML inseguro (XXE, criterio SEC-I01).

**Resiliencia.** Timeout de 5 s en el cliente. Si ARCA no responde o rechaza, el MDB de facturación hace `setRollbackOnly` y reintenta el broker: 5 entregas cada 2 s y después la DLQ. La compra ya está confirmada y no espera a la factura. No hay circuit breaker: con un solo consumidor (`maxSession` 1) y reintentos espaciados, una caída de ARCA no genera una avalancha de llamadas.

**Consecuencias.**
- (+) Si ARCA pasa de SOAP a REST, cambia una clase (`ArcaSoap`) y la fábrica: es la respuesta a la pregunta disparadora de la clase.
- (+) `ServicioDeFacturacion` se prueba con un doble de `Arca`, sin SOAP.
- (−) La interfaz `PuertoArca` se escribió a mano en lugar de generarla con `wsimport`: si cambia el WSDL hay que actualizarla.

**Alternativas descartadas.** Generar el cliente con `wsimport` (suma un paso al build y código generado). Llamar al SOAP desde `ServicioDeFacturacion` (el negocio dependería del protocolo). Una cola propia de reintento (no suma nada a la política del broker que ya usan los demás consumidores).
