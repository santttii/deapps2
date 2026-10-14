# Plan C1 · facturacion-soap — Factura de cada compra contra un ARCA simulado, por SOAP y con WSDL

- **Dueño:** Santiago Loto. **Revisa:** Julian Zuchowicki. **Rama:** `feat/facturacion-soap`. Los commits los hace Santiago, con su identidad, uno por paso.
- **Plazo:** del 15/10 al 30/10. Hitos de `docs/PLAN_AGENTES.md`: 19/10, WSDL y una factura de prueba; 21/10, una factura por compra; 23/10, punto de control; 25/10, timeout, Fault y cola; 28/10, cierre y nota. El 29 y el 30/10 quedan de margen (el 30/10 hay clase).
- **Toca:** `backend/servicios/facturacion/` y un módulo nuevo para el sistema legado simulado, `backend/legado/arca/` (si se aprueba la decisión 1).
- **Pide a otros, en pasos aparte:** a Santiago como dueño de `backend/servidor` y del pom padre, la capa `webservices` y la dependencia de la API de JAX-WS (paso 1); a Julian, la cola `cola.reintento-facturacion` en `configurar.cli` y el aviso de que el tópico suma un suscriptor (paso 6); a Sebastian, la operación de Ventas que guarda el estado de la factura (decisión 3); a Joaquin (C5), las filas I3 y S3 del documento y el diagrama de secuencia.
- **Depende de:** nada que no exista. Usa `TokenDeServicio` (rol `servicio`), `PagoConfirmadoMensaje` y `DestinosJms` de `backend/common`, sin cambiarlos.

Los nombres son los de `docs/planes/00-contratos-compartidos.md`. El contrato del mensaje (`docs/superpowers/specs/mensajeria-pago-confirmado.md`) no cambia: Facturación es un lector más.

## Qué pide la consigna

- **Entrega Obligatoria N.º 2 (13/11, con defensa):** "Al menos 1 servicio SOAP, con su WSDL, justificado como integración con un sistema externo 'legado' del dominio elegido". Y para defender: una demo de punta a punta "que cruce componentes internos, mensajería y servicios web", y por qué cada integración es síncrona o asincrónica.
- **Sección 6 (requisitos transversales):** "Al menos 1 integración síncrona vía SOAP, con su WSDL, justificada como conexión con un sistema externo legado".
- La consigna modela los sistemas externos legados como "expuestos únicamente vía SOAP" (dominio de salud, ServicioDeObrasSociales). En Palco ese papel lo cumple ARCA (ex AFIP), con su servicio de factura electrónica WSFEv1. Así figura desde el taller de la clase 6 y en la fila I3 del documento de la Entrega Parcial N.º 2.

Hoy no existe nada de esto: Facturación es solo `FacturacionApplication` y el `health` de `common`, y toda venta responde `facturaEstado: NO_APLICA`.

## Qué se construye

**Mínimo (lo que pide la consigna), para el 23/10:**
1. `arca-simulado`: un WAR que hace de ARCA. Expone por SOAP un subconjunto del WSFEv1 y WildFly publica su WSDL.
2. En Facturación: `IFacturacion` y `ServicioDeFacturacion`, que pide el CAE (el código con que ARCA autoriza una factura) a través de un cliente SOAP propio, y guarda la factura en su esquema.
3. Una factura por cada compra confirmada. Facturación se entera por el tópico `topico.compra-confirmada` y, al terminar, le informa el resultado a Ventas: `facturaEstado` pasa a `AUTORIZADA` o `RECHAZADA`.

**Después del mínimo, para el 25/10:** espera máxima de 5 segundos; ante un timeout o un SOAP Fault, la factura queda `PENDIENTE`, sale un mensaje a `cola.reintento-facturacion` y otro MDB la vuelve a pedir. Si agota los intentos, el mensaje va a la DLQ, que ya existe. Es el diseño de la actividad de la clase 9 (punto 4).

### Qué es real y qué es simulado

