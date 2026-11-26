# ADR-003 · Adapter (y Factory) para ARCA, el sistema SOAP legado

**Estado:** Aceptada · implementada. Revisada el 20/11 (DEF-33): un comprobante rechazado ya no se reintenta.

**Fecha:** 19/11/2026 (revisada el 24/11/2026).

**Contexto.** Facturación pide el CAE a ARCA, un sistema legado que habla SOAP. En el TP ARCA es un servicio SOAP simulado en un WAR aparte (`/legado/arca`, WSDL publicado por WildFly). ARCA puede tardar, rechazar o no estar.

**Decisión.** El negocio (`ServicioDeFacturacion`) depende de una interfaz propia, `Arca`. `ArcaSoap` la implementa: traduce el pedido a la interfaz anotada `PuertoArca`, la respuesta XML (`RespuestaCaeXml`) a un resultado del dominio y cualquier `WebServiceException` (tiempo vencido, conexión rechazada, SOAP Fault) a `SinRespuesta`. `FabricaDeArca` (`@Produces`) arma el cliente con los tiempos de espera de la configuración (2000 ms de conexión y 5000 ms de lectura por defecto) y se niega a crearlo si está habilitado el lector XML inseguro (XXE, criterio SEC-I01).

**Resiliencia.** Timeout de 5 s en el cliente. La compra ya está confirmada y no espera a la factura. Las dos fallas de ARCA se tratan distinto, porque reintentar sirve solo para una:
- **ARCA no responde** (`SinRespuesta`): no se guarda nada y el MDB de facturación (`FacturacionDeComprasMDB`) hace `setRollbackOnly`. El broker reintenta: 5 entregas cada 2 s (`configurar.cli`) y después la DLQ, de donde el mensaje se devuelve con el procedimiento de la sección 10.6 del documento único. Otro intento puede andar.
- **ARCA rechaza el comprobante** (`Resultado` R, que `ArcaSoap` traduce a `ReglaIncumplida` `comprobante-rechazado`): el pedido sale del mensaje y es el mismo en cada intento, así que ARCA lo rechazaría las cinco veces. Desde el 20/11 (DEF-33), `ServicioDeFacturacion` anota el rechazo, con lo que se pidió y el motivo, en `facturacion.comprobante_rechazado` (migración `V2__comprobantes_rechazados.sql`) y deja un `SEVERE` en el log. El MDB confirma el mensaje sin `setRollbackOnly`: no hay reentregas ni DLQ. `GET /api/v1/facturas/{ventaId}` responde 409 `comprobante-rechazado` al comprador de esa venta y el rechazo lo revisa una persona. Un mensaje repetido de esa venta encuentra el rechazo anotado y no le vuelve a pedir el CAE a ARCA. Si falla la base al anotarlo, la excepción sale del MDB y el broker reintenta como en el caso anterior. Hasta el 20/11 el rechazo también hacía `setRollbackOnly` y terminaba en la DLQ después de cinco pedidos iguales.

No hay circuit breaker: con un solo consumidor (`maxSession` 1) y reintentos espaciados, una caída de ARCA no genera una avalancha de llamadas.

Lo prueban `FacturacionDeComprasMDBTest` (`conArcaCaidoElMensajeVuelveAlBrokerYLaReentregaFactura`, `unComprobanteRechazadoQuedaAnotadoYNoVuelveAlBroker` y `siLaBaseFallaAlAnotarElRechazoElMensajeSeReintentaYLaEntregaSiguienteLoAnota`) y `ServicioDeFacturacionTest` (`siArcaRechazaElComprobanteSeAnotaElRechazoConElMotivoYNoUnaFactura` y `unMensajeRepetidoDeUnaVentaRechazadaNoLeVuelveAPedirAArca`).

**Consecuencias.**
- (+) Si ARCA pasa de SOAP a REST, cambia una clase (`ArcaSoap`) y la fábrica: es la respuesta a la pregunta disparadora de la clase.
- (+) `ServicioDeFacturacion` se prueba con un doble de `Arca`, sin SOAP.
- (−) La interfaz `PuertoArca` se escribió a mano en lugar de generarla con `wsimport`: si cambia el WSDL hay que actualizarla.
- (−) Un comprobante rechazado no se resuelve solo: queda sin factura hasta que una persona lo revisa con la fila de `facturacion.comprobante_rechazado` y la línea `SEVERE` del log (sección 10.6 del documento único).

**Alternativas descartadas.** Generar el cliente con `wsimport` (suma un paso al build y código generado). Llamar al SOAP desde `ServicioDeFacturacion` (el negocio dependería del protocolo). Una cola propia de reintento (no suma nada a la política del broker que ya usan los demás consumidores). Reintentar también el rechazo, como hasta el 20/11 (cinco pedidos iguales a ARCA, el mensaje en la DLQ y la consulta respondiendo el mismo 404 que una factura en camino). Guardar el rechazo como un estado de la tabla `factura` (una factura siempre tiene número y CAE, y un rechazo no tiene ninguno de los dos).