| Pieza | Real o simulada |
|---|---|
| El protocolo: SOAP 1.1 sobre HTTP, mensajes XML, WSDL publicado por WildFly y cliente JAX-WS (Jakarta XML Web Services) | Real |
| El servidor SOAP: Apache CXF dentro de WildFly (subsistema `webservices`) | Real |
| ARCA | **Simulado** por el grupo, y así se declara en el documento y en la demo. Las operaciones, los nombres de los campos y la forma de los errores siguen el manual oficial del WSFEv1 (versión 4.7), con un subconjunto de campos |
| El ticket de acceso de WSAA (el servicio de autenticación de ARCA, que exige un certificado digital) | **Simulado**: los mensajes llevan `Auth` con `Token`, `Sign` y `Cuit`, y el simulador acepta cualquier valor no vacío |
| El CAE y su vencimiento | **Inventados** por el simulador, con el formato real (14 dígitos y fecha `yyyyMMdd`) |
| Importes | El WSFEv1 real usa decimales. El simulado usa pesos enteros (`long`), la regla del proyecto. El día que se conecte el real, la conversión va en el cliente |
| La compra, el tópico, la cola de reintento, la DLQ y la factura guardada en la base | Reales |

## Decisiones para aprobar

Se implementan con la recomendada y quedan pendientes de aprobación de Julian y del grupo (viernes 16/10).

**1. Dónde vive el sistema legado simulado.**
- (a) Un WAR aparte, `backend/legado/arca` (`arca-simulado.war`, en `/legado/arca`), fuera de `backend/servicios`: no es uno de los 8 componentes de negocio sino el sistema externo.
- (b) Dentro del WAR de Facturación.
- **Recomendada: (a).** El cliente y el sistema externo no comparten clases ni despliegue, como pasaría con el ARCA real: Facturación solo conoce una URL. Se puede dar de baja solo el simulador (`undeploy`) para mostrar que la compra sigue aunque ARCA no conteste. Con (b), el "sistema externo" estaría en el mismo archivo que su cliente, y en el oral eso es difícil de sostener.
- **Costo:** un módulo más en `backend/pom.xml` y una dependencia más en `backend/servidor/pom.xml` (paso 1). Los textos que dicen "8 WAR" (README, `CLAUDE.md`) pasan a decir "8 servicios y un simulador de ARCA".

**2. Cómo se dispara la factura.**
- (a) Ventas se la pide a Facturación por REST dentro de `confirmarCompra`, como dicen hoy la fila I3 y la llamada S3.
- (b) Facturación se suscribe a `topico.compra-confirmada` con una suscripción durable propia y factura al recibir `PAGO_CONFIRMADO`.
- **Recomendada: (b).** Razones, todas del código actual:
  - `confirmarCompra` corre en una transacción `REQUIRED` que ya escribió la venta y las entradas. Esperar ahí hasta 5 segundos (más el tiempo de Facturación) retiene una de las 10 conexiones que comparten los 8 servicios, que es justo lo que evita la decisión 10 de `docs/notas/ventas-core.md`. Llamar antes de escribir tampoco sirve: se facturaría una venta que puede no confirmarse.
  - Desde la clase 10 la compra se confirma aunque ARCA no conteste, porque el pago ya está cobrado. Si la respuesta no se necesita para seguir, no hay razón para esperarla dentro de la compra.
  - No cambia el flujo de compra de Ventas ni el mensaje. El tópico existe para esto: un interesado más se suma sin que el emisor lo sepa.
  - La llamada a ARCA **sigue siendo síncrona** (SOAP es pedido y respuesta, y Facturación espera el CAE antes de guardar). Lo que pasa a ser asincrónico es el disparo.
- **Costo:** cambian la fila I3 y la llamada S3 del documento (S3 pasa a ser Facturación → Ventas), y la tabla de consumidores del spec de mensajería suma una fila. Se le avisa a Julian antes del paso 5, porque el spec dice "no lo cambies sin avisar".
- **Lo que se pierde:** la factura ya no viaja en la respuesta de la compra. El comprador la ve al consultar la venta (`GET /api/v1/ventas/{id}`) unos segundos después.

**3. Quién cambia `facturaEstado`, que es un dato de Ventas.**
- (a) Facturación llama a una operación nueva de Ventas, `PUT /api/v1/ventas/{ventaId}/factura`, con rol `servicio` y cuerpo `{"estado":"AUTORIZADA","cae":"...","numero":"0001-00000007"}`.
- (b) Facturación publica un mensaje `FACTURA_EMITIDA` y Ventas suma un MDB que lo consume.
- (c) Ventas le pregunta a Facturación cada vez que arma la respuesta de una venta.
- **Recomendada: (a).** Una sola operación, del mismo estilo que el cobro (REST entre servicios con token de servicio). (b) suma un contrato de mensaje y un MDB en el módulo de otro dueño; (c) hace que cada lectura de una venta dependa de Facturación.
- **Pedido a Sebastian:** la operación, idempotente (repetirla con el mismo estado no cambia nada; no pasa de `AUTORIZADA` a otro estado) y, al confirmar la venta, `facturaEstado` = `PENDIENTE` en vez de `NO_APLICA` cuando Facturación está activa (propiedad `palco.ventas.facturacion`, con `NO_APLICA` si no se define, para no romper los tests ni la evidencia ya generada). Si Sebastian no llega antes del 21/10, la factura queda guardada en Facturación y se ve en `GET /api/facturacion/facturas/{ventaId}`; `facturaEstado` se conecta después.

**4. Cómo se numera el comprobante.**
- En el WSFEv1 real el cliente manda el número (`CbteDesde`), que tiene que ser el último autorizado más uno; si no, ARCA rechaza por correlatividad (manual, "Operatoria ante errores").
- (a) Facturación pide `FECompUltimoAutorizado` y manda el siguiente. Para que dos compras simultáneas no pidan el mismo número, ese tramo pasa por un `@Singleton` con `@Lock(WRITE)` (`NumeradorDeComprobantes`): una factura por vez.
- (b) El simulador numera solo y el cliente manda 0.
- **Recomendada: (a)**, porque es como funciona ARCA y es defendible. Una por vez, con hasta 5 segundos cada una, alcanza para el volumen de una demo. **Línea de corte:** si el 21/10 trae problemas, se pasa a (b) y se declara como simplificación.

**5. Cliente SOAP: generado o escrito a mano.**
- (a) Generar las clases desde el WSDL con `wsconsume` o un plugin de Maven.
- (b) Escribir a mano la interfaz del puerto (SEI) y las clases JAXB en Facturación, y crear el proxy con `Service.create(wsdl, qname)`, con el WSDL copiado en `src/main/resources/wsdl/`.
- **Recomendada: (b).** Son 4 operaciones y pocas clases. Un plugin más en el build, con JDK 25, es un riesgo que no hace falta correr. El WSDL local hace que el cliente arranque aunque el simulador esté caído (con la URL remota, `Service.create` falla si no la puede leer), y la dirección real se fija con `BindingProvider.ENDPOINT_ADDRESS_PROPERTY`. Facturación no usa clases del simulador: cada WAR tiene las suyas, como entre servicios.
- **Control:** el script del paso 3 compara el WSDL publicado con la copia local (`diff`). Si difieren, manda el publicado.

**6. Patrón y forma (la de Pagos).**
- `ServicioDeFacturacion` implementa `IFacturacion`: decide, guarda y deduplica.
- La salida a ARCA va detrás de una interfaz propia, `AutorizadorDeComprobantes`, con dos implementaciones: `ClienteArcaSoap`, un **Adapter** (traduce el modelo del WSFEv1, con su `Auth`, sus tipos de comprobante y su `Resultado` A/R, al nuestro) y `AutorizadorFake`, sin red, para los tests y para trabajar sin el simulador.
- `FabricaDeAutorizador` es la **Factory** que elige con `palco.facturacion.autorizador` (`soap` por defecto, `fake`), con el mismo mecanismo que `FabricaDeCobros` y `FabricaDePasarela`.
- `ServicioDeFacturacion` es `@Stateless`: cada factura es un pedido independiente y todo el estado está en la base. El simulador también es sin estado de conversación; el único estado que guarda es el último número por punto de venta, en memoria, y se pierde al reiniciar, igual que la base H2.

## Contrato del ARCA simulado

Espacio de nombres propio, `http://palco.daii/legado/arca/wsfe`: es un simulador y no usa el del servicio real (`http://ar.gov.afip.dif.fev1/`). Estilo document/literal, SOAP 1.1. Las operaciones y los campos son los del manual del WSFEv1:

| Operación | Entrada | Salida | Para qué |
|---|---|---|---|
| `FEDummy` | — | `AppServer`, `DbServer`, `AuthServer` (`OK`) | Saber si el sistema responde. Es el `health` del legado |
| `FECompUltimoAutorizado` | `Auth`, `PtoVta`, `CbteTipo` | `CbteNro`, `Errors` | El último número autorizado |
| `FECAESolicitar` | `Auth`, `FeCAEReq` con `FeCabReq` (`CantReg` = 1, `PtoVta`, `CbteTipo`) y un `FECAEDetRequest` (`Concepto`, `DocTipo`, `DocNro`, `CbteDesde`, `CbteHasta`, `CbteFch`, `ImpTotal`, `ImpNeto`, `ImpIVA`) | `FeCabResp` con `Resultado` (`A` aprobado, `R` rechazado), `FeDetResp` con `CAE`, `CAEFchVto` y `Obs`, y `Errors` | Pedir el CAE de una factura |
| `FECompConsultar` | `Auth`, `CbteTipo`, `PtoVta`, `CbteNro` | El comprobante con su `CAE`, o `Errors` si no existe | Saber si un pedido que quedó sin respuesta llegó a autorizarse |

Valores fijos de Palco: `PtoVta` 1, `CbteTipo` 6 (Factura B, a consumidor final), `Concepto` 2 (servicios), `DocTipo` 99 y `DocNro` 0 (consumidor final sin identificar). `ImpTotal` es `montoTotal` del mensaje. Antes del paso 2 se confirman estos códigos contra las tablas de parámetros del manual; si alguno no corresponde, se corrige acá.

**Errores, en dos niveles, como en el WSFEv1 real:**
- **Rechazo de negocio:** no es un Fault. La respuesta llega bien, con `Resultado` = `R` y el motivo en `Errors/Err` (`Code`, `Msg`). Por ejemplo, número no correlativo o importe inválido. La factura queda `RECHAZADA` y no se reintenta.
- **Falla técnica:** SOAP Fault `soap:Server` ("servicio no disponible"), o ninguna respuesta. La factura queda `PENDIENTE` y se reintenta.

**Cómo se fuerza cada caso en la demo** (sin reiniciar WildFly, que vaciaría la base): el simulador lee en cada pedido la propiedad de sistema `palco.arca.modo`, que se cambia en caliente con `jboss-cli`:

| `palco.arca.modo` | Qué hace |
|---|---|
| `normal` (o sin definir) | Autoriza y devuelve un CAE |
| `rechaza` | `Resultado` = `R` con un `Err` |
| `caido` | Responde un SOAP Fault |
| `lento` | Espera 8 segundos antes de contestar: el cliente corta a los 5 |

Además, `undeploy` del simulador muestra el caso "no hay nadie del otro lado" (conexión rechazada).

## Contrato de Facturación

**Dentro del servicio (`IFacturacion`):**
- `FacturaDto facturar(PagoConfirmadoMensaje compra)`: idempotente por `ventaId`. Si la venta ya tiene factura `AUTORIZADA` o `RECHAZADA`, la devuelve sin llamar a ARCA.
- `FacturaDto reintentar(String ventaId)`: antes de volver a pedir el CAE consulta `FECompConsultar` con el número que quedó guardado. Si ARCA ya lo había autorizado (el pedido llegó pero la respuesta se perdió), toma ese CAE y no pide otro. Es la "operatoria con errores de comunicación" del manual.
- `Optional<FacturaDto> buscar(String ventaId)`.

**API REST (`/api/facturacion`):**
- `GET /facturas/{ventaId}`, con `@RolesAllowed({"user", "staff", "servicio"})`; un `user` solo ve las de sus compras (compara `compradorId` con el `sub` del token). Para la demo y Postman.
- `health`, el de `common`, ya existe.

**Datos (esquema `facturacion`, migración `V1__crear_tablas.sql`):** tabla `factura` con `venta_id` único, `comprador_id`, `monto_total` (`bigint`), `punto_venta`, `tipo_comprobante`, `numero` (nulo hasta tener número), `estado` (`PENDIENTE`, `AUTORIZADA`, `RECHAZADA`, sin `check`), `cae`, `cae_vencimiento`, `motivo_rechazo`, `intentos`, `creada_en`, `actualizada_en`. El SQL tiene que correr en H2 y en PostgreSQL.

**Mensajería:**
- `FacturacionDeComprasMDB`: suscripción durable al tópico, `clientId` = `palco-facturacion`, `subscriptionName` = `facturas`, selector `tipoEvento = 'PAGO_CONFIRMADO'`. Mismo armado que `AuditoriaDeComprasMDB`.
- `cola.reintento-facturacion` (`java:/jms/queue/reintentoFacturacion`): mensaje de texto JSON `{"ventaId":"...","intento":1}`, con la propiedad `tipoEvento` = `FACTURA_PENDIENTE`. La consume `ReintentoDeFacturacionMDB`, único consumidor. Si vuelve a fallar, lanza una excepción, la transacción se deshace y el broker la reentrega con espera.
- Configuración propia de la cola (Julian, en `configurar.cli`): `redelivery-delay` 10000, `redelivery-multiplier` 2 y `max-delivery-attempts` 5, es decir, 10, 20, 40 y 80 segundos. Después, a la DLQ: es el "se escala en vez de reintentar indefinidamente" de la clase 9, y en el log queda un SEVERE con el `ventaId`.

**Tiempos del cliente SOAP:** 2 segundos para conectar y 5 para recibir, con `jakarta.xml.ws.client.connectionTimeout` y `jakarta.xml.ws.client.receiveTimeout` en el request context del puerto.

**Transacción:** el MDB del tópico corre en transacción (`REQUIRED`, la de un MDB por defecto). La llamada SOAP va antes de la primera consulta a la base, como el cobro en `confirmarCompra`, para no retener una conexión mientras se espera a ARCA. Si ARCA falla, en la misma transacción se guarda la factura `PENDIENTE` y se envía el mensaje a la cola de reintento: o pasan las dos cosas o ninguna. La llamada a Ventas (`PUT .../factura`) va al final; si falla, se loguea y no deshace la factura (Ventas se pone al día en el próximo intento o a mano).

## Archivos

Nuevos:
- `backend/legado/arca/pom.xml`, `src/main/webapp/WEB-INF/web.xml` (mapea el endpoint a `/wsfe`), `jboss-web.xml` (`/legado/arca`), y en `com.palco.legado.arca`: `ServicioWsfe` (`@WebService`), las clases JAXB de pedido y respuesta, `ModoDeSimulacion` y `ArcaNoDisponible` (`@WebFault`). Test: `ServicioWsfeTest`, que llama a la clase directamente, sin servidor.
- En `backend/servicios/facturacion`: `negocio/IFacturacion`, `negocio/ServicioDeFacturacion`, `negocio/NumeradorDeComprobantes`, `negocio/MigracionesFacturacion`, `negocio/externos/AutorizadorDeComprobantes`, `ClienteArcaSoap`, `AutorizadorFake`, `FabricaDeAutorizador`, `ResultadoDeAutorizacion`, `ClienteDeVentas`, las clases del puerto SOAP (en `negocio/externos/wsfe/`), `dominio/Factura`, `dominio/EstadoFactura`, `datos/FacturaRepository` y su implementación JPA, `mensajeria/FacturacionDeComprasMDB`, `mensajeria/ReintentoDeFacturacionMDB`, `api/FacturacionResource`, `api/Dtos`, `META-INF/persistence.xml`, `db/migracion/V1__crear_tablas.sql`, `wsdl/wsfe.wsdl`. Tests: `ServicioDeFacturacionTest` (con `AutorizadorFake` y un repositorio en memoria) y `FabricaDeAutorizadorTest`.
- `scripts/soap/fedummy.xml` y `scripts/soap/fecaesolicitar.xml`: los sobres SOAP para probar con `curl`.
- `docs/notas/facturacion.md`: la nota para la defensa.

Modificados (en pasos aparte, con aviso al grupo): `backend/pom.xml` (módulo `legado/arca` y la API de JAX-WS en `dependencyManagement`), `backend/servidor/pom.xml` (capa `webservices` y el WAR del simulador), `backend/servidor/src/main/wildfly/configurar.cli` (la cola y su configuración), `backend/servicios/facturacion/pom.xml`.

## Pasos

Cada paso termina con su verificación y su commit. En Git Bash, con un Maven por vez y sin `mvn clean` con WildFly levantado:

```bash
export JAVA_HOME=/c/Users/santi/.jdks/temurin-25
cd "/c/Users/santi/OneDrive/Documentos/Facultad/Desarrollo de Aplicaciones II/TP Integrador/backend"
```

**1. Servidor con servicios web (16 y 17/10, paso compartido).** En `backend/servidor/pom.xml`, `<layer>webservices</layer>`. En el pom padre, `jakarta.xml.ws:jakarta.xml.ws-api` y `jakarta.xml.bind:jakarta.xml.bind-api` con `scope` `provided` (la versión, la de la línea 4.0 que trae WildFly 41). Se avisa al grupo, se detiene WildFly y se rearma una sola vez, junto con la cola del paso 6 si Julian ya la tiene.
- Verifica: `mvn -q -B install` sin errores; `grep -c 'urn:jboss:domain:webservices' servidor/target/server/standalone/configuration/standalone.xml` da 1 (hoy da 0); `./dev.sh` y los 8 `health` en 200; `mvn -q -B test` con los tests de siempre en verde.
- Commit: `build: suma la capa webservices al servidor`.

**2. El simulador publica su WSDL (17 y 18/10).** Módulo `backend/legado/arca` con `ServicioWsfe` y `FEDummy` primero; después las otras tres operaciones y los cuatro modos.
- Verifica: `mvn -q -B test -pl legado/arca -am` (`ServicioWsfeTest`: autoriza con número correlativo, rechaza el no correlativo con `Resultado` R, `FECompConsultar` devuelve el mismo CAE, modo `caido` lanza `ArcaNoDisponible`); `curl -sf "localhost:8080/legado/arca/wsfe?wsdl" | grep -c 'operation name='` da 8 (cada operación aparece en `portType` y en `binding`); `curl -s -H 'Content-Type: text/xml' -d @../scripts/soap/fedummy.xml localhost:8080/legado/arca/wsfe` responde `<AppServer>OK</AppServer>`.
- Commit: `feat: simulador de ARCA con WSDL publicado`.

**3. El cliente SOAP de Facturación (18 y 19/10).** `AutorizadorDeComprobantes`, `ClienteArcaSoap`, `AutorizadorFake`, `FabricaDeAutorizador`, el WSDL copiado y un recurso temporal `POST /api/facturacion/pruebas/factura` (rol `staff`) que pide un CAE de prueba sin compra.
- Verifica: `diff <(curl -s "localhost:8080/legado/arca/wsfe?wsdl") servicios/facturacion/src/main/resources/wsdl/wsfe.wsdl` sin diferencias de contenido; el `POST` de prueba devuelve un CAE de 14 dígitos. **Hito del 19/10.**
- Commit: `feat: cliente SOAP de facturación`.

**4. La factura guardada (19 y 20/10).** `Factura`, migración, repositorio, `IFacturacion`, `ServicioDeFacturacion`, `NumeradorDeComprobantes` y `GET /facturas/{ventaId}`.
- Verifica: `mvn -q -B test -pl servicios/facturacion -am` (`ServicioDeFacturacionTest`: dos `facturar` de la misma venta llaman una sola vez a ARCA; rechazo queda `RECHAZADA`; falla técnica queda `PENDIENTE`); `./redesplegar.sh facturacion`, health 200 y ningún `.failed` en `deployments`.
- Commit: `feat: facturación guarda la factura de cada venta`.

**5. Una factura por compra (20 y 21/10).** `FacturacionDeComprasMDB`; `ClienteDeVentas` cuando Sebastian tenga el `PUT`. Se quita el recurso temporal del paso 3.
- Verifica: una compra completa (los requests de Postman de compra y pago con un usuario recién registrado) y, a los pocos segundos, `GET /api/facturacion/facturas/{ventaId}` da `AUTORIZADA` con CAE, y `GET /api/v1/ventas/{ventaId}` da `facturaEstado: AUTORIZADA`. El log muestra un solo pedido a ARCA por venta. **Hito del 21/10.**
- Commit: `feat: factura de cada compra confirmada`.

**Punto de control, viernes 23/10** (ver "Líneas de corte").

**6. Timeout, Fault y cola de reintento (24 y 25/10, con Julian).** `ReintentoDeFacturacionMDB`, la cola y su configuración. La cola se aplica en caliente con `jboss-cli` (como los reintentos de `docs/notas/mensajeria.md`) y queda en `configurar.cli` para el próximo armado.
- Verifica, con `bin/jboss-cli.sh --connect --command="/system-property=palco.arca.modo:add(value=lento)"`: una compra responde enseguida como siempre (Ventas no espera a Facturación); la factura queda `PENDIENTE`; en el log, el corte a los 5 segundos y el mensaje a la cola. Con `write-attribute` a `normal`, el siguiente reintento la deja `AUTORIZADA` sin pedir un segundo CAE (`FECompConsultar`). Con `caido`, lo mismo con un Fault. Con `caido` todo el tiempo, a los 5 intentos el mensaje está en la DLQ (`/subsystem=messaging-activemq/server=default/jms-queue=DLQ:count-messages`). Con `rechaza`, `RECHAZADA` sin reintento.
- Commit: `feat: reintento de la factura por cola`.

**7. Cierre (26 al 28/10).** `docs/notas/facturacion.md` con las decisiones y las preguntas probables del oral; un tramo de factura en el guion de demo; dos requests en Postman (consultar la factura y el `?wsdl`); la evidencia en texto de los casos del paso 6; pedido a Joaquin (C5) del diagrama de secuencia y de las filas I3 y S3; pedido de la fila de consumidores del spec de mensajería a Julian. Revisión de Julian.
- Verifica: `mvn -q -B test` de todo el backend en verde; el guion completo corre de punta a punta; Postman sin fallas.
- Commit: `docs: nota y evidencia de la facturación SOAP`.

## Líneas de corte

- **Viernes 16/10:** si la capa `webservices` no levanta los 8 servicios (paso 1), se vuelve atrás ese mismo día y se pregunta en clase. Sin capa no hay SOAP: C1 se convierte en el bloqueo número uno y se suma Julian.
- **Viernes 23/10 (de `docs/PLAN_AGENTES.md`):** si ese día el WSDL no se descarga y una factura de prueba no responde, C1 se reduce al mínimo de la consigna (servicio SOAP, WSDL, cliente y una factura por compra), la cola de reintento pasa a la Ola 5 y Julian se suma a C1.
- **Miércoles 21/10:** si la numeración con `FECompUltimoAutorizado` trae problemas, decisión 4 (b).
- **Miércoles 21/10:** si el `PUT` de Ventas no está, `facturaEstado` se conecta después del 23/10 y la demo muestra la factura desde Facturación.
- **Miércoles 28/10:** lo que no esté cerrado ese día pasa a pendiente con nombre en `TODO.md`. El paquete de diseño del 06/11 necesita el WSDL estable desde el 04/11.

## Riesgos y dudas abiertas

- **El rearmado del paso 1 vacía la base H2 y los mensajes del broker.** Se hace una sola vez, avisado, y se regenera lo que dependa de esos datos. Es también el único reinicio que pide el plan: el resto se aplica en caliente.
- **Sin comprobar en este servidor:** la URL que toma un endpoint POJO con `web.xml` en un WAR que tiene otro `context-root` (se usa el mapeo explícito para no depender del valor por defecto); que la propiedad de sistema agregada en caliente se lea con `System.getProperty` sin reiniciar; que un WAR que solo es cliente (Facturación) reciba las dependencias de JAX-WS sin declararlas (si no, `Dependencies:` en el `MANIFEST.MF` o `jboss-deployment-structure.xml`); que `jakarta.xml.ws-api` 4.0 traiga también `jakarta.jws` (si no, se suma `jakarta.jws-api`); y la `wsdl-host` publicada fuera de `localhost` cuando haya hosting.
- **JAX-WS no está en la API de Jakarta EE 11 que usa el repo** (en el `jakarta.jakartaee-api-11.0.0.jar` local no hay `jakarta/xml/ws` ni `jakarta/jws`): sin la dependencia del paso 1 no compila.
- **Los tests no levantan CXF.** El simulador y el servicio se prueban como clases; el SOAP de verdad se prueba solo en el servidor, con `curl`. Se dice así en la nota.
- **Volumen:** el `NumeradorDeComprobantes` hace una factura por vez. Con muchas compras seguidas las facturas se encolan detrás; las compras no.
- **Lo que no se hace:** WSAA real, certificados, homologación con ARCA, notas de crédito por reembolso, varias alícuotas de IVA, factura A, y el PDF de la factura.

## Qué tiene que poder defender Santiago en el oral

1. **Por qué SOAP:** porque ARCA es el sistema legado del dominio y su servicio de factura electrónica solo se ofrece por SOAP, con WSDL. No lo elegimos: lo impone el otro lado. Lo nuestro (Ventas, Pagos, el frontend) es REST.
2. **Por qué un simulador y qué tiene de real:** ARCA exige un certificado digital y WSAA incluso para homologación. El simulador respeta las operaciones, los campos y la forma de los errores del manual; el protocolo, el WSDL, CXF y el cliente son reales. Cambiar al real es cambiar la URL y sumar WSAA en el Adapter, no tocar el negocio.
3. **Qué es el WSDL y quién lo escribe:** el contrato (tipos, mensajes, operaciones, binding y dirección). En el simulador lo genera WildFly a partir de la clase anotada; Facturación usa una copia local, como cualquier cliente de un servicio ajeno.
4. **Síncrono y asincrónico, en la misma integración:** la llamada a ARCA es síncrona (pedido y respuesta, se espera el CAE con un tope de 5 segundos); el disparo es asincrónico (tópico) y el reintento también (cola), porque la compra no necesita la factura para confirmarse: el pago ya está cobrado. Por qué cola y no tópico para el reintento: es una tarea con un único responsable que tiene que hacerse una vez por intento.
5. **Qué pasa si ARCA no contesta:** la compra no se entera; la factura queda pendiente, se reintenta con espera creciente y, si no sale, termina en la DLQ con un aviso en el log. Y por qué antes de reintentar se consulta `FECompConsultar`: si el pedido llegó pero la respuesta se perdió, pedirlo de nuevo daría error de correlatividad o una factura doble.
6. **Rechazo contra falla:** un rechazo de ARCA es una respuesta normal con `Resultado` R (no se reintenta, no va a cambiar); un Fault o un timeout es una falla técnica (sí se reintenta).
7. **Transacción:** la llamada SOAP no retiene conexión a la base; guardar la factura pendiente y encolar el reintento van juntos o no van.
8. **Patrones:** Adapter (`ClienteArcaSoap`), Factory (`FabricaDeAutorizador`) y la interfaz propia que separa el negocio del sistema externo.
9. **Stateless:** `ServicioDeFacturacion` no guarda nada entre pedidos; el estado está en la base. El numerador es un `@Singleton` porque su trabajo es justamente serializar.

## Preguntas abiertas para la cátedra

1. ¿Vale como servicio SOAP un sistema legado simulado por el grupo, publicado con su WSDL y declarado como simulado? (Es la pregunta 10 de `docs/PLAN_AGENTES.md`.)
2. ¿Cuenta como "integración síncrona vía SOAP" una llamada síncrona que se dispara desde un consumidor del tópico, o la cátedra espera que la llamada esté dentro del pedido del usuario?
3. ¿El reintento de la factura por su propia cola cuenta como un segundo proceso asincrónico por cola, si la emisión de entradas y el aviso no se cuentan por separado? (Relacionada con la pregunta 12.)
4. ¿El WSDL tiene que estar en el documento técnico del 13/11 completo, o alcanza con el enlace y un extracto de las operaciones?

## Fuentes consultadas el 14/10/2026

- [WildFly 41, Galleon Guide](https://docs.wildfly.org/41/Galleon_Guide.html): la capa `webservices`, "Support for Jakarta XML Web Services", depende de `web-server` y, opcionales, de `ejb-lite` y `messaging-activemq`; Galleon suma solo las dependencias de la capa pedida. Confirmado en el `layer-spec.xml` de `wildfly-ee-galleon-pack-41.0.0.Final.zip` del repositorio Maven local, cuyo grupo de features fija `wsdl-host` en `${jboss.bind.address:127.0.0.1}`.
- [WildFly 41, Developer Guide](https://docs.wildfly.org/41/Developer_Guide.html), sección 12.1.1 "Web Service Endpoints": "The server automatically generates and publishes the abstract contract (i.e. wsdl+schema)"; un endpoint POJO se despliega como aplicación web y "only the endpoint implementation bean and web.xml are required". Sección 12.1.2, "Timeout Configuration": `jakarta.xml.ws.client.connectionTimeout` y `jakarta.xml.ws.client.receiveTimeout`. Sección 12.5.3 "Address rewrite": `wsdl-host` y `modify-wsdl-address`. Sección 2.3: el subsistema de servicios web agrega como dependencias implícitas `org.jboss.ws.api` y `org.jboss.ws.spi`.
- [ARCA, Manual para el desarrollador WSFEv1, RG 4291, versión 4.7](https://www.afip.gob.ar/ws/documentacion/manuales/manual-desarrollador-ARCA-COMPG.pdf): métodos `FECAESolicitar`, `FECompUltimoAutorizado`, `FECompConsultar` y `FEDummy`; autenticación por ticket de WSAA con certificado digital; estructura de `FECAESolicitarResult` (`Resultado`, `CAE`, `CAEFchVto`, `Obs`, `Errors`); "Operatoria con errores de comunicación" (consultar con `FECompConsultar` antes de repetir); WSDL de homologación en `https://wswhomo.afip.gov.ar/wsfev1/service.asmx?WSDL`. Enlazado desde la [página de ayuda de web services de factura electrónica](https://www.afip.gob.ar/fe/ayuda/webservice.asp).
