# Palco: documentación del proyecto

| | |
|---|---|
| Materia | Desarrollo de Aplicaciones II · UADE |
| Comisión | Viernes, 08:45 a 11:45 |
| Integrantes | Santiago Loto, Valentina Frisoli, Sebastian Deya, Julián Zuchowicki, Joaquin Molina |
| Repositorio | https://github.com/santttii/deapps2 |
| Versión | Documento unificado del 16/10/2026, puesto al día el 25/11/2026 |

## Estado del documento

| | |
|---|---|
| Qué refleja | El código del repositorio al 25/11/2026: el commit `12a08f2` y las correcciones del 25/11 de DEF-40, DEF-42, DEF-45, DEF-56, DEF-72, DEF-73 y DEF-74 (registro de defectos). Las tablas de estado (2.2, 2.3, 8.2, 14.9 y 20) dicen el estado al 25/11. Cada corrida citada lleva su fecha y su fuente: la evidencia del 3.er check point (`docs/testing/evidencia/checkpoint-3/`, 18 y 19/11), la corrida del CI del 21/11 sobre `fbd31dc` (`docs/testing/evidencia/final/`), las de carga y estabilidad del 24/11 y la de Postman del 24/11. Las secciones que cuentan algo anterior conservan su fecha: el código del commit `bcbc26a` (19/10/2026), lo agregado el 23/10 y la evidencia de `docs/entrega3/evidencia/`, tomada el 14/10 del servidor armado desde el tag `entrega-parcial-2` |
| De dónde sale | La Entrega Parcial N.º 1, el documento de integración de la Entrega Parcial N.º 2, los talleres de las clases 6, 8, 9, 10 y 11, la OpenAPI, la colección de Postman, el registro de defectos y el código. El anexo D lista cada documento |
| Para qué | Es el documento maestro. De acá salieron el documento técnico de la Entrega Obligatoria N.º 2 (13/11, de 8 a 12 páginas) y la documentación final consolidada (`docs/final/documentacion-final.md`, 10 a 15 páginas) |
| Estados | "Implementado": está en el código y se probó. "Simulado": funciona con un sustituto declarado. "Diseñado": está diseñado y no se implementa en este trabajo |

## Índice

- 1. Presentación
- 2. Requisitos y estado
- 3. Arquitectura general
- 4. Componentes
- 5. Decisiones de diseño
- 6. Patrones aplicados
- 7. Transacciones
- 8. Estrategia de integración (SOA)
- 9. API REST
- 10. Mensajería
- 11. Servicios web SOAP: Facturación con un ARCA simulado
- 12. Sistemas externos
- 13. Diagramas de secuencia
- 14. Seguridad
- 15. Frontend
- 16. Pruebas y evidencia
- 17. Cómo levantar y probar el sistema
- 18. Gestión del proyecto
- 19. Evolución del diseño
- 20. Qué es real y qué es simulado hoy
- 21. Decisiones con riesgo aceptado
- 22. Trabajo futuro
- Aprendizajes del equipo
- Declaración de uso de herramientas de IA generativa
- Anexos

# 1. Presentación

## 1.1 Qué es Palco y qué problema resuelve

Palco es una plataforma de venta de entradas para eventos (recitales, partidos, fiestas y otros espectáculos masivos) con **reventa oficial dentro de la misma plataforma**. Ataca dos problemas del mercado de entradas:

- **La reventa especulativa.** Cuando un evento se agota, las entradas reaparecen en canales informales a precios muy superiores al original. En Palco, quien no puede ir revende su entrada solo dentro de la plataforma y con un precio máximo fijo: el original más un 10 %. El tope lo fija la plataforma y el organizador no lo puede cambiar.
- **El fraude y la entrada duplicada.** Las entradas son **nominativas**: cada una lleva el nombre y el DNI de su titular, y en la puerta se valida que la entrada sea auténtica, que no se haya usado y que corresponda a la persona que la presenta.

Las reglas numéricas del dominio están en un solo lugar del código, `backend/common/src/main/java/com/palco/common/ReglasDeNegocio.java`, y valen para todos los servicios:

| Regla | Valor | Constante |
|---|---|---|
| Tope de reventa sobre el precio original | +10 % | `TOPE_REVENTA_PORCENTAJE` |
| Precio mínimo de reventa | 50 % del original | `MINIMO_REVENTA_PORCENTAJE` |
| Tasa de servicio sobre el subtotal | 10 % | `TASA_SERVICIO_PORCENTAJE` |
| Máximo de entradas por compra | 6 | `MAX_ENTRADAS_POR_ORDEN` |
| Cierre de la reventa antes del evento | 3 horas | `HORAS_LIMITE_REVENTA` |
| Duración de la reserva de cupo durante la compra | 10 minutos | `MINUTOS_RESERVA` |

Los montos se manejan en pesos enteros (`long`) en todo el backend, para evitar errores de redondeo.

## 1.2 Actores y roles

El sistema distingue cuatro roles. Los tres primeros son de personas y se guardan en `backend/servicios/usuarios/src/main/java/com/palco/usuarios/dominio/Rol.java` (`USER`, `ORGANIZER`, `STAFF`); viajan en el token de sesión (JWT, claim `groups`) y son los que usan las anotaciones `@RolesAllowed` de cada recurso REST. El cuarto es técnico.

| Actor | Rol en el sistema | Qué hace | Estado |
|---|---|---|---|
| Comprador | `user` | Se registra, verifica su email, compra entradas nominativas a su nombre o a nombre de otros titulares, consulta sus entradas y la factura de cada compra. Con la identidad verificada puede además revender una entrada propia: no es un tipo de cuenta aparte sino una capacidad del comprador (el "vendedor" de la Entrega Parcial N.º 1). | Compra y consulta de la factura: implementadas y probadas en el servidor el 16/10. Reventa: implementada y probada en el servidor el 16/10 por la API; desde el 3.er check point, sus pantallas se prueban en el navegador en cada corrida del CI (`reventa.spec.ts`, 16.7) |
| Organizador | `organizer` | Da de alta eventos con sus sectores, precios y cupos; el evento queda a su nombre. En el panel ve, por evento, entradas vendidas, recaudación a precio de lista, ocupación por sector, publicaciones de reventa activas y accesos registrados en la puerta. | Alta de eventos: implementado. Panel: implementado con sus eventos (Eventos), el resumen de ventas (Ventas, `GET /api/v1/resumenes`), la reventa (Reventa) y los accesos (Validación); se prueba en cada corrida del CI por la API (escenarios 21 y 24) y en el navegador (`organizador.spec.ts` y `accesibilidad.spec.ts`, 16.7) |
| Personal de puerta | `staff` | Escanea la entrada en el ingreso (o tipea el código) y contrasta nombre y DNI del titular con el documento. El mismo escaneo marca la entrada como usada. | Implementado y probado en el servidor el 16/10 por la API; desde el 3.er check point, la pantalla `/puerta` se prueba en el navegador en cada corrida del CI (`puerta.spec.ts`, 16.7) |
| Otro servicio de Palco | `servicio` | Rol técnico para las llamadas entre servicios: por ejemplo, Ventas actualiza el cupo en Eventos o pide un cobro a Pagos, y Validación le pide a Ventas que marque una entrada como usada. El token lo emite Usuarios en `POST /api/usuarios/servicios/token` y lo obtiene `common/seguridad/TokenDeServicio.java`. | Implementado |

Los sistemas externos del dominio son la pasarela de pago (Mercado Pago, partner moderno por REST) y el sistema de facturación electrónica de AFIP/ARCA (legado, por SOAP). El 16/10 la cátedra aceptó que los dos sean simulados. La pasarela está **simulada** dentro de Pagos (`backend/servicios/pagos/src/main/java/com/palco/pagos/pasarela/PagosFake.java`). ARCA está **simulado** por un servicio SOAP propio en un WAR aparte (`backend/servicios/arca`, publicado en `/legado/arca`), que hace de sistema legado y no comparte clases con Palco; Facturación le pide el CAE de cada compra por SOAP. Esa integración está implementada y se probó en el servidor el 16/10 (sección 1.5).

## 1.3 Flujos principales

### Compra nominativa con reserva y pago (implementado, con pasarela simulada)

1. El comprador elige un evento del catálogo (`GET /api/eventos`, público) y las cantidades por sector.
2. Carga los titulares: nombre y DNI de cada entrada (hasta 6 por compra).
3. Ventas abre la compra (`POST /api/v1/ventas`): valida, **reserva el cupo** en Eventos con un UPDATE atómico (dos compras simultáneas del último lugar: pasa una sola; evidencia en `docs/entrega3/evidencia/compras-simultaneas.txt`) y calcula subtotal más tasa de servicio. La compra en curso vive en un componente **stateful** (`ServicioDeVentas`, `@Stateful` con `@StatefulTimeout` de 10 minutos).
4. El comprador paga (`POST /api/v1/ventas/{ventaId}/pagos`). Ventas cobra a través de Pagos, que hoy responde con la pasarela simulada. Si el cobro sale aprobado, una única transacción declarativa guarda la venta como confirmada y publica el mensaje `PAGO_CONFIRMADO`. Si Pagos no contesta, o una falla después del cobro deshace esa transacción, la venta queda `EN_CHECKOUT` con el intento de pago anotado; la conciliación de pagos (`ConciliacionDePagos`, una vez por minuto) le pregunta a Pagos y, si el cobro salió aprobado, confirma la venta con ese mismo pago. En el servidor, el 16/10, una falla provocada después del cobro dio 500 con `idError` y la conciliación emitió la entrada en menos de 3 minutos.
5. Las entradas se emiten de forma **asincrónica**: el mensaje llega por la cola `cola.emision-entradas` al consumidor de emisión (`EmisionDeEntradasMDB`), que pasa las entradas de `PENDIENTE_EMISION` a `VALIDA`. El mismo mensaje sale por el tópico `topico.compra-confirmada` hacia el aviso al comprador (que hoy **se escribe en el log**, no se envía por email: simulado), hacia la auditoría (suscripción durable, implementado) y hacia Facturación (suscripción durable), que le pide el CAE al ARCA simulado por SOAP y guarda la factura; el comprador la consulta en `GET /api/v1/facturas/{ventaId}`. La facturación está implementada y se probó en el servidor el 16/10: la primera compra recibió la factura B 00001-00000001 con un CAE de 14 dígitos, una sola por venta, y la siguiente, el número siguiente.
6. Si el comprador cancela o pasan los 10 minutos sin pagar, el cupo se libera (`DELETE /api/v1/ventas/{ventaId}` o el barrido de `VencimientoDeReservas`, un `@Singleton` con `@Schedule` cada minuto).

### Reventa oficial con tope +10 % (implementado)

1. El titular publica una entrada propia `VALIDA` con un precio entre el 50 % y el 110 % del precio de lista de su sector, hasta 3 horas antes del evento (`POST /api/v1/reventas`, rol `user`). Reventa le pregunta a Ventas, la dueña de las entradas, por la entrada, controla el rango y el horario en el servidor y le pide a Ventas que la pase a `PUBLICADA`; después guarda la publicación `ACTIVA` en su esquema. Para iniciar sesión hace falta el email verificado, que en esta etapa es la verificación de identidad (sección 1.5).
2. Mientras sigue `ACTIVA`, el vendedor la puede retirar (`DELETE /api/v1/reventas/{publicacionId}`) y la entrada vuelve a `VALIDA`.
3. Otro comprador la compra (`POST /api/v1/reventas/{publicacionId}/compra`, con `Idempotency-Key`, el titular nuevo y el medio de pago). Desde el 20/11, antes de tomarla, Reventa le pide a Ventas que aplique las reglas entre compras del catálogo: hasta 6 entradas por comprador y evento, y un DNI por evento (DEF-30, sección 7.10). Reventa toma la publicación con un UPDATE condicional (de dos compras a la vez, cobra una sola), cobra en Pagos el precio más la tasa de servicio y le pide a Ventas la transferencia: en una sola transacción, la entrada original pasa a `VENDIDA` y nace una venta `EMITIDA` del comprador con una entrada nueva `VALIDA`, con código QR nuevo y a nombre del titular nuevo. La publicación queda `VENDIDA`.

Se probó en el servidor el 16/10 de punta a punta, con la compra hecha desde otra cuenta y la entrada nueva en la venta del comprador. Las pantallas (`MyTicketsPage.tsx` para publicar y retirar, la sección "Reventa oficial" de `EventPage.tsx` y `ResalePage.tsx`) llaman a `/api/v1/reventas` (`frontend/src/servicios/reventa.ts`) y no guardan publicaciones en el navegador; las recorre en el navegador `frontend/e2e/reventa.spec.ts` en cada corrida del CI (16.7). Desde el 23/10 la transferencia publica `REVENTA_CONFIRMADA`, con el aviso al comprador, la auditoría y la factura de la compra en la reventa (10.1), y si la transferencia falla después de un cobro aprobado, Reventa devuelve el pago (7.10). El vendedor no recibe un aviso de la venta.

### Validación en puerta (implementado)

1. El personal de puerta elige el evento que controla y escanea el QR (o tipea el código) en `/puerta` (`frontend/src/pages/GatePage.tsx`).
2. El frontend llama a `POST /api/v1/validacion/escaneos` (rol `staff`) con el código y el evento. El código va en el cuerpo y no en la ruta, para que no quede en los logs de acceso.
3. Validación le pide a Ventas, la dueña de las entradas, que la use: `POST /api/v1/entradas/usos` (rol `servicio`). Ventas pasa la entrada de `VALIDA` a `USADA` con un UPDATE condicional: de dos puertas que escanean la misma entrada a la vez, cambia la fila una sola.
4. Validación guarda el escaneo en su registro y responde 201 con el resultado: `VALIDA` (con nombre, DNI y sector del titular para contrastar con el documento), `YA_USADA` (con el escaneo anterior), `INEXISTENTE`, `OTRO_EVENTO` (la entrada no se toca y sigue sirviendo en su evento) o `NO_HABILITADA` (publicada o vendida en la reventa, o anulada). "No puede entrar" es un dato del escaneo y no un error HTTP.
5. Si Ventas no responde, el intento queda registrado como `SIN_RESPUESTA` y el pedido devuelve 504; hay que escanear de nuevo.

El registro de la puerta (`GET /api/v1/validacion/escaneos?eventoId=`, para el staff asignado y el organizador dueño) muestra el código recortado a sus últimos 6 caracteres y, desde el 20/11, el email de la cuenta que escaneó (DEF-32, sección 4.6). El código QR sigue siendo el UUID al azar que genera Ventas al emitir: no se puede adivinar, pero no está firmado.

Se probó en el servidor el 16/10 por la API: `OTRO_EVENTO`, `VALIDA` (la entrada queda `USADA`), `YA_USADA`, `INEXISTENTE` y 403 para un comprador. La pantalla `/puerta` la recorre en el navegador `frontend/e2e/puerta.spec.ts` en cada corrida del CI: una entrada válida, el mismo código por segunda vez y uno que no existe (16.7).

### Alta de eventos (implementado)

El organizador crea el evento con su categoría, fecha, lugar y sectores con precio y cupo (`POST /api/eventos`, rol `organizer`). El servidor valida los datos, genera un slug único y no deja crear eventos que ya empezaron. El dueño también lo edita (`PUT /api/eventos/{eventoId}`, con `If-Match` opcional, sección 4.2) y lo cancela (`POST /api/eventos/{eventoId}/cancelacion`): la cancelación anula las compras, devuelve los pagos y retira la reventa del evento (sección 10.8, escenario 22).

## 1.4 Casos de uso

El diagrama reúne los tres actores de persona, los dos sistemas externos simulados y los quince casos de uso del sistema, CU-01 a CU-15. La descripción completa de cada caso (actor, precondición, flujo principal, flujos alternativos con el código HTTP y el tipo de error, postcondición y estado) está en `docs/documentacion/casos-de-uso.md`; acá va un resumen.

![Diagrama de casos de uso de Palco](diagramas/casos-de-uso.svg)

*Estado al 25/11, el mismo en el diagrama y en la tabla: los quince casos están implementados, tienen pantalla en el frontend y los prueba el CI contra el `docker compose`, con al menos una prueba E2E en el navegador (Playwright, `frontend/e2e/`) o un escenario de integración por la API (`scripts/pruebas/escenarios.mjs`). La corrida del CI del 21/11 sobre el commit `fbd31dc` pasó entera: 31 de 31 pruebas E2E y los 24 escenarios (16.7). Desde el 24/11 la suite E2E tiene 39 pruebas. La columna Estado nombra la prueba de cada caso.*

| N.º | Caso de uso | Actor principal | Qué hace | Estado |
|---|---|---|---|---|
| CU-01 | Registrarse | Comprador | Alta de la cuenta con rol `user` (`POST /api/usuarios/registro`) y verificación del email con un código de 6 dígitos (`POST /api/usuarios/verificacion`), que deja la identidad verificada y abre la sesión. Sin servidor SMTP, el código queda en el log del servidor, y el renglón lo trae solo con `PALCO_CORREO_LOG_COMPLETO=true`, como en desarrollo (14.6) | Implementado (envío de email simulado). En el CI: `registro.spec.ts` y escenario 24 |
| CU-02 | Iniciar sesión | Comprador, organizador, personal de puerta | `POST /api/usuarios/login` devuelve un JWT RS256 de 8 horas con el rol en `groups`. Con 5 fallidos en 15 minutos para un email, el siguiente responde 429 con `Retry-After` | Implementado. En el CI: `sesion.spec.ts` y escenario 24 |
| CU-03 | Consultar el catálogo | Comprador (también sin sesión) | Lista con filtro por categoría y búsqueda (`GET /api/eventos`) y detalle con sectores, precio y cupo (`GET /api/eventos/{slug}`) | Implementado. En el CI: `catalogo.spec.ts` y escenario 24 |
| CU-04 | Comprar entradas | Comprador (con la pasarela y ARCA simulados) | Reserva del cupo con titulares nominativos, pago, confirmación en una transacción con la publicación de `PAGO_CONFIRMADO`, emisión asincrónica de las entradas y factura pedida al ARCA simulado por SOAP | Implementado, con pasarela simulada. En el CI: `compra.spec.ts`, escenario 1 y, la factura, escenario 15 |
| CU-05 | Ver mis entradas | Comprador | Compras del dueño del token con sus entradas (`GET /api/v1/ventas`) y cada entrada con su código QR una vez emitida | Implementado. En el CI: `compra.spec.ts` (la entrada con su QR en Mis entradas) |
| CU-06 | Publicar en reventa | Comprador (vendedor) | Publica una entrada propia `VALIDA` con un precio entre el 50 % y el 110 % del precio de lista de su sector, hasta 3 horas antes del evento; el rango se valida en el servidor (`POST /api/v1/reventas`). Mientras está activa la puede retirar | Implementado. En el CI: `reventa.spec.ts` y escenario 17 |
| CU-07 | Comprar en reventa | Comprador (con la pasarela simulada) | Compra una publicación de otro usuario (`POST /api/v1/reventas/{publicacionId}/compra`): se cobra precio más tasa, se emite una entrada nueva a su nombre y la original queda `VENDIDA`. Desde el 20/11 vale el mismo tope de 6 entradas por evento y el mismo DNI por evento que en la compra del catálogo (DEF-30). Desde el 23/10 la compra publica `REVENTA_CONFIRMADA`, con el aviso al comprador y su factura. No hay aviso al vendedor | Implementado, con pasarela simulada. En el CI: `reventa.spec.ts` y escenario 17 |
| CU-08 | Crear evento | Organizador | `POST /api/eventos` con sectores, precio y cupo; el servidor valida, genera un slug único y rechaza fechas que no son futuras | Implementado. En el CI: `organizador.spec.ts` y escenario 24 |
| CU-09 | Ver estadísticas | Organizador | El panel de `/organizador` muestra solo los eventos del organizador (`GET /api/eventos/mios`): las entradas vendidas y lo recaudado sin el cargo de servicio, del resumen de Ventas (`GET /api/v1/resumenes`, solo compras pagadas y vigentes), el cupo restante, la reventa activa y los accesos en puerta. Desde el 24/11 (DEF-48), "Ventas y liquidación" muestra por evento lo recaudado, el cargo de Palco, lo que se le liquida al organizador y la tabla "Ventas por día". El resumen de un evento ajeno responde 404 | Implementado. En el CI: el resumen por la API en el escenario 24; en el navegador, `organizador.spec.ts` (las ventas por día y la liquidación después de una compra) y `accesibilidad.spec.ts` |
| CU-10 | Validar entrada en puerta | Personal de puerta | El escaneo (`POST /api/v1/validacion/escaneos`) usa la entrada en Ventas con un UPDATE condicional, guarda el renglón del registro y devuelve el resultado con el titular (sección 1.3) | Implementado. En el CI: `puerta.spec.ts` y escenario 16 |
| CU-11 | Cancelar evento | Organizador | `POST /api/eventos/{eventoId}/cancelacion`, con el motivo: el evento sale del catálogo, se anulan las compras (también las de la reventa), se devuelve cada pago, se retira la reventa y se avisa a cada comprador (10.8) | Implementado. En el CI: `organizador.spec.ts` y escenario 22 |
| CU-12 | Editar evento | Organizador | `PUT /api/eventos/{eventoId}`, con `If-Match` opcional (4.2): un sector con ventas no se quita ni cambia de precio, y su cupo no baja de lo vendido | Implementado. En el CI: `organizador.spec.ts` |
| CU-13 | Recuperar la contraseña | Comprador, organizador, personal de puerta | `POST /api/usuarios/password/olvide` manda un código de 6 dígitos por email, con la misma respuesta exista o no la cuenta, y `POST /api/usuarios/password/reinicio` cambia la contraseña con ese código. Pantalla `/recuperar`, desde "¿Olvidaste tu contraseña?" en el ingreso | Implementado. En el CI: `recuperar.spec.ts` y escenario 24 |
| CU-14 | Asignar personal de puerta | Organizador | `PUT` y `DELETE /api/v1/validacion/puertas/{eventoId}/{email}`: solo en sus eventos y solo cuentas de puerta. Pantalla: Equipo de puerta, en el panel del organizador | Implementado. En el CI: escenario 21, por la API; la pantalla no tiene prueba E2E |
| CU-15 | Subir la foto del evento (extiende CU-08 y CU-12) | Organizador | `POST /api/eventos/imagenes` con una imagen JPG, PNG o WEBP de hasta 2 MB; el evento se guarda con la URL que devuelve | Implementado. En el CI: `organizador.spec.ts` (la foto que sirve el servidor es la que se subió) |

Los diagramas de secuencia de cada integración que recorren estos casos están en la sección 13.

## 1.5 Alcance del trabajo práctico

**Entra en el alcance:**

- Los 8 componentes de negocio definidos en la Entrega Parcial N.º 1, cada uno como un WAR independiente con su propio esquema de base: Usuarios, Eventos, Ventas, Pagos, Validación, Reventa, Notificaciones y Facturación. Se comunican solo por REST o por JMS. Un WAR más, el asistente con IA (sección 4.10), responde dudas de los usuarios a través de un gateway y no guarda datos. Otro, el ARCA simulado, representa al sistema externo legado y no es un componente de Palco.
- Los cuatro flujos de la sección 1.3 y los quince casos de uso de la sección 1.4, de punta a punta, con el frontend React conectado al backend.
- La integración síncrona por REST (API propia y cliente de la pasarela de pago) y por SOAP (facturación contra el sistema legado), y la asincrónica por cola y tópico sobre el ActiveMQ Artemis embebido en WildFly.
- Seguridad declarativa por rol y transacciones declarativas en el flujo de compra.

**Queda fuera del alcance** (o se resuelve con una versión simulada):

- **Cobro real.** El 13/10 se decidió no integrar Mercado Pago real: Pagos sigue con `PagosFake` y el requisito de REST lo cubre la API propia. El diseño de la integración real queda en el diagrama `docs/entrega-obligatoria-2/diagramas/diagrama_secuencia_mercadopago.png`, y se retoma si la cátedra pide una pasarela real. El 16/10 la cátedra confirmó que la pasarela simulada se acepta.
- **Envío real de emails.** No hay proveedor de correo configurado: sin `PALCO_SMTP_HOST`, el código de verificación y el aviso de compra quedan en el log del servidor (`CorreoAlLog`), con el destinatario enmascarado y sin el cuerpo, salvo en desarrollo, donde `PALCO_CORREO_LOG_COMPLETO=true` escribe el email entero (14.6).
- **Verificación de identidad con foto del DNI.** Al verificar el email, la cuenta queda con la identidad verificada; no hay validación documental.
- **Facturación contra el ambiente real de AFIP/ARCA.** La cátedra aceptó el 16/10 que la integración sea simulada. Se implementó el diseño decidido ese día, el más simple que cumple la consigna, y se probó en el servidor el mismo 16/10 (detalle en las secciones 4.8, 4.9 y 11):
  - ARCA es un servicio SOAP propio (`ArcaSimulado`, un POJO con `@WebService`) en un WAR aparte, `backend/servicios/arca`, publicado en `/legado/arca`, que representa al sistema externo legado. WildFly genera el WSDL a partir de la clase (`/legado/arca?wsdl`); para eso el servidor suma la capa `webservices`.
  - Facturación se suscribe de forma durable al tópico `topico.compra-confirmada` (`FacturacionDeComprasMDB`) y, por cada compra confirmada, le pide el CAE al ARCA simulado por SOAP (operación `FECAESolicitar`, síncrona). Guarda el número y el CAE en su esquema. La numeración la asigna el ARCA simulado.
  - Si ARCA no responde, el MDB deshace la entrega y se usan los reintentos del broker que ya existen (5 entregas cada 2 segundos y después la DLQ). No hay una cola de reintento aparte.
  - Si ARCA rechaza el comprobante, reintentar no lo arregla: desde el 20/11 (DEF-33) el rechazo queda anotado con su motivo en `facturacion.comprobante_rechazado`, el mensaje se confirma sin volver al broker y la consulta de la factura responde 409 `comprobante-rechazado`. Lo revisa una persona (sección 10.6).
  - Ventas no cambia: la factura se consulta en Facturación (`GET /api/v1/facturas/{ventaId}`) y el campo `facturaEstado` de la venta sigue en `NO_APLICA`.
  - El cliente SOAP de Facturación se arma con una interfaz anotada (`PuertoArca`) y `Service.create` sobre el WSDL, sin generar código. El lector XML de CXF trae la DTD y las entidades externas deshabilitadas, y Facturación se niega a crear el cliente si alguien las habilita (SEC-I01).
  - Lo que el ARCA simulado no reproduce del real: no tiene autenticación (el real usa WSAA), numera en memoria y vuelve a 1 al redesplegarse, y si se le pide de nuevo el CAE de la misma venta devuelve el mismo comprobante.
- **Despliegue en la nube.** La consigna no lo exige. El sistema se levanta en una sola máquina con `docker compose`: WildFly con los diez WAR, nginx con TLS y PostgreSQL 17 (`postgres:17-alpine`), con los datos en un volumen. H2 en modo PostgreSQL queda para `backend/dev.sh` y los tests. Supabase no se usa como base: el datasource apunta a cualquier PostgreSQL por variables de entorno (3.5), y Supabase Storage es un almacenamiento opcional para las fotos de los eventos (8.3).
- **QR firmado.** El código de la entrada es un UUID al azar que genera Ventas: no se puede adivinar, pero no lleva firma.
- Modo offline de la puerta, el pago de la liquidación al vendedor y al organizador (el panel calcula lo que se le liquida al organizador, pero Palco no lo transfiere), reembolsos a pedido del comprador (Palco devuelve un pago solo en dos casos: una compra en la reventa que Ventas no pudo transferir, 7.10, y las compras de un evento cancelado, 10.8), y anulación de facturas o notas de crédito.

# 2. Requisitos y estado

## 2.1 Cómo leer la tabla

La tabla reúne los requisitos de cada entrega de la consigna (sección 5) y los transversales obligatorios (sección 6), con su estado al **25/11**. Las filas C3, del 3.er check point (20/11), salen del pedido de la cátedra para esa fecha y dicen con qué evidencia se cumplieron el **19/11**. Las fechas son las de la comisión del viernes; la consigna está escrita para la comisión del lunes y sus fechas se citan solo como referencia.

| Entrega (nombre de la consigna) | Fecha del viernes | La consigna dice |
|---|---|---|
| Entrega Parcial N.º 1 | 04/09 (ya pasó) | 31/08 |
| Entrega Obligatoria N.º 1 (1.º Parcial) | 18/09 (ya pasó) | 14/09 |
| Entrega Parcial N.º 2 | 23/10, **sin confirmar con la cátedra** | 19/10 |
| Entrega Obligatoria N.º 2 (2.º Parcial) | 13/11 | 09/11 |
| 3.er check point (no figura en la consigna) | 20/11 | No figura |
| Entrega Final | Sistema completo y defensa el viernes 27/11, a las 08:40 (cronograma de exposiciones de la cátedra del 12/11; el 16/10 el grupo había elegido el 04/12) | 30/11 en el detalle, 04/12 u 11/12 en el encabezado |

El 16/10 la cátedra confirmó que se entrega con las fechas del cronograma de la comisión del viernes, que el grupo de cinco integrantes está aceptado (la consigna fija de 3 a 4), que la documentación que se pide es técnica, con diagramas de casos de uso y casos de uso, y que no pide Figma ni manual de usuario. El mensaje de la cátedra del 12/11 sumó después la primera versión del manual de usuario al 3.er checkpoint (fila C3.7), y el prototipo navegable de las pantallas principales que presentó el grupo quedó aprobado por la cátedra. Las 8 a 12 páginas del documento técnico del 13/11 son de contenido, sin contar los anexos. El mismo día el grupo decidió defender la Entrega Final el 04/12, la primera fecha posible después de tener el sistema completo el 27/11. El 20/11 la pasó al viernes 27/11, el día que le asigna el cronograma de exposiciones que la cátedra publicó el 12/11. La hora límite de subida no está confirmada, y por eso cada entrega se sube el jueves anterior (18.2).

Estados: **Cumplido** (está en el código o en la evidencia y se puede mostrar), **Parcial** (una parte está hecha y otra no, o una pieza está simulada) y **No está en el repo** (no hay evidencia en el repositorio). La primera prueba en el servidor de los ocho componentes fue la del 16/10, con el servidor armado desde cero: `mvn clean install` con 577 tests y 0 fallas, los 9 WAR desplegados con sus 8 `health` en 200 y el WSDL del ARCA simulado en 200, los 17 escenarios de integración (`scripts/pruebas/escenarios.mjs`), la colección de Postman con Newman (126 requests, 406 aserciones, 0 fallas) y la demo de la compra con `curl`, con el pago aprobado y con el rechazado. Desde el 3.er check point lo prueba el CI en cada push contra el `docker compose`: la corrida del 21/11 sobre `fbd31dc` pasó entera, con los 24 escenarios, la colección de Postman y las 31 pruebas E2E de entonces, que recorren en el navegador las pantallas de puerta, reventa y panel del organizador (16.7). Las filas C3 se apoyan en las corridas del 18/11 guardadas en `docs/testing/evidencia/checkpoint-3/` y en la colección de Postman del 19/11 (sección 16.7). Las rutas de código son relativas a `backend/servicios/<servicio>/src/main/java/com/palco/<servicio>/` salvo que se indique otra cosa.

## 2.2 Requisitos por entrega

| # | Requisito | Se exige en | Estado al 25/11 | Dónde se ve |
|---|---|---|---|---|
| E1.1 | Componentes candidatos (mínimo 6) con su interfaz: nombre, operaciones y responsabilidad | Entrega Parcial N.º 1 (04/09) | Cumplido | `docs/entrega1/Entrega_Parcial_1_Arquitectura.docx`, sección 2 (8 componentes con interfaz `I<Servicio>`) |
| E1.2 | Diagrama de arquitectura general | Entrega Parcial N.º 1 (04/09) | Cumplido | `docs/entrega1/Diagrama_Capas_EntradasEventos.drawio.png` |
| E1.3 | Stack tecnológico elegido y justificado | Entrega Parcial N.º 1 (04/09) | Cumplido | Documento de la Entrega Parcial N.º 1, sección 4 |
| E1.4 | Un componente completo desplegado en un servidor real, con capas separadas, y su justificación stateful o stateless | Entrega Parcial N.º 1 (04/09) | Cumplido | `ServicioDeEventos` (`@Stateless`) en WildFly; justificación en la sección 5 del documento |
| E1.5 | Documento de 2 a 4 páginas | Entrega Parcial N.º 1 (04/09) | Cumplido | `docs/entrega1/Entrega_Parcial_1_Arquitectura.docx` |
| O1.1 | Al menos 3 componentes implementados y desplegados, cada uno en capas | Entrega Obligatoria N.º 1 (18/09) | Cumplido hoy (los 8 componentes desplegados y probados en el servidor el 16/10). Si se cumplía al 18/09 no se puede ver en el repo: el historial de git tiene 3 commits hasta el 17/09 y el resto desde el 02/10 | Usuarios, Eventos, Ventas, Pagos, Notificaciones, Validación, Reventa y Facturación en `backend/servicios/` |
| O1.2 | Un componente stateful y uno stateless, con ciclo de vida gestionado por el contenedor | Entrega Obligatoria N.º 1 (18/09) | Cumplido | `ventas/negocio/ServicioDeVentas.java` (`@Stateful`, `@StatefulTimeout`, `@PostConstruct`, `@PreDestroy`, `@Remove`); `eventos/negocio/ServicioDeEventos.java` y `usuarios/negocio/ServicioDeUsuarios.java` (`@Stateless`) |
| O1.3 | Al menos 3 patrones de diseño aplicados y justificados por escrito | Entrega Obligatoria N.º 1 (18/09) | Cumplido: aplicados en el código y justificados por escrito en la sección 6 (dónde está cada uno, qué problema resuelve y por qué ese) y, desde el 19/11, en ADR-001 a ADR-003 (`docs/adr/`) | Factory: `pagos/pasarela/FabricaDePasarela.java`, `ventas/negocio/externos/FabricaDeCobros.java` y `facturacion/negocio/externos/FabricaDeArca.java`. Adapter: `ventas/negocio/externos/ClienteDePagos.java` y `facturacion/negocio/externos/ArcaSoap.java` (traduce el contrato SOAP de ARCA a la interfaz propia `Arca`). Repository (DAO): un repositorio por entidad en `datos/` de cada servicio implementado, por ejemplo `ventas/datos/VentaRepository.java`, `eventos/datos/EventoRepository.java` y `usuarios/datos/UsuarioRepository.java`. Interfaz con implementaciones intercambiables: `pagos/pasarela/PasarelaDePagos.java` |
| O1.4 | Seguridad declarativa mínima en una operación sensible | Entrega Obligatoria N.º 1 (18/09) | Cumplido | `@RolesAllowed("organizer")` en `POST /api/eventos` (`eventos/api/EventosResource.java`) |
| O1.5 | Documento técnico de 5 a 8 páginas | Entrega Obligatoria N.º 1 (18/09) | No está en el repo | Sin evidencia en el repo |
| P2.1 | Diagrama de arquitectura de integración (SOA) | Entrega Parcial N.º 2 (23/10, sin confirmar) | Cumplido | `docs/entrega3/Diagrama_Integracion_SOA.svg` |
| P2.2 | Clasificación justificada de cada integración externa en síncrona o asincrónica | Entrega Parcial N.º 2 (23/10, sin confirmar) | Cumplido | `docs/entrega3/Entrega_Parcial_2_Integracion.md` |
| P2.3 | Un proceso de negocio de punta a punta con mensajería real y broker configurado | Entrega Parcial N.º 2 (23/10, sin confirmar) | Cumplido | Compra → `PAGO_CONFIRMADO` → emisión de entradas. Broker: Artemis embebido, destinos en `backend/servidor/src/main/wildfly/configurar.cli`. Evidencia en `docs/entrega3/evidencia/` (tag `entrega-parcial-2`) |
| O2.1 | Dos procesos con mensajería de punta a punta: uno por cola y uno por tópico | Entrega Obligatoria N.º 2 (13/11) | Cumplido, con el aviso al comprador por email solo si hay SMTP (sin él, se escribe en el log) | Tres procesos de negocio distintos. **La emisión de las entradas, por cola:** `cola.emision-entradas` → `ventas/mensajeria/EmisionDeEntradasMDB.java`. **La compra confirmada, por tópico:** `topico.compra-confirmada`, con `PAGO_CONFIRMADO` (compra del catálogo) y `REVENTA_CONFIRMADA` (compra en la reventa, desde el 23/10) y tres suscriptores: `notificaciones/mensajeria/AvisoDeCompraMDB.java` (no durable), `AuditoriaDeComprasMDB.java` (durable) y `facturacion/mensajeria/FacturacionDeComprasMDB.java` (durable); cada compra deja 3 copias en el tópico (escenarios 7 y 17). **La cancelación de un evento, por otro tópico:** `topico.eventos-cancelados`, con `EVENTO_CANCELADO`, que publica Eventos y consumen Ventas y Reventa, y `COMPRA_ANULADA`, que publica Ventas y consumen el aviso y la auditoría de Notificaciones (10.8, escenario 22). Evidencia: `docs/entrega3/evidencia/auditoria.txt`, `suscriptor-durable.txt`, `mensajes-pendientes.txt` y `docs/testing/evidencia/final/cancelacion-con-reventa.txt` |
| O2.2 | Un servicio SOAP con su WSDL, justificado como sistema legado | Entrega Obligatoria N.º 2 (13/11) | Cumplido, con ARCA simulado (aceptado por la cátedra el 16/10). Probado en el servidor el 16/10: el WSDL responde 200 y cada compra recibe su factura con número y CAE (escenario 15 y carpeta 7 de Postman) | Servicio: `backend/servicios/arca/src/main/java/com/palco/arca/ArcaSimulado.java` (`@WebService`, operación `FECAESolicitar`), WAR propio en `/legado/arca`, WSDL generado por WildFly en `/legado/arca?wsdl`. Cliente: `facturacion/negocio/externos/ArcaSoap.java` y `PuertoArca.java`. Capa `webservices` en `backend/servidor/pom.xml`. Justificación en las secciones 1.5 y 11. Tests, al 20/11: ARCA simulado, 18 (incluyen el WSDL, un sobre SOAP real y, en `ContratoDelWsdlTest`, el WSDL publicado comparado con `docs/api/arca.wsdl` y los mensajes contra su XSD); Facturación, 50 |
| O2.3 | Un servicio REST (partner moderno o API de consumo externo) | Entrega Obligatoria N.º 2 (13/11) | Cumplido como API propia; el partner moderno (Mercado Pago) está simulado, y la cátedra aceptó la pasarela simulada el 16/10 | `docs/api/openapi.yaml`, colección de Postman en `docs/api/`, `pagos/pasarela/PagosFake.java` |
| O2.4 | Transacciones declarativas en un flujo crítico de varios pasos | Entrega Obligatoria N.º 2 (13/11) | Cumplido | `ServicioDeVentas` confirma la venta con `@TransactionAttribute(REQUIRED)` y publica con `common/mensajeria/PublicadorPagoConfirmadoJms.java` (`MANDATORY`, por `java:/JmsXA`), así que la venta y el mensaje se confirman o se deshacen juntos. La falla a mitad del flujo tiene prueba repetible en `scripts/pruebas/escenarios.mjs`: el escenario 13 deshace la transacción después de publicar y el mensaje no sale; el 14 la hace fallar después del cobro (propiedad `palco.ventas.fallar-despues-del-cobro`): 500 con `idError`, la venta sigue `EN_CHECKOUT` sin entradas ni mensaje, el pago queda `APROBADO` en Pagos y la conciliación (`ventas/negocio/ConciliacionDePagos.java`) confirma la venta con ese mismo pago y emite la entrada en menos de 3 minutos. Los dos pasaron en el servidor el 16/10 y corren en cada corrida del CI. La base entra a la transacción como último recurso y no como datasource XA: es una decisión con su riesgo aceptado (7.4 y sección 21) |
| O2.5 | Documento técnico de 8 a 12 páginas con un diagrama de secuencia por integración | Entrega Obligatoria N.º 2 (13/11) | Cumplido | `docs/entrega-obligatoria-2/Palco_Documento_Tecnico.md` (y su PDF). Diagramas de secuencia de la compra contra Pagos por REST (dos partes), la emisión por la cola (dos partes), el tópico con aviso y auditoría, la facturación por SOAP, la validación en puerta, la autenticación con el token de servicio, la reventa, la cancelación de un evento, la falla después del cobro y el asistente con IA, en `docs/documentacion/diagramas/` (secciones 13 y 13.10). El de Mercado Pago (`docs/entrega-obligatoria-2/diagramas/`) es diseño. Según la cátedra (16/10), las 8 a 12 páginas son de contenido, sin contar los anexos |
| O2.6 | Diagramas de casos de uso y casos de uso | Documentación técnica (pedido de la cátedra del 16/10) | Cumplido | Diagrama `docs/documentacion/diagramas/casos-de-uso.svg` y descripción de CU-01 a CU-15 en `docs/documentacion/casos-de-uso.md`; resumen en la sección 1.4. El manual de usuario se sumó en el 3.er checkpoint (fila C3.7) y el prototipo navegable de las pantallas principales lo aprobó la cátedra |
| C3.1 | Integraciones SOAP, REST y mensajería funcionando | 3.er check point (20/11), pedido de la cátedra | Cumplido (19/11) | SOAP: escenario 15, la factura con el CAE del ARCA simulado. REST: los 24 escenarios de `scripts/pruebas/escenarios.mjs` usan la API y cruzan servicios (24 de 24, 379 comprobaciones, el 18/11: `docs/testing/evidencia/checkpoint-3/escenarios.txt`), y la colección de Postman (16.7). Mensajería: la compra por cola y tópico (escenarios 7 a 13) y la cancelación de un evento por su tópico (escenario 22, sección 10.8) |
| C3.2 | Resiliencia: tiempo máximo de 5 s con fallback | 3.er check point (20/11) | Cumplido (19/11) | Cada llamada entre servicios corta a los 5 s de lectura y tiene su fallback (tabla de la sección 8.8). En el asistente, con el modelo demorado 8 s, el gateway respondió el fallback a los 5007 ms (`checkpoint-3/asistente.txt`, parte C). Contra el compose, con el ARCA sin responder, el cliente SOAP cortó cada pedido a los 5 s y la compra se emitió igual (`checkpoint-3/arca-lento-dlq.txt`). Escenarios 14, 18, 19 y 20 |
| C3.3 | Consumidores idempotentes, DLQ y ningún mensaje perdido si se apaga el broker | 3.er check point (20/11) | Cumplido (19/11) | Mensaje repetido sin efecto (escenario 8), mensaje inválido en la DLQ después de 5 entregas (escenario 12), el mensaje de Facturación en la DLQ con el ARCA sin responder y, devuelto con `move-messages`, una sola factura (29 de 29, `checkpoint-3/arca-lento-dlq.txt`), consumidor detenido y suscripción durable (escenarios 10 y 11) y `scripts/pruebas/broker-apagado.mjs`: con un mensaje pendiente se reinicia el servidor con el broker adentro y la venta llega a `EMITIDA` (10 de 10, `checkpoint-3/broker-apagado.txt`). En el compose el journal del broker está en el volumen `datos-servidor` (ADR-005), y el mensaje sobrevive también a recrear el contenedor: 16 de 16 (`checkpoint-3/broker-recreado.txt`) |
| C3.4 | `Idempotency-Key`, `ETag` y errores en Problem Details | 3.er check point (20/11) | Cumplido (19/11) | `Idempotency-Key` en la compra, el pago, el cobro y la compra en la reventa (escenario 2; 9.3). `ETag` con `If-None-Match` y 304 en 10 GET (`common/rest/FiltroDeEtag.java`; `scripts/pruebas/etag.mjs`, 19 de 19, `checkpoint-3/etag.txt`), e `If-Match` con 412 en la edición de un evento (4.2). Problem Details en `common/rest/ManejadoresDeErrores.java` (escenarios 4 y 5) |
| C3.5 | Funcionalidad de IA a través de su gateway | 3.er check point (20/11) | Cumplido (19/11), con el modelo simulado por defecto | El asistente (sección 4.10): gateway `asistente/negocio/AsistenteIA.java`, pantalla `/asistente`. `scripts/pruebas/asistente.mjs`: 11 de 11 (`checkpoint-3/asistente.txt`); `EvaluacionDelAsistenteTest`: 26 de 26 y 11 de 11 adversariales (`checkpoint-3/evaluacion-ia.txt`); carpeta 9 de Postman |
| C3.6 | CI con reporte de cobertura parcial | 3.er check point (20/11) | Cumplido (19/11) | `.github/workflows/ci.yml` (16.7): `mvn -B verify` con JaCoCo y la tabla de `scripts/qa/cobertura.mjs` en el resumen de la corrida. Cobertura del 19/11: 78,4 % de las líneas (`checkpoint-3/cobertura.md`). El CI corre además PMD y, desde el 20/11, la colección de Postman con Newman (job `api`) |
| C3.7 | Primera versión del plan de pruebas, de la matriz de trazabilidad y del manual de usuario | 3.er check point (20/11) | Cumplido (19/11) | `docs/testing/plan-de-pruebas.md`, `docs/testing/evidencia/matriz-de-trazabilidad.md` (la arma `scripts/qa/matriz.mjs`: 62 criterios de las 14 historias de entonces, los 62 con caso y en verde) y `docs/manual/manual-de-usuario.md`. Desde el 20/11 las historias son 16, con 89 criterios (la matriz regenerada el 24/11 da 89 de 89 en verde), y se suman la especificación de casos y el registro de defectos (16.7) |
| C3.8 | Tablero de tareas (Jira) al día | 3.er check point (20/11) | Fuera del repositorio | El tablero se lleva en Notion (18.5) y hay una exportación en el formato de Jira |
| F.1 | Sistema completo integrado, con el checklist de la sección 6 cumplido | Entrega Final (sistema y defensa el 27/11) | Cumplido, con la pasarela de pagos y ARCA simulados (aceptados por la cátedra el 16/10) | Los 8 componentes, el asistente y el ARCA simulado se levantan juntos con `docker compose`. El CI los prueba en cada push: la corrida del 21/11 sobre `fbd31dc` pasó entera, con los 24 escenarios, la colección de Postman y la suite E2E. El 25/11, sobre `12a08f2`, estaban en verde los siete workflows que corren con cada push; carga y estabilidad, que corren con un tag `v*` o a mano, lo estaban el 24/11 sobre `fbd31dc` (16.7 y 16.8). Los requisitos transversales están en 2.3 |
| F.2 | Documentación final de 10 a 15 páginas | Entrega Final (27/11) | Cumplido | `docs/final/documentacion-final.md`, recortada de este documento |
| F.3 | Repositorio git con historial incremental y participación visible de todos | Entrega Final (27/11) | Cumplido | Ver T.11 |
| D.1 | Declaración del uso de IA en el documento de cada entrega obligatoria (consigna, sección 9) | Entregas obligatorias (13/11 y final) | Cumplido | Sección "Declaración de uso de herramientas de IA generativa" de este documento, sección 15 del documento técnico del 3.er check point (`docs/checkpoint-3/Palco_Documento_Tecnico.md`) y `docs/final/declaracion-uso-ia.md` para la Entrega Final. La redacta el grupo, igual que los aprendizajes del equipo (decidido el 16/10) |

## 2.3 Requisitos transversales (sección 6 de la consigna)

Se exigen todos para la Entrega Final; varios ya se piden antes, en la entrega que figura en la tabla anterior.

| # | Requisito | Estado al 25/11 | Dónde se ve |
|---|---|---|---|
| T.1 | Mínimo 6 componentes de negocio, cada uno con interfaz explícita y documentada | Cumplido: los 8 componentes tienen su interfaz en el código y están probados en el servidor el 16/10 | `IUsuarios`, `IEventos`, `IVentas`, `IPagos`, `INotificaciones` (y `IAuditoria`), `IValidacion`, `IReventa` e `IFacturacion` en `negocio/` de cada servicio. Interfaces documentadas en `docs/entrega1/` y en la sección 4 |
| T.2 | Un componente stateful y uno stateless, justificados por escrito | Cumplido | `ServicioDeVentas` (`@Stateful`) y `ServicioDeEventos` (`@Stateless`); la justificación está en el comentario de cada clase y en el documento de la Entrega Parcial N.º 1 |
| T.3 | Arquitectura en capas (presentación, negocio, datos) en cada componente implementado | Cumplido | Los 8 componentes tienen las capas explícitas `api/` → `negocio/` → `datos/` → `dominio/`, y ningún Session Bean usa el `EntityManager`: el acceso a la base está en un repositorio por entidad (interfaz `@Local` más implementación `@Stateless` `<Entidad>RepositoryJpa`). Todos se probaron en el servidor el 16/10 con esa estructura. Los repositorios se prueban contra H2 en modo PostgreSQL con las migraciones (sección 3.3) |
| T.4 | Al menos 3 patrones de diseño distintos, aplicados y justificados | Cumplido | Ver O1.3: Factory, Adapter y Repository están en el código; la justificación por escrito está en los comentarios de las clases y en la sección 6 |
| T.5 | Integración SOAP con WSDL, justificada como sistema legado | Cumplido, con ARCA simulado | Ver O2.2 |
| T.6 | Integración REST con partner moderno o API de consumo externo | Cumplido como API de consumo externo; Mercado Pago simulado, aceptado por la cátedra el 16/10 | Ver O2.3 |
| T.7 | Dos procesos asincrónicos: uno por cola y uno por tópico | Cumplido | Ver O2.1: la emisión de las entradas por la cola `cola.emision-entradas`; la compra confirmada (`PAGO_CONFIRMADO` y `REVENTA_CONFIRMADA`) por el tópico `topico.compra-confirmada`, con aviso, auditoría y facturación; y la cancelación de un evento (`EVENTO_CANCELADO` y `COMPRA_ANULADA`) por el tópico `topico.eventos-cancelados` (10.8). Contratos en `docs/api/mensajeria-pago-confirmado.md` y `docs/api/mensajeria-cancelacion-de-eventos.md`; evidencia en los escenarios 7 a 13, 17 y 22 |
| T.8 | Seguridad declarativa (autenticación y autorización por rol) en al menos 2 operaciones sensibles | Cumplido | `@RolesAllowed` / `@PermitAll` en cada operación: `organizer` en el alta de eventos, `user` en las 6 operaciones de Ventas y en las de Reventa que cambian algo, `servicio` en el cupo, en Pagos, en Notificaciones, en el uso de la entrada y en las operaciones de reventa de Ventas, `staff` en el escaneo de la puerta, `staff` y `organizer` en su registro, y `user` o `servicio` en la consulta de la factura (una ajena da el mismo 404 que una inexistente). En el servidor, el 16/10, un comprador recibió 403 al escanear y el personal de puerta, 403 al pedir una factura. JWT RS256 firmado por Usuarios y validado con MicroProfile JWT. 401 sin token y 403 con rol incorrecto. Probado en el servidor el 16/10: el 401 por token inválido sale en Problem Details (`/problemas/token-invalido`) con `WWW-Authenticate: Bearer error="invalid_token"` y CORS (`common/rest/ErrorDeAutenticacionServlet.java` y `common/src/main/resources/META-INF/web-fragment.xml`); 5 logins fallidos en 15 minutos para un mismo email dan 429 con `Retry-After` (migración V4; los intentos los guarda `usuarios/datos/IntentoLoginRepository.java`, y la petición SEC-W04 de la colección de Postman pasó con esa clase); el 500 devuelve un `idError` que se busca en el log; PBKDF2 a 600.000 iteraciones, y desde el 24/11 un hash guardado con menos se recalcula al iniciar sesión (DEF-44). Desde el 24/11, `SeguridadDeclarativaTest`, en cada uno de los nueve servicios con recursos JAX-RS, falla si una operación queda sin `@RolesAllowed`, `@PermitAll` ni `@DenyAll` (DEF-43). Decididas el 16/10: la vigencia de la sesión (SEC-W05) queda en 8 horas sin refresh token, parcial con vigencia justificada (sección 18.5), y los permisos por destino en el broker (SEC-I02) quedan como riesgo aceptado |
| T.9 | Transacciones declarativas en un flujo crítico de varios pasos | Cumplido | Ver O2.4 |
| T.10 | Stack consistente y justificado (Jakarta EE, Spring Boot, .NET, etc.) | Cumplido | Jakarta EE 11 sobre WildFly 41, Java 25, Maven; frontend React + Vite + TypeScript. Justificación en la Entrega Parcial N.º 1, sección 4 |
| T.11 | Repositorio git con historial incremental | Cumplido | Al 25/11, `master` tiene 245 commits. Con el `.mailmap` de la raíz, que muestra con un solo nombre las identidades de cada persona, `git shortlog -sn master` da commits de los cinco: Santiago Loto, Valentina Frisoli (también como `valentinafrisoli`), Sebastian Deya, Julián Zuchowicki y Joaquin Molina. Hasta el 16/10 los commits se concentraban en cinco días y no había ninguno a nombre de Joaquin Molina; desde el 20/11 los cinco commitean su parte con su identidad, en commits chicos (18.1 y 18.4). Tag `entrega-parcial-2`; la Entrega Final sale del tag `v1.0-final` |

## 2.4 Desafíos opcionales (sección 7 de la consigna)

| Desafío | Estado al 25/11 | Dónde se ve |
|---|---|---|
| Resiliencia ante la caída de un sistema externo | Cumplido (C3.2 y C3.3) | Si un consumidor de mensajes se detiene, la compra sigue y los mensajes esperan en el broker (`docs/entrega3/evidencia/mensajes-pendientes.txt`); hasta 5 entregas con 2 segundos entre reintentos y después la cola de mensajes fallidos (`java:/jms/queue/DLQ`), configurados en `configurar.cli`. En el servidor, el 16/10, un mensaje inválido terminó en la DLQ con 2 copias, una por cada suscriptor durable del tópico (auditoría y facturación). Si Pagos no contesta o la confirmación falla después del cobro, la conciliación de pagos cierra la venta con el resultado real del cobro (probado el 16/10, escenario 14). Facturación usa los reintentos del broker cuando ARCA no responde, y despliega aunque ARCA esté caído porque baja el WSDL recién en el primer pedido; Validación registra `SIN_RESPUESTA` y responde 504 si Ventas no contesta. Esos casos de caída tienen tests. ARCA sin responder se provocó además contra el compose el 19/11: la compra se emitió igual, el mensaje de Facturación pasó a la DLQ después de 5 entregas y, devuelto, salió una sola factura (`checkpoint-3/arca-lento-dlq.txt`). Con Eventos detenido, la devolución del cupo se reintenta sola (escenario 18). Desde el 18/11, además: cada integración tiene su tiempo máximo y su fallback (8.8), el asistente responde las preguntas frecuentes si el modelo no contesta en 5 s, y `broker-apagado.mjs` reinicia el broker con un mensaje pendiente sin perderlo (C3.2 y C3.3). No hay Circuit Breaker: con un solo servidor, el corte a los 5 s y el fallback de cada llamada alcanzan para que una caída no frene al que llama |
| Heterogeneidad tecnológica (un componente en otra tecnología) | No se encaró (opcional) | Todos los servicios son Jakarta EE, el stack de la materia (3.1) |
| Prueba de escalabilidad horizontal | No se encaró (opcional) | El sistema corre en un solo WildFly (sección 21). La prueba de carga con k6 (16.8) mide un solo servidor |
| Al menos 2 ADR | Cumplido (19/11) | Cinco ADR en `docs/adr/`: el controlador de fachada de la compra (ADR-001), el State del ciclo de vida de la venta (ADR-002), el Adapter y la Factory de ARCA (ADR-003), la publicación y suscripción de `PAGO_CONFIRMADO` (ADR-004) y el broker embebido en WildFly, con su journal en un volumen del compose (ADR-005) |

# 3. Arquitectura general

## 3.1 Stack

| Pieza | Tecnología y versión | Para qué se usa |
|---|---|---|
| Lenguaje | Java 25 (`maven.compiler.release` 25 en `backend/pom.xml`) | Todo el backend |
| Plataforma | Jakarta EE 11 (`jakarta.jakartaee-api` 11.0.0) | JAX-RS, CDI, EJB (Session Beans y MDB), JPA, Bean Validation, JMS, JSON-B |
| Servidor | WildFly 41.0.1.Final, armado con Galleon | Contenedor de los 10 WAR: los 9 servicios de Palco (8 componentes de negocio y el asistente) y el ARCA simulado |
| Construcción | Maven multimódulo: `common`, `servicios` (un módulo por servicio) y `servidor` | Compila, prueba y arma el servidor con los WAR adentro |
| Base de datos | PostgreSQL 17 (`postgres:17-alpine`) en el `docker compose`; H2 en memoria en modo PostgreSQL con `backend/dev.sh` y en los tests. Se cambia de una a otra solo con variables de entorno | Un esquema por servicio |
| Migraciones | Flyway 13.9.0, scripts en `src/main/resources/db/migracion` de cada servicio | Crean y actualizan el esquema al desplegar |
| Seguridad | MicroProfile JWT 2.2 (capa `microprofile-jwt` de WildFly), tokens firmados con RSA por Usuarios. Cada servicio verifica la firma con la clave pública que descarga de `/api/usuarios/jwks` y exige el emisor `palco` (`common/src/main/resources/META-INF/microprofile-config.properties`; la dirección de Usuarios sale de `PALCO_URL_INTERNA`) | `@RolesAllowed` / `@PermitAll` en cada operación |
| Mensajería | ActiveMQ Artemis embebido en el mismo WildFly (capa `embedded-activemq`) | Cola de emisión de entradas, tópico de compras confirmadas y tópico de eventos cancelados |
| Servicios web SOAP | Jakarta XML Web Services (`jakarta.xml.ws-api` 4.0.2, `provided`) sobre JBossWS/CXF (capa `webservices`) | El ARCA simulado (`@WebService`) y el cliente SOAP de Facturación. En los tests se publica con Metro (`jaxws-rt` 4.0.3, solo `test`) |
| Frontend | React + Vite + TypeScript (`frontend/`) | SPA que consume la API REST |
| Asistente con IA | Sin biblioteca de IA: Jakarta Concurrency (`ManagedExecutorService`) para el tiempo máximo, MicroProfile Config para `palco.asistente.*` y el cliente JAX-RS para el modelo real | El gateway del asistente y sus dos modelos (sección 4.10) |

**Por qué este stack.** La Entrega Parcial N.º 1 eligió Jakarta EE porque es el stack de los laboratorios de la materia (WildFly), da soporte nativo a lo que pide el TP como requisito transversal (transacciones y seguridad declarativas, ciclo de vida manejado por el contenedor) y es portable entre servidores compatibles. Esa elección se mantuvo. H2 en modo PostgreSQL permite trabajar sin instalar una base, con la condición de que todo el SQL de las migraciones corra en las dos: las corren PostgreSQL 17 en el compose y en los `*IT`, y H2 en los tests (16).

**Estado del frontend.** Todas las pantallas usan el backend real. La pantalla de puerta llama a Validación, las de reventa llaman a `/api/v1/reventas` y el panel del organizador arma sus números con Eventos, Ventas, Validación y Reventa. La suite E2E recorre esas tres pantallas en el navegador en cada corrida del CI (16.7). En el navegador quedan solo la sesión y el carrito de la compra en curso (`localStorage`; el carrito, con la clave `palco.state.v3`).

## 3.2 Diez WAR independientes: nueve servicios y el ARCA simulado

Los nueve servicios de Palco son los ocho componentes de negocio y el asistente con IA (`backend/servicios/pom.xml`); el décimo WAR es el ARCA simulado, y `backend/servidor/pom.xml` despliega los diez. Cada componente de negocio es un WAR propio (`backend/servicios/<servicio>`), con su ruta (`context-root` en `WEB-INF/jboss-web.xml`) y, si guarda datos, su propio esquema (`hibernate.default_schema` en `META-INF/persistence.xml`). Todos comparten un único datasource de WildFly (`java:jboss/datasources/PalcoDS`), pero cada uno lee y escribe solo su esquema.

| Servicio | Ruta base | Esquema | Estado |
|---|---|---|---|
| Usuarios | `/api/usuarios` | `usuarios` | Implementado |
| Eventos | `/api/eventos` | `eventos` | Implementado |
| Ventas | `/api/v1` (`/ventas`, `/entradas`) | `ventas` | Implementado |
| Pagos | `/api/v1/pagos` | `pagos` | Implementado, con pasarela **simulada** (`PagosFake`) |
| Notificaciones | `/api/notificaciones` | `notificaciones` | Implementado; el aviso al comprador es **simulado** (se escribe en el log) |
| Validación | `/api/v1/validacion` | `validacion` (tablas `escaneo` y `puerta_asignada`) | Implementado |
| Reventa | `/api/v1/reventas` | `reventa` (tablas `publicacion` y `reembolso`) | Implementado |
| Facturación | `/api/v1/facturas` | `facturacion` (tablas `factura` y `comprobante_rechazado`) | Implementado |
| Asistente con IA | `/api/v1/asistente` | no tiene: no guarda datos | Implementado, con el modelo **simulado** por defecto (sección 4.10) |
| ARCA simulado (no es un servicio de Palco) | `/legado/arca` (WSDL en `/legado/arca?wsdl`) | no tiene: numera en memoria | Implementado |

El 16/10, con el servidor armado desde cero, se desplegaron los 9 WAR de entonces, los 8 `health` respondieron 200 (`/api/usuarios`, `/api/eventos`, `/api/v1`, `/api/v1/pagos`, `/api/v1/validacion`, `/api/v1/reventas`, `/api/notificaciones` y `/api/v1/facturas`), el WSDL de `/legado/arca?wsdl` respondió 200 y el log no tuvo ninguna línea `ERROR` al arrancar. El asistente se sumó el 18/11: desde entonces son 10 WAR y 9 `health`, y la CI pide los 9, `/api/v1/asistente/health` incluido, en cada corrida (`.github/workflows/ci.yml`, job de integración).

El WAR del ARCA simulado, `backend/servicios/arca`, representa al sistema externo legado (sección 4.9). Se arma con el resto, pero su `pom.xml` hereda de `palco-backend` y no de `palco-servicios`, así que no recibe `palco-common` ni ninguna clase de Palco: el WAR tiene solo sus propias clases. Queda fuera de `/api` porque no es parte de la API de Palco.

**Por qué un WAR por componente.** Hace visible en el despliegue el límite entre componentes: un servicio no puede usar las clases de otro porque no están en su WAR, y cada uno se puede dar de baja o redesplegar solo (la evidencia de la Entrega Parcial N.º 2 lo usa para mostrar qué pasa cuando Pagos no está). El costo es más configuración que en un único WAR, y que entre servicios no hay transacción compartida: cada llamada REST queda confirmada en el otro servicio apenas responde, y quien llama se hace cargo de compensar (por ejemplo, Ventas devuelve el cupo a Eventos si la compra no se completa; ver `ClienteDeEventos`).

**Rutas.** Usuarios, Eventos y Notificaciones nacieron en `/api/<servicio>`. Ventas y Pagos se publicaron versionados (`/api/v1`). Los servicios nuevos nacen versionados: Validación en `/api/v1/validacion`, Facturación en `/api/v1/facturas`, Reventa en `/api/v1/reventas` y el asistente en `/api/v1/asistente`. Las rutas viejas `/api/validacion`, `/api/facturacion` y `/api/reventa`, que solo tenían `health`, dejaron de existir. El `health` de cada uno queda bajo su ruta nueva (por ejemplo, `/api/v1/facturas/health`), y el nombre que informa sale del último tramo de la ruta: Facturación responde `"servicio": "facturas"`.

**Regla de integración.** Los servicios se hablan solo por REST o por JMS, nunca por clases o tablas del otro (`BaseDeDatos` en `common` lo deja escrito). Las llamadas entre servicios llevan un token con rol `servicio`, que consigue `TokenDeServicio` presentándole a Usuarios un secreto compartido (credenciales de cliente; token de 5 minutos). El secreto sale de `PALCO_SECRETO_SERVICIOS` y el código no trae un valor por defecto: si falta, cada servicio usa uno al azar que nadie conoce, no consigue tokens de servicio (y Usuarios no los entrega) y el log lo avisa (14.2). En desarrollo, `dev.sh` usa el de `backend/.env`, que no se versiona, o genera uno para ese arranque, y deja el que usa el servidor en `backend/servidor/target/secreto-servicios`; en el compose lo genera `backend/docker/arrancar.sh`. Los guiones de `scripts/pruebas/` y, desde el 25/11 (DEF-56), los de evidencia de `docs/entrega3/evidencia/` lo leen de la variable, de `backend/.env` o de ese archivo, y sin ninguno de los tres se cortan con un aviso; la colección de Postman, de su variable `secretoServicios`, que viene vacía. Fuera de desarrollo, el secreto es un requisito de despliegue (decisión del grupo del 16/10): `PALCO_SECRETO_SERVICIOS` se define con el mismo valor en todos los servicios. No se implementa un bloqueo del arranque: el sistema corre en un solo servidor que arma y configura el propio grupo, y sin la variable las llamadas entre servicios fallan y el log lo dice. La única excepción a "nada compartido" es `backend/common`, que no es un servicio sino una biblioteca que va adentro de cada WAR.

## 3.3 Capas dentro de cada servicio

Todos los servicios siguen la misma estructura de paquetes, tomada de Eventos y Usuarios:

| Paquete | Qué contiene | Regla |
|---|---|---|
| `api/` | Recurso JAX-RS y DTOs | Capa delgada: valida la entrada (Bean Validation), declara el rol y delega. No tiene lógica de negocio |
| `negocio/` | Interfaz `I<Servicio>` (`@Local`) y su Session Bean; migraciones (`Migraciones<Servicio>`, `@Singleton @Startup`); clientes de otros servicios y de sistemas externos en `negocio/externos/` | Acá viven las reglas y las transacciones |
| `datos/` | Un repositorio por entidad: interfaz `@Local` `<Entidad>Repository` e implementación `@Stateless` `<Entidad>RepositoryJpa` | El único lugar que usa el `EntityManager`. Está en los 8 componentes de negocio; el asistente no guarda datos |
| `dominio/` | Entidades JPA y enums de estado | Sin dependencias de JAX-RS |
| `mensajeria/` | Message-Driven Beans | Puntos de entrada delgados, como el recurso REST: leen el mensaje y delegan |

Pagos tiene además `pasarela/` (la interfaz `PasarelaDePagos`, su fábrica y el simulador `PagosFake`), que es su capa de integración con el exterior. En Facturación esa capa es `negocio/externos/` (el cliente SOAP de ARCA). Ventas, Notificaciones y Facturación tienen `mensajeria/` con sus MDB. Validación y Reventa, que llaman a otros servicios por REST, tienen sus clientes en `negocio/externos/`. El asistente tiene solo `api/` y `negocio/`: no guarda datos, y su `negocio/externos/` es el modelo de lenguaje (sección 4.10).

**Por qué una capa de datos explícita.** El negocio conoce solo la interfaz del repositorio, así que sus tests usan dobles en memoria y el acceso a la base se prueba aparte, contra H2 en modo PostgreSQL con las migraciones de Flyway (por ejemplo, `eventos/datos/CupoEnH2Test`, `usuarios/datos/RepositoriosEnH2Test` y `notificaciones/datos/RegistroDeAuditoriaEnH2Test` y `reventa/datos/PublicacionesEnH2Test`). Los repositorios son `@Stateless` con la transacción por defecto (`REQUIRED`): se suman a la del Session Bean o del MDB que los llama y comparten su contexto de persistencia. Por eso un cambio sobre una entidad administrada (marcar el email verificado, renovar el código) se guarda al confirmar sin un método aparte, y la decisión de no deshacer la transacción (`CodigoIncorrecto`, `CredencialesIncorrectas`) sigue en el negocio. Validación y Facturación, cuyos Session Beans corren sin transacción (`NOT_SUPPORTED`), tienen repositorios que abren una transacción corta por método; en Reventa pasa lo mismo durante la compra, que corre `NOT_SUPPORTED` (sección 4.7).

## 3.4 `backend/common`

Biblioteca compartida (`palco-common`) que va dentro de cada WAR. No es un servicio y no tiene estado propio.

| Clase | Qué hace |
|---|---|
| `ReglasDeNegocio` | Fuente única de las reglas: tope de reventa (+10 %), mínimo de reventa (50 %), cargo de servicio (10 %), máximo de 6 entradas por orden, cierre de la reventa 3 horas antes, reserva de 10 minutos, validación de DNI. Montos en pesos enteros (`long`). El frontend tiene una copia en `frontend/src/types/index.ts` que tiene que coincidir |
| `ErroresDeNegocio` | Excepciones de negocio (`@ApplicationException(rollback = true)`) que el negocio lanza sin depender de JAX-RS. Incluye `DemasiadosIntentos`, que lleva los segundos de espera (nunca menos de 1) |
| `rest/ManejadoresDeErrores`, `rest/Problema` | Traducen cada excepción a un código HTTP con cuerpo Problem Details (RFC 9457, `application/problem+json`). Nunca se devuelve un stack trace. `DemasiadosIntentos` sale como 429 con la cabecera `Retry-After`. El 500 (`Inesperado`) genera un UUID, lo escribe en el log junto con la traza y lo devuelve en el miembro de extensión `idError`; los demás errores no lo llevan |
| `rest/ErrorDeAutenticacionServlet` y `META-INF/web-fragment.xml` | El 401 por un token que no se puede verificar (firma alterada, mal formado) lo emite el mecanismo MP-JWT del servidor antes de llegar a JAX-RS. El `web-fragment.xml` del jar registra este servlet como página de error del 401 en todos los servicios, y responde `application/problem+json` (tipo `token-invalido`) con `WWW-Authenticate: Bearer error="invalid_token"` y las cabeceras CORS. Pedida directo, la ruta responde 404 |
| `rest/JsonProvider` | JSON-B en lugar del Jackson de RESTEasy: fechas ISO, `null` incluidos, y JSON mal formado como 400 con el mismo formato de error |
| `rest/HealthResource` | `GET <ruta del servicio>/health`: 200 si el servicio llega a la base, 503 si no |
| `rest/CorsFilter` | Orígenes permitidos desde `PALCO_CORS_ORIGENES`. Las cabeceras se arman en `cabecerasPara`, que usa también `ErrorDeAutenticacionServlet`: hay una sola lista de orígenes y cabeceras |
| `seguridad/Sesion`, `seguridad/TokenDeServicio` | Datos del usuario sacados del JWT; obtención y renovación del token de rol `servicio` |
| `mensajeria/PagoConfirmadoMensaje`, `DestinosJms`, `PublicadorPagoConfirmado(Jms)`, `MensajeInvalido` | El contrato del mensaje `PAGO_CONFIRMADO` (contrato en `docs/api/mensajeria-pago-confirmado.md`), los nombres JNDI de la cola y el tópico, y el publicador, que exige transacción (`MANDATORY`) para que el mensaje forme parte de la transacción de la venta |
| `email/Correos`, `FabricaDeCorreos`, `CorreoSmtp`, `CorreoAlLog`, `Email` | El envío de emails que usan Usuarios y Notificaciones. `FabricaDeCorreos` elige la implementación con `PALCO_CORREO` (`smtp` o `log`) y, sin ella, por SMTP si está `PALCO_SMTP_HOST` y al log si no. Desde el 20/11, en el log el destinatario va enmascarado (`Email.enmascarar`) y el cuerpo solo se escribe con `PALCO_CORREO_LOG_COMPLETO=true` (14.6) |
| `datos/BaseDeDatos`, `Textos` | Acceso a la base compartida y utilidades de texto |

**Por qué existe.** Las reglas de negocio y el contrato del mensaje tienen que ser los mismos para quien publica y quien consume; duplicarlos en cada servicio permitiría que diverjan. Lo que va en `common` es contrato y plomería transversal, nunca lógica de un servicio en particular.

## 3.5 Armado y despliegue del servidor

El módulo `backend/servidor` arma un WildFly a medida con el `wildfly-maven-plugin` y Galleon, en `servidor/target/server`. Usa los feature packs `wildfly-galleon-pack` (41.0.1.Final) y `wildfly-datasources-galleon-pack` (11.5.0.Final) y solo estas capas:

| Capa | Para qué |
|---|---|
| `jaxrs-server` | REST, CDI, JPA, Bean Validation y transacciones |
| `ejb` | Session Beans completos: `@Stateful`, `@Schedule` y Message-Driven Beans |
| `embedded-activemq` | Broker JMS dentro del mismo servidor |
| `microprofile-jwt` | Validación de los tokens que firma Usuarios |
| `h2-driver`, `postgresql-driver` | Drivers de las dos bases |
| `webservices` | JAX-WS sobre JBossWS/CXF: publica el ARCA simulado y genera su WSDL (sección 4.9); da también el cliente SOAP de Facturación |

La capa `webservices` se sumó para la facturación por SOAP (nombre verificado en el feature pack 41.0.1). El armado se saltea si `servidor/target/server` ya existe, así que tomarla obliga a rearmar el servidor desde cero una vez (`mvn clean install` con WildFly detenido), lo que vacía la H2 en memoria y los mensajes del broker.

Después de provisionar, aplica `backend/servidor/src/main/wildfly/configurar.cli`, que deja versionada toda la configuración:

- **Datasource `PalcoDS`**: H2 en memoria (`MODE=PostgreSQL`) por defecto; con `PALCO_DB_DRIVER=postgresql`, `PALCO_DB_URL`, `PALCO_DB_USUARIO` y `PALCO_DB_PASSWORD` apunta a un PostgreSQL sin tocar los servicios. Así lo configura el `docker-compose.yml`, contra su servicio `db` (PostgreSQL 17). Pool de hasta 10 conexiones, compartido por todos los servicios (por eso Pagos, Validación, Facturación, la compra en Reventa y el barrido de reservas evitan transacciones largas). El ARCA simulado no usa la base.
- **Mensajería**: la cola `cola.emision-entradas` (`java:/jms/queue/emisionEntradas`, punto a punto) y los tópicos `topico.compra-confirmada` (`java:/jms/topic/compraConfirmada`) y `topico.eventos-cancelados` (`java:/jms/topic/eventosCancelados`), de publicación y suscripción. Reintentos para todos los destinos: hasta 5 entregas con 2 segundos entre una y otra, y después la cola de mensajes fallidos (DLQ) que trae WildFly.
- **Seguridad**: `resteasy-role-based-security=true`, para que `@RolesAllowed` y `@PermitAll` se apliquen en los recursos REST. Los roles salen del claim `groups` del JWT.

El servidor tiene como dependencias los 10 WAR (los 9 servicios y `palco-arca`) y los despliega. El script corre una sola vez, cuando se arma el servidor: un cambio en `configurar.cli` se aplica con `jboss-cli` sobre el servidor en marcha o rearmando con `mvn clean install` (lo que borra los mensajes guardados del broker y, con H2 en memoria, los datos). En desarrollo, `backend/dev.sh` levanta el servidor con los 9 servicios y el ARCA simulado.

## 3.6 Diagramas

**Diagrama de capas de la Entrega Parcial N.º 1** (entregada el viernes 04/09; la consigna dice 31/08):

![Diagrama de capas de la Entrega Parcial N.º 1](../entrega1/Diagrama_Capas_EntradasEventos.drawio.png)

Representa la arquitectura lógica en tres capas (presentación, negocio, datos) más una capa de integración con tres sistemas externos: ARCA (ex AFIP) por SOAP para factura electrónica, Mercado Pago por REST y un broker de mensajería para los procesos asincrónicos. La flecha marca el recorrido del caso de uso "comprar entrada". Es un diagrama de diseño: en esa fecha solo estaba implementado ServicioDeEventos. Lo que cambió desde entonces: la capa de negocio quedó repartida en 8 WAR con un esquema cada uno; ARCA y Mercado Pago no tienen conexión real, y el 16/10 la cátedra aceptó que los dos queden simulados (Pagos usa una pasarela simulada y Facturación habla por SOAP con un ARCA simulado en un WAR aparte); el broker sí está funcionando.

**Diagrama de integración SOA de la Entrega Parcial N.º 2** (`docs/entrega3/`, para la entrega del 23/10, fecha sin confirmar con la cátedra):

![Diagrama de integración SOA](../entrega3/Diagrama_Integracion_SOA.svg)

Lleva el título "arquitectura de integración (SOA) al 13/10/2026": es una foto del sistema en esa fecha. Muestra los 8 servicios desplegados en WildFly, cada uno con su ruta y si es stateless o stateful; el broker Artemis con la cola `cola.emision-entradas` (I4, asincrónica, con su único consumidor `EmisionDeEntradasMDB`), el tópico `topico.compra-confirmada` (I5, con los dos suscriptores de Notificaciones) y la DLQ; las llamadas REST síncronas entre servicios (S1: Ventas hacia Usuarios, Eventos y Pagos; S2: los servicios hacia Usuarios por la clave pública y el token de servicio) y los dos sistemas externos, Mercado Pago (I2) y ARCA (I3, SOAP con timeout de 5 s y `cola.reintento-facturacion`). Las flechas llenas están implementadas y comprobadas en el servidor el 13/10; las punteadas (Facturación, Reventa y Validación, Mercado Pago, ARCA) están diseñadas y pendientes. Desde esa fecha el diagrama quedó desactualizado en cuatro puntos. Validación, Reventa y Facturación ya están implementadas, se probaron en el servidor el 16/10 y cambiaron de ruta (`/api/v1/validacion`, `/api/v1/reventas` y `/api/v1/facturas`). Facturación dispara la factura desde una suscripción durable al tópico, le pide el CAE por SOAP al ARCA simulado, que es un WAR aparte en `/legado/arca`, no tiene `cola.reintento-facturacion` (los reintentos son los del broker) y no informa la factura a Ventas (sección 4.8). Validación llama por REST a Ventas para usar la entrada (sección 4.6). Y Reventa llama por REST a Ventas (estado y transferencia de la entrada), a Pagos (cobro) y a Eventos (fecha del evento), sin mensajería (sección 4.7). Queda como se entregó; la versión vigente es la que sigue.

**Diagrama de integración SOA vigente** (`docs/documentacion/diagramas/integracion-soa.svg`):

![Diagrama de integración SOA vigente](diagramas/integracion-soa.svg)

Muestra el sistema como está en el código: los 8 componentes de negocio y el gateway de IA, un WAR cada uno en WildFly 41, con su ruta y si son stateless o stateful; el broker con `cola.emision-entradas`, `topico.compra-confirmada`, `topico.eventos-cancelados` y la DLQ (5 entregas, cada 2 s), y los MDB que consumen cada destino; las llamadas REST entre servicios con su ruta; y los sistemas externos: el ARCA simulado por SOAP, el proveedor de IA (opcional, con `PALCO_ASISTENTE_MODELO=openai`), Supabase Storage y el servidor SMTP (opcionales). El nginx con TLS figura como pieza del `docker compose`. Los diagramas de secuencia de cada integración están en la sección 13.

# 4. Componentes

Los ocho componentes salen de la Entrega Parcial N.º 1 y cada uno es un WAR de `backend/servicios/`: Usuarios, Eventos, Ventas, Pagos, Notificaciones, Validación, Reventa y Facturación. Los ocho están implementados y se probaron en el servidor el 16/10, armado desde cero: `mvn clean install` con 577 tests y 0 fallas, los 17 escenarios de integración de entonces (`scripts/pruebas/escenarios.mjs`) y la colección de Postman con Newman (126 requests, 406 aserciones, 0 fallas). Desde el 3.er check point los prueba el CI en cada push contra el `docker compose` (16.7). `backend/servicios/` tiene además otros dos WAR: el asistente con IA y el ARCA simulado. La sección 4.9 describe el ARCA simulado, que no es un componente de Palco sino el sistema legado con el que se integra Facturación. La 4.10 describe el asistente con IA, un servicio de Palco que se sumó el 18/11 y no es uno de los ocho componentes de la Entrega Parcial N.º 1. En las tablas de endpoints, "rol" es la anotación de seguridad de la operación en el recurso JAX-RS. Todos los servicios responden `GET <ruta>/health` sin token.

## 4.1 Usuarios

- **Responsabilidad:** identidad, autenticación y roles. Registra usuarios, verifica el email con un código, inicia sesión y firma los tokens JWT que validan los demás servicios. También emite el token de rol `servicio` para las llamadas entre servicios.
- **Interfaz:** `IUsuarios` (`@Local`): `registrar`, `verificarEmail`, `reenviarCodigo`, `iniciarSesion`, `pedirReinicioDePassword` y `reiniciarPassword` (recuperar la contraseña, CU-13), `obtener`, `rolDeLaCuenta` (la usa Validación al asignar una puerta) y `emitirTokenDeServicio`. El comentario de la interfaz lo presenta como "el servicio reutilizable del taller de integración: Ventas, Reventa, Validación y el panel del organizador dependen de él".
- **Stateless.** `ServicioDeUsuarios`: "cada operación recibe todo lo que necesita (los datos del pedido o el id del token) y lo que tiene que durar queda en la base, así que el contenedor reutiliza un pool de instancias".
- **Seguridad propia:** contraseñas con hash PBKDF2-HMAC-SHA256 (`seguridad/Contrasenas`; los hashes nuevos usan 600.000 iteraciones, `Contrasenas.ITERACIONES`; los anteriores, de 310.000, siguen verificando porque el formato guarda las iteraciones de cada hash, y desde el 24/11 se recalculan con 600.000 en el primer ingreso correcto, DEF-44), claves RSA para firmar los JWT (`seguridad/ClavesJwt`) y publicación de la clave pública en `/jwks`. El código de verificación admite hasta 5 intentos (`MAX_INTENTOS_CODIGO`).
- **Límite de intentos de login (SEC-W04):** con 5 fallidos en 15 minutos para un mismo email (`MAX_INTENTOS_LOGIN` y `VENTANA_INTENTOS_LOGIN`, en `ServicioDeUsuarios`), el pedido siguiente responde 429 `demasiados-intentos` con `Retry-After`, aunque la clave sea correcta; vale también para emails que no tienen cuenta, así el límite no revela cuáles existen. Los intentos los guarda y consulta `datos/IntentoLoginRepository` (implementación `IntentoLoginRepositoryJpa`; antes era `negocio/IntentosDeLogin`, con las mismas consultas). Viven en la base y no en memoria, así el límite es el mismo con varias instancias o después de un redespliegue. Un login correcto borra los fallidos de ese email. El 401 del login es `CredencialesIncorrectas`, que no deshace la transacción (`rollback = false`) para que el intento fallido quede guardado.

| Método | Ruta | Rol | Qué hace |
|---|---|---|---|
| POST | `/api/usuarios/registro` | `@PermitAll` | Alta de usuario; queda pendiente de verificar el email |
| POST | `/api/usuarios/verificacion` | `@PermitAll` | Verifica el código y devuelve la sesión |
| POST | `/api/usuarios/verificacion/reenvio` | `@PermitAll` | Reenvía el código |
| POST | `/api/usuarios/password/olvide` | `@PermitAll` | Manda un código de 6 dígitos para cambiar la contraseña; responde lo mismo exista o no la cuenta |
| POST | `/api/usuarios/password/reinicio` | `@PermitAll` | Cambia la contraseña con ese código |
| POST | `/api/usuarios/login` | `@PermitAll` | Inicia sesión y devuelve el JWT; 401 con credenciales incorrectas y 429 al superar el límite de intentos |
| GET | `/api/usuarios/yo` | `user`, `organizer`, `staff` | Datos del dueño del token (Ventas la usa para saber si el comprador verificó su identidad) |
| POST | `/api/usuarios/servicios/token` | `@PermitAll` (lo protege el secreto compartido) | Token de rol `servicio` |
| GET | `/api/usuarios/cuentas?email=` | `servicio` | El rol de una cuenta verificada (la llama Validación al asignar una puerta) |
| GET | `/api/usuarios/{usuarioId}` | `servicio` | Una cuenta por su id (la llama Notificaciones para el email del comprador) |
| GET | `/api/usuarios/jwks` | `@PermitAll` | Clave pública para validar los JWT |

- **Datos (esquema `usuarios`):** `usuario` (nombre, apellido, DNI único, email único, teléfono, fecha de nacimiento, hash de la contraseña, rol, email verificado, identidad verificada), `codigo_verificacion` (hash del código, vencimiento, intentos) e `intento_login` (SHA-256 del email normalizado y momento del intento fallido, con un índice por email y momento). V2, V3 y V6 cargan las seis cuentas de prueba (17.5); V4 crea `intento_login`; V5 rehashea las cuentas de prueba a 600.000 iteraciones (contraseña sin cambios, y solo si la fila conserva el hash original), para que su verificación no tarde distinto que la del hash falso que se calcula para un email inexistente y el tiempo de respuesta no deje ver qué emails existen. Con `PALCO_USUARIOS_CUENTAS_DE_PRUEBA=false`, las seis cuentas se desactivan al arrancar (`negocio/DesactivacionDeCuentasDePrueba`, sección 5.6).
- **Capa de datos:** `UsuarioRepository` (buscar por id, email o DNI, guardar, borrar), `CodigoVerificacionRepository` e `IntentoLoginRepository`. `borrar` hace `flush` para que el `DELETE` de una cuenta sin verificar salga antes del `INSERT` de la cuenta nueva con el mismo email o DNI.
- **Integraciones:** la consumen el frontend (sesión y recuperar la contraseña), Ventas (`ClienteDeUsuarios`, con el token del propio comprador), Validación (el rol de una cuenta) y Notificaciones (el email del comprador), estas dos con el token de servicio. Todos los servicios dependen de sus tokens.
- **Estado:** Implementado y probado en el servidor el 16/10. La sesión del usuario dura 8 horas (`DURACION_SESION`) y el token de servicio, 5 minutos. El pedido sin token a una operación con `@RolesAllowed` responde 401 con Problem Details (`ManejadoresDeErrores`); el 401 por token inválido, que rechaza el propio servidor antes de llegar al recurso, también sale como Problem Details (`ErrorDeAutenticacionServlet`, sección 3.4), y el 500 lleva `idError`. Verificación del 16/10, con el servidor armado desde cero: Usuarios desplegado en la migración v5; seis logins fallidos de un mismo email dieron cinco 401 y, el sexto, 429 con `Retry-After: 898`; una cuenta recién registrada completó registro, verificación, login y compra hasta `EMITIDA`. La verificación se hizo con la capa de datos de repositorios ya incorporada. Al 20/11, Usuarios tiene 75 tests (detalle en 16.1), entre ellos `RegistroYVerificacionTest`, `RepositoriosEnH2Test`, `LimiteDeIntentosDeLoginTest` y `MensajesDeValidacionTest` (14.5).
- **Vigencia de la sesión (SEC-W05): parcial, vigencia justificada** (decisión del grupo del 16/10). La sesión se mantiene en 8 horas, sin refresh token, porque una sesión de compra o de un organizador dura una jornada. El token se firma con RS256 y cada servicio lo valida con la clave pública; la clave se regenera al redesplegar Usuarios si no se define `PALCO_JWT_CLAVE_PRIVADA`, lo que invalida las sesiones abiertas; el token entre servicios dura 5 minutos. El riesgo de que se use un token robado durante su vigencia se acepta para el alcance del trabajo.

## 4.2 Eventos

- **Responsabilidad:** catálogo de eventos y sus sectores (precio y cupo). Alta de eventos por el organizador y control del cupo vendido.
- **Interfaz:** `IEventos`: `listarEventos`, `obtenerDetalle`, `crearEvento`, `actualizarCupo`, `devolverCupo` y, desde el 23/10, `listarDelOrganizador` y `organizadorDe`, para que cada organizador vea y administre solo sus eventos (4.6).
- **Stateless.** `ServicioDeEventos`: "cada operación es autocontenida y no depende de la llamada anterior, así que el contenedor reutiliza un pool de instancias". Fue el primer componente completo, presentado en la Entrega Parcial N.º 1 con esa misma justificación.

| Método | Ruta | Rol | Qué hace |
|---|---|---|---|
| GET | `/api/eventos?categoria=&q=` | `@PermitAll` | Lista eventos, con filtro por categoría y búsqueda |
| GET | `/api/eventos/mios` | `organizer` | Los eventos del organizador del token, ordenados por fecha (desde el 23/10). Lo usa su panel. Es una ruta fija: le gana a `{slug}`, y ningún evento recibe el slug `mios` |
| GET | `/api/eventos/{slug}` | `@PermitAll` | Detalle de un evento con sus sectores |
| POST | `/api/eventos` | `organizer` | Alta de evento; queda a nombre del organizador del token |
| PUT | `/api/eventos/{eventoId}` | `organizer` dueño | Edita el evento; para otro organizador no existe (404). Con `If-Match` y el `ETag` del detalle edita solo si el evento sigue como lo leyó el cliente; si cambió, 412 `evento-modificado` en Problem Details y no guarda nada. Sin la cabecera edita sin condición. La respuesta trae el `ETag` nuevo |
| POST | `/api/eventos/{eventoId}/cancelacion` | `organizer` dueño | Cancela el evento y publica `EVENTO_CANCELADO` en la misma transacción; cancelarlo otra vez da 409 `evento-cancelado` (sección 10.8) |
| GET | `/api/eventos/{eventoId}/organizador` | `servicio` | El dueño de un evento (la llama Validación; desde el 23/10) |
| POST | `/api/eventos/{eventoId}/sectores/{sectorId}/cupo` | `servicio` | Reserva o devuelve lugares de un sector (la llama Ventas) |

- **Datos (esquema `eventos`):** `evento` (slug único, título, categoría, lugar, fecha, descripción, destacado, imagen, organizador) y `sector` (nombre, precio en pesos enteros, cupo, vendidas). La base garantiza que `vendidas` esté entre 0 y el cupo (`sector_vendidas_validas`): ese chequeo es la última red contra la sobreventa. V2 a V4 cargan eventos iniciales, corren sus fechas y agregan fotos; V6 les pone dueño (`organizador@palco.test`).
- **Capa de datos:** `EventoRepository` (listar por categoría, buscar por slug, `existeSlug`, guardar) y `SectorRepository` (buscar por id, `sumarVendidas`). El UPDATE condicional del cupo quedó en `SectorRepository.sumarVendidas`: una sola sentencia con la condición `0 <= vendidas + cantidad <= cupo`, así que de dos compras simultáneas del último lugar la base deja pasar una sola; si cambió una fila, relee el sector. La regla de evento empezado (solo frena reservar; devolver siempre se acepta) sigue en `ServicioDeEventos`. La búsqueda por texto del catálogo se hace en Java, para que no dependa de cómo cada base compara los acentos.
- **Edición con `If-Match`:** el valor se compara con el `ETag` del estado actual, calculado con la misma función que el filtro del GET (`FiltroDeEtag.etagDeLaRepresentacion`), con la comparación fuerte de RFC 9110. La edición lee el evento y sus sectores con `SELECT ... FOR UPDATE` (`EventoRepository.buscarPorIdParaCambiar`): entre la comparación y el guardado no entra otra edición, una cancelación ni una venta de ese evento, y de dos ediciones con el mismo `ETag` la segunda recibe 412.
- **Fotos de los eventos** (`POST /api/eventos/imagenes`, rol `organizer`): solo JPG, PNG o WEBP de hasta 2 MB. Además del `Content-Type` se mira la firma del contenido, los primeros bytes fijos de cada formato (`TiposDeImagen.validar`): un archivo que no es imagen se rechaza con 400 `datos-invalidos` aunque se declare `image/png`, y nunca se guarda ni se sirve como imagen. Un SVG no se acepta (415). Sin Supabase configurado, la foto se guarda en la base de Palco.
- **Integraciones:** frontend (catálogo, alta y panel del organizador), Ventas (cupo, con token de servicio) y Validación (dueño del evento, con token de servicio). Publica `EVENTO_CANCELADO` en `topico.eventos-cancelados`, que consumen Ventas y Reventa.
- **Estado:** Implementado y probado en el servidor el 16/10, con la capa de datos. Eventos tiene 113 tests (al 20/11; detalle en 16.1).

## 4.3 Ventas

- **Responsabilidad:** la compra de punta a punta: reserva de lugares, datos de los titulares, cobro, confirmación y emisión de las entradas nominativas. Es dueña de las entradas.
- **Interfaz:** `IVentas`: `iniciarCompra`, `confirmarCompra`, `cancelarCompra`. Las tres son pasos de una misma conversación y se llaman sobre la misma instancia, por eso `confirmarCompra` y `cancelarCompra` no reciben el id de la venta. La Entrega Parcial N.º 1 listaba además `agregarEntrada`; en la implementación los titulares llegan todos juntos en `iniciarCompra` y esa operación no existe.
- **Stateful.** `ServicioDeVentas`: "una instancia por compra en curso, que el contenedor mantiene viva entre el pedido que abre la compra y el que la paga o la cancela. Guarda lo que todavía no está en la base: cuál es su venta y a nombre de quién va cada entrada (los titulares). [...] Un stateless no serviría: el pago podría caer en otra instancia del pool, que no conoce a los titulares." Muere con `@Remove` al pagar o cancelar (sobrevive a un pago rechazado, `retainIfException = true`) o por `@StatefulTimeout` si se abandona.
- **Piezas que rodean al stateful:**
  - `ComprasEnCurso` (`@ApplicationScoped`): índice de las compras abiertas, fachada que ordena los pasos que quedan fuera de la transacción (consultar a Usuarios, devolver cupo, marcar el intento de pago) y freno de la segunda llamada simultánea sobre la misma compra (409 `operacion-en-curso`).
  - `ReglasEntreCompras` (`@Stateless`): las dos reglas entre compras de una compra del catálogo (hasta 6 entradas por comprador y evento, y un DNI por evento), miradas otra vez con un candado y en la misma transacción que escribe (DEF-46). `ServicioDeVentas` ya las mira al abrir la compra, pero con dos compras a la vez ninguna ve a la otra y pasan las dos. `guardarDentroDelTope` (`REQUIRED`; como `iniciarCompra` no tiene transacción, el contenedor abre una corta) toma el candado del comprador y el evento, guarda la venta y recién después cuenta: si pasa el tope, 409 `tope-por-evento` y la venta no queda. `exigirDnisSinEntradaYCopiarTitulares` (`REQUIRES_NEW`, antes del cobro) toma el candado de cada DNI para el evento, mira que ninguno tenga entrada (409 `dni-con-entrada`) y copia los titulares a `venta_titular`. La segunda compra espera en el candado y, cuando entra, ya ve a la primera. Los mismos candados los toma `ReventaDeEntradas.transferir`, así una compra del catálogo y una en la reventa también se ordenan entre ellas. Stateless porque cada operación es independiente y no recuerda nada. Es un bean aparte del stateful porque una llamada de un bean a sí mismo no pasa por el contenedor y el atributo de cada operación no se aplicaría.
  - `VencimientoDeReservas` (`@Singleton` con `@Schedule`, sin estado): una vez por minuto pasa a `VENCIDA` las compras con la reserva vencida y devuelve el cupo a Eventos. La reserva tiene un solo reloj, la hora guardada en la base, porque el `@PreDestroy` del stateful puede no ejecutarse.
  - `EmisionDeEntradasMDB` (sin estado): consume `PAGO_CONFIRMADO` de la cola, pone el código QR a cada entrada, las deja `VALIDA` y pasa la venta de `CONFIRMADA` a `EMITIDA`. Es idempotente y lo garantiza la base.
  - `Cobros` con dos implementaciones, `ClienteDePagos` (Adapter sobre la API REST de Pagos) y `CobrosFake`, elegidas por `FabricaDeCobros` según `PALCO_VENTAS_COBROS` (por defecto, Pagos real).
  - `ReventaDeEntradas` (`@Stateless`, transacción por defecto): los cambios de estado que pide Reventa; queda fuera de `IVentas` por la misma razón que `UsoDeEntradas`. La transferencia es una sola transacción: la entrada original pasa de `PUBLICADA` a `VENDIDA` y nace una venta `EMITIDA` del comprador con una entrada nueva `VALIDA`, con código QR nuevo y el titular nuevo, o no pasa nada de eso. Es idempotente: si el comprador ya tiene la venta de esa publicación, la devuelve sin cambiar nada. Desde el 20/11 (DEF-30), antes del cobro, `controlarCompra` aplica a la compra en la reventa las dos reglas entre compras del catálogo, con las mismas consultas y los mismos errores de `ServicioDeVentas` (`exigirTopePorEvento` y `exigirDnisSinEntrada`): hasta 6 entradas por comprador y evento (409 `tope-por-evento`) y un DNI por evento (409 `dni-con-entrada`). La entrada que se revende no cuenta para el DNI (`VentaRepository.dnisConOtraEntradaParaElEvento`), así la persona a nombre de la que estaba puede comprarla; las demás entradas de esa venta sí cuentan. Desde el 24/11 (DEF-46), `transferir` las vuelve a mirar con los candados del comprador y del DNI para el evento, los mismos de `ReglasEntreCompras`, en la transacción que escribe la venta nueva: la compra en la reventa no deja nada en Ventas hasta la transferencia, así que con dos compras a la vez la mirada del control no alcanza. Si con esta entrada se pasa una regla, responde el mismo 409 y no cambia nada; Reventa devuelve el cobro y deja la publicación libre (7.10). La ruta es `/api/v1/operaciones-de-reventa` y no `/api/v1/reventas`, porque ese prefijo es el `context-root` del WAR de Reventa.
  - `UsoDeEntradas` (`@Stateless`, transacción por defecto): usa una entrada en la puerta a pedido de Validación. Queda fuera de `IVentas` porque esa interfaz es la conversación stateful de una compra, y usar una entrada no tiene nada que ver con ella. Busca la entrada por su código QR y la pasa de `VALIDA` a `USADA` con un UPDATE condicional (`EntradaRepositoryJpa.marcarUsada`, con la condición del estado dentro del UPDATE, igual que la emisión); después la relee. De dos escaneos simultáneos solo uno cambia la fila: el otro espera el bloqueo y ya la ve `USADA` (`UsoEnH2Test` lo prueba con dos conexiones a la vez). Una entrada de otro evento no se toca.
  - `CancelacionDeEventoMDB`, `CancelacionDeEventos` y `AnulacionDeCompras` (sin estado): la cancelación de un evento. El MDB consume `EVENTO_CANCELADO` y `CancelacionDeEventos` anula una por una las compras pagadas del evento. Cada compra se anula en su propia transacción: `AnulacionDeCompras.anular` lleva `REQUIRES_NEW` y es un bean aparte porque una llamada sobre `this` no pasa por el contenedor y el atributo no se aplicaría. Un fallo en una compra no deshace las anteriores (sección 10.8). Una entrada revendida deja dos compras pagadas del evento, y cada lugar se devuelve una sola vez, a quien lo tiene (desde el 20/11, 10.8).
  - `ReintentoDeReembolsos` (`@Singleton` con `@Schedule` en el segundo 45 de cada minuto, sin estado): vuelve a pedir los reembolsos que Pagos no contestó al anular una compra. La marca `reembolso_pendiente` (migración V4) la pone la misma transacción que anula la venta.
  - `ResumenDeVentas` (`@Stateless`): las ventas por día y la liquidación de un evento, para su organizador. No cuenta las compras en la reventa: ese lugar ya lo liquida la compra original, que sigue pagada aunque su entrada pase a `VENDIDA`.

| Método | Ruta | Rol | Qué hace |
|---|---|---|---|
| POST | `/api/v1/ventas` | `user` | Inicia la compra (cabecera `Idempotency-Key`); reserva cupo |
| GET | `/api/v1/ventas` | `user` | Compras del comprador |
| GET | `/api/v1/ventas/{ventaId}` | `user` | Una compra (404 si no es del comprador) |
| POST | `/api/v1/ventas/{ventaId}/pagos` | `user` | Paga la compra; al aprobarse, confirma y publica `PAGO_CONFIRMADO` |
| DELETE | `/api/v1/ventas/{ventaId}` | `user` | Cancela la compra y devuelve el cupo |
| GET | `/api/v1/entradas/{entradaId}` | `user` | Una entrada del comprador |
| POST | `/api/v1/entradas/usos` | `servicio` | Usa una entrada en la puerta (la llama Validación). 200 con la entrada `USADA`; 404 `no-encontrado`; 409 `entrada-de-otro-evento`, `entrada-ya-usada` o `entrada-no-habilitada` (publicada, vendida o anulada) |
| GET | `/api/v1/operaciones-de-reventa/entradas/{entradaId}?vendedorId=` | `servicio` | La entrada, si es de ese vendedor (la llama Reventa) |
| POST | `/api/v1/operaciones-de-reventa/publicaciones` | `servicio` | Pasa una entrada `VALIDA` del vendedor a `PUBLICADA` |
| POST | `/api/v1/operaciones-de-reventa/retiros` | `servicio` | Devuelve una entrada `PUBLICADA` a `VALIDA` |
| POST | `/api/v1/operaciones-de-reventa/controles-de-compra` | `servicio` | Antes de cobrar una compra en la reventa: tope de 6 entradas por comprador y evento, y un DNI por evento. 204 si puede comprar; 409 `tope-por-evento` o `dni-con-entrada` si no; 404 si la entrada no es del vendedor. No cambia nada, y es `POST` para que el DNI viaje en el cuerpo y no en la URL (desde el 20/11, DEF-30) |
| POST | `/api/v1/operaciones-de-reventa/transferencias` | `servicio` | Transfiere la entrada vendida en la reventa (sección 4.7) |
| GET | `/api/v1/resumenes?eventoId=` | `organizer` | Ventas por día y liquidación de un evento propio; el de otro organizador da 404 |

- **Datos (esquema `ventas`):** `venta` (comprador, evento, estado, estado de la factura, subtotal, cargo de servicio, total, vencimiento de la reserva, claves de idempotencia de la venta y del pago, id del pago), `venta_item` (sector, precio unitario, cantidad), `entrada` (titular con nombre y DNI, precio pagado, estado, código QR único) y `candado_de_compra` (migración V5, DEF-46: solo una clave, `comprador|<evento>|<comprador>` o `dni|<evento>|<dni>`; es la fila que bloquean con `SELECT ... FOR UPDATE` dos compras que pueden chocar, y `ReglasEntreCompras` mira la regla con ese bloqueo tomado. La crea la primera compra que la necesita, con `INSERT ... ON CONFLICT DO NOTHING`, y no se borra). La base exige que el total sea subtotal más cargo, que no haya dos entradas con el mismo DNI en una compra y que la clave de idempotencia sea única por comprador.
- **Integraciones:** REST síncrono con Usuarios (`/yo`), Eventos (cupo) y Pagos (cobro), con token de servicio salvo Usuarios; publica `PAGO_CONFIRMADO` en la cola y en el tópico dentro de la transacción de `confirmarCompra`, y `REVENTA_CONFIRMADA` en el tópico dentro de la transferencia de la reventa (10.1); consume la cola. Validación la llama por REST para usar una entrada, y Reventa, para consultar, publicar, retirar y transferir entradas y, desde el 20/11, para controlar una compra antes de cobrarla. Consume `EVENTO_CANCELADO` de `topico.eventos-cancelados` (suscripción durable) y publica en el mismo tópico un `COMPRA_ANULADA` por compra anulada, después de pedirle el reembolso a Pagos (`POST /api/v1/pagos/{pagoId}/reembolso`).
- **Estado:** Implementado y probado en el servidor el 16/10. Ventas tiene 366 tests (al 20/11; detalle en 16.1). `factura_estado` vale `NO_APLICA` en toda venta: la factura se consulta en Facturación y Ventas no se entera de ella.

## 4.4 Pagos

- **Responsabilidad:** cobrar una venta a través de una pasarela, guardar el pago y garantizar la idempotencia (un mismo pedido repetido no cobra dos veces).
- **Interfaz:** `IPagos`: `procesarPago`, `obtener`, `buscarPorClave` y, desde el 23/10, `reembolsar`. `confirmarPago` (el aviso de la pasarela), de la Entrega Parcial N.º 1, no está en la interfaz: depende del webhook de Mercado Pago, que es diseño (12.1).
- **Stateless.** `ServicioDePagos`: "no recuerda nada entre una llamada y la siguiente. Lo que tiene que durar (el pago y la clave con la que se pidió) queda en la base, así que cualquier instancia del pool puede atender cualquier pedido, también la repetición de uno anterior". Corre sin transacción propia (`NOT_SUPPORTED`) para no retener una conexión del pool mientras espera a la pasarela.
- **Pasarela:** `PasarelaDePagos`, elegida por `FabricaDePasarela` según `PALCO_PAGOS_PROVEEDOR` (por defecto `fake`; un valor desconocido impide el despliegue). Hoy la única implementación es `PagosFake`, que decide el resultado por el token de la tarjeta (`tok_rechazado`, `tok_sin_respuesta`, cualquier otro aprueba).

| Método | Ruta | Rol | Qué hace |
|---|---|---|---|
| POST | `/api/v1/pagos` | `servicio` | Procesa un pago (cabecera `Idempotency-Key`) |
| GET | `/api/v1/pagos?claveIdempotencia=` | `servicio` | El pago guardado con esa clave, o 404 `no-encontrado`. Lo usan las conciliaciones de Ventas y de Reventa |
| GET | `/api/v1/pagos/{pagoId}` | `servicio` | Consulta un pago |
| POST | `/api/v1/pagos/{pagoId}/reembolso` | `servicio` | Devuelve un pago `APROBADO` (`ventaId`, `motivo` y un `monto` opcional: sin él, el pago entero; con él, esa parte) y responde 200 con el pago `REEMBOLSADO` y su `montoReembolsado`. Repetirlo con el mismo monto no devuelve dos veces. 409 `pago-no-reembolsable` si no está aprobado, es de otra venta, el monto pasa lo cobrado o ya se devolvió por otro monto (desde el 23/10; el monto parcial, desde el 20/11) |

- **Datos (esquema `pagos`):** `pago` (venta, monto, medio de pago, proveedor, estado, motivo, clave de idempotencia única, id externo único, fechas y, si se devolvió, el motivo y la hora del reembolso, migración V2, y cuánto se devolvió, `monto_reembolsado`, migración V3).
- **Reembolso** (desde el 23/10; parcial desde el 20/11): la pasarela recibe el monto a devolver y la clave `reembolso:<pagoId>`, así dos pedidos a la vez no devuelven dos veces; después un `UPDATE` condicional pasa el pago de `APROBADO` a `REEMBOLSADO` y anota cuánto se devolvió. Un pago se devuelve una sola vez: entero cuando lo pide Reventa porque Ventas rechazó la transferencia (7.10), y entero o una parte cuando lo pide Ventas porque se canceló el evento (10.8). Si la pasarela no contesta, el pago sigue `APROBADO` y se puede repetir. `PagosFake` siempre devuelve.
- **Integraciones:** la llaman Ventas (compra del catálogo) y Reventa (compra en reventa), por REST con token de servicio. Mercado Pago no está conectado.
- **Caracteres de control** (desde el 25/11, DEF-77): `ServicioDePagos.sinCaracteresDeControl` rechaza con 400 `datos-invalidos` la clave de idempotencia, `ventaId`, `medioDePago` y el motivo de un reembolso si traen un carácter de control (`Textos.esDeControl`), antes de buscar, cobrar o devolver; un `pagoId` así da 404 sin consultar la base. Antes, el carácter nulo llegaba a PostgreSQL y la respuesta era un 500 (lo encontró la prueba de contrato, 16.8).
- **Estado:** Implementado, con pasarela **Simulada**, y probado en el servidor el 16/10; ese día la cátedra aceptó la pasarela simulada. Pagos tenía 36 tests al 23/10 (16.1); los de DEF-77 están en `ServicioDePagosTest`. Mercado Pago real con su webhook: Diseñado (diagrama de secuencia en `docs/entrega-obligatoria-2/diagramas/diagrama_secuencia_mercadopago.png`).

## 4.5 Notificaciones

- **Responsabilidad:** avisar al comprador cuando se confirma una compra y dejar registro de auditoría de cada compra confirmada.
- **Interfaces:** `INotificaciones` (`enviarConfirmacion`, `enviarConfirmacionDeReventa` y `enviarAlertaCancelacion`, que avisa la anulación de una compra cuando se cancela su evento, 10.8; `enviarRecordatorio`, de la Entrega Parcial N.º 1, no existe, porque ningún hecho del sistema lo dispara) e `IAuditoria` (`registrar`, `registrarAnulacion`, `listar`). Son dos interfaces porque "avisar puede fallar o perderse, auditar no".
- **Stateless.** `ServicioDeNotificaciones`: "cada aviso se arma solo con el mensaje que llega y no depende del aviso anterior". `ServicioDeAuditoria`: "registrar un mensaje no depende del anterior, y lo que tiene que durar queda en la base".
- **Consumidores del tópico `topico.compra-confirmada`:**
  - `AvisoDeCompraMDB`: suscripción no durable. Si el servicio está caído el aviso se pierde, y se acepta: "un aviso viejo no le sirve a nadie".
  - `AuditoriaDeComprasMDB`: suscripción durable (el broker guarda los mensajes mientras está detenido), un solo consumidor (`maxSession = 1`) para registrar en orden, y transacción del contenedor: si falla, reintento y DLQ al quinto intento.
- **Herramienta de desarrollo:** `PruebasDeMensajeria` publica mensajes de prueba (con `ventaId` que empieza con `prueba-`) para ver la mensajería sin hacer una compra. Decisión del grupo del 16/10: los endpoints `/pruebas/*` se mantienen, porque los usan la demo, la evidencia y la colección de Postman; son solo de desarrollo y piden rol `servicio`. Van en el WAR que se despliega: en un despliegue fuera de este trabajo, el comentario de la clase indica cómo apagarlos (una variable de entorno que dé 404 si falta y el listado sin las filas `prueba-`). Es una decisión con riesgo aceptado (sección 21).

| Método | Ruta | Rol | Qué hace |
|---|---|---|---|
| POST | `/api/notificaciones/pruebas/pago-confirmado` | `servicio` | Publica un `PAGO_CONFIRMADO` de prueba |
| POST | `/api/notificaciones/pruebas/pago-confirmado-con-falla` | `servicio` | Publica y falla, para mostrar que el mensaje no sale (forma parte de la transacción) |
| POST | `/api/notificaciones/pruebas/mensaje-invalido` | `servicio` | Publica un mensaje que no respeta el contrato, para mostrar el camino a la DLQ |
| GET | `/api/notificaciones/auditoria` | `servicio` | Lista el registro de auditoría |

- **Datos (esquema `notificaciones`):** `registro_auditoria` (id del mensaje como clave, tipo, venta, comprador, evento, monto total, cuerpo del mensaje y hora de recepción). V2 agranda la columna del cuerpo. Desde el 24/11 (DEF-37) el cuerpo se guarda sin el DNI de los titulares (`ServicioDeAuditoria.sinElDniDeLosTitulares`, para un mensaje publicado antes que llegue después) y la migración `V3__sacar_el_dni_de_la_auditoria.sql` se lo saca a las filas que ya estaban.
- **Capa de datos:** `RegistroDeAuditoriaRepository` (`existe`, `guardar`, `ultimos`). `guardar` hace `persist` y `flush`, para que un `INSERT` rechazado falle dentro del método y el MDB no escriba en el log que procesó el mensaje. `ServicioDeAuditoria` decide el registro idempotente y el listado de los últimos 50. Notificaciones tiene 33 tests, entre ellos `RegistroDeAuditoriaEnH2Test` (3).
- **Integraciones:** solo JMS (consume el tópico). Nadie la llama por REST salvo las pruebas y el listado de auditoría.
- **Estado:** Implementado y probado en el servidor el 16/10, con la capa de datos: la demo de la compra con `curl` terminó con código 0 con el pago aprobado y con el rechazado, y un mensaje inválido llegó a la DLQ con una copia por cada suscriptor durable. El aviso al comprador sale por `Correos`: por SMTP si se configura `PALCO_SMTP_HOST` y, si no, es **Simulado**: se escribe en el log (12.3). Ni el compose ni `dev.sh` configuran un SMTP.

## 4.6 Validación

- **Responsabilidad:** validar en la puerta que el código de una entrada exista, sea del evento que se controla y se use una sola vez, y llevar el registro auditable de cada escaneo.
- **Interfaz:** `IValidacion` (`@Local`): `escanear(codigoQr, eventoId, staff)`, `listar(eventoId, quien)` y `obtener(escaneoId, quien)`, y desde el 23/10 la puerta de cada evento: `misPuertas`, `equipoDePuerta`, `asignar` y `quitar`. `quien` es un `Solicitante` (id, email y si es organizador) que arma el recurso con el token. La Entrega Parcial N.º 1 definía dos operaciones, `validarEntrada` y `marcarComoUsada`; quedaron unidas en `escanear` porque, con dos pasos, entre "es válida" y "marcala" otra puerta podría usar la misma entrada.
- **Stateless.** `ServicioDeValidacion`: "cada escaneo es independiente y no recuerda nada del anterior. Lo que tiene que durar (que la entrada se usó) queda en la base de Ventas, y el registro de la puerta en la de este servicio, así que cualquier instancia del pool atiende cualquier escaneo de cualquier puerta". Corre sin transacción propia (`NOT_SUPPORTED`), igual que Pagos: entre leer el registro y guardar el escaneo espera a Ventas, y con una transacción abierta esa espera retendría una conexión del pool que comparten los servicios. Por la misma razón, el escaneo `SIN_RESPUESTA` queda guardado aunque después se devuelva el error.
- **Dueño y puerta asignada** (desde el 23/10; antes cualquier organizador veía el registro de cualquier evento y cualquier cuenta de puerta escaneaba en cualquiera): solo escanea la cuenta de puerta asignada a ese evento (si no, 403 `puerta-no-asignada`, sin llamar a Ventas ni dejar renglón en el registro). El registro lo ven esa cuenta y el organizador dueño del evento; para otro organizador el evento no existe (404, como en el resto de la API). La asignación la hace el dueño por email (tabla `puerta_asignada`, migración V2) y el escaneo la consulta en la base de Validación, así la puerta sigue funcionando aunque Eventos no responda. El dueño se le pregunta a Eventos (`ClienteDeEventos`, `GET /api/eventos/{eventoId}/organizador`); si Eventos no responde, el organizador recibe 504. Antes de asignar, Validación le pide a Usuarios el rol de la cuenta (`ClienteDeUsuarios`, `GET /api/usuarios/cuentas?email=`, rol `servicio`): una cuenta que no es `staff`, o que no existe o no verificó su email, responde 422 `no-es-de-puerta` y no se asigna (CA-14.3). El escaneo no depende de Usuarios.
- **Uso único:** no se decide en Validación sino en Ventas, la dueña de la entrada, con el UPDATE condicional de `UsoDeEntradas` (sección 4.3). La base bloquea la fila y solo uno de dos escaneos simultáneos cambia algo, sin transacción distribuida.
- **Capas:** `api/` (`EscaneosResource`, `PuertasResource` y DTOs), `negocio/` (`IValidacion`, `ServicioDeValidacion`, `Solicitante`, `MigracionesValidacion`), `negocio/externos/` (`ClienteDeVentas`, `ClienteDeEventos` y `ClienteDeUsuarios`), `datos/` (`EscaneoRepository`, `PuertaAsignadaRepository` y sus implementaciones JPA, una transacción corta por método) y `dominio/` (`Escaneo`, `PuertaAsignada` y el enum `ResultadoEscaneo`).

| Método | Ruta | Rol | Qué hace |
|---|---|---|---|
| POST | `/api/v1/validacion/escaneos` | `staff` | Escanea un código (`codigoQr` y `eventoId` en el cuerpo). Responde 201 con `Location` y el resultado (`VALIDA`, `YA_USADA`, `INEXISTENTE`, `OTRO_EVENTO` o `NO_HABILITADA`), `puedeEntrar`, el mensaje para el personal, el titular (solo con `VALIDA`) y el uso anterior (solo con `YA_USADA`). 504 `sin-respuesta` si Ventas no responde |
| GET | `/api/v1/validacion/escaneos?eventoId=` | `staff` asignado, `organizer` dueño | Registro de un evento, del más nuevo al más viejo, con un máximo de 500 renglones. Cada renglón trae el id de la cuenta que escaneó (`escaneadoPor`) y, desde el 20/11, su email (`escaneadoPorEmail`, null en los escaneos anteriores a la migración V4) |
| GET | `/api/v1/validacion/escaneos/{escaneoId}` | `staff` asignado, `organizer` dueño | Un escaneo; 404 si no existe o es de un evento que no le corresponde |
| GET | `/api/v1/validacion/puertas/mias` | `staff` | Los eventos donde la cuenta puede escanear |
| GET | `/api/v1/validacion/puertas?eventoId=` | `organizer` dueño | Las cuentas de la puerta del evento |
| PUT | `/api/v1/validacion/puertas/{eventoId}/{email}` | `organizer` dueño | Asigna la cuenta; 200 con la asignación, también al repetirlo |
| DELETE | `/api/v1/validacion/puertas/{eventoId}/{email}` | `organizer` dueño | Quita la cuenta; 204 aunque no estuviera |

- **Por qué 201 con cualquier resultado:** lo que se crea es el renglón del registro. "No puede entrar" es un dato y no un error HTTP, con el mismo criterio que Pagos aplica a un cobro rechazado.
- **Decisiones sobre los datos del escaneo:** el código viaja en el cuerpo y no en la ruta, para que no quede en los logs de acceso, y no se escribe en los logs de la aplicación. El registro guarda el código completo pero lo muestra recortado (`codigoParcial`, "…" más los últimos 6 caracteres), porque un código de otro evento escaneado por error sigue sirviendo allá. No guarda nombre ni DNI: son datos de Ventas, y a la entrada se llega por `entradaId`. Un 409 de Ventas con un tipo desconocido cuenta como `NO_HABILITADA`: ante la duda, no se deja entrar.
- **Si Ventas no responde:** el intento queda como `SIN_RESPUESTA` y el pedido devuelve 504. Si ese intento sí llegó a usar la entrada, el próximo escaneo da `YA_USADA` con `usoAnterior` apuntando a él y un mensaje para que el personal controle el documento.
- **Datos (esquema `validacion`):** tabla `escaneo` (id, código QR, evento, resultado, entrada, quién escaneó, con su id y su email, y cuándo, en UTC), con índices por evento y momento y por código; y tabla `puerta_asignada` (evento, email de la cuenta, quién la asignó y cuándo, con clave en el par evento y email). La migración V2 asigna `puerta@palco.test` a los seis eventos iniciales. La V4 (`V4__email_de_quien_escanea.sql`, 20/11, DEF-32) suma `escaneado_por_email` (`varchar(200)`, el largo de `puerta_asignada.staff_email`): el email sale del mismo token que el id (claim `upn`) y se guarda en minúsculas, también en los escaneos `SIN_RESPUESTA`. Admite null porque los escaneos anteriores a V4 no lo tienen, y no se completan: los emails son de Usuarios, otro esquema que este servicio no lee. Para esos el registro sigue mostrando el id. Sin claves foráneas a otros esquemas y sin check de lista de valores: los resultados válidos los controla el enum.
- **Integraciones:** REST síncrono con Ventas (`POST /api/v1/entradas/usos`, rol `servicio`) a través de `ClienteDeVentas` (`@ApplicationScoped`), con 2 segundos para conectar y 5 para leer; ante un 401 pide un token de servicio nuevo y reintenta una sola vez. Es síncrona porque la puerta necesita el resultado en el momento. La consumen la pantalla de puerta y el panel del organizador. Reventa no la llama: una entrada publicada o vendida da `NO_HABILITADA` porque su estado está en Ventas.
- **Decisiones con riesgo aceptado** (sección 21): el código QR es el UUID al azar que genera Ventas, que no se puede adivinar y no está firmado. Si falla el guardado de un escaneo `VALIDA` después de que Ventas usó la entrada, el pedido da 500 y el próximo escaneo da `YA_USADA` sin `usoAnterior`: la entrada no se usa dos veces y solo falta ese renglón del registro.
- **Estado:** Implementado y probado en el servidor el 16/10 por la API (escenario 16 y carpeta 5 de Postman): `OTRO_EVENTO` sin tocar la entrada, `VALIDA` con la entrada `USADA`, `YA_USADA` al repetir, `INEXISTENTE` y 403 para un comprador. El caso de Ventas sin responder (504 y `SIN_RESPUESTA`) lo cubren `ServicioDeValidacionTest` y `ClienteDeVentasTest`, y la pantalla `/puerta` la recorre `frontend/e2e/puerta.spec.ts` en cada corrida del CI. Desde el 21/11 (DEF-61), un código QR, un evento o un email con un carácter de control dan 400 sin llamar a Ventas ni guardar nada. Validación tiene 53 tests al 20/11 (detalle en 16.1); entre ellos, `RegistroEnH2Test` corre las migraciones en H2 modo PostgreSQL y lee un escaneo guardado antes de V4 con el email en null. Del lado de Ventas, `UsoDeEntradasTest` (6), `UsoEnH2Test` (3) y 2 tests en `EntradasResourceTest`.

## 4.7 Reventa

- **Responsabilidad:** reventa oficial de entradas nominativas entre usuarios con sesión (el email verificado es la verificación de identidad de esta etapa), con el precio entre el 50 % y el 110 % del precio de lista del sector y cierre 3 horas antes del evento. Las reglas salen de `ReglasDeNegocio` (`minimoReventa`, `topeReventa`, `HORAS_LIMITE_REVENTA`).
- **Interfaz:** `IReventa` (`@Local`): `listarActivas(eventoId)`, `listarDelVendedor`, `publicar`, `retirar` y `comprar`. De la Entrega Parcial N.º 1, `publicarReventa`, `cancelarPublicacion` y `comprarReventa` quedaron como `publicar`, `retirar` y `comprar`; se sumaron los dos listados que necesitan las pantallas.
- **Stateless.** `ServicioDeReventa`: "cada pedido llega completo (quién, qué publicación, qué precio, con qué tarjeta) y no hay una conversación que recordar entre un pedido y el siguiente, como sí la hay en el checkout de Ventas. Todo lo que tiene que durar queda en la base: el estado de la publicación y la compra en curso".
- **No es dueña de las entradas:** las tiene Ventas. Cada cambio de una entrada (publicarla, retirarla, transferirla) se le pide a Ventas por REST (`/api/v1/operaciones-de-reventa`, rol `servicio`, sección 4.3); Reventa guarda la publicación y su precio. Los dos servicios no comparten una transacción, así que cada operación decide qué hacer si la otra parte falla:
  - **Publicar:** primero controla, sin cambiar nada, que la entrada sea del vendedor y esté `VALIDA`, el rango de precio (`precio-fuera-de-rango`) y el horario (`reventa-cerrada`); después le pide a Ventas que la pase a `PUBLICADA` y recién ahí guarda la publicación `ACTIVA`. Si guardarla falla, le pide a Ventas que la retire. El mínimo y el tope se calculan sobre el precio de lista del sector, que Reventa le pide a Eventos (`ClienteDeEventos.precioDelSector`), y no sobre lo que pagó el vendedor: una entrada comprada en la reventa tiene como precio pagado el de esa reventa, y si el tope saliera de ahí cada reventa lo subiría otro 10 %. Eventos no deja cambiar el precio de un sector con entradas vendidas. Si Eventos no responde, no se publica nada.
  - **Retirar:** un UPDATE condicional deja la publicación `RETIRADA` y después Ventas devuelve la entrada a `VALIDA`. Si Ventas falla, la transacción se deshace y la publicación sigue `ACTIVA`. No se retira mientras alguien la está comprando.
  - **Comprar:** corre sin transacción propia (`NOT_SUPPORTED`), porque entre paso y paso llama a otros servicios. Desde el 20/11 (DEF-30), después de controlar el estado de la publicación y el cierre de 3 horas, y antes de tomarla, le pide a Ventas que controle la compra (`POST /api/v1/operaciones-de-reventa/controles-de-compra`): si el comprador pasaría de 6 entradas del evento o el DNI del titular ya tiene una entrada para ese evento, Ventas responde 409 `tope-por-evento` o `dni-con-entrada`, con el mismo texto que en la compra del catálogo, y no se toma ni se cobra nada. El reintento de una toma propia, con la misma clave y el mismo DNI, no se vuelve a controlar, porque su cobro pudo haber salido; con otro DNI, sí. Después toma la publicación con un UPDATE condicional que anota comprador, clave, titular y momento (de dos compras a la vez, cobra una sola), cobra en Pagos el precio más el cargo de servicio con una clave derivada de la `Idempotency-Key`, le pide a Ventas la transferencia y deja la publicación `VENDIDA`. Si el cobro se rechaza, la publicación se libera; si Pagos no responde, la toma queda: el mismo comprador puede reintentar con la misma clave sin que se cobre dos veces y, si no reintenta, la resuelve la conciliación de reventas (desde el 23/10, sección 7.10). La toma no vence por tiempo. Si el cobro sale aprobado y Ventas rechaza la transferencia, el pago se devuelve: la publicación queda `ANULADA` si la entrada ya no estaba publicada (desde el 23/10) y libre si con ella el comprador pasaba una regla entre compras (desde el 24/11, DEF-46; 7.10); si Ventas no responde, la toma queda como cuando no responde Pagos. No se puede comprar una publicación propia (`compra-propia`).

| Método | Ruta | Rol | Qué hace |
|---|---|---|---|
| GET | `/api/v1/reventas?eventoId=` | `@PermitAll` | Publicaciones `ACTIVA` de un evento, de la más barata a la más cara |
| GET | `/api/v1/reventas/mias` | `user` | Publicaciones del usuario, en todos los estados |
| POST | `/api/v1/reventas` | `user` | Publica una entrada propia (`entradaId` y `precio`); 201 con la publicación |
| DELETE | `/api/v1/reventas/{publicacionId}` | `user` | Retira una publicación propia `ACTIVA`; 204. Una ajena da el mismo 404 que una inexistente |
| POST | `/api/v1/reventas/{publicacionId}/compra` | `user` | Compra (cabecera `Idempotency-Key`, titular nuevo y medio de pago); 201 con la publicación vendida, la entrada nueva y su venta, y `Location` hacia la entrada en Ventas. Repetirlo con la misma clave devuelve lo mismo |

- **Datos (esquema `reventa`):** tabla `publicacion` (id, entrada, vendedor, evento, sector, precio pagado, precio, precio máximo calculado al publicar sobre el precio de lista, estado, fecha de publicación, la compra en curso con comprador, clave y momento, y al venderse la venta nueva, el pago y el cierre). La base exige precio mayor que 0 y no mayor que el máximo; los estados (`ACTIVA`, `VENDIDA`, `RETIRADA` y, desde el 23/10, `ANULADA`) los controla el enum. Tabla `reembolso` (migración V3): una fila por pago a devolver, con publicación, comprador, monto, motivo y estado (`PENDIENTE`, `HECHO` o `RECHAZADO`). Sin claves foráneas a otros esquemas.
- **Capas:** `api/` (`ReventasResource` y DTOs), `mensajeria/` (`CancelacionDeEventoMDB`), `negocio/` (`IReventa`, `ServicioDeReventa`, `ConciliacionDeReventas`, `DevolucionDeCobros`, `RetiroPorCancelacion`, `MigracionesReventa`, `TiposDeProblema`), `negocio/externos/` (`ClienteDeVentas`, `ClienteDePagos`, `ClienteDeEventos` sobre `ClienteRest`, con 2 segundos para conectar y 5 para leer), `datos/` (`PublicacionRepository`, `ReembolsoRepository` y sus implementaciones JPA) y `dominio/` (`Publicacion`, `EstadoPublicacion`, `Reembolso` y `EstadoReembolso`).
- **Integraciones:** REST síncrono, con token de servicio, con Ventas (entrada y, desde el 20/11, el control de la compra), Pagos (cobro) y Eventos (fecha del evento para la regla de las 3 horas y precio de lista del sector para el rango; se leen del catálogo porque Eventos busca el detalle por slug). Consume `EVENTO_CANCELADO` de `topico.eventos-cancelados` con su propia suscripción durable (`reventa-cancelaciones`): `RetiroPorCancelacion` pasa a `RETIRADA` las publicaciones `ACTIVA` del evento que nadie está comprando, con un `UPDATE` condicional, así que un mensaje repetido no cambia nada (sección 10.8). Validación no la llama: una entrada `PUBLICADA` o `VENDIDA` da `NO_HABILITADA` en la puerta porque su estado está en Ventas. La consumen las pantallas de reventa (`frontend/src/servicios/reventa.ts`) y el panel del organizador.
- **Decisiones con riesgo aceptado** (sección 21): si Pagos contesta que un pago no se puede devolver, el reembolso queda `RECHAZADO` con un `SEVERE` y lo revisa una persona, porque repetirlo daría lo mismo. El comprador ve el reembolso en la respuesta de la compra (409); si lo resolvió la conciliación, no recibe un aviso. La compra en la reventa no avisa al vendedor.
- **Estado:** Implementado y probado en el servidor el 16/10 (escenario 17 y carpeta 6 de Postman): reventa de punta a punta con la compra hecha desde otra cuenta y la entrada nueva en la venta del comprador. Las pantallas de reventa las recorre `frontend/e2e/reventa.spec.ts` en cada corrida del CI (16.7). Desde el 24/11 (DEF-46), si Ventas rechaza la transferencia por una regla entre compras, Reventa devuelve el cobro y deja la publicación libre (7.10). Reventa tiene 90 tests (al 20/11; detalle en 16.1).

## 4.8 Facturación

- **Responsabilidad:** emitir la factura electrónica de cada compra confirmada contra ARCA (ex AFIP), el sistema legado del dominio, por SOAP, y dejarla consultable. Es la integración SOAP con WSDL que pide la consigna; el 16/10 la cátedra aceptó que ARCA sea simulado (sección 4.9).
- **Interfaz:** `IFacturacion` (`@Local`): `emitirFactura(PagoConfirmadoMensaje)`, `consultarFactura(ventaId)` y `consultarFacturaDelComprador(compradorId, ventaId)`. De la Entrega Parcial N.º 1, `consultarEstado` quedó como las dos consultas, y `reintentar` no hace falta como operación: los reintentos son los del broker.
- **Stateless.** `ServicioDeFacturacion`: "cada factura se arma solo con el mensaje que llega, y lo que tiene que durar (la factura, con su número y CAE, o el rechazo de ARCA) queda en la base. No hay conversación con nadie, así que cualquier instancia del pool atiende cualquier mensaje, también la reentrega de uno anterior". Corre sin transacción propia (`NOT_SUPPORTED`) para no retener una conexión de la base mientras espera a ARCA; la factura se guarda en una transacción corta del repositorio, y la transacción del mensaje queda suspendida y se confirma después. Si esa transacción se deshiciera con la factura ya guardada, la reentrega la encuentra y no hace nada.
- **Capas:** `mensajeria/` (`FacturacionDeComprasMDB`), `api/` (`FacturasResource` y DTOs), `negocio/` (`IFacturacion`, `ServicioDeFacturacion`, `MigracionesFacturacion`, `TiposDeProblema`), `negocio/externos/` (el cliente de ARCA), `datos/` (`FacturaRepository` y `FacturaRepositoryJpa`) y `dominio/` (`Factura` y, desde el 20/11, `ComprobanteRechazado`).
- **Disparo por el tópico:** `FacturacionDeComprasMDB` es el tercer suscriptor de `topico.compra-confirmada`: durable (`clientId` `palco-facturacion`, suscripción `facturacion-compras-confirmadas`), con el mismo selector de `PAGO_CONFIRMADO` y `maxSession = 1`, para facturar de a una compra y que no se le pidan a ARCA dos facturas de la misma venta a la vez. La compra no necesita la factura para confirmarse, así que esperar a ARCA dentro de `confirmarCompra` solo retendría una conexión, y sumar un suscriptor al tópico no toca Ventas.
- **Comprobante:** factura B (tipo 6) y punto de venta 1, constantes de `ServicioDeFacturacion` porque son datos fiscales de Facturación y no reglas de negocio compartidas. El importe es el total del mensaje y la fecha, el día en hora argentina (`America/Argentina/Buenos_Aires`). El número lo asigna ARCA, como hace el sistema real; Facturación no lleva un contador propio.
- **Idempotencia:** antes de llamar a ARCA se busca la factura de la venta, y un mensaje repetido termina ahí. Si dos entregas pasan ese paso a la vez, la restricción única de la base (`factura_venta_unica`) deja guardar una sola y la otra devuelve la que quedó. Además, el ARCA simulado devuelve el mismo comprobante si se le pide de nuevo la misma venta. Con un rechazo anotado pasa lo mismo: un mensaje repetido de esa venta no le vuelve a pedir el CAE a ARCA.
- **Fallas:** si ARCA no responde (tiempo vencido, conexión rechazada, WSDL que no baja o SOAP Fault), el servicio lanza `SinRespuesta` sin guardar nada, el MDB marca la entrega para deshacer (`setRollbackOnly`) y el broker reintenta con la política que ya existe: 5 entregas cada 2 segundos y después la DLQ, sin cola propia. Otro intento puede andar, y un mensaje en la DLQ se devuelve con el procedimiento de la sección 10.6. Lo mismo hace con un mensaje inválido. Si ARCA rechaza el comprobante (`Resultado` R), desde el 20/11 (DEF-33) el servicio anota el rechazo en `facturacion.comprobante_rechazado`, con lo que se pidió y el motivo, deja en el log un `SEVERE` ("Venta ... sin factura.") y lanza `ReglaIncumplida` (`comprobante-rechazado`). El MDB confirma el mensaje sin `setRollbackOnly()`: el pedido es el mismo en cada intento y ARCA lo rechazaría las cinco veces, así que no vuelve al broker ni termina en la DLQ. El rechazo lo revisa una persona (10.6). Si falla la base al anotarlo, el error sale del MDB y el broker reintenta.
- **Cliente SOAP (`negocio/externos/`):** `Arca` es la interfaz propia; `ArcaSoap` es el Adapter que la implementa sobre `PuertoArca`, una interfaz Java anotada con el contrato del WSDL, y `RespuestaCaeXml`. Se arma con `Service.create` sobre el WSDL, sin generar código en el build. El WSDL se baja en el primer pedido y no al desplegar, así Facturación despliega aunque ARCA esté caído; el `Service` queda en memoria y el puerto se crea en cada pedido. `FabricaDeArca` (Factory, `@ApplicationScoped`) lee la configuración: `palco.facturacion.arca.wsdl` (por defecto `${PALCO_URL_INTERNA}/legado/arca?wsdl`) y los tiempos de espera, 2 segundos para conectar y 5 para leer, que rechaza si son 0 o menos.
- **Seguridad del XML (SEC-I01):** el lector StAX de CXF trae la DTD y las entidades externas deshabilitadas salvo que se defina la propiedad de sistema `org.apache.cxf.stax.allowInsecureParser`; Palco no la define, y `FabricaDeArca` se niega a crear el cliente si está en `true`.

| Método | Ruta | Rol | Qué hace |
|---|---|---|---|
| GET | `/api/v1/facturas/{ventaId}` | `user`, `servicio` | La factura de una venta: comprobante, número, CAE, vencimiento del CAE, importe y fecha. Con rol `user`, solo la propia; una ajena, una inexistente o una todavía sin facturar dan el mismo 404 `no-encontrado`. Si ARCA rechazó el comprobante, 409 `comprobante-rechazado` al comprador de esa venta y a otro servicio (desde el 20/11, DEF-33); a otro comprador, el mismo 404. El motivo de ARCA no viaja en la respuesta. Si la venta tiene factura, se devuelve la factura |

- **Datos (esquema `facturacion`):** tabla `factura` (venta, comprador, evento, tipo de comprobante, punto de venta, número, CAE, vencimiento del CAE, importe en pesos enteros, fecha del comprobante y momento en que se guardó), con una sola factura por venta. No hay restricción única sobre tipo, punto de venta y número, porque el ARCA simulado vuelve a numerar desde 1 al redesplegarse. Tabla `comprobante_rechazado` (migración `V2__comprobantes_rechazados.sql`, 20/11): venta, comprador, evento, el importe y la fecha que se pidieron, el motivo de ARCA en un renglón (recortado a 500 caracteres) y el momento en que se anotó, con un solo rechazo por venta (`comprobante_rechazado_venta_unica`). Es una tabla aparte y no un estado de `factura` porque una factura siempre tiene número y CAE, y un rechazo no tiene ninguno de los dos.
- **Integraciones:** consume el tópico `topico.compra-confirmada` (JMS) y llama al ARCA simulado por SOAP. Ventas no cambia: `facturaEstado` sigue en `NO_APLICA` y la factura se consulta acá.
- **Estado:** Implementado y probado en el servidor el 16/10 (escenario 15 y carpeta 7 de Postman): la primera compra recibió la factura B 00001-00000001 con un CAE de 14 dígitos, una sola por venta, y la compra siguiente, el número siguiente. Con eso queda comprobado que el cliente corre sobre CXF en el servidor igual que sobre Metro en los tests. Al 20/11, Facturación tiene 50 tests en 6 clases: el negocio, el MDB, el recurso, la fábrica, el cliente SOAP contra un servicio publicado con `Endpoint.publish` y el repositorio contra H2 en modo PostgreSQL, con las migraciones V1 y V2. El rechazo anotado y el 409 (DEF-33) los cubren `ServicioDeFacturacionTest`, `FacturacionDeComprasMDBTest`, `FacturasResourceTest` y `FacturaEnH2Test`. El corte por tiempo de espera se provocó el 19/11 contra el compose, con el ARCA sin responder: cortó a los 5 s, el mensaje llegó a la DLQ y, devuelto, salió una sola factura (11.9). Que el CXF de WildFly rechaza un sobre con DTD lo prueba el bloque D de `scripts/pruebas/seguridad.mjs`, con sobres que le llegan al ARCA simulado (11.6 y 14.10); en Facturación lo cubren `FabricaDeArcaTest` y los tests del cliente.

## 4.9 ARCA simulado (sistema legado)

- **Qué es:** el sistema externo de facturación electrónica, simulado por un servicio SOAP propio que la cátedra aceptó el 16/10. No es un componente de Palco: es un WAR aparte (`backend/servicios/arca`, artefacto `palco-arca`) publicado en `/legado/arca`, fuera de `/api`, y no recibe `palco-common` ni comparte ninguna clase con Palco (sección 3.2). Así, Facturación se integra con él solo por el contrato del WSDL, como con un sistema legado real.
- **Servicio:** `ArcaSimulado`, un POJO con `@WebService` (portType `ServicioFacturaElectronica`, servicio `ArcaSimulado`, puerto `ArcaSimuladoPort`, espacio de nombres `urn:palco:legado:arca:wsfe`), mapeado en `web.xml` a la raíz del contexto. WildFly genera el WSDL a partir de la clase en `/legado/arca?wsdl`.
- **Operación:** `FECAESolicitar` (tipo de comprobante, punto de venta, importe total, fecha `aaaammdd` e id de la venta). Responde `Resultado` (`A` aprobado o `R` rechazado), número de comprobante, CAE de 14 dígitos, vencimiento del CAE (la fecha más 10 días) y observaciones. Rechaza con una observación, y sin numerar, un tipo que no sea 1, 6 u 11, un punto de venta fuera de 1 a 99998, un importe de 0 o menos, una fecha inválida o un id de venta vacío o de más de 40 caracteres.
- **Sin estado propio:** la numeración está en un `Talonario` en memoria del despliegue, correlativa por punto de venta y tipo, con métodos `synchronized` porque los pedidos llegan en varios hilos. No tiene base de datos.
- **Simulación declarada:** si se le pide otra vez el CAE de la misma venta, devuelve el mismo comprobante, así un reintento no saltea números (el sistema real no autoriza dos veces y se consulta con otra operación). No tiene autenticación (el real usa WSAA) y su numeración vuelve a 1 al redesplegarlo. Por eso no se publica hacia afuera: desde el 25/11 (DEF-45) el nginx del compose responde 404 en `/legado/`, Facturación lo llama por la dirección interna (`PALCO_URL_INTERNA`, `http://localhost:8080` dentro del backend) y las pruebas, por el puerto del backend, que el compose publica solo en 127.0.0.1.
- **Modo sin respuesta:** con la propiedad de sistema `palco.arca.simular-sin-respuesta=true`, que se lee en cada pedido, espera 30 segundos y responde un SOAP Fault, sin numerar. Sirve para mostrar los reintentos y la DLQ de Facturación sin redesplegar.
- **Estado:** Implementado y probado en el servidor el 16/10: el WSDL responde 200 y numera las facturas de Facturación (sección 4.8). El modo sin respuesta se usó el 19/11 contra el compose para provocar el corte por tiempo y la DLQ de Facturación (11.9). Tiene 18 tests (al 20/11): 8 unitarios (numeración, rechazos, misma venta, modo sin respuesta y 60 pedidos simultáneos sin números repetidos), 3 por SOAP, que publican el servicio y comprueban el WSDL y un sobre escrito a mano, y 7 de contrato en `ContratoDelWsdlTest`: el WSDL que publica el servicio con `Endpoint.publish` coincide con la copia de referencia, `docs/api/arca.wsdl` (tipos con su orden y cardinalidad, mensajes, operación, binding, servicio y puerto, resumidos por `ResumenDeWsdl`), un cambio de nombre o de orden de un elemento hace que no coincida, y el pedido que manda Facturación y una respuesta aprobada y una rechazada reales cumplen el XSD de la copia, que rechaza una respuesta sin `Resultado` o con un número que no es número. El XML se lee sin DTD ni entidades externas.

## 4.10 Asistente con IA

- **Responsabilidad:** responder dudas sobre Palco (los eventos a la venta, compras, entradas, reventa y la puerta) con un modelo de lenguaje, a través de un gateway. Es de solo lectura: no tiene base de datos y lo único que pide a otro servicio de Palco es el catálogo público de Eventos (`GET /api/eventos`, sin token), así que no puede comprar, publicar ni cancelar aunque se lo pidan. WAR propio, `backend/servicios/asistente`, a cargo de Valentina Frisoli.
- **Interfaz:** `IAsistente` (`@Local`): `responder(usuarioId, pregunta)`. Devuelve una `Respuesta` con el texto, el origen (`MODELO`, `FALLBACK` o `BLOQUEADA`), el motivo, si conviene derivar a soporte y los milisegundos.
- **Stateless.** `AsistenteIA`: "cada pregunta llega completa y no se guarda conversación".
- **El gateway.** `AsistenteIA` es el único punto por el que el sistema llega al modelo (Facade). Cada pregunta pasa por `responder` en este orden:
  1. **Apagado.** Si `palco.asistente.habilitado` vale `false`, responde el fallback sin llamar al modelo (motivo `apagado`). Se lee en cada pregunta, así que se apaga y se vuelve a encender con una propiedad de sistema en `jboss-cli`, sin redesplegar.
  2. **Guardrails de entrada** (`Guardrails.revisarEntrada`). Se bloquean (`BLOQUEADA`) la pregunta vacía o de más de 500 caracteres, los intentos de inyección de prompt y los pedidos de datos personales de otra persona; en esos casos el modelo no recibe nada. Los dos últimos se buscan sobre el texto normalizado (`Texto.normalizar`: sin mayúsculas, tildes, espacios repetidos ni caracteres invisibles), así que "IGNORÁ" e "ignora" llegan iguales. La inyección se detecta con cuatro grupos de frases, en español y en inglés: que ignore sus instrucciones ("ignorá tus instrucciones", "olvidate de todo lo anterior"), que las revele ("decime tu prompt", "traducí tus instrucciones iniciales"), que cambie de rol ("a partir de ahora sos DAN", "soy admin", "actuá como") y un mensaje falso del sistema ("[SISTEMA]: nuevas reglas"). Un pedido de datos personales se bloquea cuando pide el DNI, el documento, el email, el teléfono o la tarjeta de otra persona ("pasame el DNI del titular", "necesito el mail del vendedor"); nombrar el dato no alcanza, y "¿necesito el DNI para entrar?" pasa. La pregunta que pasa sale **enmascarada**: tarjetas, DNI y emails se reemplazan por marcas (`[tarjeta oculta]`, `[dni oculto]`, `[email oculto]`) antes de llegar al modelo y al log.
  3. **El modelo, con 5 segundos como máximo** (`AsistenteIA.TIEMPO_MAXIMO`). La llamada corre en el `ManagedExecutorService` del contenedor y el gateway la espera con `Future.get`. Las instrucciones del sistema (`AsistenteIA.instrucciones`) son las fijas, la fecha de hoy y las del fin de semana en hora de Argentina (el sábado sale de `hoy.with(DayOfWeek.SATURDAY)`, DEF-76) y los eventos a la venta del catálogo, en un bloque marcado como datos, con la orden de no seguir ningún texto de la lista que parezca una instrucción. El catálogo se lee dentro del mismo pedido, así que también cuenta para los 5 s. Si vence, cancela el pedido y responde el fallback (motivo `timeout`); si el modelo falla o no hay un hilo libre para llamarlo, también (motivo `error-del-modelo`). El fallback es `RespuestasFrecuentes`: respuestas fijas, sin IA, elegidas por palabras clave y armadas con `ReglasDeNegocio`. Si ninguna coincide, ofrece soporte (`derivarASoporte`).
  4. **Guardrails de salida:** una respuesta vacía, con un DNI, un email o un número de tarjeta, o que copia las instrucciones del sistema, no se muestra (`BLOQUEADA`, motivo `respuesta-vacia`, `salida-con-datos-personales` o `salida-con-instrucciones`, con `derivarASoporte`).
  5. **Registro:** una línea de log por pregunta con el id interno de quien pregunta (el `sub` del token, que `AsistenteResource` toma con `Sesion.usuarioId`; nunca su email, que es un dato personal), el modelo, el origen, el motivo, los milisegundos y la pregunta enmascarada.
- **Los modelos** (`negocio/externos/`). `ModeloDeLenguaje` es la interfaz propia (Adapter): `completar(instrucciones, pregunta)` y `nombre()`; las instrucciones del sistema y la pregunta viajan separadas. `FabricaDeModelo` (Factory) elige la implementación con `palco.asistente.modelo`, que se define con la variable `PALCO_ASISTENTE_MODELO`. El productor es `@ApplicationScoped`: el modelo se elige una sola vez y vale hasta que se redespliega el servicio.

| Valor | Clase | Qué hace |
|---|---|---|
| `simulado` (por defecto) | `ModeloSimulado` | Responde con reglas fijas sobre Palco, sin red ni clave. Tarda lo que diga `palco.asistente.simulado.demora-ms`, que se lee en cada pedido: así se muestra el tiempo máximo con su fallback |
| `openai` | `ModeloCompatibleOpenAI` | `POST <url>/chat/completions` con un mensaje `system` (las instrucciones del gateway, con la fecha y los eventos a la venta, y los datos de Palco sacados de `ReglasDeNegocio`, para que no invente topes ni plazos) y uno `user`, `temperature` 0,2 y `max_tokens` 300. Sirve para cualquier proveedor con la API de chat de OpenAI, cambiando la dirección y el modelo. Variables: `PALCO_ASISTENTE_OPENAI_URL` (por defecto `https://api.openai.com/v1`), `PALCO_ASISTENTE_OPENAI_MODELO` (por defecto `gpt-4o-mini`) y `PALCO_ASISTENTE_OPENAI_CLAVE`, la clave, que va en `backend/.env` con `dev.sh` o en el `.env` de la raíz con el compose, nunca en el repo (los dos `.env` están en `.gitignore`; `backend/.env.example` trae los nombres). Su cliente HTTP tiene 2 s para conectar y 6 para leer: uno más que el tiempo máximo del gateway, para que el que corta a los 5 s sea el gateway, con su motivo `timeout`. Un código distinto de 200 o una respuesta sin texto cuenta como falla del modelo, y el cuerpo del error no va al log |

- **Los eventos a la venta** (desde el 24/11, `negocio/externos/CatalogoDeEventos`, `@ApplicationScoped`). Para responder "¿qué hay este fin de semana?", el gateway le pasa al modelo los eventos del catálogo público de Eventos (`GET /api/eventos`, el mismo que ve cualquiera sin sesión, así que no viaja ningún dato personal ni hace falta el token de servicio): los 15 más cercanos que no pasaron ni están cancelados, con título, categoría, lugar, fecha y precio desde o "agotadas", sin la descripción. Cada texto va en una sola línea, sin caracteres de control y recortado a 80 caracteres. El título y el lugar los escribe un organizador, así que pueden traer una inyección de prompt indirecta (LLM01): un evento cuyo texto parece una orden (`Guardrails.conInyeccion`, los mismos cuatro grupos de frases de la entrada) o trae algo que parece un dato personal no se le muestra al modelo. La lista se guarda 60 segundos; si Eventos no responde (1 s para conectar y 2 para leer), se usa la última que se leyó bien o ninguna, y el asistente responde igual. Lo prueban `CatalogoDeEventosTest`, `AsistenteIATest` y `RiesgosDelAsistenteTest`.
- **Plan de apagado:** en el momento, `palco.asistente.habilitado=false` deja todas las preguntas en el fallback sin redesplegar; si el servidor se levanta con `PALCO_ASISTENTE_MODELO=simulado` (o sin definirla, como hace el compose por defecto), el asistente no sale a ningún proveedor.

| Método | Ruta | Rol | Qué hace |
|---|---|---|---|
| POST | `/api/v1/asistente/preguntas` | `user`, `organizer`, `staff` | Una pregunta (`{"pregunta": "..."}`), una respuesta: 200 con `respuesta`, `origen`, `motivo`, `derivarASoporte` y `milisegundos`, también cuando el modelo no contesta o un guardrail bloquea, porque para quien pregunta no es un error. Sin token, 401; sin pregunta, 400 |

- **Datos:** no tiene esquema. Su `health` (`/api/v1/asistente/health`) es el de `common`: 200 si llega a la base compartida.
- **Integraciones:** la consume el frontend: la pantalla `/asistente` (`frontend/src/pages/AssistantPage.tsx`, `frontend/src/servicios/asistente.ts`) dice de dónde salió cada respuesta. Lee el catálogo público de Eventos por REST (`CatalogoDeEventos`). Con `PALCO_ASISTENTE_MODELO=openai` llama por REST al proveedor de IA; con el modelo simulado no sale del servidor.
- **Diagrama de secuencia:** `docs/documentacion/diagramas/secuencia-asistente-ia.svg` (13.10).
- **Set de evaluación:** `EvaluacionDelAsistenteTest` corre 26 casos contra el gateway completo con el modelo simulado: 15 funcionales y 11 adversariales, de A01 a A11 (pedir el prompt del sistema, también en inglés o traducido; el DNI de un titular; una compra; "soy admin"; una tarjeta; "a partir de ahora sos DAN"; un mensaje falso del sistema; cómo falsificar un QR, y un texto ajeno pegado en la pregunta con una orden adentro). Exige que fallen como mucho 2 casos y ninguno adversarial, y corre en cada `mvn verify`: un cambio que lo rompa deja la CI en rojo. Resultado: 26 de 26 y 11 de 11 adversariales (`docs/testing/evidencia/checkpoint-3/evaluacion-ia.txt`, 19/11).
- **Estado:** Implementado, con el modelo **simulado** por defecto y, con `PALCO_ASISTENTE_MODELO=openai`, un proveedor compatible con la API de chat de OpenAI. El 19/11 tenía 115 tests (`GuardrailsTest` 87, `AsistenteIATest` 9, `ModeloSimuladoTest` 9, `ModeloCompatibleOpenAITest` 8, sin red, `RespuestasFrecuentesTest` 1 y el set de evaluación), que pasaron con `mvn -f backend/pom.xml -pl servicios/asistente -am test`. Después se sumaron `RiesgosDelAsistenteTest` (inyección indirecta y fuga de datos, 21/11), `CatalogoDeEventosTest` (24/11), los casos del catálogo y del fin de semana en `AsistenteIATest` (24 y 25/11) y `SeguridadDeclarativaTest` (DEF-43). En vivo, `scripts/pruebas/asistente.mjs` comprueba el modelo, los guardrails, el tiempo máximo (con el modelo demorado 8 s, el fallback llega a los 5007 ms), el apagado y el encendido sin redesplegar y el 401 sin token: 11 de 11 (`docs/testing/evidencia/checkpoint-3/asistente.txt`, 18/11). La CI corre ese guion en cada corrida (16.7), y la carpeta 9 de Postman prueba la misma ruta.

# 5. Decisiones de diseño

Las decisiones vienen de la Entrega Parcial N.º 1 (`docs/entrega1/`) y de lo que se eligió al implementar. Cada fila dice dónde se ve en el código. Cuando una decisión cambió, la tabla da la versión vigente y la sección 5.8 resume qué cambió y por qué.

## 5.1 Arquitectura

| Decisión | Alternativas consideradas | Por qué | Dónde se ve |
|---|---|---|---|
| Ocho componentes de negocio, cada uno un WAR propio con su esquema de base; se hablan solo por REST o JMS | Un único WAR con módulos; servicios que comparten clases | Es la descomposición de la Entrega Parcial N.º 1. Separar los despliegues obliga a que la integración sea explícita (contratos REST y mensajes), que es lo que evalúa la materia | `backend/servicios/*`, un `persistence.xml` y una migración por servicio. Con el ARCA simulado (`backend/servicios/arca`), que es un sistema externo y no un servicio de Palco, se despliegan 9 WAR: el 16/10, con el servidor armado desde cero, los 9 quedaron arriba y los 8 `health` respondieron 200. Desde el 18/11 el asistente con IA (4.10) suma un décimo WAR, sin esquema propio |
| Capas `api/` → `negocio/` → `dominio/`, con `datos/` (repositorios) y `mensajeria/` (MDB) donde hacen falta | Lógica en el recurso REST | El recurso valida la forma del pedido y delega; la regla de negocio queda en el Session Bean, donde el contenedor da transacciones y seguridad declarativas | `VentasResource` → `ComprasEnCurso` → `ServicioDeVentas` → `VentaRepository` |
| Reglas de negocio (tope +10 %, tasa 10 %, 6 entradas por compra, 10 minutos de reserva, DNI de 7 u 8 dígitos) en un solo lugar | Una constante por servicio | Con copias distintas, un servicio puede aceptar lo que otro rechaza. La regla del DNI estaba escrita dos veces y Ventas iba a sumar una tercera | `common/ReglasDeNegocio.java` (`esDniValido`, `MINUTOS_RESERVA`, `MAX_ENTRADAS_POR_ORDEN`) |
| `ServicioDeVentas` es `@Stateful`: una instancia por compra en curso | (a) `@SessionScoped` con sesión HTTP; (c) sin stateful, todo en la base | La instancia guarda lo que todavía no está en la base: los titulares de cada entrada (hasta que la compra se manda a cobrar, 5.2), el total y el vencimiento. La identidad sigue en un solo mecanismo, el token; la cookie de sesión solo vale dentro de un WAR. La reserva temporal es el ejemplo de stateful que da la consigna | `ServicioDeVentas` (`@Stateful(passivationCapable = false)`, `@StatefulTimeout(MINUTOS_RESERVA)`) |
| El stateful se ubica por el id de la venta, con un índice en un bean `@ApplicationScoped` que antes comprueba en la base que la venta sea del `sub` del token | Guardar la referencia en el recurso REST (se crea de nuevo en cada pedido) | Una venta ajena responde 404, igual que una que no existe | `ComprasEnCurso.enCurso`, `delComprador` |
| Una sola llamada a la vez por compra, frenada en la fachada con 409 `operacion-en-curso` | Dejarlo solo en `@AccessTimeout(0)` del stateful | En WildFly 41 la llamada que el contenedor rechaza hace además que descarte la instancia, con la primera llamada todavía adentro: un doble clic dejaba la compra sin poder pagarse. `@AccessTimeout(0)` queda como última barrera | `ComprasEnCurso.ocupar` / `desocupar` |
| La reserva la vence un barrido periódico sobre `vence_reserva`; los pagos sin resultado los cierra otro barrido, la conciliación (5.10) | (a) Que el `@PreDestroy` del stateful escriba en la base y llame a Eventos | La especificación avisa que `@PreDestroy` puede no ejecutarse (se vio: ante una excepción inesperada no corrió) y el timeout del stateful mide inactividad, no una hora fija. Queda un solo reloj, el de la base. Costo: la reserva puede durar hasta un minuto de más | `VencimientoDeReservas` (`@Singleton`, `@Schedule(minute = "*", persistent = false)`) |
| Mientras se espera a otro servicio no se retiene una conexión de la base | Agrandar el pool | Los servicios comparten un pool de 10 conexiones (`configurar.cli`, `--max-pool-size=10`) y el servicio llamado necesita una para contestar. Con el orden anterior (guardar la venta antes de reservar), 80 compras simultáneas dieron 28 abiertas, 52 respuestas 504 y 20 lugares tomados sin venta; con el orden actual las 80 responden 201 (`docs/entrega3/evidencia/compras-simultaneas.txt`). Agrandar el pool solo corría el límite | `ServicioDeVentas.iniciarCompra` (reserva primero, guarda al final), `confirmarCompra` (cobra con el total que recuerda la instancia), `ServicioDePagos` (`NOT_SUPPORTED`) |
| Versionado de rutas: los servicios nuevos en `/api/v1` | Reescritura en el servidor; esperar; un WAR de puerta de entrada | Cambia una línea por servicio y no toca lo que ya funcionaba. Usuarios, Eventos y Notificaciones siguen en `/api/<servicio>` | Context root de Ventas (`/api/v1`), Pagos (`/api/v1/pagos`), Validación (`/api/v1/validacion`), Reventa (`/api/v1/reventas`) y Facturación (`/api/v1/facturas`) |
| Una capa `datos/` con repositorios en todos los servicios: interfaz `@Local` `<Entidad>Repository` e implementación `@Stateless` `<Entidad>RepositoryJpa`; ningún Session Bean usa el `EntityManager` | Dejar el `EntityManager` en el Session Bean de Usuarios, Eventos y Notificaciones | El negocio conoce solo la interfaz: sus tests usan dobles y el acceso a la base se prueba aparte contra H2 en modo PostgreSQL. Los repositorios usan `REQUIRED`, se suman a la transacción del Session Bean o del MDB y comparten el contexto de persistencia, así que el comportamiento no cambió | `usuarios/datos/`, `eventos/datos/`, `notificaciones/datos/` y los de Ventas, Pagos, Validación, Reventa y Facturación |

## 5.2 Datos

| Decisión | Alternativas consideradas | Por qué | Dónde se ve |
|---|---|---|---|
| H2 en modo PostgreSQL en desarrollo y en los tests; PostgreSQL 17 en el `docker compose`; migraciones Flyway con SQL que corra en las dos | Una base por motor; `hbm2ddl` | Se desarrolla sin instalar nada y el SQL se escribe una vez. Las migraciones corren contra PostgreSQL 17 en el `docker compose` y en los `*IT` de Usuarios, Eventos, Ventas y Facturación (16), y contra H2 en los tests `...EnH2Test`. Supabase no se usa como base: el datasource apunta a cualquier PostgreSQL por variables (3.5) | `src/main/resources/db/migracion` de cada servicio, `configurar.cli` (`PalcoDS`), `docker-compose.yml` (servicio `db`) |
| Ninguna tabla lleva `check` de lista de valores | Mantenerlos y sumar una migración que los borre | En H2 2.4.240 un `check (rol in (...))` quedó atado a la conexión que corrió la migración; al cerrarla el pool, todo `INSERT` fallaba con 500. Los valores válidos los controla el enumerado de Java. Se editó la V1 porque esas restricciones no tienen nombre y todavía no había base permanente | V1 de Usuarios y Eventos; enumerados `EstadoVenta`, `EstadoPago`, `Rol` |
| Las fechas de los eventos de prueba se corren en una migración nueva | Editar la V2 | Flyway rechaza un script ya aplicado que cambió | `eventos/.../V3__correr_fechas.sql` |
| Ids de texto con prefijo y 8 caracteres de UUID (`V-3f9a1c2b`) | Número de la base mostrado como `V-00001` | Es la convención que ya funcionaba. Con la base en memoria la numeración volvería a 1 en cada reinicio mientras el broker conserva mensajes viejos | `ServicioDeVentas.nuevoId` |
| Dinero en pesos enteros (`long`) | `double`, `BigDecimal` | Sin errores de redondeo y simple de defender | Entidades `Venta`, `Pago`; DTOs |
| Cada cambio de estado de la venta es un `UPDATE` con la condición adentro, y solo sigue quien cambió la fila | Leer y después escribir | Con lectura y escritura separadas, pagar, cancelar y vencer se pueden pisar. Es la base la que decide, en una sola sentencia | `VentaRepositoryJpa.cambioUnaFila`, `confirmar`, `cancelar`, `vencer`, `marcarEmitida`, `confirmarConciliada`, `vencerPagoSinCobro`; `EntradaRepositoryJpa.marcarUsada`; `ReventaDeEntradasRepositoryJpa`; `PublicacionRepositoryJpa` en Reventa; en Eventos, `SectorRepository.sumarVendidas` (`0 <= vendidas + cantidad <= cupo`) |
| Las entradas nacen con el pago, en `PENDIENTE_EMISION`, y el consumidor de la cola las emite | Que las cree el MDB | El mensaje ya lleva el `entradaId` y el `GET` de la entrada responde apenas vuelve el pago | `ServicioDeVentas.entradasPara`, `EmisionDeEntradasMDB` |
| Los titulares se copian a la base justo antes del primer cobro, en la tabla `venta_titular`, y no al reservar | (a) Guardarlos al reservar; (b) dejarlos solo en la instancia hasta el pago aprobado | Desde el cobro puede haber plata cobrada, y la compra tiene que poder terminarse sin la instancia: con la copia, la conciliación crea las entradas (5.10). Una compra abandonada antes de pagar no deja DNI en la base, y hasta ese momento los titulares viven solo en la instancia, que sigue justificando el stateful. Con (b), una falla después del cobro dejaba plata cobrada sin forma de emitir las entradas. La copia corre en su propia transacción (`REQUIRES_NEW`), junto con la última mirada al DNI de los titulares, con el candado de cada DNI (DEF-46): queda confirmada antes de llamar a Pagos y no se deshace si después falla el pago. Una compra que pierde su instancia antes de intentar pagar ya no se puede pagar (7.3) | `ServicioDeVentas.copiaDeLosTitulares`, `ReglasEntreCompras.exigirDnisSinEntradaYCopiarTitulares`, `VentaRepositoryJpa.respaldarTitulares`, `ventas/.../V2__conciliacion_de_pagos.sql` |
| La venta guarda cuándo empezó el último intento de pago (`pago_intentado`) | Medir desde `vence_reserva` | La conciliación necesita saber si un cobro puede seguir en camino. La anota el mismo `UPDATE` que anota `clave_pago`, y un reintento la actualiza | `VentaRepositoryJpa.guardarClaveDePago`, V2 de Ventas |
| Restricciones únicas como defensa de idempotencia: `(comprador_id, clave_idempotencia)` en la venta, la clave en el pago | Verificar solo en Java | Dos pedidos simultáneos pasan los dos la búsqueda previa; la restricción deja guardar a uno solo. `BaseDeDatos.esClaveDuplicada` busca el SQLState 23505 para no convertir en 409 cualquier falla | `ventas/.../V1__crear_tablas.sql` (`venta_clave_idempotencia_unica`), `common/datos/BaseDeDatos.java` |

## 5.3 API

| Decisión | Alternativas consideradas | Por qué | Dónde se ve |
|---|---|---|---|
| Errores en Problem Details (`application/problem+json`) para todos los servicios | Formato propio `{estado, mensaje, campos}`; excepciones propias en Ventas | Es un estándar, es lo que promete la OpenAPI, y `type` distingue dos errores con el mismo código (un 409 por cupo agotado de uno por reserva vencida) sin leer el texto | `common/rest/Problema.java`, `ManejadoresDeErrores.java` |
| La excepción lleva el tipo de problema; el código HTTP y el título los pone el manejador | Guardar el código HTTP en la excepción | El negocio no conoce HTTP. Todas las `ErroresDeNegocio` llevan `@ApplicationException(rollback = true)` | `common/ErroresDeNegocio.java` |
| Para errores que no son de la aplicación (405, 415), `type` vale `about:blank` y no lleva `detail` | Exponer el mensaje de la excepción | Ese texto es interno del servidor. Nunca se devuelve una traza | `ManejadoresDeErrores` |
| Un pago rechazado: Pagos responde 201 con `RECHAZADO`; Ventas le responde 402 al comprador | Que Pagos responda 402 | Para Pagos el pago se procesó y quedó guardado; si respondiera 402 el intento no quedaría registrado | `PagosResource`, `ClienteDePagos` |
| `Idempotency-Key` obligatoria al iniciar y al pagar; dos columnas en la venta (`clave_idempotencia`, `clave_pago`) | Una tabla clave → respuesta | Repetir la clave devuelve la venta como está hoy, sin reservar ni cobrar de nuevo, y no hay que guardar respuestas. Repetirla con otro pedido responde 409 `clave-reutilizada` | `ComprasEnCurso.iniciar` (`mismoPedido`), `pagar` |
| La clave que Ventas manda a Pagos es `<ventaId>:<clave>` | Reenviar la clave del comprador tal cual | En Ventas la clave es única por comprador; en Pagos, en todo el servicio. Sin el prefijo, dos compradores con la misma clave chocarían en Pagos | `ClienteDePagos.claveParaPagos` |
| Un 409 de Eventos se distingue por `type`: `evento-finalizado` o `cupo-agotado` | Decidir solo por el código | Eventos responde 409 en los dos casos; mirar solo el código diría "no quedan entradas" de un evento que ya pasó | `ClienteDeEventos` |
| Las respuestas de Ventas llevan enlaces a las operaciones siguientes | Respuestas sin enlaces | El cliente no arma las rutas a mano | `ventas/api/Enlaces.java` |

## 5.4 Mensajería

El contrato del mensaje está en `docs/api/mensajeria-pago-confirmado.md`; la mensajería se describe en detalle en su propia sección.

| Decisión | Alternativas consideradas | Por qué | Dónde se ve |
|---|---|---|---|
| Un mensaje `PAGO_CONFIRMADO` que va a una cola (emisión de entradas) y a un tópico (aviso, auditoría y facturación) | Solo tópico; llamadas REST síncronas | La cola tiene un único consumidor: las entradas se emiten una sola vez. El tópico le da una copia a cada suscriptor. El pago no espera a la emisión | `PublicadorPagoConfirmadoJms`, `configurar.cli` |
| El publicador es `@Stateless` con `MANDATORY` y publica por `java:/JmsXA` | `REQUIRED`; la fábrica `java:/ConnectionFactory` | Con `REQUIRED`, llamado sin transacción abriría una propia y el mensaje saldría aunque la venta fallara. `java:/ConnectionFactory` queda fuera de la transacción | `common/mensajeria/PublicadorPagoConfirmadoJms.java`, `DestinosJms.FABRICA_XA` |
| La base no es XA y entra a la transacción como último recurso; los consumidores son idempotentes | (b) Base XA; (c) tabla de salida con un proceso que publica | La consigna pide transacciones declarativas y explicar las fallas, no dos fases. Un datasource XA suma configuración y un driver XA en cada base, y la tabla de salida era más código. Se acepta la ventana de una caída entre los dos commits (7.4 y sección 21) | Sección 7.4 |
| El mensaje es un `record` con `timestamp` `Instant`, y `desdeJson()` siempre valida | Clase con setters; fecha como texto | JSON-B no avisa si falta un campo: lo deja en `null` o en 0 | `common/mensajeria/PagoConfirmadoMensaje.java` |
| Aviso y auditoría son dos MDB con dos interfaces en el mismo WAR | Una sola interfaz; un noveno WAR | Una interfaz mezclaba responsabilidades; otro WAR sumaba un despliegue | `AvisoDeCompraMDB`, `AuditoriaDeComprasMDB`, `INotificaciones`, `IAuditoria` |
| Auditoría es suscripción durable, con una sola sesión; el aviso no es durable | Las dos durables, o ninguna | Auditoría no puede perder eventos; un aviso viejo no le sirve a nadie. Con una sesión procesa en el orden de entrega | `AuditoriaDeComprasMDB` (`palco-auditoria`, `compras-confirmadas`, `maxSession` 1) |
| La clave de `registro_auditoria` es el `JMSMessageID` | Clave única por `venta_id` | El id no cambia en la reentrega; una clave por venta escondería que Ventas publicó dos veces | `ServicioDeAuditoria` |
| 5 entregas, con 2 segundos entre una y otra, y después la DLQ | Los valores de fábrica (10 seguidas, sin espera) | Una falla pasajera agotaría los intentos en un instante | `configurar.cli`, `address-setting=#` |
| Ante un mensaje inválido: la cola, auditoría y facturación deshacen (`setRollbackOnly`) y van a la DLQ; el aviso lo descarta con un `WARNING` | Lanzar una excepción; reintentar también en el aviso | Una excepción dejaba una traza por intento, y reintentar en el aviso sumaba en la DLQ una copia que nadie necesita recuperar. Un mensaje inválido del tópico deja en la DLQ una copia por suscriptor durable: dos, la de auditoría y la de facturación (escenario 12, 16/10) | `EmisionDeEntradasMDB.onMessage`, MDB de Notificaciones, `FacturacionDeComprasMDB` |

## 5.5 Pagos

| Decisión | Alternativas consideradas | Por qué | Dónde se ve |
|---|---|---|---|
| `ServicioDePagos` decide y guarda; la pasarela solo habla con el exterior | (b) Que cada pasarela implemente `IPagos` | Guardar el pago y detectar repetidos se escribe una sola vez y vale para cualquier pasarela. Coincide con la Entrega Parcial N.º 1 | `IPagos`, `ServicioDePagos`, `pasarela/PasarelaDePagos` |
| La pasarela la elige una Factory por configuración; un valor desconocido impide el despliegue | Un `if` en el servicio; caer al simulador | Separa configuración y negocio; no se cobra de mentira sin avisar | `FabricaDePasarela` (`palco.pagos.proveedor`, por defecto `fake`) |
| Ventas cobra detrás de la interfaz `Cobros`, con dos implementaciones elegidas por una Factory; el defecto es el servicio de Pagos | Llamar al simulador desde el bean | El servicio de Pagos se conectó sin tocar el bean de la compra. El simulador solo se usa si alguien escribió `fake`; un valor desconocido cobra contra Pagos con un `WARNING` | `FabricaDeCobros` (`palco.ventas.cobros`), `ClienteDePagos`, `CobrosFake` |
| Solo el cobro con pasarela simulada antes del 23/10; Mercado Pago, webhook y reembolso después | Sumar también Mercado Pago, webhook y reembolso antes del 23/10 | Esas piezas no responden a ningún punto del 23/10 y la firma del webhook no se pudo verificar contra la documentación oficial. El 16/10 la cátedra aceptó la pasarela simulada. Estado: **Simulado** (`PagosFake` decide por el token: `tok_rechazado`, `tok_sin_respuesta`, cualquier otro aprueba) | `PagosFake`; diagrama de secuencia de Mercado Pago (diseño) en `../entrega-obligatoria-2/diagramas/diagrama_secuencia_mercadopago.png` |
| El token de la tarjeta no se guarda | Guardarlo para reintentos | Es sensible y sirve para un solo cobro. Consecuencia: tras un `RECHAZADO`, la misma clave devuelve siempre ese rechazo; un intento nuevo necesita clave nueva | `Pago` (sin columna de token) |
| Una consulta por clave de idempotencia, `GET /api/v1/pagos?claveIdempotencia=...`, solo para el rol `servicio` | Una consulta por venta | La clave es única en Pagos y es lo que Ventas sabe del intento sin resultado: devuelve el pago guardado (aprobado o rechazado) o 404 `no-encontrado`. Por venta podría haber varios intentos y habría que elegir uno. Solo lee: no cobra ni cambia nada | `PagosResource.buscarPorClave`, `IPagos.buscarPorClave` |
| Ninguna transacción abierta mientras se espera a la pasarela | Una transacción por cobro | Retendría una conexión mientras contesta un tercero y, después de una clave duplicada, quedaría marcada para deshacerse: ya no se podría releer el pago ganador | `ServicioDePagos` (`NOT_SUPPORTED`), `PagoRepositoryJpa` (`@Stateless`, transacción corta por método) |

## 5.6 Seguridad

| Decisión | Alternativas consideradas | Por qué | Dónde se ve |
|---|---|---|---|
| Seguridad declarativa con `@RolesAllowed` / `@PermitAll` en cada operación; roles `user`, `organizer`, `staff`, `servicio` | Comprobaciones a mano en cada método | Lo pide la consigna y queda visible en la firma de cada operación | Recursos de cada servicio; `configurar.cli` (`resteasy-role-based-security`) |
| JWT firmado con RSA por Usuarios; roles en el claim `groups` | Sesión con cookie | Un token vale en todos los servicios; la cookie solo dentro de un WAR | `usuarios/seguridad/Tokens.java`, `ClavesJwt` |
| Token de rol `servicio` por secreto compartido (credenciales de cliente) | (b) Un usuario "servicio" que hace login; (c) firmar a mano con una clave fija | Se explica en una frase; (b) obligaba a inventarle DNI y contraseña a algo que no es una persona | `POST /api/usuarios/servicios/token`, `common/seguridad/TokenDeServicio.java` |
| El token de servicio dura 5 minutos y se guarda en un bean `@ApplicationScoped`, que lo renueva cuando le quedan menos de 30 segundos; ante un 401 se invalida y se reintenta una vez | Pedir uno por llamada | Pedir uno por llamada duplicaba las llamadas a Usuarios | `TokenDeServicio.obtener` / `invalidar` |
| El secreto se compara con `MessageDigest.isEqual` | `equals` | Tarda lo mismo acierte o no | `ServicioDeUsuarios` |
| Contraseñas con PBKDF2 y 600.000 iteraciones (desde el 16/10; antes 310.000); el formato guarda las iteraciones | Otro algoritmo | El formato permite subir las iteraciones sin invalidar hashes viejos, como pide la guía actual de OWASP. La migración V5 rehashea las cuentas de prueba para que no tarden distinto que el hash falso, y desde el 24/11 un hash con menos iteraciones se recalcula en el primer ingreso correcto, el único momento en que se tiene la contraseña (DEF-44) | `usuarios/seguridad/Contrasenas.java` (`tieneMenosIteraciones`), `ServicioDeUsuarios.iniciarSesion`, `V5__cuentas_de_prueba_con_600000_iteraciones.sql`; `IteracionesAlIniciarSesionEnH2Test` |
| Después de 5 logins fallidos en 15 minutos para un email, 429 con `Retry-After` aunque la clave sea correcta, también para emails inexistentes | Bloquear la cuenta; limitar por IP | No revela qué emails existen y no deja la cuenta bloqueada para siempre. Los intentos van en una tabla (V4), así funciona con varias instancias | `ServicioDeUsuarios`, `datos/IntentoLoginRepository`, `V4__intentos_de_login.sql` |
| El 401 por token inválido lo responde una página de error de `backend/common` como Problem Details, con `WWW-Authenticate` y CORS | Dejar la página HTML del servidor | El servidor lo rechazaba antes de la aplicación, en HTML y sin CORS, y el navegador lo veía como error de red | `common/rest/ErrorDeAutenticacionServlet.java`, `META-INF/web-fragment.xml` |
| Una venta o entrada ajena responde 404, igual que una que no existe | 403 | No revela que el recurso existe | `ComprasEnCurso.delComprador`, `obtenerEntrada` |
| Validación de caracteres de control en nombres e ids que van al log o al mensaje | Limpiar solo al escribir | Un salto de línea en el nombre del titular haría aparecer una línea falsa en el log del consumidor | `ServicioDeVentas.validar`, `common/Textos.java` |
| La sesión del usuario dura 8 horas, sin refresh token (decidido el 16/10) | Bajar el token a 15-30 minutos con un refresh token revocable | Una sesión de compra o de un organizador dura una jornada. El token se firma con RS256 y cada servicio lo valida con la clave pública; si no se define `PALCO_JWT_CLAVE_PRIVADA`, la clave se regenera al redesplegar Usuarios y los tokens anteriores dejan de valer; el token entre servicios dura 5 minutos. El riesgo de un token robado, que sirve hasta que vence, se acepta para el alcance del trabajo (DEF-38). Criterio SEC-W05: parcial, vigencia justificada | `DURACION_SESION` en `ServicioDeUsuarios`; request SEC-W05c de Postman, que comprueba que el token dura 8 horas |
| Sin usuarios ni permisos por destino en el broker: riesgo aceptado (decidido el 16/10) | Un usuario por servicio con permisos por destino | El broker está embebido en el mismo WildFly, los servicios se conectan por in-vm y la interfaz pública del servidor queda en 127.0.0.1 por defecto: ningún sistema externo se conecta al broker. Se revisa si el sistema se despliega fuera de una sola máquina. Criterio SEC-I02: no cumple, riesgo aceptado (DEF-39) | `configurar.cli` (sin `security-setting` por destino) |
| El secreto de servicios fuera de desarrollo es un requisito de despliegue; el arranque no se bloquea (decidido el 16/10). Desde el 20/11 el código no trae un valor por defecto | Que un servidor fuera de desarrollo no arranque sin la variable (`PALCO_ENTORNO` y una comprobación al desplegar Usuarios); un valor de reserva escrito en el código (hasta el 19/11, `palco-desarrollo`) | El bloqueo tocaba `backend/common` para un caso que se resuelve en el despliegue: definir `PALCO_SECRETO_SERVICIOS` con el mismo valor en todos los servicios. Un valor escrito en el repo lo conoce quien lo lea, y con él podría pedir un token de servicio: si falta la variable, cada servicio usa uno al azar, las llamadas entre servicios fallan y el log lo avisa como error. En desarrollo, `dev.sh` usa el de `backend/.env` o genera uno | `common/seguridad/TokenDeServicio.java` (`leerSecreto`), `backend/dev.sh`, `backend/docker/arrancar.sh`, `backend/.env` (no versionado) |
| Los endpoints de prueba de Notificaciones (`/pruebas/*`) se mantienen, solo para desarrollo (decidido el 16/10) | Quitarlos; moverlos a un WAR aparte | Los usan la demo, la evidencia y la colección de Postman. Piden rol `servicio`, así que un usuario común no puede publicar pagos falsos. Fuera de este trabajo se apagarían con una variable de entorno, como dice el comentario de `PruebasDeMensajeria` (sección 21) | `NotificacionesResource`, `PruebasDeMensajeria` |
| Las seis cuentas de prueba (`@palco.test`) siguen en las migraciones, y fuera de desarrollo se desactivan al arrancar con `PALCO_USUARIOS_CUENTAS_DE_PRUEBA=false` (propiedad `palco.usuarios.cuentas-de-prueba`) | Cambiar o borrar V2, V3 y V6; borrar las filas de esas cuentas | Flyway valida las migraciones que ya corrieron: cambiar o borrar una rompe esa validación en toda base que ya la tiene. Borrar las filas deja sin dueño los eventos de prueba (V6 de Eventos) y la puerta asignada (V2 de Validación). Con la variable en `false`, Usuarios les pone a las seis un hash con el formato y las iteraciones de siempre que ninguna contraseña abre, así que el login responde el mismo 401 que con una clave equivocada. Por defecto vale `true`, porque el compose, el CI y las pruebas entran con esas cuentas, y el log avisa que están activas. Corre en una transacción (quedan desactivadas todas o ninguna) y repetirlo no cambia nada | `usuarios/negocio/DesactivacionDeCuentasDePrueba.java` (`@Startup` `@Singleton`, con `@DependsOn` de la migración), `Contrasenas.INUTILIZABLE`; `DesactivacionDeCuentasDePruebaEnH2Test` (3) y `ContrasenasTest` |

## 5.7 Frontend

| Decisión | Alternativas consideradas | Por qué | Dónde se ve |
|---|---|---|---|
| Dos contextos: sesión (`SesionContext`) y catálogo y carrito (`AppContext`) | Uno solo | Son responsabilidades distintas | `frontend/src/store/SesionContext.tsx`, `frontend/src/store/AppContext.tsx` |
| `fetch` con `useEffect`, sin TanStack Query | Una librería de datos | Pocas llamadas; la librería sumaba conceptos que habría que saber explicar | `frontend/src/lib/api.ts` |
| Token en `localStorage`, confirmado después con `GET /yo` | Memoria; cookie HttpOnly | En memoria no sobrevive a recargar; la cookie exigía cambiar el backend. Riesgo aceptado: un script inyectado podría leer el token, y lo acotan la CSP de nginx (`script-src 'self'`) y el escapado de React (DEF-58) | `SesionContext`, `frontend/src/lib/sesion.ts` |
| Sin datos de respaldo local para usuarios y eventos | Mantener los de mentira | Escondían las fallas del backend y creaban datos que el servidor no conoce | Catálogo, sesión |
| La reserva se crea al terminar el paso "Titulares" | Reservar al entrar al checkout | El backend pide los titulares en el mismo pedido que reserva y no deja cambiarlos | Checkout |
| Dos claves de idempotencia: la de la compra cambia si cambia el pedido; la del pago nace con la reserva, se guarda en el carrito y cambia solo después de un 402 | Deshabilitar el botón | El botón no cubre un corte de red. Ante un 504 se reintenta con la misma clave, que es lo único que destraba esa venta | `frontend/src/servicios/ventas.ts`, carrito |
| Cuenta regresiva recalculada contra el vencimiento del servidor, corregida por el desfase del reloj del navegador | Restar uno por segundo | El contador se atrasa con la pestaña en segundo plano y se reinicia al recargar; con el reloj adelantado la página cancelaba reservas recién hechas | Checkout (`desfaseReloj`) |
| Espera de la emisión: una consulta por segundo, hasta 30 | WebSockets | El backend no los ofrece; alcanza con un `setTimeout` | Confirmación |
| Un corte de red no cierra la sesión | Consultar el `health` y cerrar la sesión si responde | En un corte el `health` también responde un instante después: se cerraba la sesión y se perdía la clave de idempotencia | `api.ts` |
| Un 502 o un 503 dicen "No pudimos conectar con el servidor", como un corte de red (desde el 24/11, DEF-54) | Mostrar el error genérico de un 500 | Son las respuestas de nginx cuando el backend no atiende: para quien usa la pantalla es lo mismo que no tener conexión, y el botón "Reintentar" lo resuelve. Un 500 sigue diciendo "Ocurrió un error inesperado" | `api.ts` (`ESTADOS_SIN_CONEXION`); `frontend/e2e/catalogo.spec.ts` |
| Cada pantalla se carga aparte, con `React.lazy`, salvo el catálogo (desde el 24/11, DEF-49) | Un solo archivo de JavaScript | El archivo único pasaba los 500 kB y hacía más lenta la primera carga en un teléfono. Si el archivo de una pantalla no llega, se ve "No pudimos abrir esta pantalla" con "Recargar la página" | `App.tsx`; `frontend/e2e/carga-diferida.spec.ts` |
| Reventa, puerta y panel del organizador usan el backend; en el navegador queda solo el carrito de la compra en curso | Mantener los datos locales donde no hay servicio | Un dato fijo esconde lo que el backend no hace: lo que no tiene servicio no se muestra. Desde el 24/11 el panel muestra también las ventas por día y la liquidación, que salen de `GET /api/v1/resumenes` (DEF-48). Las tres pantallas las recorre la suite E2E en cada corrida del CI (16.7) | `frontend/src/servicios/reventa.ts`, `validacion.ts`, `ventas.ts`; `AppContext` (clave `palco.state.v3`) |
| Recuperar la contraseña tiene su pantalla, `/recuperar`, en dos pasos: el email y la contraseña nueva con el código (DEF-47) | Dejarlo solo en la API | Quien olvidó la contraseña no podía volver a entrar desde la aplicación. El enlace está en Ingresar | `RecuperarPage.tsx`; `frontend/e2e/recuperar.spec.ts` |
| La pantalla de puerta no decide nada: manda el código y el evento y muestra lo que responde Validación | Validar en el navegador | El uso único lo decide Ventas (5.12). Escanear ya marca la entrada como usada, así que no hay un segundo paso de "marcar ingreso" | Pantalla de puerta |
| El rango de precio de la reventa y el cierre de 3 horas también se controlan en la pantalla | Esperar la respuesta del servidor | Avisa sin ir al servidor. Es una copia de `ReglasDeNegocio` (`TOPE_REVENTA`, `MINIMO_REVENTA`, `HORAS_LIMITE_REVENTA`); la validación que vale es la de Reventa | `frontend/src/types/index.ts` |
| La compra en la reventa va con `Idempotency-Key`: ante un 504 o un corte se repite con la misma clave; ante otro error, clave nueva | Una clave por clic | Es el mismo criterio que el pago de una compra: el backend no cobra dos veces | `CompraReventaModal` |

## 5.8 Decisiones que cambiaron

| Decisión original | Fuente | Versión vigente | Por qué cambió |
|---|---|---|---|
| Repositorios para todos los servicios (`UsuarioRepository`, `EventoRepository`, `EntradaRepository`, `VentaRepository`) | Entrega Parcial N.º 1, sección 4 | Todos los servicios con base tienen su capa `datos/` (5.1 y 6.5). `negocio/IntentosDeLogin` pasó a `datos/IntentoLoginRepository` | Primero se sumaron en Ventas y Pagos, donde había UPDATE condicionales y transacciones cortas que separar del negocio; después en Usuarios, Eventos y Notificaciones, para que todos los servicios sigan las mismas capas y el negocio se pruebe con dobles |
| `validarEntrada` y `marcarComoUsada`, dos operaciones de Validación | Entrega Parcial N.º 1 | Una sola, `IValidacion.escanear` | Con dos pasos, entre "es válida" y "marcala" otra puerta podría usar la misma entrada (5.12) |
| Pagos sin resultado retenidos hasta resolverlos a mano | Ventas, hasta el 16/10 | Los cierra `ConciliacionDePagos` | Pagos sumó la consulta por clave y Ventas, la copia de los titulares (5.10) |
| La prueba de una falla después de publicar, con un WAR temporal | Ventas, hasta el 16/10 | Una propiedad de sistema que se prende y se apaga sin redesplegar (5.10) | El WAR temporal no quedaba en el repo y la prueba no se podía repetir |
| Facturación con cola de reintento propia, factura `PENDIENTE` y numeración en Facturación | Diseño anterior de Facturación | Reintentos del broker, numeración en el ARCA simulado (5.9) | Menos piezas con el mismo resultado; la sección 11.7 da el detalle |
| `IVentas` con `iniciarCompra`, `agregarEntrada`, `confirmarCompra`, `cancelarCompra` | Entrega Parcial N.º 1, sección 2 | Tres operaciones: `iniciarCompra` (con todos los renglones y titulares), `confirmarCompra`, `cancelarCompra` | La compra se arma completa en un pedido; agregar entradas de a una sumaba llamadas sin necesidad |
| "XA entre la base y el broker" | `TODO.md`, Fase 6, en su primera versión | Broker XA y base como último recurso | No es un commit en dos fases: ver 7.4 |
| Guardar la cabecera de la venta antes de reservar | Primera versión de Ventas | Reservar primero y guardar la venta al final, de una vez | La prueba de 80 compras simultáneas agotaba el pool (5.1) |
| Concurrencia por compra solo con `@AccessTimeout(0)` | Primera versión de Ventas | Freno en `ComprasEnCurso` y `@AccessTimeout(0)` como última barrera | Comportamiento de WildFly 41, visto en vivo (5.1) |
| Una compra sin instancia seguía `EN_CHECKOUT` hasta el barrido | Ventas, hasta el 14/10 | Se cancela y devuelve el cupo cuando el comprador intenta pagarla | La compra nueva podía encontrar el sector agotado por la propia compra perdida |
| "Mis entradas" se recordaba en el navegador porque el backend no listaba ventas | Checkout del frontend | `GET /api/v1/ventas` lista las compras del usuario del token y el frontend las pide | Se sumó el listado en Ventas (`ComprasEnCurso.listar`, `MyTicketsPage.tsx`) |
| La línea del aviso decía "aviso enviado" | Evidencia del 13/10 | "Aviso registrado en el log; no se envía: no hay proveedor de correo" | No hay correo: el texto no puede ser más optimista que lo que pasa |
| `registro_auditoria.cuerpo` `varchar(4000)` | V1 de Notificaciones | `varchar(100000)` desde la V2 | Un mensaje válido de 6 entradas con nombres largos ocupaba 4733 caracteres y terminaba en la DLQ |

## 5.9 Facturación por SOAP

Estado: **Implementado**, contra un ARCA **simulado**. El 16/10, con el servidor armado desde cero, `/legado/arca?wsdl` respondió 200, la compra del escenario 15 se facturó una sola vez como factura B con el número 00001-00000001 y un CAE de 14 dígitos, la compra siguiente tomó el número siguiente, y la carpeta 7 de Postman pasó sin fallas. El 16/10 la cátedra confirmó que, por ahora, las integraciones con ARCA son simuladas; el grupo eligió el diseño más simple que cumple con un contrato SOAP con WSDL. La sección 11.7 tiene la lista completa de decisiones; acá van las que ordenan el resto del sistema.

| Decisión | Alternativas consideradas | Por qué | Dónde se ve |
|---|---|---|---|
| ARCA es un sistema legado simulado: un servicio SOAP propio (`@WebService`) en un WAR aparte, en `/legado/arca`, que no recibe `palco-common` | Llamar a un servicio real de ARCA; simular ARCA dentro del WAR de Facturación | Facturación lo llama como a cualquier sistema externo, por la red y con su contrato, sin compartir clases | `backend/servicios/arca` (`ArcaSimulado`, `Talonario`) |
| El WSDL lo publica WildFly a partir de la clase | Escribir el WSDL a mano primero | Es un solo contrato y lo genera el servidor; se sumó la capa `webservices` | `backend/servidor/pom.xml`; `/legado/arca?wsdl` |
| La factura la dispara un tercer suscriptor durable del tópico `topico.compra-confirmada` | Que Ventas llame a Facturación durante la compra; una cola propia | La compra no necesita la factura para confirmarse; sumar un suscriptor no cambia al productor (6.8). Es durable por la misma razón que auditoría: no se puede perder una compra sin facturar | `FacturacionDeComprasMDB` (`palco-facturacion`, `facturacion-compras-confirmadas`, `maxSession` 1) |
| Si ARCA no responde, el MDB hace `setRollbackOnly` y se usan los reintentos del broker: 5 entregas cada 2 segundos y después la DLQ | Una cola de reintento aparte | No se sabe si ARCA autorizó y otro intento puede andar. No suma piezas: es la política de `configurar.cli` que ya usan los otros consumidores | `FacturacionDeComprasMDB`, `address-setting=#` |
| Si ARCA rechaza el comprobante, el rechazo se anota con su motivo en `facturacion.comprobante_rechazado`, el mensaje se confirma y la consulta de la factura responde 409 `comprobante-rechazado` (desde el 20/11, DEF-33) | Tratarlo como la falta de respuesta, como hasta el 20/11: 5 entregas y la DLQ; guardarlo como un estado de la tabla `factura` | El pedido sale del mensaje y es el mismo en cada intento, así que ARCA lo rechazaría las cinco veces. Con la fila, la consulta dice qué pasó en lugar del 404 de una factura en camino, y queda lo que se pidió y el motivo para revisarlo a mano. Va en una tabla aparte porque una factura siempre tiene número y CAE, y un rechazo no tiene ninguno de los dos | `ServicioDeFacturacion`, `FacturacionDeComprasMDB`, `V2__comprobantes_rechazados.sql` |
| `ServicioDeFacturacion` corre sin transacción (`NOT_SUPPORTED`) y la factura se guarda en la transacción corta del repositorio | Guardarla en la transacción del MDB | No retiene una conexión de la base mientras espera a ARCA. Si la transacción del mensaje se deshiciera con la factura ya guardada, la reentrega la encuentra y no hace nada | `ServicioDeFacturacion`, `FacturaRepositoryJpa` |
| Ventas no cambia: la factura se consulta en `GET /api/v1/facturas/{ventaId}` y `facturaEstado` sigue en `NO_APLICA` | Que Facturación le avise a Ventas | Evita un mensaje de vuelta y un cambio en Ventas; cada servicio guarda lo suyo | `FacturasResource` |
| La numeración y el CAE los asigna el ARCA simulado | Que numere Facturación | En el sistema real el número y el CAE los da ARCA; Facturación solo los guarda | `Talonario` |
| Cliente SOAP con una interfaz anotada y `Service.create` sobre el WSDL, detrás de la interfaz propia `Arca` | Generar el cliente con `wsimport` | No suma un paso al build ni código generado. El negocio no sabe que es SOAP (Adapter, 6.2) | `PuertoArca`, `ArcaSoap`, `FabricaDeArca` |
| DTD y entidades externas deshabilitadas: `FabricaDeArca` no crea el cliente si `org.apache.cxf.stax.allowInsecureParser` está en `true` | Dejar la configuración de fábrica | Lo pide el criterio SEC-I01 (sección 14.9), contra XXE. CXF ya trae el lector StAX con las dos cosas deshabilitadas salvo con esa propiedad. Contra el servidor lo prueba el bloque D de `seguridad.mjs`: tres sobres con DOCTYPE, rechazados sin expandir nada (11.6) | `FabricaDeArca`, `scripts/pruebas/seguridad.mjs` |

## 5.10 Pagos sin resultado: conciliación y falla repetible

Estado: **Implementado**. El 16/10, con el servidor armado desde cero, el escenario 14 prendió la falla de prueba y pagó una compra: el comprador recibió 500 con `idError`, la venta siguió `EN_CHECKOUT` sin entradas ni mensaje, el pago quedó `APROBADO` en Pagos, y la conciliación confirmó la venta con ese mismo pago y emitió la entrada en menos de 3 minutos. La sección 7.8 cuenta qué se deshace y cómo se recupera.

| Decisión | Alternativas consideradas | Por qué | Dónde se ve |
|---|---|---|---|
| Los pagos sin resultado los cierra un barrido, `ConciliacionDePagos`, que le pregunta a Pagos por la clave derivada de cada uno | (a) Que el comprador reintente siempre; (b) resolverlos a mano; (c) vencerlos sin preguntar | Ventas no sabe si Pagos cobró, y solo Pagos lo sabe. (a) depende de la instancia, que puede perderse; (b) era la situación anterior; (c) podría vender dos veces un lugar cobrado. Con un pago aprobado confirma la venta; sin pago o con un rechazo, la vence y devuelve el cupo; si no se sabe, espera al minuto siguiente | `ConciliacionDePagos` (`@Singleton`, `@Schedule` en el segundo 30, `NOT_SUPPORTED`) |
| Un margen de 2 minutos desde el último intento antes de preguntar | Preguntar en la primera corrida | Un cobro de Ventas espera a Pagos como mucho unos 21 segundos (2 de conexión y 5 de lectura por pedido, con hasta dos reintentos: el del 401 y el del 409 `pago-duplicado`). Antes de eso, un "no hay pago" puede ser falso. Es un tiempo técnico y no una regla de negocio: por eso está en la clase y no en `ReglasDeNegocio` | `ConciliacionDePagos.MINUTOS_DE_MARGEN` |
| No se toca una venta mientras su instancia vive y su reserva vale | Conciliar todas las marcadas | Mientras tanto el comprador puede reintentar con la misma clave, y es ese reintento el que la resuelve | `ConciliacionDePagos.conciliar` |
| La conciliación y el pago usan la misma marca de "ocupada" de `ComprasEnCurso` | Coordinarlos solo por la base | Un reintento y la conciliación nunca trabajan a la vez sobre la misma venta; el reintento recibe 409 `operacion-en-curso` | `ComprasEnCurso.tomarParaConciliar` |
| Un 404 de Pagos cuenta como "no hay pago" solo si trae `type` `/problemas/no-encontrado` | Decidir por el código | Un 404 sin ese cuerpo puede ser Pagos sin desplegar, y vencer por error vendería dos veces un lugar cobrado | `ClienteDePagos.buscarCobro` |
| Un componente aparte del barrido de reservas | Sumar la pregunta a `VencimientoDeReservas` | Responde otra pregunta ("¿se cobró?" y no "¿venció la hora?"), espera a otro servicio y toma ventas disjuntas: el barrido de reservas solo toca las que no tienen `clave_pago` | `ConciliacionDePagos`, `VencimientoDeReservas` |
| La confirmación conciliada va en un bean aparte, `ConfirmacionConciliada` (`@Stateless`, `REQUIRED`) | Un método de la misma conciliación | Una llamada a un método propio no pasa por el contenedor y no abriría la transacción: confirmar, crear las entradas y publicar tienen que ir juntos, igual que en una compra pagada | `ConfirmacionConciliada.confirmar` |
| La falla a mitad de la compra se repite con una propiedad de sistema solo de desarrollo, `palco.ventas.fallar-despues-del-cobro=true` | Un WAR temporal con la falla escrita; no probarla | Se lee en cada pago, así que se prende y se apaga con `jboss-cli` sin redesplegar. Lanza una `IllegalStateException` después del cobro aprobado, de confirmar, de crear las entradas y de publicar: el contenedor deshace las tres cosas y descarta la instancia. El comentario la declara como herramienta de prueba. La usa el escenario 14 | `ServicioDeVentas.PROPIEDAD_FALLA_DE_PRUEBA`, `scripts/pruebas/escenarios.mjs` |
| Con el simulador de cobros de Ventas (`fake`) no hay conciliación | Que el simulador recuerde los cobros | El simulador es para trabajar sin Pagos; `CobrosFake.buscarCobro` contesta "no se sabe" y la venta queda retenida | `CobrosFake` |

## 5.11 Reventa

Estado: **Implementado**. El 16/10, con el servidor armado desde cero, el escenario 17 recorrió la reventa de punta a punta (publicar dentro del rango, la compra de otra cuenta y la entrada nueva) y la carpeta 6 de Postman (29 requests, con los errores de rango, compra propia, cobro rechazado, compra repetida y retiro) pasó sin fallas. La pantalla de reventa la recorre `frontend/e2e/reventa.spec.ts` en cada corrida del CI (16.7).

| Decisión | Alternativas consideradas | Por qué | Dónde se ve |
|---|---|---|---|
| Reventa guarda las publicaciones; las entradas siguen siendo de Ventas, que las publica, retira y transfiere cuando Reventa se lo pide por REST con el token de servicio | Que Reventa cambie las entradas en su esquema | Cada servicio es dueño de sus datos y se hablan solo por REST. Las operaciones internas están en `/api/v1/operaciones-de-reventa`, no en `/reventas`, porque bajo `/api/v1` ese prefijo es el context root del WAR de Reventa | `ServicioDeReventa`, `ReventaDeEntradasResource` |
| `ServicioDeReventa` es stateless | Stateful, como la compra | Cada pedido llega completo y lo que dura (el estado de la publicación y la compra en curso) queda en la base | `ServicioDeReventa` |
| Antes de cobrar, un `UPDATE` condicional "toma" la publicación (comprador, clave, titular y hora) sin cambiar su estado. La toma no vence por tiempo: la saca la compra, un reintento del mismo comprador o la conciliación de reventas (cambio del 23/10; antes valía 2 minutos) | Cambiar el estado a "reservada"; no tomarla; dejar que la toma venza | De dos compras simultáneas, una sola llega a cobrar; la otra recibe 409. Si la toma venciera, otra persona podría comprar una publicación ya cobrada (pasaba hasta el 23/10); por eso solo la libera quien sabe cómo terminó el cobro | `PublicacionRepositoryJpa.tomar`, `ConciliacionDeReventas` |
| El cobro va contra Pagos con la clave `<idPublicación>:<Idempotency-Key>` | Reenviar la clave del comprador | Es la misma derivación que Ventas (5.3): en Pagos la clave es única en todo el servicio, y repetir la compra manda la misma clave, así que Pagos devuelve el mismo pago y no cobra dos veces | `reventa/.../ClienteDePagos.claveParaPagos` |
| La transferencia es una sola transacción en Ventas: la entrada original pasa a `VENDIDA` y nace una venta `EMITIDA` del comprador con una entrada nueva `VALIDA`, con QR y titular nuevos | Cambiar el titular de la entrada original | El código QR anterior deja de servir, y la compra en la reventa aparece en `GET /api/v1/ventas` como cualquier otra. La venta se arma con las mismas piezas que una compra del catálogo. Es idempotente: la clave de la venta es `reventa:<idPublicación>`, única por comprador | `ReventaDeEntradas.transferir` |
| Cobro aprobado y transferencia rechazada por Ventas: se anota el reembolso, la publicación queda `ANULADA` y Pagos devuelve el pago; si Pagos no responde, un barrido lo reintenta (cambio del 23/10; antes la publicación volvía a `ACTIVA` y el pago se devolvía a mano). Desde el 24/11, si Ventas la rechaza por una regla entre compras, el pago también se devuelve y la publicación queda libre (DEF-46, 7.10) | Volver a `ACTIVA` y devolver a mano; reintentar la transferencia | Ventas rechaza porque la entrada ya no está publicada, y eso no cambia con reintentar: dejarla `ACTIVA` haría cobrar y devolver a cada comprador nuevo. El reembolso se anota antes de anular, así nunca queda una publicación anulada con un cobro que nadie devuelve. Si Ventas no responde, en cambio, pudo haber transferido: la toma queda y la resuelve la conciliación | `ServicioDeReventa.comprar`, `DevolucionDeCobros` |
| Antes de tomar la publicación y de cobrar, Reventa le pide a Ventas que controle el tope de 6 entradas por comprador y evento y el DNI por evento (`POST /api/v1/operaciones-de-reventa/controles-de-compra`, desde el 20/11, DEF-30) | Copiar las reglas y las consultas en Reventa; controlarlas en la transferencia | Ventas es la dueña de las entradas y ya tiene las consultas y los errores de la compra del catálogo: con la misma regla, el comprador lee el mismo mensaje. En la transferencia el cobro ya salió, y cortar ahí dejaría un pago sin compra. Por la misma razón, el reintento de una toma propia con el mismo DNI no se vuelve a controlar | `ReventaDeEntradas.controlarCompra`, `ServicioDeReventa.comprar`, `reventa/.../ClienteDeVentas.controlarCompra` |
| La compra corre sin transacción (`NOT_SUPPORTED`); publicar y retirar, con la de defecto | Una transacción para toda la compra | La compra llama a Pagos y a Ventas entre paso y paso, y una transacción abierta no deshace nada en ellos. Al publicar, si falla el guardado después de que Ventas dejó la entrada `PUBLICADA`, se le pide que la retire. Al retirar, el `UPDATE` va primero y, si Ventas falla, la transacción se deshace y la publicación sigue `ACTIVA` | `ServicioDeReventa` |
| La reventa cierra 3 horas antes del evento, medido en hora de Argentina | Hora del servidor | La fecha del evento es hora local, sin zona | `ServicioDeReventa.ZONA_DE_LOS_EVENTOS`, `ReglasDeNegocio.HORAS_LIMITE_REVENTA` |
| El mínimo (50 %) y el tope (110 %) se calculan sobre el precio de lista del sector, que se le pide a Eventos al publicar | Calcularlos sobre lo que pagó el vendedor | Una entrada comprada en la reventa tiene como precio pagado el de esa reventa: con el tope sobre lo pagado, cada reventa lo subiría otro 10 %. El precio de lista no cambia, porque Eventos no deja cambiar el precio de un sector con entradas vendidas | `ServicioDeReventa.publicar`, `reventa/.../ClienteDeEventos.precioDelSector`, `Publicacion` (`precioMaximo`) |
| Al cancelarse un evento, Reventa retira sus publicaciones activas con su propia suscripción durable a `topico.eventos-cancelados` | Que Ventas le avise a Reventa; dejarlas publicadas | Ventas ya anula esas entradas, y una publicación de una entrada anulada no puede seguir a la venta. Sumar el suscriptor no tocó a Eventos, que publica sin saber quién escucha | `reventa/.../mensajeria/CancelacionDeEventoMDB`, `RetiroPorCancelacion` |

## 5.12 Validación en puerta

Estado: **Implementado**. El 16/10, con el servidor armado desde cero, el escenario 16 dio `OTRO_EVENTO`, `VALIDA` (la entrada quedó `USADA`), `YA_USADA`, `INEXISTENTE` y 403 para un comprador, y la carpeta 5 de Postman pasó sin fallas. La pantalla de puerta la recorre `frontend/e2e/puerta.spec.ts` en cada corrida del CI (16.7).

| Decisión | Alternativas consideradas | Por qué | Dónde se ve |
|---|---|---|---|
| El uso único lo decide Ventas, dueña de la entrada, con un `UPDATE` condicional de `VALIDA` a `USADA` | Validación lleva su propio estado de la entrada; leer y después escribir | La base bloquea la fila y solo uno de dos escaneos simultáneos la cambia. No hace falta una transacción distribuida | `EntradaRepositoryJpa.marcarUsada`, `UsoDeEntradas`, `POST /api/v1/entradas/usos` (rol `servicio`) |
| `UsoDeEntradas` es un bean aparte y no una operación de `IVentas` | Sumarlo a `IVentas` | Esa interfaz es la conversación stateful de una compra; usar una entrada no tiene nada que ver con ella | `UsoDeEntradas` (`@Stateless`) |
| `POST /api/v1/validacion/escaneos` responde 201 con cualquier resultado (`VALIDA`, `YA_USADA`, `INEXISTENTE`, `OTRO_EVENTO`, `NO_HABILITADA`) | Un código de error por resultado | Lo que se crea es el renglón del registro; "no puede entrar" es un dato y no un error HTTP. Es el mismo criterio que Pagos con un cobro `RECHAZADO` | `EscaneosResource`, `ServicioDeValidacion` |
| Si Ventas no responde, el intento queda registrado como `SIN_RESPUESTA` y se responde 504 | No registrar nada | Si el intento sí llegó a usar la entrada, el escaneo siguiente da `YA_USADA` y el renglón explica por qué | `ServicioDeValidacion.escanear` |
| Una entrada de otro evento no se marca | Marcarla igual | Tiene que seguir sirviendo en la puerta de su evento | `UsoDeEntradas.marcarUsada` |
| Un 409 de Ventas con un `type` desconocido cuenta como `NO_HABILITADA` | Dejar pasar | Ante la duda, no se deja entrar | `validacion/.../ClienteDeVentas` |
| El código viaja en el cuerpo y no en la ruta; el registro lo muestra recortado a sus últimos 6 caracteres y no guarda nombre ni DNI | Código en la ruta; registro completo | No queda en los logs de acceso, un código de otro evento escaneado por error sigue siendo válido allá, y los datos personales son de Ventas | `Escaneo`, `EscaneoDto` |
| `ServicioDeValidacion` es stateless y corre sin transacción (`NOT_SUPPORTED`) | `REQUIRED` | Cada escaneo es independiente; así no retiene una conexión mientras espera a Ventas | `ServicioDeValidacion` |
| Cada renglón del registro guarda, además del id de la cuenta que escaneó, su email (migración V4, desde el 20/11, DEF-32) | Mostrar solo el id; completar los escaneos viejos con los emails de Usuarios | El organizador conoce a la cuenta de puerta por el email con que la asignó, y el id interno no le dice nada. El email llega en el mismo token que el id (claim `upn`), así que no hace falta llamar a Usuarios. Los escaneos anteriores a V4 quedan con el email en null, porque los emails son de otro esquema, y el panel muestra su id | `ServicioDeValidacion.escanear`, `Escaneo`, `V4__email_de_quien_escanea.sql`, `OrganizerPage.tsx` |

## 5.13 Entregas

| Decisión | Alternativas consideradas | Por qué |
|---|---|---|
| La Entrega Final se defiende el viernes 27/11 (decidido el 20/11; el 16/10 se había elegido el 04/12, el final adelantado) | El 04/12 (final adelantado) y el final regular del 11/12 | El cronograma de exposiciones que publicó la cátedra el 12/11 asigna al grupo el 27/11 y deja el 04/12 para otros grupos. El sistema completo ya tenía que estar funcionando el 27/11 para la prueba del TP Obligatorio, así que defender ese día no pide trabajo nuevo. La cátedra confirmó el 16/10 que se entrega con las fechas del cronograma de la comisión del viernes |

## 5.14 Asistente con IA: por qué un gateway

Estado: **Implementado**, con el modelo simulado por defecto (sección 4.10).

| Decisión | Alternativas consideradas | Por qué | Dónde se ve |
|---|---|---|---|
| Toda llamada al modelo pasa por un gateway propio, `AsistenteIA` | Que la pantalla o cada servicio llamen al proveedor | En un solo lugar quedan las protecciones que no dependen del proveedor: guardrails de entrada y de salida, enmascarado, tiempo máximo, fallback, apagado y registro. La clave del proveedor vive solo en el servidor, y cambiar de modelo no toca a quien pregunta | `asistente/negocio/AsistenteIA.java` |
| El asistente es de solo lectura: no llama a otros servicios de Palco ni tiene base | Darle acciones, como comprar o publicar | Un texto que engañe al modelo no puede hacer nada en el sistema. Las acciones siguen en las pantallas, con su seguridad declarativa | `backend/servicios/asistente`: sin clientes de Palco ni `persistence.xml` |
| Tiempo máximo de 5 s con `Future.get` sobre el `ManagedExecutorService` | Esperar lo que tarde el proveedor; un hilo propio | El usuario espera en pantalla y el proveedor es un sistema externo. El ejecutor es el del contenedor, que es la forma de Jakarta EE de correr una tarea en otro hilo | `AsistenteIA.TIEMPO_MAXIMO` |
| El fallback son respuestas fijas armadas con `ReglasDeNegocio` | Responder 503 o 504 | Quien pregunta recibe una respuesta útil aunque el modelo no conteste, y los números son los mismos que usa el resto del sistema | `RespuestasFrecuentes` |
| Siempre 200, con el origen de la respuesta (`MODELO`, `FALLBACK`, `BLOQUEADA`) | Un código HTTP por resultado | Que el modelo no conteste o que un guardrail corte no es un error para quien pregunta. Es el criterio del escaneo de la puerta (5.12) | `AsistenteResource` |
| `palco.asistente.habilitado` se lee en cada pregunta; el modelo se elige una sola vez por despliegue | Leer todo una sola vez | Apagar tiene que ser inmediato y sin redesplegar. Cambiar de modelo es una decisión de despliegue, con su clave | `AsistenteIA`, `FabricaDeModelo` (`@ApplicationScoped`) |
| Un adaptador para la API de chat de OpenAI, y no uno por proveedor | Un adaptador por proveedor; la biblioteca de un proveedor | Es un formato que aceptan otros proveedores, también Ollama en local: cambiar de proveedor es cambiar la dirección y el modelo. No suma dependencias, porque usa el cliente JAX-RS del servidor | `ModeloCompatibleOpenAI` |
| Modelo simulado por defecto | Un proveedor real por defecto | El sistema se desarrolla, se prueba y se muestra sin clave ni red, y el set de evaluación da siempre lo mismo | `ModeloSimulado`, `EvaluacionDelAsistenteTest` |

# 6. Patrones aplicados

Solo se cuentan los patrones que están aplicados en el código. Para cada uno: dónde está, qué problema resuelve ahí y por qué ese y no otro.

## 6.1 Factory

| | |
|---|---|
| Dónde | `pagos/pasarela/FabricaDePasarela`, `ventas/negocio/externos/FabricaDeCobros`, `facturacion/negocio/externos/FabricaDeArca` y `asistente/negocio/externos/FabricaDeModelo`, que elige el modelo del asistente con `palco.asistente.modelo` (4.10). Todas son beans `@ApplicationScoped` con un método `@Produces` |
| Problema | El código que usa la pasarela (o el cobro) no tiene que saber qué implementación concreta hay del otro lado ni leer la configuración. `ServicioDePagos` pide un `PasarelaDePagos` con `@Inject`; `ServicioDeVentas` pide un `Cobros` |
| Por qué este | Es el único lugar que conoce las clases concretas y la propiedad que elige (`palco.pagos.proveedor`, `palco.ventas.cobros`). Un `if` en el negocio mezclaría configuración con reglas. Con el `@Produces` de CDI no hace falta escribir una fábrica abstracta: el contenedor llama al método y entrega el resultado donde se inyecta |
| Detalle | Las dos fábricas tratan distinto el valor desconocido, y a propósito: en Pagos impide el despliegue; en Ventas cobra contra el servicio de Pagos y deja un `WARNING`, para que el simulador nunca quede puesto por olvido. `FabricaDeArca` arma el cliente SOAP con la dirección del WSDL y los tiempos de espera de la configuración (`palco.facturacion.arca.*`), rechaza tiempos de 0 o menos y se niega a crearlo si la propiedad que habilita el lector XML inseguro está en `true` (5.9) |

## 6.2 Adapter

| | |
|---|---|
| Dónde | `ventas/negocio/externos/ClienteDePagos` (implementa `Cobros`) y `facturacion/negocio/externos/ArcaSoap` (implementa `Arca`). Con la misma idea, los clientes REST de cada servicio: `ClienteDeEventos` y `ClienteDeUsuarios` en Ventas, `ClienteDeVentas` en Validación, y `ClienteDeVentas`, `ClienteDePagos` y `ClienteDeEventos` en Reventa. En el asistente, `ModeloCompatibleOpenAI` implementa la interfaz propia `ModeloDeLenguaje` sobre la API de chat de OpenAI; `ModeloSimulado` implementa la misma interfaz sin proveedor (4.10) |
| Problema | Pagos habla con códigos HTTP y JSON; la compra solo entiende `Cobros.cobrar(...)` y su `ResultadoDeCobro`. `ClienteDePagos` traduce: 201 `APROBADO` o `RECHAZADO` a un resultado; 400 o 409 `clave-reutilizada` a un rechazo sin pago; 401 a renovar el token y reintentar una vez; 401 repetido, 403 o 404 a `CobroNoRealizado`; cualquier otra respuesta, o ninguna, a `SinRespuesta`. Para la conciliación, `buscarCobro` traduce la consulta por clave: 200 a un cobro, 404 con `type` `no-encontrado` a "no hay cobro", lo demás a `SinRespuesta`. En Facturación, ARCA habla SOAP: `ArcaSoap` traduce el pedido de CAE a la interfaz anotada `PuertoArca` y la respuesta XML (`RespuestaCaeXml`) al resultado que entiende `ServicioDeFacturacion`; cualquier `WebServiceException` (tiempo vencido, conexión rechazada, WSDL que no baja, SOAP Fault) sale como `SinRespuesta` |
| Por qué este | El negocio no depende del protocolo: el mismo bean de la compra cobró primero con `CobrosFake` y después con el servicio de Pagos sin cambiar una línea, y `ServicioDeFacturacion` no sabe que del otro lado hay SOAP |
| Estado | El adaptador de Mercado Pago, que traduciría la API del proveedor a `PasarelaDePagos`, está **diseñado** y no se implementa (12.1). `PagosFake` no es un adaptador: es un simulador que implementa la interfaz |

## 6.3 Strategy (por configuración)

| | |
|---|---|
| Dónde | Las interfaces `Cobros` (implementaciones `ClienteDePagos` y `CobrosFake`) y `PasarelaDePagos` (hoy solo `PagosFake`) |
| Problema | Cobrar es un algoritmo intercambiable: de verdad o simulado. El contexto (`ServicioDeVentas`, `ServicioDePagos`) trabaja contra la interfaz |
| Por qué este | Permite trabajar en Ventas sin Pagos desplegado y conectar Pagos sin reescribir la compra |
| Alcance | La estrategia se elige una vez, al primer uso, y vale hasta redesplegar (los dos productores son `@ApplicationScoped`); no cambia por pedido. En `PasarelaDePagos` hay una sola implementación: la variación todavía es potencial. Por eso en Pagos se cuenta como Adapter más Factory |

## 6.4 Facade

| | |
|---|---|
| Dónde | `ventas/negocio/ComprasEnCurso`. En el asistente, `asistente/negocio/AsistenteIA` es el gateway de IA: una sola entrada que esconde guardrails, tiempo máximo, fallback y el modelo elegido (4.10 y 5.14) |
| Problema | Una compra involucra el stateful de la compra, dos repositorios, Usuarios (identidad verificada) y Eventos (devolución de cupo), con pasos que quedan fuera de la transacción del stateful. Para la compra, los recursos REST (`VentasResource`, `EntradasResource`) hablan solo con esta clase. Lo que no es parte de la conversación de una compra va a beans stateless aparte: el uso de una entrada en la puerta (`EntradasResource` → `UsoDeEntradas`) y las operaciones de la reventa (`ReventaDeEntradasResource` → `ReventaDeEntradas`) |
| Por qué este | Concentra en un lugar el orden de los pasos (consultar a Usuarios antes de abrir la compra, anotar el intento de pago antes de cobrar, devolver el cupo después de confirmar la cancelación) y la regla de una llamada a la vez. El recurso queda delgado |
| Detalle | No es un Session Bean: es `@ApplicationScoped` porque guarda el índice de compras abiertas, que tiene que ser el mismo para todos los pedidos, y no abre transacciones. La conciliación también pasa por ella para tomar una compra como una llamada más (`tomarParaConciliar`) |

## 6.5 Repository

| | |
|---|---|
| Dónde | En la capa `datos/` de cada servicio con base, una interfaz `@Local` `<Entidad>Repository` y su implementación `@Stateless` `<Entidad>RepositoryJpa`: Ventas (`VentaRepository`, `EntradaRepository`, `ReventaDeEntradasRepository`), Pagos (`PagoRepository`), Usuarios (`UsuarioRepository`, `CodigoVerificacionRepository`, `IntentoLoginRepository`), Eventos (`EventoRepository`, `SectorRepository`), Notificaciones (`RegistroDeAuditoriaRepository`), Validación (`EscaneoRepository`), Reventa (`PublicacionRepository`) y Facturación (`FacturaRepository`) |
| Problema | El negocio necesita operaciones con significado (`guardarClaveDePago`, `confirmar`, `vencerPagoSinCobro`, `marcarUsada`, `tomar`, `sumarVendidas`) y no consultas JPQL. Las que cambian un estado son un `UPDATE` condicional que dice si cambió la fila |
| Por qué este | Aísla JPA del negocio, da a cada operación su propia transacción corta cuando el llamador no tiene una (el bean `@Stateless` usa `REQUIRED` por defecto, y se suma a la del llamador si existe, compartiendo el contexto de persistencia) y permite probar el negocio con dobles: Mockito en la mayoría y un repositorio en memoria en Pagos (`PagoRepositoryEnMemoria`), que necesita comportamiento (rechazar una clave repetida). El acceso a la base se prueba aparte contra H2 en modo PostgreSQL con las migraciones de Flyway |
| Detalle | Una operación se aparta de `REQUIRED` a propósito: `VentaRepositoryJpa.tomarCandados` exige la transacción de quien llama (`MANDATORY`), porque el bloqueo de la fila de `candado_de_compra` dura lo que la transacción y en una propia se soltaría al volver (DEF-46). La copia de los titulares (`respaldarTitulares`) se suma a la transacción propia (`REQUIRES_NEW`) que abre `ReglasEntreCompras` antes del cobro, para que sobreviva a una falla del pago (7.3). Y `UsuarioRepository.borrar` y `RegistroDeAuditoriaRepository.guardar` hacen `flush` para que la sentencia salga dentro del método: el `DELETE` de una cuenta sin verificar antes del `INSERT` de la nueva, y un `INSERT` rechazado antes de que el MDB escriba en el log que procesó el mensaje |

## 6.6 DTO

| | |
|---|---|
| Dónde | `api/Dtos.java` de cada servicio (por ejemplo `NuevaVentaDto`, `VentaDto`, `EntradaDto` en Ventas), todos `record`. El mensaje `PagoConfirmadoMensaje` cumple el mismo papel entre servicios |
| Problema | Lo que entra y sale por la API no es la entidad JPA: la venta tiene columnas que no se exponen (`clave_pago`) y la respuesta lleva datos que la entidad no tiene (enlaces) |
| Por qué este | Separa el contrato público del modelo de datos; cambiar una tabla no rompe a los clientes. `ServicioDeVentas.aDto` hace la conversión en un solo lugar. Ventas no usa ninguna clase de otro servicio: tiene sus propios `record` para lo que recibe (`ClienteDeEventos.Sector`) |

## 6.7 Singleton

| | |
|---|---|
| Dónde | Singletons EJB (`@Singleton`): `VencimientoDeReservas`, `ConciliacionDePagos`, `ClavesJwt` (`@Startup`) y `Migraciones<Servicio>` en cada servicio. Instancia única por CDI (`@ApplicationScoped`): `ComprasEnCurso`, `TokenDeServicio`, los clientes externos, `ArcaSoap` y las fábricas |
| Problema | Hay cosas que tienen que existir una sola vez por servicio: un solo barrido de reservas y una sola conciliación (el contenedor no deja que una corrida empiece mientras la anterior sigue), un solo par de claves RSA para firmar, un solo índice de compras abiertas, un solo token de servicio vigente, un solo cliente SOAP con su `Service` en memoria |
| Por qué este | Lo da el contenedor, sin código propio de sincronización; donde varios pedidos comparten estado (el índice de compras) se usan colecciones concurrentes (`ConcurrentHashMap`). `@DependsOn("MigracionesVentas")` asegura que los dos barridos no arranquen antes de que existan las tablas. La conciliación corre en el segundo 30 para no coincidir con el barrido de reservas |

## 6.8 Patrones de mensajería

| Patrón | Dónde | Qué resuelve |
|---|---|---|
| Punto a punto (cola) | `cola.emision-entradas` (JNDI `java:/jms/queue/emisionEntradas`), consumida por `EmisionDeEntradasMDB` | Un único consumidor: las entradas se emiten una sola vez |
| Publicación y suscripción (tópico) | `topico.compra-confirmada` (JNDI `java:/jms/topic/compraConfirmada`), consumido por `AvisoDeCompraMDB`, `AuditoriaDeComprasMDB` y `FacturacionDeComprasMDB` | Cada suscriptor recibe su copia; sumar un suscriptor no cambia al productor: Facturación se sumó sin tocar Ventas, y Reventa se sumó a `topico.eventos-cancelados` sin tocar Eventos (10.8) |
| Suscriptor durable | `AuditoriaDeComprasMDB` (`clientId` `palco-auditoria`, `subscriptionName` `compras-confirmadas`) y `FacturacionDeComprasMDB` (`palco-facturacion`, `facturacion-compras-confirmadas`) | El broker guarda los mensajes mientras el consumidor está detenido: no se pierde una compra sin auditar ni sin facturar |
| Consumidor dirigido por mensajes | Los MDB de Ventas, Notificaciones, Facturación y Reventa: los cuatro de la compra y los cuatro de la cancelación de un evento (10.8) | El contenedor entrega cada mensaje en una transacción, con pool de instancias |
| Selector de mensajes | `messageSelector` con `DestinosJms.SELECTOR_PAGO_CONFIRMADO` | El consumidor recibe solo el tipo de evento que entiende |
| Canal de mensajes fallidos (DLQ) | `configurar.cli`: 5 entregas cada 2 segundos y después `java:/jms/queue/DLQ` | Un mensaje que no se puede procesar no se pierde ni traba la cola |
| Receptor idempotente | Emisión: `UPDATE` condicionales sobre la entrada y la venta. Auditoría: clave primaria `JMSMessageID`. Facturación: búsqueda previa por venta y restricción única `factura_venta_unica` | Tolera la entrega repetida que permite la garantía de último recurso (7.4) |
| Publicación transaccional | `PublicadorPagoConfirmadoJms` con `MANDATORY` y `java:/JmsXA` | El mensaje sale solo si la venta se confirma |

No se cuentan como aplicados otros patrones que se evaluaron (por ejemplo una tabla de salida u *outbox*, que fue una de las alternativas para publicar el mensaje y se descartó: 5.4).

## 6.9 State

| | |
|---|---|
| Dónde | `ventas/dominio/estado/`: la interfaz sellada `EstadoDeVenta` y una clase por estado (`EnCheckout`, `Confirmada`, `Emitida`, `Cancelada`, `Vencida`), cada una con una sola instancia. `EstadoDeVenta.de(...)` devuelve el objeto que corresponde al `EstadoVenta` guardado en la base. Lo usan `ServicioDeVentas` al pagar y `ComprasEnCurso` al cancelar. ADR-002 en `docs/adr/` |
| Problema | Qué se puede hacer con una venta depende de su estado: pagar solo en `EN_CHECKOUT` y con la reserva vigente; cancelar una venta paga no corresponde; cancelar dos veces no es un error. Escrito con `if` sobre el enum, cada operación repetía las mismas preguntas |
| Por qué este | Cada estado decide sus operaciones en su clase, y una operación que no corresponde lanza `TransicionInvalida`, que es un `Conflicto` y sale como 409 Problem Details (`venta-ya-paga` o `reserva-vencida`). La interfaz es `sealed` y `de(...)` es un `switch` sin `default`: un estado nuevo sin su clase no compila. JPA sigue guardando el enum `EstadoVenta`, así que no hace falta un convertidor |
| Alcance | El State decide si la operación corresponde; el cambio en la base lo sigue haciendo el `UPDATE` condicional del repositorio, para que de dos pedidos simultáneos cambie la fila uno solo. Las transiciones que no pide el comprador (confirmar el pago, emitir, vencer, anular por cancelación del evento) son `UPDATE` condicionales sin pasar por el State. La entrada (`EstadoEntrada`) sigue siendo un enum sin comportamiento. Prueba: `EstadoDeVentaTest` y `scripts/pruebas/patron-state.mjs` (16.7) |

# 7. Transacciones

Las transacciones son declarativas: las abre y cierra el contenedor según el `@TransactionAttribute` de cada operación. Entre servicios no hay transacción distribuida: lo que se confirma en Eventos o en Pagos queda confirmado allá aunque después falle Ventas, y se compensa, se concilia o se protege con idempotencia. Todo lo de esta sección sale de `ComprasEnCurso`, `ServicioDeVentas`, `ReglasEntreCompras`, `VencimientoDeReservas`, `ConciliacionDePagos`, `ConfirmacionConciliada`, `VentaRepositoryJpa`, `EmisionDeEntradasMDB`, `PublicadorPagoConfirmadoJms`, `UsoDeEntradas`, `ReventaDeEntradas`, `ServicioDeReventa`, `ServicioDeValidacion`, `ServicioDeFacturacion` y los clientes de cada `negocio/externos/`.

## 7.1 Resumen de atributos

| Operación | Clase | Atributo | Qué entra en la transacción |
|---|---|---|---|
| Iniciar compra | `ServicioDeVentas.iniciarCompra` | `NOT_SUPPORTED` | Nada propio. La única escritura corre en la transacción corta de `ReglasEntreCompras.guardarDentroDelTope` |
| Guardar la compra dentro del tope | `ReglasEntreCompras.guardarDentroDelTope` (desde `iniciarCompra`, después de reservar) | `REQUIRED`, sin transacción del llamador | El candado del comprador y el evento (`tomarCandados`, `MANDATORY`), la venta con sus renglones y la cuenta de sus entradas para el evento. Si pasa el tope, se deshace y la venta no queda (DEF-46) |
| Anotar el intento de pago | `VentaRepositoryJpa.guardarClaveDePago` (desde `ComprasEnCurso.pagar`) | `REQUIRED` del repositorio, sin transacción del llamador | Un `UPDATE` (`clave_pago` y `pago_intentado`), confirmado en el momento |
| Mirar el DNI y copiar los titulares | `ReglasEntreCompras.exigirDnisSinEntradaYCopiarTitulares` (desde `confirmarCompra`, antes del cobro) | `REQUIRES_NEW` | El candado de cada DNI para el evento (`tomarCandados`, `MANDATORY`), la última mirada al DNI y un `INSERT` por titular en `venta_titular` (`respaldarTitulares`, que se suma a esta), confirmados antes de llamar a Pagos; la copia no se deshace si después falla el pago |
| Pagar y confirmar | `ServicioDeVentas.confirmarCompra` | `REQUIRED` | Pasar la venta a `CONFIRMADA`, crear las entradas, publicar `PAGO_CONFIRMADO` |
| Publicar | `PublicadorPagoConfirmadoJms.publicar` | `MANDATORY` | Se suma a la de `confirmarCompra` o a la de `ConfirmacionConciliada`; sin transacción falla |
| Conciliar | `ConciliacionDePagos.conciliarPagosSinResultado` | `NOT_SUPPORTED` | Nada propio: la consulta a Pagos va sin conexión tomada; el vencimiento (`vencerPagoSinCobro`) corre en la transacción corta del repositorio |
| Confirmar conciliada | `ConfirmacionConciliada.confirmar` | `REQUIRED` | Confirmar la venta con el pago encontrado, crear las entradas con la copia de los titulares y publicar `PAGO_CONFIRMADO` |
| Cancelar | `ServicioDeVentas.cancelarCompra` | `REQUIRED` | El `UPDATE` a `CANCELADA` |
| Vencer | `VencimientoDeReservas.vencerReservas` | `NOT_SUPPORTED` | Nada propio; cada `vencer` corre en la transacción corta del repositorio |
| Emitir | `EmisionDeEntradasMDB.onMessage` | `REQUIRED` | Recibir el mensaje, emitir cada entrada y pasar la venta a `EMITIDA` |
| Cobrar en Pagos | `ServicioDePagos` | `NOT_SUPPORTED` | Nada propio; cada método de `PagoRepositoryJpa` corre la suya |
| Devolver cupo | `ComprasEnCurso.devolverCupo` → `ClienteDeEventos.liberarCupo` | Sin transacción (bean CDI) | Una llamada REST a Eventos por renglón, después de confirmar el cambio de estado |
| Usar una entrada | `UsoDeEntradas.marcarUsada` (Ventas) | `REQUIRED` | El `UPDATE` condicional de `VALIDA` a `USADA` y la relectura de la entrada |
| Escanear | `ServicioDeValidacion.escanear` (Validación) | `NOT_SUPPORTED` | Nada propio: la llamada a Ventas va sin conexión tomada; el renglón del escaneo se guarda en la transacción corta de `EscaneoRepositoryJpa` |
| Transferir en la reventa | `ReventaDeEntradas.transferir` (Ventas) | `REQUIRED` | El candado del comprador y el del DNI para el evento (`tomarCandados`, `MANDATORY`) y la última mirada a las reglas entre compras; entrada original a `VENDIDA`, venta nueva, entrada nueva, confirmar, emitir |
| Comprar en la reventa | `ServicioDeReventa.comprar` (Reventa) | `NOT_SUPPORTED` | Nada propio: tomar, liberar, anular y marcar vendida corren cada uno en la transacción corta de `PublicacionRepositoryJpa` |
| Devolver un cobro de reventa | `DevolucionDeCobros.anotar`, `intentar` (Reventa) → `ServicioDePagos.reembolsar` (Pagos) | `NOT_SUPPORTED` los dos | Anotar el reembolso y marcarlo hecho corren cada uno en la transacción corta de `ReembolsoRepositoryJpa`; en Pagos, el `UPDATE` a `REEMBOLSADO`, después de la pasarela |
| Publicar y retirar en la reventa | `ServicioDeReventa.publicar`, `retirar` | `REQUIRED` (por defecto) | Guardar la publicación, o el `UPDATE` a `RETIRADA`. Lo que cambia Ventas en la entrada se confirma allá, en su propia transacción (7.10) |
| Facturar | `FacturacionDeComprasMDB.onMessage` → `ServicioDeFacturacion.emitirFactura` | `REQUIRED` el MDB; `NOT_SUPPORTED` el servicio | La transacción del MDB es la del mensaje y queda suspendida mientras se espera a ARCA; la factura se guarda en la transacción corta de `FacturaRepositoryJpa` |
| Cancelar un evento | `ServicioDeEventos.cancelarEvento` (Eventos) | `REQUIRED` | Marcar el evento cancelado y publicar `EVENTO_CANCELADO` (`PublicadorDeCancelacionesJms`, `MANDATORY`) |
| Recibir la cancelación | `CancelacionDeEventoMDB.onMessage` (Ventas) → `CancelacionDeEventos` | `REQUIRED` | La recepción del mensaje; cada compra se anula en la suya |
| Anular una compra del evento | `AnulacionDeCompras.anular` (Ventas) | `REQUIRES_NEW` | La venta a `CANCELADA`, sus entradas a `ANULADA`, la marca `reembolso_pendiente` si Pagos no contestó y el `COMPRA_ANULADA`, que no sale si quien compró había revendido todas sus entradas. El reembolso en Pagos es una llamada REST que confirma allá (10.8) |
| Reintentar reembolsos | `ReintentoDeReembolsos.reintentarReembolsos` (Ventas) | `NOT_SUPPORTED` | Nada propio: el pedido a Pagos va sin conexión tomada y la marca se saca en la transacción corta del repositorio |
| Retirar la reventa del evento | `CancelacionDeEventoMDB.onMessage` (Reventa) → `RetiroPorCancelacion` | `REQUIRED` | La recepción del mensaje y el `UPDATE` de las publicaciones `ACTIVA` a `RETIRADA` |
| Capa de datos de Usuarios, Eventos y Notificaciones | `<Entidad>RepositoryJpa` | `REQUIRED` | Se suman a la transacción del Session Bean o del MDB que los llama |

Las excepciones de `ErroresDeNegocio` son excepciones de aplicación con `rollback = true`: deshacen la transacción y el stateful sobrevive. Cualquier otra excepción además hace que el contenedor descarte la instancia.

## 7.2 Iniciar: reserva de cupo y guardado

1. `ComprasEnCurso.iniciar` busca una venta con la misma clave de idempotencia y comprador; si existe y el pedido es el mismo, la devuelve sin reservar (si es otro pedido, 409 `clave-reutilizada`).
2. Consulta a Usuarios (`GET /yo` con el token del comprador): sin identidad verificada, 403 `identidad-no-verificada`, antes de pedir una instancia o reservar.
3. Pide una instancia nueva del stateful por JNDI y llama a `iniciarCompra` (`NOT_SUPPORTED`). Valida todo sin tocar la base, arma la venta en memoria y reserva sector por sector en Eventos (`ClienteDeEventos.reservarCupo`). Eventos aplica la reserva con un `UPDATE` condicional (`vendidas + cantidad <= cupo`), en su propia transacción.
4. Recién después guarda la venta completa con `ReglasEntreCompras.guardarDentroDelTope`, en una transacción corta: toma el candado del comprador y el evento, guarda la venta y vuelve a contar sus entradas para el evento. Si otra compra suya se guardó mientras esta reservaba y con las dos pasa el tope, 409 `tope-por-evento` y la venta no queda (DEF-46).
5. **Compensación:** si una reserva o el guardado fallan (también por el tope del paso 4), `devolverCupo` libera los sectores ya reservados, que son los renglones agregados después de una respuesta correcta. El sector cuya llamada no respondió no se devuelve, porque no se sabe si la reserva se aplicó: puede quedar un lugar sin vender, nunca uno vendido dos veces.
6. **Carrera por la misma clave:** si dos pedidos con la misma clave llegan a la vez, los dos reservan; la restricción única `(comprador_id, clave_idempotencia)` deja guardar a uno. El otro recibe `clave-repetida`, devuelve su cupo y `ComprasEnCurso` responde con la venta ganadora. Si en el sector quedaba lugar para uno solo, el que pierde puede recibir 409 `cupo-agotado`; al repetir, recibe la venta.

Por qué `NOT_SUPPORTED`: con Eventos no hay nada que una transacción de esta base pueda deshacer, y así ninguna llamada a Eventos espera con una conexión tomada.

## 7.3 Pagar y confirmar

`ComprasEnCurso.pagar` ordena cuatro pasos:

1. **Mirar sin cambiar.** Lee la venta del comprador. Si ya está paga con la misma clave, la devuelve sin cobrar (idempotencia). Si está paga con otra clave, 409 `venta-ya-paga`. Si no se puede pagar (vencida, cancelada) 409 `reserva-vencida`. Si la venta está vigente, sin intento de pago anotado, pero su instancia ya no está, la cancela con el `UPDATE` condicional y devuelve el cupo antes de responder `reserva-vencida`. Si ya tiene un intento anotado y no queda instancia que pueda reintentarlo, responde 409 `operacion-en-curso` ("puede que ya se haya cobrado") y la venta queda para la conciliación (7.8).
2. **Anotar el intento.** `guardarClaveDePago` hace `update venta set clave_pago = :clave, pago_intentado = :ahora where estado = 'EN_CHECKOUT' and ((clave_pago is null and vence_reserva > :ahora) or clave_pago = :clave)` y lo confirma en el momento, fuera de la transacción del pago. Con la marca puesta, `cancelar` y `vencer` (que exigen `clave_pago is null`) no cambian la fila: el cupo no se libera con un cobro en curso. Y si el pago falla después de cobrar, queda el rastro.
3. **Cobrar y confirmar** en `confirmarCompra` (`REQUIRED`, `@Remove(retainIfException = true)`):
   - Valida con el estado de la instancia, sin leer la base.
   - Mira el DNI de los titulares y los copia a `venta_titular` (`ReglasEntreCompras.exigirDnisSinEntradaYCopiarTitulares`, `REQUIRES_NEW`): el contenedor suspende la transacción del pago y abre otra, que toma el candado de cada DNI para el evento, vuelve a mirar que ninguno tenga una entrada en pie ni esté en otra compra que se está cobrando (si alguno está tomado, 409 `dni-con-entrada` y no se cobra) y hace los `INSERT` (`respaldarTitulares`, que se suma a esa transacción). La confirma y devuelve la conexión. Así ninguna conexión queda retenida durante el cobro, la copia sobrevive a un rollback posterior y otra compra con el mismo DNI, que esperaba en el candado, ya la ve (DEF-46). En un reintento del mismo cobro no se hace; en un pago nuevo después de un rechazo se vuelve a mirar el DNI y la copia que ya existe no se repite.
   - Cobra (`cobros.cobrar`) con el total que la instancia calculó al abrir la compra. Es una llamada hacia afuera: va antes de escribir porque un rollback no devuelve la plata. La transacción ya está abierta, pero no tomó ninguna conexión: la toma con la primera consulta, en el paso siguiente.
   - Si el cobro se rechaza, `PagoRechazado` (402): no hay nada que deshacer y la instancia sigue viva para pagar con otro medio.
   - Si se aprueba, en la misma transacción: `ventas.confirmar` (UPDATE condicional a `CONFIRMADA` con el `pagoId`), `entradas.guardar` (una entrada por titular, en `PENDIENTE_EMISION`) y `publicador.publicar` (`PAGO_CONFIRMADO` a la cola y al tópico). Si algo falla se deshace todo junto: no queda una venta confirmada sin entradas ni un mensaje de una venta que no se confirmó. El mensaje sale del servidor recién con el commit.
   - El `@Remove` termina la conversación al volver bien.
4. **Decidir sobre la marca.** La regla es una: la marca se quita cuando se sabe que no se cobró. Se quita ante `PagoRechazado`, ante `CobroNoRealizado` (el pedido se frenó antes del cobro: 401 repetido, 403, 404, sin token de servicio), ante `NoSuchEJBException` (la instancia ya no existía y la llamada no entró) y ante una reserva que venció entre la marca y la revisión. Se deja ante `SinRespuesta` (504) y ante una falla inesperada (`EJBException`, que además hace que `ComprasEnCurso` saque la instancia del índice): la venta queda `EN_CHECKOUT` con su cupo, y la cierra el reintento del comprador o la conciliación (7.8). En un reintento nunca se quita, porque del intento anterior con esa clave no se sabe el resultado.

Si `ventas.confirmar` no cambia la fila después de un cobro aprobado (no debería pasar, porque la marca impide cancelar y vencer), queda un `SEVERE` en el log con el id del pago para devolverlo y la operación sale como falla inesperada.

## 7.4 La publicación dentro de la transacción: broker XA y base como último recurso

- El publicador usa la fábrica `java:/JmsXA` (`DestinosJms.FABRICA_XA`): el broker participa de la transacción JTA como recurso XA, con fase de preparación.
- `PalcoDS` es un datasource común, no XA (`configurar.cli`, `data-source add`). Entra a la misma transacción como último recurso: el administrador de transacciones prepara el broker, confirma la base y después confirma el broker.
- `MANDATORY` en el publicador impide publicar fuera de la transacción de quien llama. Con `REQUIRED` abriría una propia y el mensaje saldría aunque la venta se deshiciera.
- **No es un commit en dos fases completo.** Si el servidor se cayera justo entre el commit de la base y el del broker, quedaría una venta `CONFIRMADA` con sus entradas sin emitir y sin mensaje. Es el riesgo que se acepta con el último recurso (sección 21): el orden de los commits es el que la documentación del servidor describe para un recurso no XA, y la ventana es la de un solo commit. Lo que se prueba es que un mensaje publicado dentro de una transacción que se deshace no sale: el endpoint de prueba con falla de Notificaciones responde 409, los contadores de mensajes agregados de la cola, del tópico y de la DLQ no cambian y los tres consumidores no escriben ninguna línea en el log (`docs/entrega3/evidencia/reproduccion.txt`, paso 8). En Ventas, la falla después de publicar se repite con la propiedad de sistema `palco.ventas.fallar-despues-del-cobro=true` (5.10): se prende con `jboss-cli` (`/system-property=palco.ventas.fallar-despues-del-cobro:add(value=true)`), se paga una compra y se apaga con `:remove`. El escenario 14 de `scripts/pruebas/escenarios.mjs` hace esos pasos y comprueba el rollback y la conciliación (7.8).
- Por eso los consumidores son idempotentes (7.6).

## 7.5 Cancelar y vencer: cambio de estado primero, compensación después

**Cancelar** (`ComprasEnCurso.cancelar`): si la instancia está, `cancelarCompra` (`REQUIRED`, `@Remove`) hace `update venta set estado = 'CANCELADA' where estado = 'EN_CHECKOUT' and clave_pago is null`; si no está, el mismo `UPDATE` lo hace el repositorio. Solo quien cambió la fila devuelve el cupo, y recién cuando la cancelación ya quedó confirmada. Repetir la cancelación sobre una venta cancelada o vencida no hace nada. Con un pago en curso responde 409 `operacion-en-curso`; si ya está paga, 409 `venta-ya-paga`.

**Vencer** (`VencimientoDeReservas`, `@Singleton`, `@Schedule` cada minuto, `persistent = false`, `NOT_SUPPORTED`): busca las ventas `EN_CHECKOUT` con la reserva vencida y sin `clave_pago`, y por cada una hace `update ... set estado = 'VENCIDA' where estado = 'EN_CHECKOUT' and vence_reserva <= :ahora and clave_pago is null`. Solo si cambió la fila devuelve el cupo y quita la compra del índice. Si la base no responde, la corrida se corta con un `WARNING` y la toma la del minuto siguiente.

Con este orden, el cupo de una venta lo devuelve uno solo (quien la cerró) y solo por una venta que ya está cerrada en la base. Entre Ventas y Eventos no hay transacción: la devolución es una compensación (`ClienteDeEventos.liberarCupo`, `POST .../cupo` con cantidad negativa).

**Si la devolución falla, se reintenta** (desde el 23/10; antes esos lugares quedaban sin vender). Tres piezas:

- **La marca va con el cierre.** Los tres `UPDATE` que cierran una venta sin pagarse (cancelar, vencer y vencer un pago sin cobro en la conciliación) ponen también `devolucion_pendiente = true` (migración V3 de Ventas). Como va en la misma sentencia, la marca no se pierde aunque el servidor se caiga entre el cierre y la llamada a Eventos. `ComprasEnCurso.devolverCupo` la saca recién cuando Eventos aceptó todos los renglones.
- **Un barrido la reintenta.** `DevolucionDeCupos` (`@Singleton`, `@Schedule` cada minuto en el segundo 30, `persistent = false`, `NOT_SUPPORTED`, igual que `VencimientoDeReservas`) busca las ventas con la marca y vuelve a devolver su cupo hasta que Eventos responda.
- **Devolver es seguro de repetir.** Cada devolución lleva una clave fija por venta y sector (`devolucion:<ventaId>:<sectorId>`). Eventos guarda la clave en la tabla `devolucion_de_cupo` (migración V5) en la misma transacción en la que resta las vendidas, y si la clave ya está no resta otra vez. Sin esto, reintentar una devolución que Eventos sí aplicó, pero cuya respuesta se perdió, liberaría lugares de más, y un lugar podría venderse dos veces. Si dos pedidos con la misma clave llegan a la vez, la clave primaria deja pasar a uno y el otro recibe 409 `devolucion-ya-aplicada`, que Ventas cuenta como devuelto.

El cliente distingue tres resultados (`ClienteDeEventos.Devolucion`): `HECHA`, `SIN_RESPUESTA` (Eventos no contestó o el servidor respondió 404 sin el error de Eventos, porque el WAR no está desplegado: se reintenta) y `RECHAZADA` (Eventos contestó que el sector no existe o que no tiene tantas vendidas: reintentar daría lo mismo, así que se saca la marca y queda un `SEVERE` para revisarlo a mano). Probado en el servidor el 23/10: con el WAR de Eventos detenido se canceló una compra de 2 entradas, el barrido falló mientras Eventos seguía detenido y, al volver a desplegarlo, el siguiente barrido devolvió las 2 entradas una sola vez. La devolución que se hace al abrir una compra que falló (`ServicioDeVentas.iniciarCompra`) no se reintenta: la venta no llegó a guardarse y no hay marca que poner (riesgo aceptado, sección 21).

## 7.6 Emisión e idempotencia del consumidor

`EmisionDeEntradasMDB.onMessage` (`REQUIRED`): recibir el mensaje, poner el código QR a cada entrada, dejarla `VALIDA` y pasar la venta de `CONFIRMADA` a `EMITIDA` se confirman juntos. Si algo falla, no queda nada escrito y el broker reentrega (5 entregas, 2 segundos, DLQ). Cada cambio es un `UPDATE` condicional: la entrada tiene que seguir `PENDIENTE_EMISION` y la venta `CONFIRMADA` (`marcarEmitida`). Una segunda entrega no cambia ninguna fila y el mensaje se confirma sin error; vale también si dos copias llegan a la vez a dos instancias. Un mensaje inválido se deshace con `setRollbackOnly()` y termina en la DLQ; un mensaje de una venta que no existe se descarta con un `WARNING`.

## 7.7 Idempotencia: resumen

| Paso | Mecanismo | Qué pasa si se repite |
|---|---|---|
| Iniciar | `Idempotency-Key` + restricción única `(comprador_id, clave_idempotencia)` | Devuelve la venta como está hoy, sin reservar otra vez |
| Pagar | `clave_pago` en la venta; clave `<ventaId>:<clave>` hacia Pagos, que deduplica con restricción única | Devuelve la venta paga sin cobrar; tras un 504 el reintento con la misma clave hace que Pagos devuelva el pago ya guardado. Ante 409 `pago-duplicado` `ClienteDePagos` repite una vez con la misma clave |
| Cancelar / vencer | `UPDATE` condicional | No cambia nada y no vuelve a devolver cupo |
| Emitir | `UPDATE` condicional por entrada y por venta | No cambia nada |
| Auditar | Clave primaria `JMSMessageID` | No inserta otra fila |
| Conciliar | `UPDATE` condicional: confirmar exige `EN_CHECKOUT` y la misma `clave_pago`; vencer exige además `pago_intentado` anterior al margen. Más la marca de "ocupada" de `ComprasEnCurso` | La segunda corrida no cambia nada, no vuelve a devolver el cupo ni a publicar |
| Usar una entrada | `UPDATE` condicional de `VALIDA` a `USADA` | El segundo escaneo da `YA_USADA` |
| Comprar en la reventa | Toma con la clave del comprador; clave `<idPublicación>:<clave>` hacia Pagos; venta nueva con clave `reventa:<idPublicación>` | No cobra dos veces ni crea dos ventas: Pagos devuelve el mismo pago y Ventas, la misma venta |
| Facturar | Búsqueda previa por venta, restricción única `factura_venta_unica` y el ARCA simulado, que devuelve el mismo comprobante para la misma venta | No factura dos veces ni saltea números |

En el frontend, la clave de la compra vive en la memoria de la página: si la respuesta de la reserva se pierde y se recarga la página antes de reintentar, el reintento lleva otra clave y la primera reserva queda sin dueño hasta que la vence el barrido, a los 10 minutos. Se acepta porque el cupo vuelve solo y no se cobra nada (sección 21).

## 7.8 Pagos sin resultado: qué se deshace y cómo se recupera

Un pago queda sin resultado cuando Ventas anotó el intento (`clave_pago`) y no supo cómo terminó: Pagos no respondió (504), o hubo una falla inesperada después de cobrar que deshizo la confirmación. La falla se repite con la propiedad `palco.ventas.fallar-despues-del-cobro=true` (5.10).

**Qué se deshace y qué queda.** Con la falla de prueba, `confirmarCompra` lanza una `IllegalStateException` después de confirmar la venta, crear las entradas y publicar. No es un error de negocio, así que el contenedor deshace la transacción entera y descarta la instancia:

| Dónde | Qué queda después de la falla |
|---|---|
| Ventas, venta | `EN_CHECKOUT`, sin `pagoId`, con `clave_pago` y `pago_intentado`: la marca se confirmó antes, en su propia transacción |
| Ventas, entradas | Ninguna: se crearon en la transacción que se deshizo |
| Ventas, `venta_titular` | La copia de los titulares: se confirmó antes del cobro (`REQUIRES_NEW`) |
| Broker | Ningún mensaje: el publicador se sumó a la transacción que se deshizo |
| Pagos | El pago `APROBADO`, guardado en la transacción corta de su repositorio |
| Eventos | El cupo sigue tomado: nadie lo devolvió, porque la venta tiene la marca |
| Comprador | 500 `error-inesperado` con su `idError`. Si repite el pago, 409 `operacion-en-curso` ("puede que ya se haya cobrado"): ya no hay instancia que pueda reintentarlo |

**Cómo se recupera.** `ConciliacionDePagos` corre en el segundo 30 de cada minuto, sin transacción:

1. Busca las ventas `EN_CHECKOUT` con `clave_pago` cuyo último intento tiene más de 2 minutos (`buscarPagosSinResultado`).
2. Saltea las que todavía tienen dueño: instancia viva y reserva vigente. Las demás las toma con la marca de "ocupada" de `ComprasEnCurso`; si hay otra llamada en curso, quedan para la corrida siguiente.
3. Le pregunta a Pagos por la clave derivada (`GET /api/v1/pagos?claveIdempotencia=<ventaId>:<clave>`, rol `servicio`).
4. Según la respuesta:
   - **Pago aprobado.** `ConfirmacionConciliada.confirmar` (`REQUIRED`) hace `update venta set estado = 'CONFIRMADA', pago_id = :pagoId where estado = 'EN_CHECKOUT' and clave_pago = :clave`, crea una entrada por titular con la copia de `venta_titular` y publica `PAGO_CONFIRMADO`, todo junto. Las entradas las emite la cola, como siempre. El cupo no cambia: nunca se devolvió.
   - **Sin pago (404 `no-encontrado`) o pago rechazado.** Un solo `UPDATE` quita la marca y pasa la venta a `VENCIDA`, con la condición `pago_intentado <= límite`: si llegó un reintento después de la pregunta, la venta no se vence. Confirmado ese cambio, se devuelve el cupo a Eventos.
   - **Pagos no responde, o la respuesta no permite saberlo** (un 404 sin ese `type`, un pago de otra venta): no cambia nada y la venta queda para el minuto siguiente.
5. Si el `UPDATE` no cambia ninguna fila, la cerró otro (un reintento u otra corrida): no se crean entradas, no se publica y no se devuelve cupo.

Con la falla de prueba, la conciliación confirma la venta en la primera corrida después de los 2 minutos de margen, con el pago que quedó en Pagos. El escenario 14 de `scripts/pruebas/escenarios.mjs` recorre ese camino. Estado: **Implementado**. El 16/10, con el servidor armado desde cero, el escenario 14 mostró lo que dice la tabla: 500 con `idError`, la venta `EN_CHECKOUT` sin entradas ni mensaje y el pago `APROBADO` en Pagos; la conciliación la confirmó con ese mismo pago y la entrada se emitió en menos de 3 minutos. En los tests, `ConciliacionEnH2Test` corre la falla y la conciliación contra H2 con las migraciones V1 y V2.

## 7.9 Uso de una entrada en la puerta

1. La pantalla de puerta manda el código y el evento a `POST /api/v1/validacion/escaneos`. `ServicioDeValidacion.escanear` corre sin transacción (`NOT_SUPPORTED`) y llama a Ventas (`POST /api/v1/entradas/usos`, rol `servicio`) sin retener una conexión.
2. En Ventas, `UsoDeEntradas.marcarUsada` (`REQUIRED`) busca la entrada por su código. Si es de otro evento, responde 409 `entrada-de-otro-evento` sin tocarla.
3. Si no, `update entrada set estado = 'USADA' where id = :id and estado = 'VALIDA'`. La base bloquea la fila mientras la actualiza: de dos escaneos simultáneos, uno solo cambia la fila; el otro espera, ya la ve `USADA` y no cambia nada. Después se relee la entrada: si este escaneo la cambió, 200 con la entrada; si está `USADA`, 409 `entrada-ya-usada`; si está `PUBLICADA`, `VENDIDA` o `ANULADA`, 409 `entrada-no-habilitada`.
4. Validación guarda el renglón del escaneo con el resultado, en la transacción corta de su repositorio, y responde 201.

No hay transacción entre Validación y Ventas, y no hace falta: lo que tiene que ser atómico (el uso único) es una sola sentencia en la base de la dueña de la entrada. Si Ventas no responde, se guarda el intento como `SIN_RESPUESTA` y se responde 504; si ese intento sí había usado la entrada, el escaneo siguiente da `YA_USADA` con `usoAnterior` apuntando a ese renglón. `UsoEnH2Test` prueba el `UPDATE` con dos conexiones a la vez.

## 7.10 Transferencia en la reventa

La compra de una publicación (`ServicioDeReventa.comprar`) corre sin transacción propia: cada paso queda confirmado al volver, porque entre paso y paso se llama a otros servicios.

1. **Controlar** (desde el 20/11, DEF-30). Reventa le pide a Ventas `POST /api/v1/operaciones-de-reventa/controles-de-compra` con la entrada, el vendedor, el comprador y el titular. `ReventaDeEntradas.controlarCompra` no cambia nada: si el comprador llegaría a 7 entradas del evento o el DNI del titular ya tiene otra entrada para ese evento, responde 409 `tope-por-evento` o `dni-con-entrada` y no se toma ni se cobra. Un reintento de la misma toma, con la misma clave y el mismo DNI, saltea este paso; si trae otro DNI, se controla.
2. **Tomar.** `update publicacion set comprador_id = :comprador, clave_compra = :clave, compra_intentada = :ahora, titular_nombre = :nombre, titular_dni = :dni where estado = 'ACTIVA' and vendedor_id <> :comprador and (clave_compra is null or (clave_compra = :clave and comprador_id = :comprador))`. De dos compras, una sola toma; la otra recibe 409. Desde el 23/10 la toma no vence a los 2 minutos y guarda el titular (migración V2 de Reventa).
3. **Cobrar** contra Pagos el precio más el cargo de servicio, con la clave `<idPublicación>:<clave>`. Si se rechaza, o el pedido no llegó a salir, se libera la toma (402 o 504). Si Pagos no responde, la toma queda: no se sabe si cobró, y nadie más paga por esa entrada ni el vendedor la retira. El mismo comprador puede reintentar con la misma clave y Pagos devuelve el pago si existe.
4. **Transferir.** Reventa le pide a Ventas `POST /api/v1/operaciones-de-reventa/transferencias`. `ReventaDeEntradas.transferir` (`REQUIRED`) hace todo en una transacción: la entrada original pasa de `PUBLICADA` a `VENDIDA` con un `UPDATE` condicional que además exige que sea del vendedor, nace la venta del comprador con la clave `reventa:<idPublicación>`, se crea la entrada nueva, se confirma con el `pagoId` y se emite con un código QR nuevo. Antes de escribir toma el candado del comprador y el del DNI para el evento, los mismos de una compra del catálogo, y vuelve a mirar el tope y el DNI (desde el 24/11, DEF-46): si otra compra se cruzó con esta desde el control, la que entra segunda cuenta a la primera. O pasa todo, o nada. Aunque se cruzaran dos transferencias, la entrada original se transfiere una sola vez. Si el comprador ya tiene la venta de esa publicación, la devuelve sin cambiar nada.
5. **Cerrar.** Un `UPDATE` deja la publicación `VENDIDA` con la venta y el pago.

Si el cobro sale aprobado y la transferencia falla, depende de cómo falla (desde el 23/10; antes el pago se devolvía a mano):

- **Ventas la rechaza** (409 `entrada-no-publicada` o 404: la entrada ya no está publicada o no es del vendedor). La compra no va a terminar, y reintentarla no cambia nada. `DevolucionDeCobros` anota el reembolso en la tabla `reembolso`, `PENDIENTE`; después la publicación queda `ANULADA` y recién ahí se le pide a Pagos que devuelva el pago (`POST /api/v1/pagos/{pagoId}/reembolso`). El comprador recibe 409 `publicacion-no-activa` con "Te devolvimos el pago" o, si Pagos no respondió, "El pago se va a devolver". Si la base falla al anotar, la publicación no se anula: sigue tomada y la conciliación lo vuelve a intentar.
- **Ventas la rechaza por una regla entre compras** (409 `tope-por-evento` o `dni-con-entrada`, desde el 24/11, DEF-46): otra compra del comprador, o con el mismo DNI, se cruzó con esta entre el control y la transferencia. Reventa lo recibe como `ClienteDeVentas.CompraFueraDeLasReglas`. La entrada sigue publicada en Ventas, así que `DevolucionDeCobros` anota el reembolso con un motivo propio (`motivoPorRegla`), la publicación queda libre para otra persona y se pide la devolución. El comprador recibe el 409 de la regla, con el texto de Ventas y "Te devolvimos el pago" o "El pago se va a devolver".
- **Ventas no responde:** pudo haber transferido. La toma queda y el comprador recibe 504; la resuelve un reintento con la misma clave, que no vuelve a cobrar, o la conciliación.

Un reembolso `PENDIENTE` lo reintenta `ConciliacionDeReventas` en el segundo 15 de cada minuto (`reintentarReembolsos`). Pagos devuelve un pago una sola vez, así que repetir el pedido es seguro. Si Pagos contesta que ese pago no se puede devolver (409 `pago-no-reembolsable`), el reembolso queda `RECHAZADO` con un `SEVERE` para revisarlo a mano. Antes de transferir, la compra y la conciliación miran si ese pago ya tiene un reembolso anotado: si lo tiene, la entrada no se transfiere, y el motivo del reembolso decide si la publicación se anula (la entrada ya no estaba publicada) o queda libre (una regla entre compras).

**Conciliación de las compras sin resultado** (desde el 23/10; antes la toma vencía a los 2 minutos y otra persona podía comprar una entrada ya cobrada). `ConciliacionDeReventas` (`@Singleton`, `@Schedule` cada minuto en el segundo 45, `persistent = false`, `NOT_SUPPORTED`) busca las publicaciones `ACTIVA` con una toma de hace más de 2 minutos (margen para no preguntar mientras el cobro sigue en camino) y le pregunta a Pagos por su clave (`GET /api/v1/pagos?claveIdempotencia=<idPublicación>:<clave>`):

- **Pago aprobado:** Ventas transfiere la entrada al titular que quedó anotado en la toma y la publicación queda `VENDIDA`. Ventas transfiere una sola vez por publicación y comprador, así que un reintento simultáneo del comprador no la duplica. Si Ventas no responde, se reintenta en un minuto sin liberar la publicación, porque hay plata cobrada. Si Ventas rechaza la transferencia, el pago se devuelve igual que en la compra: la publicación queda `ANULADA` si la entrada ya no estaba publicada, y libre si la rechazó por una regla entre compras (DEF-46).
- **Pago rechazado o ninguno con esa clave:** la publicación se libera, con un UPDATE que exige que la toma siga siendo la que se miró. Si el comprador reintentó mientras tanto, la toma es más nueva y no se toca.
- **Pagos no responde** (o responde 404 sin su error, porque el WAR no está desplegado): no cambia nada y se pregunta de nuevo en un minuto.

Probado en el servidor el 23/10 con el escenario 19: dos compras con `tok_sin_respuesta` (504). En una no hubo cobro; en la otra se registró en Pagos un cobro aprobado con la misma clave, como si Pagos hubiera cobrado sin que Reventa lo supiera. Mientras tanto otra cuenta no pudo comprar ni el vendedor retirar (409). En la primera corrida de la conciliación, la primera quedó libre y otra cuenta la compró, y la segunda quedó `VENDIDA` con la entrada a nombre del titular de la compra y sin cobrar de nuevo.

Probado en el servidor el 23/10 con el escenario 20: con la entrada devuelta a `VALIDA` en Ventas por detrás de Reventa, la compra cobró, Ventas rechazó la transferencia y el comprador recibió 409 con "Te devolvimos el pago"; el pago quedó `REEMBOLSADO` por el total cobrado y la publicación `ANULADA`, y ni el reintento ni otra cuenta volvieron a cobrar. Una segunda publicación, con el cobro hecho sin que Reventa lo supiera, la anuló la conciliación, que también devolvió el pago.

**Publicar y retirar** usan la transacción por defecto de Reventa. Al publicar, Ventas deja la entrada `PUBLICADA` y después Reventa guarda la publicación; si el guardado falla, se le pide a Ventas que la retire (compensación). Al retirar, el `UPDATE` a `RETIRADA` va primero y después Ventas devuelve la entrada a `VALIDA`; si Ventas falla, la transacción se deshace y la publicación sigue `ACTIVA`. Retirar en Ventas es idempotente, así que el vendedor puede repetirlo.

**Si después se cancela el evento** (desde el 20/11). Una entrada revendida deja dos compras pagadas del evento: la de quien la vendió, con esa entrada `VENDIDA`, y la que nació en la transferencia para quien la compró. `AnulacionDeCompras` anula las dos y cada lugar se devuelve una sola vez, a quien lo tiene. La compra hecha en la reventa pide el reembolso con el id de la publicación, que es la venta con la que Pagos guardó ese cobro (`ReventaDeEntradas.ventaEnPagos`), y no devuelve cupo, porque no reservó ninguno. A quien vendió se le devuelve lo que pagó menos el precio y el cargo de servicio de las entradas que revendió, y su compra devuelve el cupo entero. El detalle está en la sección 10.8.

## 7.11 Facturación

`FacturacionDeComprasMDB` recibe `PAGO_CONFIRMADO` en la transacción del mensaje (`REQUIRED`). `ServicioDeFacturacion` (`NOT_SUPPORTED`) suspende esa transacción mientras busca la factura, pide el CAE por SOAP y la guarda en la transacción corta del repositorio. Si ARCA no responde, el MDB hace `setRollbackOnly()` y el broker reentrega (5 entregas, 2 segundos, DLQ). Si ARCA rechaza el comprobante, desde el 20/11 el rechazo se guarda en la transacción corta del repositorio, como la factura, y la transacción del mensaje se confirma sin `setRollbackOnly()` (DEF-33). Si la transacción del mensaje se deshiciera con la factura o el rechazo ya guardados, la reentrega los encuentra y no llama a ARCA. El detalle está en la sección 11.

## 7.12 Riesgos aceptados en las transacciones

Son decisiones de diseño: cada una dice qué pasa, por qué se acepta y qué la cambiaría si cambiara el alcance (por ejemplo, varios servidores o una pasarela real). La sección 21 las junta con las del resto del sistema.

| Riesgo | Qué pasa | Por qué se acepta | Si cambia el alcance |
|---|---|---|---|
| Ventas marcadas antes de la copia de titulares | Una venta con `clave_pago` anotada antes de la migración V2 no tiene filas en `venta_titular`. Si Pagos dice `APROBADO`, la conciliación deja un `SEVERE` cada minuto y no la confirma ni la vence; si no hay cobro, la vence bien | Hay plata cobrada y no se sabe a nombre de quién van las entradas: no se inventan titulares | Resolverlas a mano |
| Conciliación con el simulador de cobros | Con `PALCO_VENTAS_COBROS=fake`, `CobrosFake.buscarCobro` contesta "no se sabe" y la venta queda retenida | El simulador no guarda los cobros; es para trabajar sin Pagos | Usar el servicio de Pagos, que es el valor por defecto |
| Pasarela real más lenta que el margen | Pagos no tiene un tiempo de espera propio con la pasarela. Si una pasarela tardara más de 2 minutos, la conciliación podría leer "no hay pago" y vencer la venta mientras el cobro sigue en curso | Con `PagosFake` no pasa: responde en el momento | Al sumar Mercado Pago, un tiempo de espera en Pagos menor que `MINUTOS_DE_MARGEN` |
| Exclusión en memoria entre conciliación y reintento | La marca de "ocupada" de `ComprasEnCurso` vale para un solo servidor. La base protege el vencimiento con `pago_intentado`, pero no el caso de un reintento y una conciliación que confirman a la vez el mismo pago en dos servidores: el que pierde el `UPDATE` dejaría un `SEVERE` falso | Hay un solo WildFly | Una marca en la base, si el sistema corre en varios servidores |
| Caída entre los dos commits | Venta `CONFIRMADA` sin mensaje ni emisión. La conciliación no la cubre: solo toma ventas `EN_CHECKOUT` | Es la garantía elegida (5.4): la ventana es la de un solo commit | Una base XA o una tabla de salida |
| Reserva sin dueño ante una caída | Si el servidor se cae entre la reserva en Eventos y el guardado de la venta, el cupo queda tomado sin venta. La conciliación no la ve, porque no hay venta guardada | Ventana corta; queda en el log, y nunca se vende dos veces un lugar | Un registro de la reserva antes de llamar a Eventos |
| Compras en curso en memoria | Redesplegar Ventas vacía el índice. Una compra sin intento de pago pierde los titulares: se puede consultar y cancelar, y pagarla la cancela y devuelve el cupo. Una con un intento sin resultado ya tiene la copia y la cierra la conciliación | El stateful es el diseño elegido (5.1) y la reserva dura 10 minutos | Ninguno: es lo que se elige al usar un stateful |
| Reventa: un reembolso que Pagos rechaza | Queda `RECHAZADO` con un `SEVERE` y nadie lo reintenta | Pagos solo lo rechaza si el pago no está aprobado o es de otra publicación: repetir daría lo mismo | Revisarlo a mano |
| Reventa: retirar espera a Ventas con la transacción abierta | El `UPDATE` a `RETIRADA` toma una conexión de Reventa y la retiene mientras Ventas responde (hasta 2 s de conexión y 5 de lectura) | Así una falla de Ventas deshace el retiro sin compensación | Llamar a Ventas primero y compensar si falla el `UPDATE` |
| Validación: falla al guardar el escaneo | Si Ventas ya usó la entrada y falla el guardado del renglón `VALIDA`, el pedido da 500 y el escaneo siguiente da `YA_USADA` sin `usoAnterior` | La entrada no se usa dos veces; solo falta el renglón del registro | Guardar el intento antes de llamar a Ventas |
| Timeouts de los clientes | Todas las llamadas entre servicios: 2 s de conexión y 5 s de lectura; ARCA: 2 s y 5 s, configurables; el resto, en la tabla de la sección 8.8. Al vencer, `SinRespuesta` (504, o reentrega en Facturación) | Valores fijos e iguales en todos los clientes: 5 s es el tiempo máximo que pidió la cátedra para el 3.er check point (C3.2), y la prueba de carga mide p95 menor que 500 ms en los endpoints críticos (16.8) | Ajustarlos por cliente con mediciones de cada servicio |

La conciliación, la falla repetible, la reventa, la validación en puerta y la facturación se probaron en vivo el 16/10, con el servidor armado desde cero (escenarios 14 a 17), y desde el 3.er check point corren en cada corrida del CI contra el `docker compose`, con PostgreSQL 17 (16.7). Las migraciones, la V2 de Ventas incluida, corren contra PostgreSQL 17 en el compose y en los `*IT`. Eventos caído se provoca en el escenario 18, Pagos sin respuesta en el 19 y la falla después del cobro en el 14.

# 8. Estrategia de integración (SOA)

Cada integración lleva uno de tres estados: **Implementado** (hay código y se comprobó contra el servidor en marcha), **Simulado** (el código existe, pero el otro lado es un reemplazo local: la pasarela `PagosFake`, el ARCA simulado o el aviso que se escribe en el log) y **Diseñado** (decidido y documentado, sin código).

## 8.1 Diagrama de integración

![Arquitectura de integración de Palco](diagramas/integracion-soa.svg)

*Fuente: `docs/documentacion/diagramas/integracion-soa.svg`, al día con el código (sección 3.6). La leyenda del diagrama distingue componentes, MDB, destinos JMS, sistemas externos y piezas opcionales o de un solo entorno.*

El diagrama de la Entrega Parcial N.º 2 (`docs/entrega3/Diagrama_Integracion_SOA.svg`, del 13/10/2026) queda como se entregó: es anterior a la facturación por SOAP, a la validación en puerta, a la reventa, a la cancelación de eventos y al asistente. Las flechas I1 a I5 y S1 a S3 de las tablas que siguen son las de ese diagrama; I6 e I7 se sumaron después.

## 8.2 Componentes internos

El backend son ocho componentes de negocio Jakarta EE, cada uno un WAR propio desplegado en el mismo WildFly 41, con su esquema de base, más el asistente con IA. Se hablan solo por REST o por JMS, nunca por clases del otro servicio. Hay además un noveno WAR, `palco-arca`, que **no es un servicio de Palco**: es el ARCA simulado, el sistema externo legado (sección 11). Vive fuera de `/api` (`/legado/arca`) y su `pom.xml` hereda de `palco-backend` y no de `palco-servicios`, así que no recibe `palco-common` ni comparte ninguna clase con Palco. El 16/10, con el servidor armado desde cero, los 9 WAR quedaron desplegados, los 8 `health` respondieron 200, el WSDL de `/legado/arca?wsdl` respondió 200 y el log no tuvo ninguna línea `ERROR` al arrancar. Desde el 18/11 hay un décimo WAR, el asistente con IA (4.10), un servicio de Palco que no es uno de los ocho componentes de negocio.

| Componente | Ruta base | Integra con | Estado |
|---|---|---|---|
| `ServicioDeUsuarios` (stateless) | `/api/usuarios` | Lo consumen todos: firma los JWT, publica la clave pública en `/api/usuarios/jwks` y entrega el token de rol `servicio` | Implementado |
| `ServicioDeEventos` (stateless) | `/api/eventos` | Recibe de Ventas la reserva y la devolución de cupo; publica `EVENTO_CANCELADO` (I6) | Implementado |
| `ServicioDeVentas` (stateful) | `/api/v1` | Llama a Usuarios, Eventos y Pagos por REST; publica `PAGO_CONFIRMADO`; consume la cola de emisión y `EVENTO_CANCELADO`, y publica `COMPRA_ANULADA` | Implementado |
| `UsoDeEntradas` (stateless, en Ventas) | `/api/v1/entradas/usos` | Lo llama Validación con rol `servicio`: marca la entrada como usada | Implementado (escenario 16) |
| `ReventaDeEntradas` (stateless, en Ventas) | `/api/v1/operaciones-de-reventa` | Lo llama Reventa con rol `servicio`: consulta, publica, retira y transfiere la entrada y, desde el 20/11, controla la compra antes del cobro (tope por evento y DNI por evento, DEF-30) | Implementado (escenario 17) |
| `ServicioDePagos` (stateless) | `/api/v1/pagos` | Lo llama Ventas; cobra contra la interfaz `PasarelaDePagos` | Implementado, con la pasarela **simulada** (`PagosFake`) |
| `ServicioDeNotificaciones` (stateless) | `/api/notificaciones` | Suscriptor del tópico (aviso y auditoría) | Implementado; el aviso es **simulado** (una línea en el log) |
| `ServicioDeFacturacion` (stateless) y `FacturacionDeComprasMDB` | `/api/v1/facturas` | Tercer suscriptor durable del tópico y cliente SOAP del ARCA simulado (sección 11) | Implementado (escenario 15); ARCA es **simulado** |
| `ServicioDeValidacion` (stateless) | `/api/v1/validacion` | Llama a Ventas por REST (`POST /api/v1/entradas/usos`) en cada escaneo | Implementado (escenario 16) |
| `ServicioDeReventa` (stateless) | `/api/v1/reventas` | Llama a Eventos (fecha del evento, para el cierre de 3 horas, y precio de lista del sector, para el rango), a Pagos (cobro) y a Ventas (`/api/v1/operaciones-de-reventa`) por REST; consume `EVENTO_CANCELADO` | Implementado (escenarios 17 y 22), con la pasarela **simulada** |
| `AsistenteIA` (stateless) | `/api/v1/asistente` | Gateway hacia el modelo de lenguaje: simulado por defecto o un proveedor con la API de chat de OpenAI (I7). Desde el 24/11 lee el catálogo público de Eventos (`GET /api/eventos`, sin token) para responder por los eventos a la venta; no llama a ninguna operación que cambie algo | Implementado, con el modelo **simulado** por defecto (`asistente.mjs`, 18/11, y en cada corrida del CI) |

## 8.3 Sistemas externos y canal

| Sistema | Naturaleza | Canal previsto | Estado |
|---|---|---|---|
| Mercado Pago | Partner moderno | REST (cobro) y webhook entrante | Diseñado (decisión del 13/10, sección 12). Se cobra con la pasarela **simulada** `PagosFake`, que la cátedra aceptó el 16/10 |
| ARCA (ex AFIP), WSFEv1 | Sistema legado, solo SOAP con WSDL | SOAP | Implementado contra un ARCA **simulado** (escenario 15, 16/10) en un WAR propio (`/legado/arca`, sección 11). La cátedra aceptó el 16/10 que las integraciones con ARCA sean simuladas por ahora |
| Servidor de correo | Cualquier SMTP, configurado por variables (`PALCO_SMTP_*`) | SMTP (`common/email/CorreoSmtp`) | Opcional: sin `PALCO_SMTP_HOST`, `FabricaDeCorreos` elige `CorreoAlLog` y el email se escribe en el log |
| Supabase Storage | Almacenamiento de las fotos de los eventos | REST (`eventos/negocio/externos/AlmacenEnSupabase`) | Opcional: sin `PALCO_SUPABASE_URL` y `PALCO_SUPABASE_CLAVE`, las fotos se guardan en la base de Palco |
| Proveedor de IA | Modelo de lenguaje con la API de chat de OpenAI | REST (`asistente/negocio/externos/ModeloCompatibleOpenAI`, I7) | Opcional: solo con `PALCO_ASISTENTE_MODELO=openai`. Por defecto el asistente usa el modelo **simulado** y no sale a la red |

El broker (ActiveMQ Artemis embebido en WildFly) **no es un sistema externo**: es infraestructura propia, en el mismo proceso. Las llamadas a sistemas externos que hay en el código son la de Facturación al ARCA simulado, por SOAP, y tres opcionales que se prenden por configuración: el correo por SMTP, las fotos en Supabase Storage y el proveedor de IA. No hay cliente de Mercado Pago.

## 8.4 Modelo de integración y por qué no un ESB

La integración se resuelve con tres piezas, sin un bus de integración como producto:

1. **REST directo entre servicios** cuando quien llama necesita la respuesta para seguir. Cada llamada sale de una clase cliente propia de quien llama, con tiempos de espera explícitos: 2 s para conectar y 5 para leer en todos (tabla de la sección 8.8).
2. **El broker JMS** para lo que puede resolverse después; quien publica no conoce a quienes consumen.
3. **Un adaptador por sistema externo detrás de una interfaz propia.** En Pagos, `PasarelaDePagos` con `PagosFake`, elegida por `FabricaDePasarela`; el adaptador de Mercado Pago sería otra implementación. En Facturación, la interfaz `Arca` con el adaptador `ArcaSoap`, elegido por `FabricaDeArca`; lo único que Facturación conoce del sistema legado es `PuertoArca`, una interfaz Java anotada con el contrato del WSDL (sección 11).

Por qué no un ESB: con 11 nodos (8 servicios, Mercado Pago, ARCA y el broker) hay 55 conexiones posibles; el diseño de la Entrega Parcial N.º 2 usaba 18 y entonces funcionaban 8. El 16/10, con el servidor armado desde cero, funcionan 17: las 8 de entonces (Usuarios con Eventos, Ventas, Pagos y Notificaciones; Ventas con Eventos y con Pagos; Ventas y Notificaciones con el broker), Usuarios con Validación, Reventa y Facturación, Facturación con el broker y con ARCA, Validación con Ventas, y Reventa con Eventos, Pagos y Ventas. La de Pagos con Mercado Pago es diseño, y la llamada de Facturación a Ventas se descartó (8.7). Después se sumaron Eventos y Reventa con el broker, por el tópico de cancelaciones (I6), Validación con Eventos (el dueño del evento) y el asistente, con el proveedor de IA (I7) y con el catálogo de Eventos. Un producto de ESB sumaría infraestructura sin resolver un problema que el sistema tenga hoy.

## 8.5 Criterio de clasificación

- **Síncrona** cuando quien llama necesita la respuesta para decidir cómo sigue y la operación es corta.
- **Asincrónica** cuando quien publica puede contestarle a su cliente sin esperar, y conviene que una demora o una caída del otro lado no lo afecte. Dentro de lo asincrónico, **cola** para lo que resuelve un único responsable una sola vez y **tópico** para lo que les interesa a varios que no se conocen.

## 8.6 Clasificación de las integraciones (tabla de la Entrega Parcial N.º 2, al día)

| N.º | Integración | Tipo y canal | Por qué | Si el otro lado no responde | Estado |
|---|---|---|---|---|---|
| I1 | Clientes → API REST de Palco | Síncrona · REST (JSON sobre HTTP) | La persona espera en pantalla si hay lugar y si el pago pasó | El cliente repite con la misma `Idempotency-Key`: no se reserva ni se cobra de nuevo | Implementado (`docs/entrega3/evidencia/reproduccion.txt`, pasos 2 a 4) |
| I2 | `ServicioDePagos` → Mercado Pago: cobro | Síncrona · REST | Pagos necesita el resultado para contestarle a Ventas | Pagos responde 504 y no guarda nada; Ventas reintenta con la misma clave | Diseñado. Hoy cobra `PagosFake` (**simulado**) |
| I2 | Mercado Pago → `ServicioDePagos`: webhook | Canal síncrono · REST, para un resultado que la compra recibe de forma diferida | Solo para pagos que la pasarela no resuelve en el momento | La pasarela reenvía; Pagos descarta repetidos con un `UPDATE` condicional | Diseñado. No existe la ruta |
| I3 | Facturación → ARCA (simulado) | Síncrona · SOAP con WSDL; **disparo asincrónico** por una suscripción durable de Facturación al tópico | ARCA solo ofrece SOAP (pedido y respuesta). La compra no necesita la factura para confirmarse: el pago ya está cobrado | El cliente SOAP corta a los 2 s sin conectar o a los 5 s sin respuesta; `FacturacionDeComprasMDB` deshace su transacción con `setRollbackOnly()` y el broker reentrega con los reintentos que ya existen (5 entregas cada 2 s); agotados, el mensaje va a la DLQ. Mientras Facturación está detenido, la suscripción durable guarda los mensajes. La compra ya está confirmada y no se entera | Implementado (escenario 15 y carpeta 7 de Postman, 16/10); ARCA **simulado**. La factura se consulta en `GET /api/v1/facturas/{ventaId}`; la venta sigue respondiendo `facturaEstado` `NO_APLICA` |
| I4 | Ventas → `cola.emision-entradas` → `EmisionDeEntradasMDB` | Asincrónica · cola JMS | Emitir no tiene que demorar ni hacer fallar el pago; un solo responsable, una sola vez | El mensaje espera en el broker; si el consumidor falla, 5 entregas y DLQ | Implementado (sección 10) |
| I5 | Ventas → `topico.compra-confirmada` → `AvisoDeCompraMDB`, `AuditoriaDeComprasMDB` y `FacturacionDeComprasMDB` | Asincrónica · tópico JMS | Tres interesados en el mismo hecho que no se conocen; sumar otro no toca Ventas (así se sumó Facturación) | Auditoría y facturación son durables (el broker les guarda lo pendiente); el aviso no, y se acepta perderlo | Implementado; el aviso es **simulado** (log). Cada compra deja 3 copias en el tópico (demo del 16/10) |
| I6 | Eventos → `topico.eventos-cancelados` → Ventas y Reventa (`EVENTO_CANCELADO`); Ventas → mismo tópico → aviso y auditoría (`COMPRA_ANULADA`) | Asincrónica · tópico JMS | Un hecho del evento afecta a cada compra y a cada publicación; anular, reembolsar y avisar puede tardar y no frena al organizador | Ventas, Reventa y la auditoría tienen suscripción durable; un reembolso que Pagos no contestó lo reintenta `ReintentoDeReembolsos` (10.8) | Implementado (escenario 22) |
| I7 | Asistente → proveedor de IA | Síncrona · REST (API de chat de OpenAI) | Quien pregunta espera la respuesta en pantalla | A los 5 s el gateway responde el fallback de `RespuestasFrecuentes`; si el proveedor falla, también (4.10) | Implementado; por defecto el modelo es **simulado** y no se llama a ningún proveedor |

**Cambio respecto del documento entregado:** la fila I3 de la Entrega Parcial N.º 2 decía que la factura se pedía *dentro* de la compra (Ventas → Facturación, S3). El grupo decidió el 16/10 (sección 11.4) que Facturación se suscriba al tópico: la llamada a ARCA sigue siendo síncrona, pero el disparo es asincrónico, para no retener una conexión de la base dentro de `confirmarCompra`, y Ventas no cambia. También se decidió no sumar una cola de reintento propia: alcanzan los reintentos del broker y la DLQ. Así quedó implementado.

**Procesos asincrónicos distintos.** La cola I4 y el tópico I5 salen del mismo pago confirmado, pero hacen dos cosas distintas: emitir las entradas, una sola vez, y avisar, auditar y facturar. Además, el tópico I5 lleva un segundo hecho, `REVENTA_CONFIRMADA` (10.1), y la cancelación de un evento (I6) es un proceso asincrónico propio, con su tópico, sus dos mensajes y sus cuatro consumidores (10.8).

## 8.7 Llamadas entre servicios internos

Todas son síncronas por REST: quien llama necesita el resultado.

| N.º | Llamada | Para qué | Estado |
|---|---|---|---|
| S1 | Ventas → Eventos: `POST /api/eventos/{eventoId}/sectores/{sectorId}/cupo`, rol `servicio` | Reservar cupo al abrir la compra; devolverlo al cancelar o vencer | Implementado (`reproduccion.txt`, paso 10) |
| S1 | Ventas → Usuarios: `GET /api/usuarios/yo` con el token del comprador | Datos del comprador antes de abrir la compra | Implementado (`ClienteDeUsuarios`) |
| S1 | Ventas → Pagos: `POST /api/v1/pagos`, rol `servicio`, con `Idempotency-Key` | Cobrar | Implementado (`ClienteDePagos`, `reproduccion.txt`, paso 6) |
| S2 | Todos → Usuarios: JWKS y token de servicio | Verificar tokens y autenticar llamadas internas | Implementado |
| S3 | Validación → Ventas: `POST /api/v1/entradas/usos`, rol `servicio`, con `codigoQr` y `eventoId` en el cuerpo | Marcar la entrada como usada en el momento del escaneo | Implementado (`ClienteDeVentas`; escenario 16) |
| S3 | Validación → Eventos: `GET /api/eventos/{eventoId}/organizador`, rol `servicio` | Saber de quién es el evento, para que el organizador vea el registro y arme la puerta solo de los suyos (4.6). El escaneo no la usa: la asignación del staff está en la base de Validación, así la puerta sigue funcionando aunque Eventos no responda | Implementado el 23/10 (`ClienteDeEventos`; escenario 21) |
| S3 | Reventa → Eventos (`GET /api/eventos`), Pagos (`POST /api/v1/pagos`, clave `<idPublicación>:<clave>`) y Ventas (`/api/v1/operaciones-de-reventa/...`), rol `servicio` salvo el catálogo, que es público | Revender: controlar el cierre de 3 horas y el rango de precio sobre el precio de lista del sector, que Ventas controle el tope de 6 por evento y el DNI por evento (desde el 20/11), cobrar y transferir la entrada (sección 7.10) | Implementado (escenario 17); la pasarela es **simulada** |

**Por qué la validación en puerta es síncrona.** El personal de puerta necesita saber en el momento si la persona entra. La decisión del uso único la toma Ventas, que es la dueña de las entradas, con un `UPDATE` condicional (`VALIDA` → `USADA` solo si sigue `VALIDA`): de dos escaneos simultáneos del mismo código, la base deja cambiar la fila a uno solo, sin transacción distribuida. Validación guarda el registro de cada escaneo en su esquema (`validacion.escaneo`). Si Ventas no responde, `ClienteDeVentas` corta a los 2 s sin conectar o a los 5 s sin respuesta; el escaneo queda registrado como `SIN_RESPUESTA` y la puerta recibe 504 `sin-respuesta` para escanear de nuevo. Ante un 401, el cliente pide un token de servicio nuevo y reintenta una sola vez: Ventas rechazó el pedido sin tocar la entrada, así que repetir es seguro. `ServicioDeValidacion` lleva `NOT_SUPPORTED`, como Pagos, para no retener una conexión de la base mientras espera a Ventas. El detalle está en el diagrama de la sección 13.8.

Facturación no llama a Ventas: por la decisión del 16/10, la factura se consulta en Facturación y `facturaEstado` sigue en `NO_APLICA`. La llamada `PUT /api/v1/ventas/{ventaId}/factura` del diseño anterior queda descartada.

Entre servicios no hay transacción compartida: cada llamada confirma por su cuenta. La regla para el cupo es que, ante la duda, queda un lugar sin vender y nunca uno vendido dos veces.

## 8.8 Tiempos de espera y resiliencia

Cada cliente REST entre servicios es un bean `@ApplicationScoped` que crea su cliente HTTP una vez, en el `@PostConstruct`, con `connectTimeout(2, TimeUnit.SECONDS)` y `readTimeout(5, TimeUnit.SECONDS)`. Si el tiempo vence o no hay conexión, lanza `ErroresDeNegocio.SinRespuesta`, que la API responde como 504 en Problem Details. Lo que cambia de una integración a otra es el fallback.

| Integración | Tiempo de espera (conexión / lectura) | Si vence o no responde |
|---|---|---|
| Ventas → Eventos: reservar y devolver cupo (`ventas/.../ClienteDeEventos`) | 2 s / 5 s | Al reservar, 504, y se devuelve el cupo de los sectores que sí respondieron. Al devolver, queda la marca `devolucion_pendiente` y `DevolucionDeCupos` reintenta cada minuto (7.5, escenario 18) |
| Ventas → Pagos: cobrar y reembolsar (`ClienteDePagos`) | 2 s / 5 s | Cobro: 504; la venta sigue `EN_CHECKOUT` con el intento anotado y la cierra `ConciliacionDePagos` (7.8, escenario 14). Reembolso de una compra de un evento cancelado: la compra se anula igual y `ReintentoDeReembolsos` lo vuelve a pedir (10.8) |
| Ventas → Usuarios: identidad del comprador (`ventas/.../ClienteDeUsuarios`) | 2 s / 5 s | 504 y la compra no se abre (`ComprasEnCursoTest`) |
| Reventa → Ventas, Pagos y Eventos (`ClienteRest`, común a los tres clientes) | 2 s / 5 s | Publicar: no se publica nada. Controlar la compra (desde el 20/11): 504 sin tomar la publicación ni cobrar. Comprar: la toma queda y la resuelve `ConciliacionDeReventas`; si Ventas rechaza la transferencia, `DevolucionDeCobros` devuelve el pago (7.10, escenarios 19 y 20) |
| Validación → Ventas: usar la entrada (`validacion/.../ClienteDeVentas`) | 2 s / 5 s | 504 y el escaneo queda como `SIN_RESPUESTA` (7.9) |
| Validación → Eventos y Usuarios: dueño del evento y rol de una cuenta | 2 s / 5 s | El organizador recibe 504; el escaneo no depende de ellos |
| Notificaciones → Usuarios: email del comprador (`notificaciones/.../ClienteDeUsuarios`) | 2 s / 5 s | No lanza: el aviso queda en el log y el mensaje se da por atendido, para no avisar dos veces |
| Todos → Usuarios: token de servicio (`common/seguridad/TokenDeServicio`) | 2 s / 5 s | `SinRespuesta`: el pedido no sale, y el siguiente vuelve a pedir el token |
| Facturación → ARCA, por SOAP (`ArcaSoap`, `FabricaDeArca`) | 2 s / 5 s, configurables | El MDB deshace la entrega y el broker la repite 5 veces, cada 2 s; después, la DLQ (sección 11). Probado contra el compose el 19/11: la compra se emite igual y el corte llega a los 5 s (11.9) |
| Asistente → modelo de lenguaje (`AsistenteIA`) | 5 s en el gateway; el cliente HTTP del modelo real (`ModeloCompatibleOpenAI`), 2 s / 6 s: uno más que el gateway, para que el que corta a los 5 s sea el gateway, con su motivo `timeout` | El fallback de `RespuestasFrecuentes` (4.10) |
| Asistente → Eventos: el catálogo público (`CatalogoDeEventos`, desde el 24/11) | 1 s / 2 s, dentro de los 5 s del gateway | El modelo recibe la última lista que se leyó bien o ninguna, y el asistente responde igual (4.10) |
| Usuarios y Notificaciones → servidor SMTP (`common/email/CorreoSmtp`) | 5 s para conectar, leer y escribir | El email no sale y queda un `WARNING`; la operación que lo disparó sigue. Jakarta Mail no tiene límite por defecto, y sin él un servidor colgado retendría el hilo del aviso |
| Eventos → Supabase Storage: subir una foto (`AlmacenEnSupabase`) | 2 s / 10 s, a propósito: subir una imagen tarda más que una consulta | 504 y la foto no se guarda |

Lo demás que pide la resiliencia, con su prueba:

- **Consumidores idempotentes.** Emitir, auditar, facturar, anular y retirar no se repiten si el mensaje llega dos veces: `UPDATE` condicionales en la emisión, la anulación y el retiro, el `JMSMessageID` como clave en la auditoría y una factura por venta (`factura_venta_unica`). Escenario 8.
- **DLQ.** Un mensaje que falla se entrega hasta 5 veces, con 2 s entre una y otra, y después pasa a `java:/jms/queue/DLQ` (`configurar.cli`). Escenario 12, y la copia de Facturación con el ARCA sin responder, reprocesada con el procedimiento de 10.6 (29 de 29, `checkpoint-3/arca-lento-dlq.txt`).
- **Broker apagado.** Los mensajes son persistentes, y son durables las suscripciones de la auditoría y de Facturación al tópico de compras y las de Ventas, Reventa y la auditoría al de cancelaciones. `scripts/pruebas/broker-apagado.mjs` deja un mensaje pendiente en la cola, reinicia el servidor con el broker adentro y comprueba que la venta llega a `EMITIDA`: 10 de 10 el 19/11 (`docs/testing/evidencia/checkpoint-3/broker-apagado.txt`). En el compose, el journal del broker y el registro de transacciones están en el volumen `datos-servidor` (ADR-005): con un mensaje pendiente, `docker compose down` (sin `-v`) y `up` recrean el contenedor y la venta llega igual a `EMITIDA`, 16 de 16 (`checkpoint-3/broker-recreado.txt`).
- **`Idempotency-Key`, `ETag` e `If-Match`.** La clave es obligatoria en la compra, el pago, el cobro y la compra en la reventa (9.3). `FiltroDeEtag` pone `ETag` en 10 GET marcados con `@ConEtag` y responde 304 con `If-None-Match`: `scripts/pruebas/etag.mjs`, 19 de 19 el 19/11, con el 412 de `If-Match` incluido (`checkpoint-3/etag.txt`). La edición de un evento acepta `If-Match` y responde 412 `evento-modificado` si cambió (4.2).
- **Problem Details.** Todo error sale como `application/problem+json` desde `ManejadoresDeErrores` (9.3); el 412 de `If-Match` también.

# 9. API REST

## 9.1 Rutas y versionado

| Servicio | Ruta base | Motivo |
|---|---|---|
| Usuarios | `/api/usuarios` | Existía antes de la convención de versionado |
| Eventos | `/api/eventos` | Ídem |
| Notificaciones | `/api/notificaciones` | Ídem |
| Ventas | `/api/v1` (`/ventas`, `/entradas`) | Servicio nuevo: versión en la ruta |
| Pagos | `/api/v1/pagos` | Servicio nuevo |
| Facturación | `/api/v1/facturas` | Servicio nuevo. Reemplaza a `/api/facturacion`, que solo tenía `health` |
| Validación | `/api/v1/validacion` | Servicio nuevo. Reemplaza a `/api/validacion`, que solo tenía `health` |
| Reventa | `/api/v1/reventas` | Servicio nuevo. Sus operaciones internas sobre las entradas están en Ventas, en `/api/v1/operaciones-de-reventa` |
| Asistente con IA | `/api/v1/asistente` | Servicio nuevo (18/11) |
| ARCA simulado (no es un servicio de Palco) | `/legado/arca` | Sistema externo: queda fuera de `/api` a propósito |

La regla es que los servicios nuevos van bajo `/api/v1`. Usuarios, Eventos y Notificaciones no se migraron para no romper al frontend ni a la colección de Postman; la diferencia queda declarada.

## 9.2 Operaciones implementadas

| Operación | Rol (`@RolesAllowed` / `@PermitAll`) | Respuesta correcta |
|---|---|---|
| `POST /api/usuarios/registro`, `/verificacion`, `/verificacion/reenvio`, `/login`, `/servicios/token` | `@PermitAll` (el token de servicio lo protege un secreto compartido) | 201 / 200 / 204 / 200 / 200 |
| `POST /api/usuarios/password/olvide`, `/password/reinicio` (recuperar la contraseña) | `@PermitAll` | 204 / 204 |
| `GET /api/usuarios/cuentas?email=`, `GET /api/usuarios/{usuarioId}` | `servicio` | 200; 404 si no hay una cuenta con ese email (verificada) o con ese id |
| `GET /api/usuarios/yo` | `user`, `organizer`, `staff` | 200 |
| `GET /api/usuarios/jwks` | `@PermitAll` | 200 |
| `GET /api/eventos`, `GET /api/eventos/{slug}` | `@PermitAll` | 200 |
| `POST /api/eventos` | `organizer` | 201 con `Location` |
| `GET /api/eventos/mios` (los eventos propios) | `organizer` | 200 |
| `PUT /api/eventos/{eventoId}` (editar un evento propio, con `If-Match` opcional) | `organizer` | 200 con el `ETag` nuevo; 412 `evento-modificado` si el `If-Match` no coincide |
| `POST /api/eventos/{eventoId}/cancelacion` | `organizer` | 200 con el evento cancelado; repetido, 409 `evento-cancelado` |
| `POST /api/eventos/{eventoId}/sectores/{sectorId}/cupo` | `servicio` | 200 |
| `POST /api/v1/ventas` (abre la compra y reserva cupo) | `user` | 201 con `Location` |
| `GET /api/v1/ventas` (las compras propias), `GET /api/v1/ventas/{ventaId}` | `user` | 200 |
| `POST /api/v1/ventas/{ventaId}/pagos` | `user` | 201 con `Location` al pago |
| `DELETE /api/v1/ventas/{ventaId}` | `user` | 204 (repetido, también 204) |
| `GET /api/v1/entradas/{entradaId}` | `user` | 200 |
| `POST /api/v1/pagos`, `GET /api/v1/pagos/{pagoId}`, `GET /api/v1/pagos?claveIdempotencia=` (la usa la conciliación) | `servicio` | 201 con `Location` / 200 / 200 o 404 `no-encontrado` |
| `POST /api/v1/entradas/usos` (uso de la entrada en la puerta; la llama Validación) | `servicio` | 200 con la entrada `USADA` |
| `GET /api/v1/operaciones-de-reventa/entradas/{entradaId}?vendedorId=`, `POST /api/v1/operaciones-de-reventa/publicaciones`, `/retiros`, `/controles-de-compra` (desde el 20/11), `/transferencias` (las llama Reventa) | `servicio` | 200 / 200 / 200 / 204 / 201 con `Location` a la venta nueva |
| `GET /api/v1/reventas?eventoId=` (publicaciones activas del evento) | `@PermitAll` | 200 |
| `GET /api/v1/reventas/mias`, `POST /api/v1/reventas`, `DELETE /api/v1/reventas/{publicacionId}` | `user` | 200 / 201 con `Location` / 204 |
| `POST /api/v1/reventas/{publicacionId}/compra`, con `Idempotency-Key` | `user` | 201 con `Location` a la entrada nueva |
| `GET /api/v1/facturas/{ventaId}` | `user` (solo la propia) y `servicio` | 200; 409 `comprobante-rechazado` si ARCA rechazó el comprobante (desde el 20/11) |
| `POST /api/v1/validacion/escaneos` | `staff` | 201 con `Location`, sea cual sea el resultado del escaneo |
| `GET /api/v1/validacion/escaneos?eventoId=`, `GET /api/v1/validacion/escaneos/{escaneoId}` | `staff` asignado, `organizer` dueño | 200; 403 `puerta-no-asignada` o 404 si el evento no le corresponde |
| `GET /api/v1/resumenes?eventoId=` (ventas por día y liquidación) | `organizer` | 200; 404 si el evento no es suyo |
| `POST /api/v1/asistente/preguntas` | `user`, `organizer`, `staff` | 200 con la respuesta y su origen, también si responde el fallback o un guardrail la bloquea |
| `GET /api/notificaciones/auditoria` | `servicio` | 200 |
| `POST /api/notificaciones/pruebas/...` (tres operaciones de prueba de mensajería) | `servicio` | Para la evidencia; no son parte del negocio. Se mantienen (decisión del 16/10) porque los usan la demo, la evidencia y la colección de Postman; son solo de desarrollo (riesgo aceptado, sección 21) |

Fuentes: los `*Resource.java` de cada servicio en `backend/servicios/<servicio>/src/main/java/com/palco/<servicio>/api/`.

## 9.3 Convenciones

- **Errores como Problem Details (RFC 9457).** `ManejadoresDeErrores` (en `backend/common/.../rest/`) traduce cada excepción de `ErroresDeNegocio` a un cuerpo `Problema` con `Content-Type: application/problem+json` (`type`, `title`, `status`, `detail`, `instance` y, si corresponde, los errores por campo). El `type` es una ruta relativa `/problemas/<tipo>`. Nunca se devuelve una traza. El único 401 que no pasa por JAX-RS, el del token que el servidor no puede verificar, lo escribe `ErrorDeAutenticacionServlet` con el mismo formato (página de error registrada por el `web-fragment.xml` de `common`).
- **Códigos que usa la API:**

| Código | Cuándo | Ejemplo |
|---|---|---|
| 400 `datos-invalidos` | Validación de Bean Validation o datos mal formados | Falta la cabecera `Idempotency-Key` |
| 401 `no-autenticado` | Sin token o credenciales incorrectas; lleva `WWW-Authenticate: Bearer`. El login responde el mismo 401 con el mismo mensaje exista o no el email | `POST /api/v1/ventas` sin token |
| 401 `token-invalido` | Token con la firma alterada o mal formado: lo rechaza el mecanismo MP-JWT del servidor antes de llegar al recurso. Lleva `WWW-Authenticate: Bearer error="invalid_token"` y las cabeceras CORS | `GET /api/v1/ventas` con un token alterado |
| 402 | Pago rechazado por la pasarela | `tok_rechazado` en `PagosFake` |
| 403 | Rol insuficiente | Token de organizador en `GET /api/v1/ventas` |
| 404 | No existe o no es del que pregunta | La venta de otro comprador responde 404, no 403, para no revelar que existe |
| 409 | Conflicto de estado | Cancelar una venta paga; misma clave con otro pedido (`clave-reutilizada`); en el uso interno de una entrada, `entrada-ya-usada`, `entrada-de-otro-evento` o `entrada-no-habilitada` |
| 412 `evento-modificado` | Vino `If-Match` y el recurso ya no está como lo leyó el cliente: no se guarda nada (`ErroresDeNegocio.PrecondicionFallida`) | `PUT /api/eventos/{eventoId}` con el `ETag` de antes de una venta |
| 422 | Regla de negocio incumplida | DNI repetido en la misma compra |
| 429 `demasiados-intentos` | Cinco logins fallidos en 15 minutos para un mismo email: el siguiente se rechaza aunque la clave sea correcta, también si el email no tiene cuenta. Lleva `Retry-After` con los segundos que faltan | Sexto login seguido con la clave mal |
| 504 | El servicio llamado no respondió | Pagos sin respuesta (`sin-respuesta`); Ventas sin respuesta durante un escaneo en la puerta |
| 500 `error-inesperado` | Cualquier otra excepción: se responde un mensaje genérico con el miembro de extensión `idError`, un UUID que queda escrito en el log junto con la traza para encontrarla cuando alguien reporta el error. Los demás errores no llevan `idError` | La falla provocada después del cobro (escenario 14) |

- **`Idempotency-Key`.** Obligatoria en `POST /api/v1/ventas`, `POST /api/v1/ventas/{id}/pagos`, `POST /api/v1/pagos` y `POST /api/v1/reventas/{publicacionId}/compra` (en la reventa, la derivación hacia Pagos es `<idPublicación>:<clave>`, sección 5.11). La clave se guarda en la venta (única por comprador) y en el pago; repetir con la misma clave devuelve la misma venta o el mismo pago, en su estado actual, sin reservar ni cobrar otra vez. La misma clave con un pedido distinto da 409. Ventas no le pasa a Pagos la clave del comprador tal cual, sino esa misma con el id de la venta adelante (`ClienteDePagos.claveParaPagos`): en Ventas la clave es única por comprador y en Pagos en todo el servicio, así que dos compradores con la misma clave no chocan.
- **`Location`.** Las creaciones responden 201 con la cabecera `Location` del recurso creado (venta, pago, evento).
- **Enlaces en las respuestas (`_links`).** Las respuestas de Ventas llevan `_links` (clase `Enlaces`): `self` siempre, y `pagar` (`POST`) y `cancelar` (`DELETE`) solo mientras la venta está `EN_CHECKOUT`; cada entrada lleva su `self`. Los `href` son rutas sin host. Los demás servicios no llevan enlaces.
- **Seguridad declarativa.** Cada operación declara `@RolesAllowed` o `@PermitAll`. JWT firmado por Usuarios, verificado en cada servicio con la clave del JWKS. CORS en `common/rest/CorsFilter`, que admite las cabeceras `Authorization`, `Idempotency-Key`, `If-None-Match` e `If-Match`, y expone `Location`, `ETag` y `Retry-After`.
- **`ETag` e `If-Match`.** `FiltroDeEtag` pone `ETag` (un hash SHA-256 recortado del cuerpo JSON) en los 10 GET marcados con `@ConEtag` y responde 304 sin cuerpo si `If-None-Match` coincide. `PUT /api/eventos/{eventoId}` acepta `If-Match` con ese valor (4.2).
- **Dinero** en pesos enteros (`long`); fechas en UTC con `Z`.

## 9.4 Cambios de seguridad del 16/10

Los tres cambios que figuraban en curso están en el código desde el 16/10 y se comprobaron el 16/10 con el servidor armado desde cero.

| Cambio | Dónde | Estado |
|---|---|---|
| Límite de intentos de login (SEC-W04): con 5 fallidos en 15 minutos para un email, 429 Problem Details con `Retry-After` | `ServicioDeUsuarios`, el repositorio `IntentoLoginRepository` (antes la clase `IntentosDeLogin`, que pasó a la capa de datos) y la tabla `intento_login` (sección 4.1) | Implementado y probado: seis logins fallidos de un mismo email dieron cinco 401 y, el sexto, 429 con `Retry-After: 898` |
| 401 por token inválido como Problem Details, con `WWW-Authenticate` y CORS. Antes salía en `text/html`, sin CORS ni `WWW-Authenticate`; el 401 sin token y el 403 por rol ya salían bien desde la aplicación | `ErrorDeAutenticacionServlet` y `web-fragment.xml` en `common` | Implementado y probado: `GET /api/v1/ventas` con un token inválido y `Origin: http://localhost:5173` respondió 401, `application/problem+json`, `type` `/problemas/token-invalido`, `WWW-Authenticate: Bearer error="invalid_token"` y `Access-Control-Allow-Origin: http://localhost:5173` |
| Id de error en el 500: generarlo, loguearlo con la traza y devolverlo como `idError` | `ManejadoresDeErrores.Inesperado` | Implementado, probado con tests de `common` (`ErrorInesperadoTest`) y en vivo con la falla provocada del escenario 14, que recibe el 500 con su `idError` (14.5) |

Vigencia de la sesión (SEC-W05): **parcial, vigencia justificada** (decisión del grupo del 16/10). Se mantiene en 8 horas, sin refresh token: una sesión de compra o de un organizador dura una jornada; el token se firma con RS256 y cada servicio lo valida con la clave pública; la clave se regenera al redesplegar Usuarios si no se define `PALCO_JWT_CLAVE_PRIVADA`; el token entre servicios dura 5 minutos. El riesgo de un token robado se acepta para el alcance del trabajo.

## 9.5 Contrato OpenAPI

`docs/api/openapi.yaml` (OpenAPI 3.1.0, versión 1.3.0 del documento) describe las rutas de Usuarios (registro, verificación, reenvío, recuperar la contraseña, login, perfil, token de servicio, cuentas, un usuario por id, JWKS), Eventos (catálogo, eventos propios, detalle, alta y edición, fotos, dueño, cancelación, cupo), Ventas (`/v1/ventas` con `POST` y `GET`, `/v1/ventas/{ventaId}` con `GET` y `DELETE`, `/v1/ventas/{ventaId}/pagos`, `/v1/entradas/{entradaId}`, `/v1/entradas/usos`, `/v1/resumenes` y las cinco de `/v1/operaciones-de-reventa`, con `controles-de-compra` desde el 20/11), Pagos (`/v1/pagos`, `/v1/pagos/{pagoId}` y su `/reembolso`), Reventa (`/v1/reventas`, `/v1/reventas/mias`, `/v1/reventas/{publicacionId}` y su `/compra`), Validación (escaneos y puertas), Facturación (`/v1/facturas/{ventaId}`) y el asistente (`/v1/asistente/preguntas`). En la Entrega Parcial N.º 2 el contrato estaba desactualizado (cuatro operaciones); se puso al día después (commit "OpenAPI y colección de Postman al día"). Notificaciones no figura en el contrato. El 20/11 se sumaron `GET /usuarios/cuentas` (rol `servicio`, la usa Validación al asignar una puerta) y `GET /eventos/imagenes/{id}` (pública, sirve las fotos guardadas en la base de Palco).

Desde el 20/11, el workflow `.github/workflows/contrato.yml` prueba el sistema levantado contra este contrato con Schemathesis 4.32.7: arma pedidos válidos e inválidos para cada operación a partir de sus esquemas y ejemplos, y revisa que ninguna respuesta sea un 5xx, que el código esté documentado en esa operación y que el cuerpo cumpla el esquema documentado (16.8). El contrato SOAP lo prueba `ContratoDelWsdlTest`, en el ARCA simulado (4.9).

## 9.6 Colección de Postman

`docs/api/palco-api.postman_collection.json` la genera `scripts/postman/generar.js` (no se edita a mano) y se corre entera, en orden y sin intervención (`docs/api/GUIA_POSTMAN.md`). Al 25/11 tiene 219 requests: 214 en la corrida y 5 en los pasos manuales.

| Carpeta | Requests | Qué prueba |
|---|---|---|
| 1 Usuarios | 18 en la corrida | Registro e inválido, login correcto (comprador y staff), incorrecto y sin verificar, otro código antes de los 30 s (409), verificar una cuenta ya verificada (409), perfil con y sin token, token de servicio con secreto incorrecto y con el bueno; con rol `servicio`, un usuario por su id (200, y 403 con rol `user`) y el rol de una cuenta por su email (`GET /usuarios/cuentas`: 200, y 404 para una sin verificar); JWKS (200) y con el método equivocado (405, `about:blank`) |
| 2 Eventos | 18 | Catálogo, filtro, una categoría que no existe (400), detalle, 404, alta sin token, con `organizer` e inválida, eventos propios con y sin token, el dueño de un evento (200 con rol `servicio`, 403 con el de organizador), reserva y liberación de cupo con rol `servicio` y 403 con otro rol |
| 3 Diseño v1 (Ventas) | 32 | Compra con reserva de cupo, idempotencia (incluida la clave reutilizada, 409), pago rechazado (402) y aprobado, emisión asincrónica, compras propias, aislamiento entre compradores, control de rol y cancelación |
| 4 Seguridad (clase 11) | 15 | Los criterios de la matriz de seguridad de la clase 11 que se prueban por HTTP, cada uno con su id: recurso ajeno pedido por otro comprador (404), rol equivocado (403), sin token (401), mismo 401 para email inexistente y clave mal, token alterado y sin firma (401 Problem Details), vigencia del token, 400 sin información interna, perfil sin contraseña y límite de intentos de login (429). Usa las cuentas de prueba `titular@palco.test` y `otro.titular@palco.test` y toma una compra emitida que deja la carpeta 3; el request del límite de intentos usa un email nuevo en cada corrida para no bloquear las cuentas de prueba |
| 5 Validación en puerta | 27 | La asignación de la cuenta de puerta al evento (200) y la de una cuenta de comprador (422 `no-es-de-puerta`); el equipo de puerta (200, y 404 el de un evento ajeno) y las puertas propias (200 con `staff`, 403 con `organizer`); escaneo sin token (401), con rol `user` (403) y sin evento (400); código inexistente, de otro evento, válido y repetido (201 con `INEXISTENTE`, `OTRO_EVENTO`, `VALIDA` y `YA_USADA`); la entrada `USADA`; el registro de la puerta, con el email de quien escaneó, el de un evento ajeno (404) y sin `eventoId` (400), un renglón y uno que no existe (404); el uso interno de una entrada (200 `USADA`, y 409 `entrada-ya-usada` al repetirlo); quitar la cuenta (403 con `staff`, 204 con el organizador) y el escaneo sin la puerta (403 `puerta-no-asignada`) |
| 6 Reventa | 46 | Publicar sin token, de una entrada que no existe, sobre el tope, bajo el mínimo y dos veces la misma; listado público y propio, la lista sin `eventoId` (400) y `/mias` sin token (401); compra propia (409), sin `Idempotency-Key` (400), con el cobro rechazado (402), correcta, repetida con la misma clave y ya vendida; la entrada original `VENDIDA` y la nueva `VALIDA`; retiro ajeno (404), correcto (204) y repetido (409); con rol `servicio`, las operaciones internas de Ventas: consultar (200, y 404 con otro vendedor), publicar (200, y 409 repetida), retirar (200, y 409 para la vendida), controlar una compra (204; 409 `dni-con-entrada` con un DNI que ya tiene entrada del evento; 403 con el token de un comprador; desde el 24/11, DEF-66) y repetir la transferencia (201, la misma venta; 403 con rol `user`) |
| 7 Facturación | 11 | La factura de la compra de la carpeta 3, consultada dos veces (la misma), sin token (401), por otro comprador (404), inexistente (404), con `staff` (403) y con rol `servicio` (200) |
| 8 Operaciones del 30/10 | 42 | Editar el evento propio, el ajeno (404) y con rol `user` (403), y con `If-Match` y el `ETag` de antes de una compra (412 `evento-modificado`); ventas por día y liquidación; cancelar el evento (ajeno 404, con rol `user` 403, propio 200 y otra vez 409), la venta anulada y su pago `REEMBOLSADO`; con rol `servicio`, lo que Pagos ofrece a los demás servicios: el pago pedido con el token del comprador (403), buscado por su clave (200, y 404 sin pago), el cobro repetido (201, el mismo pago; 409 `clave-reutilizada` con otro monto) y el reembolso repetido (200; 409 `pago-no-reembolsable` por otro monto); la subida de una foto, su URL sin token (200) y una que no existe (404), un texto declarado como PNG (400) y un SVG (415); la contraseña olvidada, también sin email (400), y el reinicio con un código incorrecto (400) |
| 9 Asistente | 5 | El `health`, una pregunta con sesión (200, `MODELO`), la misma sin token (401) y una inyección de prompt (200, `BLOQUEADA`, motivo `inyeccion`) |
| Fuera de la corrida | 5 | Pasos manuales: reenviar el código y verificar el email, y cambiar la contraseña de la cuenta verificada (pedir el código, reiniciarla e iniciar sesión con la nueva). Necesitan códigos que salen en el log del servidor |

La guía, con el detalle de cada carpeta y el orden en que se corren, es `docs/api/GUIA_POSTMAN.md`. El secreto para el token de servicio sale de la variable de la colección `secretoServicios`, que viene vacía: se le pasa el `PALCO_SECRETO_SERVICIOS` con el que arrancó el servidor, que `dev.sh` deja en `backend/servidor/target/secreto-servicios` (en Newman, con `--env-var secretoServicios=...`). En el CI la corre el job `api`, contra el compose (16.7).

Última corrida, según `docs/api/GUIA_POSTMAN.md`: 24/11/2026, con Newman 6.2.3 contra el sistema levantado con `docker compose`, las nueve carpetas, 219 requests (los 214 de la corrida y los cinco intentos de login previos de SEC-W04) y 682 aserciones, sin fallas. La del 19/11/2026, contra WildFly, dio 171 requests (los 166 de la colección de entonces y los cinco intentos de login) y 541 aserciones, sin fallas (`docs/testing/evidencia/checkpoint-3/postman.txt`). La del 16/10/2026, con la colección completa de entonces contra el servidor armado desde cero a las 11:04: Newman cuenta 126 requests (los 121 de la corrida más los cinco intentos de login que SEC-W04 manda antes de enviarse) y 406 aserciones, con 0 fallas, incluidas las carpetas 5, 6 y 7. Antes, ese mismo día, con las carpetas 1 a 4, la corrida había dado 74 requests y 252 aserciones con 1 falla: SEC-W05c, "el token dura 15 minutos o menos", que quedaba en rojo a propósito hasta decidir la vigencia; con la decisión del 16/10 (sección 9.4) pasó a comprobar que el token dura 8 horas y la corrida siguiente dio 0 fallas. La del 14/10 dio 54 requests y 206 aserciones sin fallas con las carpetas 1 a 3.

# 10. Mensajería

## 10.1 El proceso

Cuando se aprueba el pago de una compra, `ServicioDeVentas.confirmarCompra` pasa la venta a `CONFIRMADA`, crea las entradas en `PENDIENTE_EMISION` y publica `PAGO_CONFIRMADO` **en la misma transacción**. El mismo texto va a dos destinos:

```
ServicioDeVentas ──► cola.emision-entradas ──► EmisionDeEntradasMDB (Ventas, consumidor único)
                 └─► topico.compra-confirmada ─┬► AvisoDeCompraMDB (Notificaciones, no durable)
                                               ├► AuditoriaDeComprasMDB (Notificaciones, durable)
                                               └► FacturacionDeComprasMDB (Facturación, durable)
```

**Segundo tipo de evento en el tópico: `REVENTA_CONFIRMADA`** (desde el 23/10; antes la compra en la reventa no generaba aviso, auditoría ni factura). Cuando se compra una entrada en la reventa, `ReventaDeEntradas.transferir` (Ventas) crea la venta nueva del comprador, emite su entrada y publica `REVENTA_CONFIRMADA` en la misma transacción. Lleva el mismo cuerpo que `PAGO_CONFIRMADO` y va **solo al tópico**: la entrada ya quedó emitida en la transferencia, así que la cola de emisión no tiene nada que hacer. Los tres suscriptores del tópico la reciben porque su selector es `tipoEvento IN ('PAGO_CONFIRMADO', 'REVENTA_CONFIRMADA')`; la emisión sigue filtrando solo `PAGO_CONFIRMADO`. Es el uso de `tipoEvento` que previó el taller de mensajería ("filtrar si en el futuro un mismo tópico agrupa más de un tipo de evento"), y la decidió el grupo el 23/10:

```
ReventaDeEntradas (Ventas) ──► topico.compra-confirmada   tipoEvento = REVENTA_CONFIRMADA
                                 ├► AvisoDeCompraMDB         "tu compra en la reventa oficial ... quedó confirmada"
                                 ├► AuditoriaDeComprasMDB    fila con tipo_evento = REVENTA_CONFIRMADA
                                 └► FacturacionDeComprasMDB  factura de la venta nueva (precio de reventa + cargo)
```

Probado en el servidor el 23/10 con el escenario 17: la compra en la reventa dejó la fila de auditoría como `REVENTA_CONFIRMADA`, el aviso "en la reventa oficial" y la factura por el precio de reventa más el cargo de servicio, y no pasó por la cola de emisión. Le da al tópico un segundo hecho de negocio, distinto de la compra del catálogo (O2.1 y T.7).

El comprador recibe la respuesta del pago (201, con las entradas todavía sin emitir) sin esperar a ningún consumidor. Estado: **Implementado** la cola y los dos suscriptores de Notificaciones; el aviso sale por SMTP si se configura `PALCO_SMTP_HOST` y, si no, es **simulado** (una línea en el log; ni el compose ni `dev.sh` configuran un SMTP). El tercer suscriptor, el de Facturación, está **implementado** (escenario 15, 16/10): se sumó sin tocar Ventas ni el contrato del mensaje. Desde entonces el tópico suma 3 copias por compra (aviso, auditoría y facturación), como comprueba el escenario 7 y como mostró la demo de la compra del 16/10.

## 10.2 Contrato de `PAGO_CONFIRMADO`

Documento normativo: `docs/api/mensajeria-pago-confirmado.md`. Clases en `backend/common`, paquete `com.palco.common.mensajeria`: `PagoConfirmadoMensaje` (record, con `aJson`, `desdeJson` y `validar`), `MensajeInvalido`, `DestinosJms` (nombres JNDI, propiedad y selector), `PublicadorPagoConfirmado` y su implementación `PublicadorPagoConfirmadoJms`.

- `TextMessage` con cuerpo JSON (JSON-B, campos en orden alfabético), entrega persistente.
- Propiedad `tipoEvento` = `PAGO_CONFIRMADO` (compra del catálogo, cola y tópico) o `REVENTA_CONFIRMADA` (compra en la reventa, solo tópico, desde el 23/10). El cuerpo es el mismo en los dos.
- Cuerpo: `ventaId`, `compradorId`, `eventoId`, `entradas[]` (1 a 6, cada una con `entradaId`, `sector` por nombre y `titular` con su `nombre`), `montoTotal` en pesos enteros y `timestamp` en UTC truncado a segundos.
- Se rechaza (`MensajeInvalido`) un texto vacío o que no es JSON, ids vacíos o de más de 40 caracteres, 0 o más de 6 entradas, un titular sin nombre, monto no positivo o sin `timestamp`. El publicador valida antes de mandar.
- Compatibilidad: un campo desconocido se ignora, así que agregar campos no rompe a los consumidores.
- El test `PagoConfirmadoMensajeTest` lee el ejemplo del documento del contrato: si contrato y clase dejan de coincidir, el build falla.

**Datos personales** (desde el 24/11, DEF-37; criterio SEC-D05). El titular de `PAGO_CONFIRMADO` y de `REVENTA_CONFIRMADA` viaja solo con su nombre: ningún consumidor necesita el DNI. La emisión lee las entradas de su base con el `ventaId`, el aviso nombra a cada titular, la auditoría anota venta, comprador, evento y monto, y Facturación pide una factura B a consumidor final, sin documento. El DNI queda en Ventas, en la entrada. Un mensaje publicado antes, con el DNI, se procesa igual: el campo se ignora y ya no se valida, y la auditoría lo guarda sin el DNI (4.5). Contrato: `docs/api/mensajeria-pago-confirmado.md`, sección "Datos personales".

## 10.3 Broker, cola y tópico

Configuración versionada en `backend/servidor/src/main/wildfly/configurar.cli`, leída del servidor en marcha el 14/10 (`reproduccion.txt`, paso 0).

| Qué | Valor |
|---|---|
| Broker | ActiveMQ Artemis 2.54.0 embebido en WildFly 41.0.1 |
| Cola | `cola.emision-entradas`, JNDI `java:/jms/queue/emisionEntradas` |
| Tópico | `topico.compra-confirmada`, JNDI `java:/jms/topic/compraConfirmada` |
| Tópico de cancelaciones | `topico.eventos-cancelados`, JNDI `java:/jms/topic/eventosCancelados` (segundo proceso, sección 10.8) |
| Persistencia | Activada: los mensajes pendientes sobreviven a un reinicio (con `dev.sh`, la base H2 en memoria no). En el compose, el journal y el registro de transacciones están en el volumen `datos-servidor`, que dura lo mismo que la base: sobreviven también a recrear el contenedor (ADR-005) |
| Reintentos | `address-setting=#`: `max-delivery-attempts` 5 y `redelivery-delay` 2000 ms |
| DLQ | La que trae el servidor, `java:/jms/queue/DLQ` |
| Fábrica para publicar | `java:/JmsXA`, transaccional (XA) |

**Por qué cola y por qué tópico.** La emisión la hace un único responsable, exactamente una vez: cola punto a punto. Aviso y auditoría son interesados independientes en el mismo hecho: tópico; sumar un tercero no obliga a tocar Ventas. Así se sumó Facturación, como tercer suscriptor durable (sección 11).

**Permisos por destino (SEC-I02): riesgo aceptado** (decisión del grupo del 16/10, DEF-39). El broker no restringe qué servicio publica o consume en cada destino. Se acepta porque el broker está embebido en el mismo WildFly, los servicios se conectan por in-vm y la interfaz pública del servidor queda en 127.0.0.1 por defecto: ningún sistema externo se conecta al broker. Se revisa si el sistema se despliega fuera de una sola máquina.

## 10.4 Los cuatro consumidores

Son los de la compra. Los del segundo proceso, la cancelación de un evento, están en la sección 10.8.

| Consumidor | Servicio | Destino y suscripción | Selector | Qué hace | Idempotencia |
|---|---|---|---|---|---|
| `EmisionDeEntradasMDB` | Ventas | Cola, consumidor único (el broker informa `consumer-count` 15: son sesiones del mismo MDB, y cada mensaje va a una sola; `broker-despues.txt`) | `tipoEvento = 'PAGO_CONFIRMADO'` | Pone el código QR a cada entrada (`PENDIENTE_EMISION` → `VALIDA`) y pasa la venta a `EMITIDA`, en una transacción `REQUIRED` | Cada cambio es un `UPDATE` que exige que la entrada siga `PENDIENTE_EMISION`; un repetido no cambia ninguna fila y se loguea "es un mensaje repetido y no cambió nada". Si la venta no existe, el mensaje se descarta con un `WARNING` |
| `AuditoriaDeComprasMDB` | Notificaciones | Tópico, **durable**: `clientId` `palco-auditoria`, `subscriptionName` `compras-confirmadas`, `maxSession` 1 | `tipoEvento IN ('PAGO_CONFIRMADO', 'REVENTA_CONFIRMADA')` (desde el 23/10) | Guarda una fila en `registro_auditoria` | La clave de la fila es el `JMSMessageID`: una reentrega no inserta dos veces. Ante un mensaje inválido marca `setRollbackOnly()` (reintentos y DLQ) |
| `AvisoDeCompraMDB` | Notificaciones | Tópico, **no durable** | Los dos tipos, como auditoría | Arma el aviso al comprador y lo manda con `Correos`: sin SMTP, lo escribe en el log (**simulado**) | No guarda estado: un repetido produce otra línea de log, sin efecto de negocio. Un mensaje inválido se descarta con un `WARNING` |
| `FacturacionDeComprasMDB` | Facturación | Tópico, **durable**: `clientId` `palco-facturacion`, `subscriptionName` `facturacion-compras-confirmadas`, `maxSession` 1 | Los dos tipos, como auditoría | Le pide el CAE al ARCA simulado por SOAP y guarda la factura en `facturacion.factura` (sección 11) | Antes de llamar a ARCA busca la factura de la venta y, desde el 20/11, un rechazo anotado; la restricción única `factura_venta_unica` decide entre dos entregas simultáneas. Ante un mensaje inválido o ARCA sin respuesta, marca `setRollbackOnly()` (reintentos y DLQ). Un comprobante rechazado queda anotado en `facturacion.comprobante_rechazado` y el mensaje se confirma, sin reintentos (DEF-33) |

**Por qué durables dos y no la tercera.** La auditoría y la factura no pueden perder un hecho: si Notificaciones o Facturación están detenidos, el broker les guarda los mensajes. Perder un aviso se acepta: sin SMTP es una línea de log y, con SMTP, un aviso viejo no le sirve a nadie.

**Por qué el selector.** El tópico lleva dos tipos de mensaje, `PAGO_CONFIRMADO` y `REVENTA_CONFIRMADA` (desde el 23/10), y cada consumidor recibe solo los que entiende. El aviso, la auditoría y Facturación usan `DestinosJms.SELECTOR_COMPRA_CONFIRMADA`, con los dos tipos; la emisión, `SELECTOR_PAGO_CONFIRMADO`, porque una compra en la reventa ya emite su entrada en la transferencia.

**Efecto del tercer suscriptor sobre la evidencia.** Con Facturación desplegado, cada mensaje del tópico tiene tres copias y no dos, y un mensaje inválido deja dos copias en la DLQ (auditoría y facturación). Los mensajes de prueba de `/api/notificaciones/pruebas/...` también se facturan, porque Facturación solo mira el mensaje. El guion de prueba ya cuenta las dos copias de la DLQ (escenario 12) y las tres del tópico (escenario 7); los dos pasaron el 16/10 con el servidor armado desde cero.

## 10.5 Garantía transaccional

- `confirmarCompra` lleva `@TransactionAttribute(REQUIRED)` y el publicador `MANDATORY`: no puede publicar fuera de la transacción de quien lo llama.
- El broker participa como recurso XA; el datasource `PalcoDS` no es XA y entra como último recurso. **No es un commit en dos fases completo.**
- Si la transacción se deshace, el mensaje no sale (probado, escenario 13).
- Si el servidor se cayera entre el commit de la base y el del broker, quedaría una venta `CONFIRMADA` con entradas `PENDIENTE_EMISION` y sin mensaje. **Es el riesgo que se acepta con el último recurso** (7.12 y sección 21): la conciliación de pagos (5.10 y 7.8) solo toma ventas `EN_CHECKOUT` con un intento de pago sin resultado, así que no ve una venta ya `CONFIRMADA`. Lo que sí cubre es la falla a mitad de la compra antes del commit, que se repite con la propiedad `palco.ventas.fallar-despues-del-cobro` (escenario 14). Cerrar esta ventana pediría una base XA o una tabla de salida.
- La transacción cubre solo la confirmación. La reserva de cupo es REST y se compensa devolviéndolo; el cobro se protege con la clave de idempotencia. `ServicioDePagos` lleva `NOT_SUPPORTED`: no retiene una transacción mientras espera a la pasarela.

## 10.6 Reintentos, DLQ y procedimiento

Si un consumidor falla, su transacción se deshace entera (no quedan entradas emitidas a medias) y el broker reentrega a los 2 segundos; después de 5 entregas, el mensaje pasa a la DLQ. En la cola de emisión eso deja la venta `CONFIRMADA` con entradas sin emitir y nada lo reintenta solo. Procedimiento:

1. **Encontrar el mensaje** en la DLQ filtrando por la propiedad `_AMQ_ORIG_QUEUE`, que guarda el nombre interno de la cola de donde salió: `jms.queue.cola.emision-entradas` para la emisión y `palco-facturacion.facturacion-compras-confirmadas` para Facturación (`count-messages` y `list-messages` de `jms-queue=DLQ` con ese filtro).
2. **Ver la causa** en `server.log` (la línea `AMQ222149` con el id del mensaje y las 5 fallas anteriores) y corregirla; si persiste, devolverlo solo lo manda otra vez a la DLQ.
3. **Devolverlo** con `move-messages` y el mismo filtro. A la cola de emisión, desde `jms-queue=DLQ` con `other-queue-name=cola.emision-entradas` (en ese recurso el destino es el nombre JMS y WildFly le agrega `jms.queue.`). A una suscripción durable (Facturación o auditoría), desde `runtime-queue=jms.queue.DLQ` con el nombre interno de la suscripción:

   ```
   /subsystem=messaging-activemq/server=default/runtime-queue=jms.queue.DLQ:move-messages(filter="_AMQ_ORIG_QUEUE='palco-facturacion.facturacion-compras-confirmadas'",other-queue-name=palco-facturacion.facturacion-compras-confirmadas)
   ```

   Es seguro porque la emisión y la factura son idempotentes, y el mensaje vuelve solo a esa cola: los demás suscriptores del tópico no lo reciben otra vez. Con `jboss-cli.bat` el filtro va en un archivo (`--file`), porque `--command` pierde las comillas.
4. **Comprobar** que la venta pasó a `EMITIDA` y sus entradas a `VALIDA`, o que `GET /api/v1/facturas/{ventaId}` trae la factura con su CAE.

**Un comprobante que ARCA rechazó** (desde el 20/11, DEF-33) no pasa por la DLQ: el mensaje se confirmó y no queda nada para devolver. Se encuentra por la línea `SEVERE` de `ServicioDeFacturacion` en `server.log` ("Venta <id> sin factura.", con el motivo de ARCA) y por su fila en `facturacion.comprobante_rechazado`, que guarda lo que se le pidió a ARCA (comprador, evento, importe y fecha) y por qué no lo autorizó. Lo resuelve una persona: devolver el mensaje no cambia nada, porque con el rechazo anotado Facturación no le vuelve a pedir el CAE a ARCA. Mientras tanto, `GET /api/v1/facturas/{ventaId}` responde 409 `comprobante-rechazado` al comprador y la pantalla de la factura le dice que ARCA la rechazó y que sus entradas valen igual.

Estado del procedimiento: la lectura filtrada se probó el 14/10 y `move-messages` **sin** filtro se usa en el escenario 12. **Con** filtro, hacia la suscripción de Facturación, se probó el 19/11 con el ARCA sin responder (`docs/testing/evidencia/checkpoint-3/arca-lento-dlq.txt`, 29 de 29).

## 10.7 Evidencia: escenarios 7 a 13

Salida en `docs/entrega3/evidencia/integracion-13-escenarios.txt` (14/10/2026, servidor armado desde el tag `entrega-parcial-2`, 177 comprobaciones, todas bien). Se repiten con `node scripts/pruebas/escenarios.mjs [n]`, que hoy tiene 24 escenarios (16.2): los de esta tabla son los de mensajería; el 14 (conciliación), el 15 (facturación), el 16 (validación en puerta), el 17 (reventa) y el 22 (cancelación de un evento) están en las secciones 7.8, 11.9, 7.9, 5.11 y 10.8.

Última corrida: 16/10/2026, con el servidor armado desde cero a las 11:04: pasan los 17. Del 1 al 13 pasaron en la primera corrida. El 12, el 14, el 15 y el 16 se repitieron después de corregir el guion de prueba, no el sistema: el token de servicio del guion vencía durante la espera del 14 (ahora se pide uno nuevo antes de cada escenario); la DLQ recibe 2 copias de un mensaje inválido, una por suscriptor durable (auditoría y facturación), y el guion esperaba 1; y el registro de la puerta se busca por id de escaneo. Esa salida no se guardó en el repositorio.

| N.º | Qué prueba | Resultado |
|---|---|---|
| 7 | Al confirmarse un pago sale un mensaje a la cola y uno al tópico, con el `tipoEvento` del contrato | Pasa |
| 8 | El consumidor de la cola emite una sola vez aunque el mensaje llegue dos veces (la copia de auditoría se mueve a la cola con `move-messages`) | Pasa: códigos, fechas de emisión y DLQ sin cambios |
| 9 | Los dos suscriptores del tópico reciben cada uno su copia: el aviso (log) y la auditoría (fila) | Pasa |
| 10 | Con el consumidor de emisión detenido (`stop-delivery`) el mensaje espera en la cola; al reanudarlo (`start-delivery`) se emite | Pasa |
| 11 | Suscripción durable: con auditoría detenida se publican mensajes y al reanudarla los recibe | Pasa (10/10) |
| 12 | Un mensaje inválido se reintenta 5 veces y termina en la DLQ sin trabar a los demás | Pasa (15/15 el 14/10). Con Facturación desplegada deja 2 copias en la DLQ; pasa el 16/10 |
| 13 | Si la transacción se deshace después de publicar, el mensaje no sale | Pasa (11/11) |

Evidencia complementaria en la misma carpeta: `suscriptor-durable.txt`, `mensajes-pendientes.txt` (64 mensajes esperando sin consumidor, procesados al desplegarlo), `venta-inexistente.txt` y `mensaje-repetido-y-reintentos.txt`. Este último se tomó con una versión modificada del servicio que no está en el repositorio, y así se declara.

## 10.8 Segundo proceso: la cancelación de un evento

Contrato: `docs/api/mensajeria-cancelacion-de-eventos.md`, al día con el código. Diagrama: `docs/documentacion/diagramas/secuencia-cancelacion-topico.svg`. Son dos mensajes en el mismo tópico, `topico.eventos-cancelados`, distinguidos por `tipoEvento`:

```
ServicioDeEventos ──► topico.eventos-cancelados ─┬► CancelacionDeEventoMDB (Ventas, durable)      EVENTO_CANCELADO
                                                 └► CancelacionDeEventoMDB (Reventa, durable)
CancelacionDeEventos (Ventas) ──► topico.eventos-cancelados ─┬► AvisoDeCancelacionMDB (Notificaciones, no durable)
                                                             └► AuditoriaDeCancelacionesMDB (Notificaciones, durable)
                                                                                                   COMPRA_ANULADA
```

1. **Eventos.** El organizador dueño cancela el evento (`POST /api/eventos/{eventoId}/cancelacion`). `ServicioDeEventos.cancelarEvento` lo marca cancelado y publica `EVENTO_CANCELADO` en la misma transacción. Cancelarlo de nuevo da 409 `evento-cancelado`, así que no sale un segundo mensaje.
2. **Ventas** (`ventas-cancelaciones`). `CancelacionDeEventoMDB` recibe el mensaje en una transacción `REQUIRED`, y `CancelacionDeEventos` anula una por una las compras pagadas del evento. **Cada compra se anula en su propia transacción:** `AnulacionDeCompras.anular` lleva `REQUIRES_NEW` y es un bean aparte, porque una llamada sobre `this` no pasa por el contenedor y el atributo se ignoraría: todas las compras quedarían en la transacción del MDB y un fallo en una desharía las anteriores. En cada una, un `UPDATE` condicional pasa la venta a `CANCELADA` (tiene que seguir `CONFIRMADA` o `EMITIDA`), sus entradas `PENDIENTE_EMISION`, `VALIDA` y `PUBLICADA` pasan a `ANULADA`, se pide el reembolso a Pagos y se publica el `COMPRA_ANULADA`. Las entradas `USADA` y `VENDIDA` no se tocan.
3. **El reembolso no frena la anulación.** Va antes de publicar, porque el aviso dice si el dinero volvió. Si Pagos no contesta, la compra se anula igual, el mensaje sale con `reembolsado` en `false` y la venta queda con la marca `reembolso_pendiente` (migración `V4__reembolso_pendiente.sql`), en la misma transacción que la anula. `ReintentoDeReembolsos` lo vuelve a pedir en el segundo 45 de cada minuto hasta que Pagos conteste: si lo devolvió, saca la marca; si contesta que ese pago no se puede devolver, saca la marca y deja un `SEVERE` para revisarlo a mano. Reintentar es seguro: Pagos devuelve un pago una sola vez.
4. **Reventa** (`reventa-cancelaciones`). Su `CancelacionDeEventoMDB` recibe el mismo `EVENTO_CANCELADO` y `RetiroPorCancelacion` pasa a `RETIRADA` las publicaciones `ACTIVA` del evento que nadie está comprando. Para sumarlo no hubo que tocar Eventos, que publica sin saber quién escucha.
5. **Notificaciones.** `AvisoDeCancelacionMDB` le manda el email al comprador, que dice si el dinero ya volvió o si está en trámite; `AuditoriaDeCancelacionesMDB` guarda una fila por compra anulada.

**Las compras que pasaron por la reventa** (desde el 20/11; corrige DEF-29 y DEF-31 de `docs/testing/registro-de-defectos.md`). Una entrada revendida deja dos compras pagadas del evento: la de quien la vendió, con esa entrada `VENDIDA`, y la que nació para quien la compró (`ReventaDeEntradas.transferir`). `AnulacionDeCompras` anula las dos, con tres diferencias:

- **El reembolso de quien compró en la reventa se pide con el id de la publicación.** Ese cobro lo hizo Reventa antes de que existiera la venta, y Pagos lo guardó con la publicación como `ventaId`: `ReventaDeEntradas.ventaEnPagos` la saca de la clave `reventa:<idPublicación>` de la venta. Pedido con el id de la venta, Pagos respondía 409 `pago-no-reembolsable` y el dinero no volvía. Lo pide Ventas y no Reventa, aunque el cobro lo haya hecho Reventa: el aviso sale de la transacción de la anulación y tiene que decir si el dinero volvió, y un reembolso sin respuesta lo retoma `ReintentoDeReembolsos` con la marca `reembolso_pendiente`, igual que el de cualquier compra, con la misma venta ante Pagos y el mismo monto.
- **La venta que nació en la reventa no devuelve cupo:** no reservó ninguno. El lugar es de la compra original, que lo devuelve entero (`VentaRepository.anularPorCancelacionDelEvento` no le marca cupo por devolver a esa venta). Antes se lo marcaba, y Eventos rechazaba la devolución de un lugar que ya devolvía la compra original.
- **A quien vendió se le devuelve solo lo que no revendió:** lo que pagó menos el precio de las entradas `VENDIDA` y su cargo de servicio (`AnulacionDeCompras.montoADevolver`, con `ReglasDeNegocio.cargoServicio`). Esa parte vuelve a quien las compró, en su propia venta. Si las revendió todas, la compra se anula sin reembolso y sin `COMPRA_ANULADA`: no queda nada que devolverle ni que avisarle.

Para eso Pagos acepta reembolsos parciales: `POST /api/v1/pagos/{pagoId}/reembolso` recibe un `monto` opcional (sin él devuelve el pago entero, que es lo que pide Reventa) y guarda lo devuelto en `monto_reembolsado` (migración `V3__reembolso_parcial.sql` de Pagos). Responde 409 `pago-no-reembolsable` si el monto pasa lo cobrado o si el pago ya se devolvió por otro monto; repetir el mismo pedido devuelve el mismo pago sin devolver dos veces. El campo `monto` del `COMPRA_ANULADA` es lo que se le devuelve al comprador (`docs/api/mensajeria-cancelacion-de-eventos.md`).

**Liquidación y ventas por día.** `ResumenDeVentas` (`GET /api/v1/resumenes?eventoId=`) no cuenta las compras en la reventa, ni en la liquidación ni en lo vendido por día: el lugar ya lo liquida la compra original, que sigue pagada aunque su entrada pase a `VENDIDA`. Contarla le pagaría dos veces el mismo lugar al organizador, con un precio que es del vendedor. La compra en la reventa se reconoce por el prefijo `reventa:` de su clave (`ReventaDeEntradas.esCompraEnLaReventa`).

**Idempotencia, reintentos y durabilidad.** Si el broker reentrega `EVENTO_CANCELADO`, el `UPDATE` de Ventas no cambia ninguna fila, no se pide otro reembolso ni sale otro `COMPRA_ANULADA`, y el de Reventa solo toca las publicaciones que siguen `ACTIVA`. La auditoría guarda la fila con el `JMSMessageID`. Ventas y la auditoría deshacen la transacción ante un mensaje inválido o un error, y el broker reintenta (5 entregas cada 2 s, después la DLQ); un reintento de Ventas solo retoma las compras que faltaban. Las suscripciones de Ventas, Reventa y la auditoría son durables.

Lo prueba el escenario 22 de `scripts/pruebas/escenarios.mjs` (anula las compras, devuelve los pagos, retira la reventa y avisa: 15 de 15 el 18/11, `docs/testing/evidencia/checkpoint-3/escenarios.txt`, y 24 de 24 el 20/11 con la corrección de DEF-29 y DEF-31, junto con los escenarios 17, 19 y 20, `docs/testing/evidencia/final/cancelacion-con-reventa.txt`) y la carpeta 8 de Postman. En los tests: `CancelacionDeEventosTest`, `ReintentoDeReembolsosTest` y `ReembolsoPendienteEnH2Test` en Ventas, y `ResumenDeVentasTest`, que comprueba que la compra en la reventa no se liquida ni se cuenta entre las vendidas. Los tests de Ventas, Reventa y Eventos pasaron el 19/11 con `mvn -f backend/pom.xml -pl servicios/ventas,servicios/reventa,servicios/eventos -am test`. La corrección del 20/11 para las compras que pasaron por la reventa tiene sus tests: `CancelacionConReventaEnH2Test` (4, contra H2 con las migraciones) y casos nuevos en `CancelacionDeEventosTest`, `ReintentoDeReembolsosTest` y `ServicioDePagosTest`; el 20/11 pasaron los 472 tests de Ventas (358), Reventa (74) y Pagos (40), sin fallas. Ese día el escenario 22 sumó una entrada comprada en la reventa: comprueba que la revendida queda `VENDIDA`, que a quien vendió se le devuelve lo de las entradas que todavía tenía, que el pago de quien compró en la reventa queda `REEMBOLSADO` entero, los dos avisos con lo devuelto y que el barrido de cupos deja las vendidas del sector en 0. Esa versión pasó contra el compose el 20/11 (`cancelacion-con-reventa.txt`) y en la corrida del CI del 21/11 sobre `fbd31dc`.

# 11. Servicios web SOAP: Facturación con un ARCA simulado

**Estado: Implementado**, contra un ARCA **simulado**. El diseño lo decidió el grupo el 16/10 y está en el código, a cargo de Santiago Loto: el ARCA simulado (`backend/servicios/arca`), el cliente SOAP y el suscriptor del tópico en Facturación (`backend/servicios/facturacion`) y la capa `webservices` en el servidor (`backend/servidor/pom.xml`). Lo cubren tests unitarios, tests con SOAP real (el servicio publicado con `Endpoint.publish`) y tests contra H2 en modo PostgreSQL. El 16/10, con el servidor armado desde cero, WildFly desplegó `palco-arca.war` y Facturación, `/legado/arca?wsdl` respondió 200, el escenario 15 facturó la compra una sola vez y la carpeta 7 de Postman pasó sin fallas (sección 11.9).

## 11.1 Qué pide la consigna y por qué ARCA

La consigna pide para la Entrega Obligatoria N.º 2 (13/11) al menos un servicio SOAP con su WSDL, justificado como integración con un sistema externo legado. En Palco ese papel lo cumple ARCA (ex AFIP), cuyo servicio de factura electrónica WSFEv1 solo se ofrece por SOAP. **El canal no se elige: lo impone el sistema legado.** Lo propio del sistema (Ventas, Pagos, Facturación hacia sus clientes, el frontend) es REST. El 16/10 la cátedra aceptó que las integraciones con ARCA sean simuladas por ahora.

## 11.2 Qué es real y qué simulado

| Pieza | Real o simulada |
|---|---|
| SOAP 1.1 sobre HTTP (documento literal envuelto), WSDL generado por WildFly (capa `webservices`, JBossWS con Apache CXF), cliente JAX-WS | Real |
| ARCA | **Simulado**: `ArcaSimulado`, una clase `@WebService` en un WAR aparte (`palco-arca`, `/legado/arca`), que representa el sistema externo |
| Número de comprobante y CAE | Los inventa el ARCA simulado (`Talonario`, en memoria); no tienen validez fiscal |
| Autenticación con ARCA (WSAA en el sistema real) | No existe: el simulado no la pide |
| Tópico, suscripción durable, reintentos del broker, DLQ y factura guardada | Reales |

## 11.3 El ARCA simulado y su WSDL

- **Dónde está.** WAR `palco-arca` (`backend/servicios/arca`), context-root `/legado/arca`. `web.xml` declara la clase como servlet mapeado a `/*`, así la dirección del servicio es la raíz del contexto. El WSDL lo genera WildFly a partir de las anotaciones en `/legado/arca?wsdl`: no hay un WSDL escrito a mano.
- **Contrato.** Espacio de nombres `urn:palco:legado:arca:wsfe` (propio, no el del WSFEv1 real); `portType` `ServicioFacturaElectronica`; servicio `ArcaSimulado`; puerto `ArcaSimuladoPort`.
- **Operación `FECAESolicitar`**, inspirada en la del WSFEv1 y simplificada a un comprobante por pedido. Recibe `CbteTipo`, `PtoVta`, `ImpTotal` (pesos enteros), `CbteFch` (`aaaammdd`) e `IdVenta`. Devuelve `FECAESolicitarResult` con `Resultado` (`A` o `R`), `CbteNro`, `CAE` (14 dígitos), `CAEFchVto` (la fecha más 10 días) y `Obs`.
- **Validaciones.** Tipo 1, 6 u 11; punto de venta entre 1 y 99998; importe mayor que 0; fecha `aaaammdd` válida; `IdVenta` presente y de hasta 40 caracteres. Si algo no vale, responde `R` con una observación y no numera.
- **Numeración.** `Talonario`, uno por despliegue, correlativo por punto de venta y tipo, con métodos `synchronized`. Si se le pide otra vez el CAE de la misma venta, devuelve el mismo comprobante: un reintento no saltea números. Es una simplificación declarada: el WSFEv1 real no autoriza dos veces y se consulta con `FECompConsultar`.
- **Modo sin respuesta.** Con la propiedad de sistema `palco.arca.simular-sin-respuesta=true` (se define y se saca con `jboss-cli` sin redesplegar, porque se lee en cada pedido), espera 30 s y responde un SOAP Fault, sin numerar. Sirve para mostrar los reintentos del broker y la DLQ.

El WSDL completo va en un anexo del documento técnico, tomado de `?wsdl` con el servidor armado desde cero: la cátedra respondió el 16/10 que las 8 a 12 páginas son de contenido, sin contar los anexos.

## 11.4 Cómo funciona

El diagrama de la sección 13.7 muestra este recorrido.

1. Ventas confirma la compra y publica `PAGO_CONFIRMADO` en el tópico, como antes. Ventas no cambió.
2. `FacturacionDeComprasMDB`, con suscripción durable a `topico.compra-confirmada` y el mismo selector que los demás consumidores, recibe su copia y la lee con `PagoConfirmadoMensaje.desdeJson`.
3. Delega en `ServicioDeFacturacion.emitirFactura`. El servicio es `NOT_SUPPORTED`: la transacción del MDB queda suspendida mientras tanto.
4. Busca la factura de la venta. Si ya existe, el mensaje es una repetición: la devuelve sin llamar a ARCA. Desde el 20/11 busca también un rechazo anotado de esa venta: si lo hay, tampoco llama a ARCA, porque sería mandarle el mismo pedido.
5. Si no existe, `ArcaSoap` llama a `FECAESolicitar` con factura B (6), punto de venta 1, el total del mensaje, la fecha del día en hora argentina (`America/Argentina/Buenos_Aires`) y el id de la venta. La llamada es síncrona: espera la respuesta.
6. El ARCA simulado numera y devuelve `A`, número, CAE y vencimiento.
7. Facturación guarda la factura en `facturacion.factura`, en la transacción corta del repositorio. Si choca con la restricción única (otra entrega de la misma venta ganó), devuelve la que quedó guardada.
8. Se confirma la transacción del MDB y la copia queda consumida.

La factura se consulta en `GET /api/v1/facturas/{ventaId}`: con rol `user`, solo la propia (el `sub` del token tiene que ser el comprador del mensaje); con rol `servicio`, cualquiera. Una factura ajena, una venta inexistente y una venta pagada que todavía no se facturó dan el mismo 404, para no revelar nada de ventas ajenas. Si ARCA rechazó el comprobante, desde el 20/11 el comprador de esa venta y otro servicio reciben 409 `comprobante-rechazado`, sin el motivo de ARCA; otro comprador, el mismo 404. El campo `facturaEstado` de la venta sigue en `NO_APLICA`.

**Si algo falla.** ARCA sin respuesta (tiempo vencido, conexión rechazada, WSDL que no baja o SOAP Fault) se traduce a `SinRespuesta`. En ese caso, y ante un mensaje inválido, el MDB hace `setRollbackOnly()` y el broker reentrega con los reintentos que ya existen (5 entregas cada 2 s); agotados, el mensaje va a la DLQ y se recupera con el procedimiento de la sección 10.6, filtrando por la suscripción de Facturación. Se usa `setRollbackOnly()` y no una excepción para no dejar una traza por intento. Un comprobante rechazado (`R`) se traduce a `ReglaIncumplida` con el tipo `comprobante-rechazado`. Un rechazo no se arregla reintentando: desde el 20/11 (DEF-33) `ServicioDeFacturacion` lo anota en `facturacion.comprobante_rechazado` con su motivo y un `SEVERE` en el log, y el MDB confirma el mensaje, que no vuelve al broker. Antes iba también a la DLQ después de cinco pedidos iguales a ARCA. Lo revisa una persona (10.6). Si falla la base al anotar el rechazo, el error sale del MDB y el broker reintenta. Si Facturación está detenido, la suscripción durable le guarda los mensajes. En ningún caso se entera la compra: ya está confirmada.

## 11.5 El cliente SOAP

| Pieza | Qué hace |
|---|---|
| `Arca` (interfaz propia) | Lo único que ve el negocio: `autorizar(SolicitudDeCae)` devuelve un `ComprobanteAutorizado` con nombres de Palco. `ServicioDeFacturacion` no sabe que del otro lado hay SOAP |
| `PuertoArca` | Interfaz Java anotada con `@WebService` que repite los nombres del WSDL (espacio de nombres, `portType`, operación y parámetros). No usa ninguna clase del simulado: los dos lados solo comparten el contrato |
| `ArcaSoap` (Adapter) | Arma el cliente con `Service.create(urlDelWsdl, QName)` y `getPort(PuertoArca.class)`, sin generar código con `wsimport` en el build. Traduce la respuesta y los errores |
| `FabricaDeArca` (Factory, `@ApplicationScoped`) | Lee la configuración y produce el `Arca` que se inyecta |

Decisiones del cliente:

- **El WSDL se baja la primera vez que se usa, no al desplegar.** Así Facturación despliega aunque ARCA esté caído. El `Service` queda en memoria porque el contrato no cambia mientras el servicio corre; si la descarga falla, no queda nada guardado y el próximo pedido lo intenta otra vez. El puerto se crea en cada pedido, porque no se garantiza que se pueda compartir entre hilos.
- **La dirección de la llamada** es la URL configurada sin `?wsdl` (`ENDPOINT_ADDRESS_PROPERTY`), no la `soap:address` que trae el WSDL, que el servidor arma con el nombre con que se lo llamó.
- **Tiempos de espera:** 2 s para conectar y 5 s para leer, configurables. Se ponen con los nombres de CXF, el cliente de WildFly (`jakarta.xml.ws.client.connectionTimeout` y `receiveTimeout`), y con los de Metro, el cliente con que corren los tests. Sin ellos, un ARCA colgado dejaría al consumidor esperando para siempre, sin reintentos ni DLQ. Un valor de 0 o menos se rechaza, porque en los dos clientes quiere decir "esperar para siempre". Con 5 s de lectura, las 5 entregas llegan a la DLQ en unos 35 s.
- **Configuración:** `palco.facturacion.arca.wsdl` (por defecto `${PALCO_URL_INTERNA}/legado/arca?wsdl`, con `http://localhost:8080` si falta la variable), `palco.facturacion.arca.espera-conexion-ms` y `palco.facturacion.arca.espera-lectura-ms`.
- **Una respuesta que no se entiende** (sin resultado, aprobada sin número o con un CAE que no tiene 14 dígitos, vencimiento ilegible) se trata como sin respuesta: no se guarda una factura a medias.

## 11.6 Seguridad del XML (SEC-I01)

El riesgo es un ataque de entidades externas (XXE): un XML con una DTD que declare una entidad apuntando a un archivo del servidor. El XML que llega por SOAP lo lee JBossWS, que usa Apache CXF, en los dos lados: el ARCA simulado al recibir el pedido y Facturación al recibir la respuesta. CXF arma su lector StAX con `javax.xml.stream.supportDTD` y `javax.xml.stream.isSupportingExternalEntities` en `false`, y solo los habilita si se define la propiedad de sistema `org.apache.cxf.stax.allowInsecureParser` (se comprobó en `StaxUtils` de `cxf-core` 4.1.8, la versión que trae WildFly 41.0.1). Palco no la define, y `FabricaDeArca` **se niega a crear el cliente** si está en `true`: se prefiere que Facturación no facture antes que leer XML con la DTD habilitada. Además, SOAP prohíbe la DTD dentro de un mensaje.

Estado: implementado y probado contra el servidor. En los tests corre Metro y no CXF, así que la prueba contra el CXF de WildFly es el bloque D de `scripts/pruebas/seguridad.mjs` (14.10): manda a `/legado/arca`, por el puerto del backend, un sobre de control y tres con DOCTYPE (una entidad externa a un archivo del servidor, una DTD por entidad de parámetro y entidades internas anidadas), y espera que los tres se rechacen con un SOAP Fault, sin expandir nada y sin que el ARCA simulado atienda ninguno. En la corrida del 21/11 sobre `fbd31dc` pasó con 6 de 6 comprobaciones (`docs/testing/evidencia/final/seguridad.txt`).

## 11.7 Decisiones de diseño

| N.º | Decisión | Por qué |
|---|---|---|
| 1 | El ARCA simulado vive en un WAR aparte, que no hereda de `palco-servicios` | Cliente y sistema externo no comparten clases ni despliegue; se puede bajar solo el simulador para mostrar que la compra sigue |
| 2 | WSDL generado por WildFly desde la clase `@WebService` | Es lo más simple: no hay un WSDL escrito a mano que mantener. Requiere la capa `webservices` en el servidor |
| 3 | La factura se dispara desde una suscripción durable al tópico | La compra no necesita la factura para confirmarse; esperar a ARCA dentro de `confirmarCompra` retendría una conexión de la base. El tópico ya existía, así que sumar un suscriptor no tocó Ventas. La llamada a ARCA sigue siendo síncrona |
| 4 | Sin cola de reintento propia: reintentos del broker y DLQ | El mecanismo ya estaba configurado y probado (secciones 10.6 y 10.7); una cola aparte sumaba piezas sin cambiar el resultado para el alcance del trabajo |
| 5 | Ventas no cambia y la factura se consulta en Facturación | Evita una llamada de Facturación a Ventas y un cambio en un servicio que ya funciona; `facturaEstado` queda en `NO_APLICA` |
| 6 | La numeración la asigna el ARCA simulado | Facturación no lleva un contador propio ni tiene que serializar pedidos |
| 7 | Cliente SOAP con una interfaz anotada y `Service.create` sobre el WSDL | Sin generar código en el build |
| 8 | DTD y entidades externas deshabilitadas en el procesamiento XML (SEC-I01) | Evita ataques XXE sobre el XML que llega por SOAP (sección 11.6) |
| 9 | La factura se guarda en una transacción corta del repositorio, no en la del MDB | `ServicioDeFacturacion` es `NOT_SUPPORTED` para no retener una conexión de la base mientras espera a ARCA. Si la transacción del MDB se deshiciera con la factura ya guardada, la reentrega la encuentra y no hace nada |
| 10 | Idempotencia en tres niveles: búsqueda previa por venta, restricción única `factura_venta_unica` y el simulado que devuelve el mismo comprobante para la misma venta | Un mensaje repetido no factura dos veces, ni siquiera con dos entregas simultáneas; y si ARCA autorizó y después falló el guardado, la reentrega no numera otro comprobante |
| 11 | `maxSession` 1 en el MDB | Se factura de a una compra: no se le piden a ARCA dos facturas de la misma venta a la vez, y los números salen en orden de llegada |
| 12 | Factura B (6) y punto de venta 1 como constantes de `ServicioDeFacturacion` | Son datos fiscales de Facturación, no reglas de negocio compartidas: no van en `ReglasDeNegocio` |
| 13 | Sin restricción única en (tipo, punto de venta, número) | El simulado numera en memoria y vuelve a 1 al redesplegarse; con una base que dura más que el despliegue (el PostgreSQL del compose, en su volumen), esa restricción frenaría toda la facturación. Es una diferencia declarada del simulado |
| 14 | Un comprobante rechazado se anota y no se reintenta (desde el 20/11, DEF-33) | El pedido sale del mensaje y ARCA lo rechazaría en cada intento. Con la fila de `comprobante_rechazado`, la consulta responde 409 en lugar del 404 de una factura en camino, y quien lo revisa tiene lo que se pidió y el motivo |

Este diseño reemplaza al anterior, que preveía `cola.reintento-facturacion` con espera creciente, la factura en estado `PENDIENTE`, la consulta `FECompConsultar` antes de reintentar, la numeración en Facturación con un `@Singleton` y una llamada `PUT /api/v1/ventas/{ventaId}/factura` para informar la factura a Ventas.

**Stateless o stateful.** `ServicioDeFacturacion` y `FacturaRepositoryJpa` son stateless: cada factura se arma solo con el mensaje que llega y lo que dura queda en la base, así que cualquier instancia atiende cualquier mensaje, también una reentrega. `ArcaSoap` y `FabricaDeArca` son `@ApplicationScoped`: guardan configuración (la URL, los tiempos y el `Service`), no la conversación de nadie. `ArcaSimulado` no tiene estado propio; la numeración está en el `Talonario` del despliegue.

## 11.8 Datos

Esquema `facturacion`, tabla `factura` (migración `V1__crear_tablas.sql`, el mismo SQL en H2 y en PostgreSQL): `id`; `venta_id`, `comprador_id` y `evento_id` (ids de otros servicios, sin FK); `tipo_comprobante`, `punto_de_venta`, `numero`, `cae`, `vencimiento_cae`; `importe` en pesos enteros (`bigint`, mayor que 0); `fecha` del comprobante en hora argentina; `emitida` en UTC truncada a segundos. Restricción `factura_venta_unica` sobre `venta_id`. Sin check de lista de valores ni valores por defecto.

Tabla `comprobante_rechazado` (migración `V2__comprobantes_rechazados.sql`, 20/11, DEF-33): `id`; `venta_id`, `comprador_id` y `evento_id` (sin FK); `importe` en pesos enteros, sin check, porque un importe imposible es justamente uno de los motivos por los que ARCA rechaza y tiene que poder quedar anotado; `fecha` del comprobante que se pidió; `motivo`, la observación de ARCA en un renglón (`varchar(500)`, una más larga se guarda recortada); `rechazado` en UTC truncada a segundos. Restricción `comprobante_rechazado_venta_unica` sobre `venta_id`: un mensaje repetido de esa venta no anota otro rechazo. Sin valores por defecto: todas las columnas las escribe `ServicioDeFacturacion`.

## 11.9 Cómo se prueba

- **Tests** (`mvn -f backend/pom.xml -pl servicios/facturacion,servicios/arca -am test`), al 20/11: 50 en Facturación (negocio, MDB, recurso, cliente SOAP real contra un ARCA publicado con `Endpoint.publish`, fábrica y repositorio en H2, con el rechazo anotado y el 409 de DEF-33) y 18 en el ARCA simulado (numeración, rechazos, modo sin respuesta, 60 pedidos simultáneos sin números repetidos, el WSDL y un sobre SOAP escrito a mano, y el contrato del WSDL de `ContratoDelWsdlTest`, sección 4.9). Los 49 que había el 16/10 (38 y 11) pasaron ese día dentro del build completo (`mvn clean install`: 577 tests, 0 fallas).
- **En vivo, 16/10/2026, con el servidor armado desde cero:** WildFly arrancó con la capa `webservices`, desplegó `palco-arca.war` y Facturación sin líneas `ERROR` en el log, y `/legado/arca?wsdl` respondió 200. El escenario 15 encontró la factura B de la compra con el número 00001-00000001 y un CAE de 14 dígitos, la misma al consultarla otra vez con rol `servicio`, 403 con `staff` y 404 para una venta inexistente; la compra siguiente salió con el número siguiente y una sola línea de factura emitida en el log. La carpeta 7 de Postman sumó la factura sin token (401) y la de otro comprador (404), sin fallas. El escenario 12 comprobó que un mensaje inválido deja en la DLQ la copia de Facturación además de la de auditoría.
- **En vivo, 19/11/2026, contra el compose:** con el ARCA simulado sin responder (`palco.arca.simular-sin-respuesta=true`), el pago respondió 201 en 129 ms y la compra llegó a `EMITIDA` sin esperar la factura. El cliente CXF de WildFly cortó cada pedido a los 5 s (el simulado tarda 30 s), el broker hizo las 5 entregas con 2 s entre una y otra y el mensaje pasó a la DLQ a los 33 s del primer pedido, sin un sexto pedido a ARCA. Con ARCA respondiendo otra vez, el mensaje devuelto a la suscripción de Facturación (10.6) salió en una sola factura con su CAE, y la auditoría y el aviso no lo recibieron de nuevo: 29 de 29 (`docs/testing/evidencia/checkpoint-3/arca-lento-dlq.txt`).
- **En vivo, en cada corrida del workflow "Pruebas de seguridad":** el sobre con DTD rechazado por el CXF de WildFly, con el bloque D de `seguridad.mjs` (11.6); el 21/11 sobre `fbd31dc`, 6 de 6.

## 11.10 Riesgos aceptados

- Sumar la capa `webservices` obliga a rearmar el servidor una vez desde cero (se vacían la base H2 y los mensajes); se hizo el 16/10.
- Hay supuestos que solo se comprueban en WildFly, porque los tests corren con Metro y no con CXF. El 16/10 se comprobó que el servicio mapeado a `/*` queda en `/legado/arca` y que WildFly agrega solo las dependencias del cliente JAX-WS al WAR de Facturación (la factura se emitió). El 19/11 se comprobó que los tiempos de espera de CXF cortan como los de Metro: con el ARCA sin responder, cada pedido se cortó a los 5 s (11.9). Y el 21/11, que el CXF de WildFly rechaza un sobre con DTD (11.6).
- `/legado/arca` no tiene autenticación, como corresponde a un simulador sin WSAA. No se publica hacia afuera: desde el 25/11 el nginx del compose responde 404 en `/legado/` (DEF-45) y el backend se publica solo en 127.0.0.1 (4.9).
- Las facturas no tienen anulación ni nota de crédito (por ejemplo, ante una reventa o un reembolso): queda fuera del alcance (1.5).
- La llamada a ARCA es síncrona (pedido y respuesta por SOAP) y la dispara un consumidor del tópico: lo asincrónico es cómo llega la compra confirmada a Facturación (8.6, I3, y 19.2).

# 12. Sistemas externos

## 12.1 Mercado Pago

**Estado: Diseñado (decisión del 13/10).** No se crea cuenta de Mercado Pago ni se integra la pasarela real; `ServicioDePagos` sigue con `PagosFake` y el requisito de servicio REST lo cubre la API propia (I1). El 16/10 la cátedra aceptó la pasarela simulada, así que el cobro real con Mercado Pago es trabajo futuro (22). Del diseño queda el diagrama de secuencia (sección 13.2).

Qué existe hoy en el código de Pagos: `IPagos` y `ServicioDePagos` (`@Stateless`, `NOT_SUPPORTED`), la interfaz `PasarelaDePagos`, `ResultadoPasarela`, `FabricaDePasarela` y `PagosFake`. El pago se guarda en el esquema de Pagos con su clave de idempotencia; repetir la clave devuelve el pago guardado.

Qué simula `PagosFake`: el token `tok_rechazado` rechaza, `tok_sin_respuesta` hace que la pasarela no conteste y cualquier otro (por ejemplo `tok_test`) aprueba; no mueve plata. Un reembolso siempre sale bien. `REEMBOLSADO` lo escribe el reembolso desde el 23/10; `PENDIENTE` y `VENCIDO` están en el enumerado `EstadoPago`, pero nadie los escribe. Tampoco existe el webhook.

Diseño del adaptador: `MercadoPagoPagos` como otra implementación de `PasarelaDePagos` (Adapter), elegida por configuración en `FabricaDePasarela` (Factory). Cobro con `POST /v1/payments` y `X-Idempotency-Key`; traducción de `approved`, `rejected` e `in_process` a `APROBADO`, `RECHAZADO` y `PENDIENTE`; webhook con validación de firma antes de leer el cuerpo.

## 12.2 ARCA

**Estado: Implementado** (escenario 15, 16/10), contra un ARCA **simulado** (sección 11), que la cátedra aceptó el 16/10. Cada compra confirmada se factura de forma asincrónica: Facturación recibe `PAGO_CONFIRMADO` del tópico y le pide el CAE al simulado por SOAP. El simulado no tiene la autenticación del sistema real (WSAA), numera en memoria (vuelve a 1 al redesplegarse) y devuelve el mismo comprobante si se le pide otra vez la misma venta; esas diferencias con el WSFEv1 están declaradas en la sección 11.3.

## 12.3 Qué se simula hoy

| Parte | Hoy |
|---|---|
| Cobro | Real hasta Pagos (REST, guardado, control por rol); la pasarela es **simulada** (`PagosFake`) |
| `CobrosFake` en Ventas | Simulador local que solo se activa con `palco.ventas.cobros=fake`; ningún archivo del repositorio lo define y no estaba activo en la evidencia |
| Aviso al comprador | Por email con `Correos` (fila de abajo): sin SMTP, **simulado**, una línea en el log |
| Factura | Real del lado de Palco (tópico, suscripción durable, cliente SOAP, factura guardada); ARCA es **simulado** (`ArcaSimulado`, `/legado/arca`). Implementado |
| Correo | Por SMTP si se configura `PALCO_SMTP_HOST` (`common/email/CorreoSmtp`); si no, `CorreoAlLog` lo escribe en el log del servidor, con el destinatario enmascarado y sin el cuerpo, salvo con `PALCO_CORREO_LOG_COMPLETO=true` (14.6). El mensaje trae solo `compradorId`, y Notificaciones le pide el email a Usuarios. Ni el compose ni `dev.sh` configuran un servidor SMTP |
| Broker, cola, tópico, DLQ y MDB | Reales |

# 13. Diagramas de secuencia

Los diagramas vigentes son los de las secciones 13.3 a 13.8, uno o dos por integración, en `docs/documentacion/diagramas/`. Cada uno lleva en el encabezado su estado y las clases de las que sale. Las convenciones son las mismas en todos: flecha llena, llamada síncrona (REST, SOAP o local); punteada, respuesta; flecha de mensaje, asincrónico por JMS; los recuadros `alt`, `opt`, `loop` y `par` encierran las alternativas, los pasos opcionales, las repeticiones y lo que pasa en paralelo. Los de las secciones 13.1 y 13.2 son anteriores a la implementación y quedan como antecedente.

## 13.1 Compra de punta a punta (actividad de la clase 10, antecedente)

![Diagrama de secuencia de la compra, actividad de la clase 10](../clases/clase-10-actividad-integradora/diagrama_secuencia.png)

*Fuente: `docs/clases/clase-10-actividad-integradora/diagrama_secuencia.png`. Rojo: síncrono; violeta: asincrónico (JMS); naranja: alternativa ante timeout; punteado: respuesta.*

Es el diagrama del taller de la clase 10, anterior a la implementación. Se conserva como antecedente del diseño; lo que hace hoy el sistema está en las secciones 13.3 a 13.8.

Paso a paso:

1. El cliente abre la compra con `POST /v1/ventas` e `Idempotency-Key`; la API responde 201 con `Location` y una reserva de 10 minutos.
2. El cliente paga con `POST /v1/ventas/{id}/pagos`.
3. La API cobra en Mercado Pago (`POST /v1/payments`) y recibe `approved`.
4. Con un tope de 5 s pide la factura a Facturación por SOAP y recibe el CAE. Alternativa: si ARCA no responde en 5 s, la factura pasa a `cola.reintento-facturacion`, la venta queda `CONFIRMADA` con factura `PENDIENTE` y un trabajo la reintenta.
5. Publica `PAGO_CONFIRMADO` en `cola.emision-entradas` y en `topico.compra-confirmada`.
6. Responde 201 con la venta `CONFIRMADA`, sin esperar la emisión.
7. Los consumidores emiten la entrada (cola) y avisan (tópico).
8. El cliente consulta la entrada.
9. Si el pago fuera diferido, Mercado Pago llama al webhook.

**Coincidencia con el código:**

| Paso | ¿Coincide? |
|---|---|
| 1, 2, 5, 6, 7 | Sí. Detalles: Ventas y Pagos son dos servicios (el diagrama los junta en "API Palco"); el pago responde 201 con `Location` al pago; el tópico tiene tres suscriptores (aviso, auditoría y facturación), no uno |
| 3 | No: el cobro va a `ServicioDePagos` por `POST /api/v1/pagos`, y Pagos cobra con `PagosFake`. Mercado Pago es diseño |
| 4 | No: la factura se pide fuera de la compra, desde el suscriptor de Facturación al tópico (sección 13.7), sin `cola.reintento-facturacion`, y la venta no cambia su `facturaEstado` |
| 8 | Parcial: `GET /api/v1/entradas/{id}` devuelve la entrada `VALIDA`; `EMITIDA` es el estado de la venta, no de la entrada |
| 9 | No existe la ruta del webhook |

## 13.2 Cobro con Mercado Pago (diseño)

![Diagrama de secuencia del cobro con Mercado Pago](../entrega-obligatoria-2/diagramas/diagrama_secuencia_mercadopago.png)

*Fuente: `docs/entrega-obligatoria-2/diagramas/diagrama_secuencia_mercadopago.png` (también en SVG, generado por `diagrama_secuencia_mercadopago.py`). Todo el diagrama es diseño: no se implementa (12.1).*

Paso a paso:

- **A. Cobro, síncrono por REST.** (1) Ventas llama a `POST /api/v1/pagos` con rol `servicio` e `Idempotency-Key`. (2) Pagos busca un pago con esa clave; si existe, lo devuelve sin cobrar. (3) Cobra a través de `PasarelaDePagos`. (4) El adaptador `MercadoPagoPagos` llama a `POST /v1/payments` con `X-Idempotency-Key` y espera como máximo 2 s para conectar y 5 para leer; recibe `approved`, `rejected` o `in_process`, que traduce a `APROBADO`, `RECHAZADO` o `PENDIENTE`. (5) Guarda el pago en una transacción corta. (6) Responde 201; un rechazo también es 201 y el 402 se lo da Ventas al comprador. Ningún paso corre dentro de una transacción abierta (`NOT_SUPPORTED`).
- **Alternativa.** Si Mercado Pago no responde a tiempo, Pagos responde 504 `sin-respuesta` sin guardar nada y Ventas reintenta con la misma clave.
- **B. Webhook, solo si el pago quedó `PENDIENTE`.** (7) Mercado Pago llama a `POST /api/v1/pagos/webhook` con la cabecera `x-signature`. (8) Pagos valida la firma HMAC antes de leer el cuerpo (401 si no coincide). (9 y 10) Consulta el estado real con `GET /v1/payments/{id}`. (11) Actualiza con un `UPDATE` condicional sobre `estado = 'PENDIENTE'`, así que un aviso repetido no cambia nada. (12) Responde 200, o 504 si no pudo consultar, para que Mercado Pago reintente.

**Coincidencia con el código:** los pasos 1, 2, 5, 6 y la alternativa (504 sin guardar, reintento con la misma clave) coinciden con lo que hace hoy `ServicioDePagos` contra `PagosFake`. Los pasos 4 y 7 a 12 no existen. El diagrama deja explícitamente sin definir cómo se entera Ventas de que un pago `PENDIENTE` se resolvió.

## 13.3 Compra, parte 1: iniciar la compra y reservar el cupo (I1 y S1)

![Diagrama de secuencia de la compra, parte 1: iniciar la compra y reservar el cupo](diagramas/secuencia-compra-reserva-rest.svg)

*Fuente: `docs/documentacion/diagramas/secuencia-compra-reserva-rest.svg`. Estado: Implementado. Sale de `VentasResource`, `ComprasEnCurso`, `ServicioDeVentas`, `ClienteDeUsuarios`, `ClienteDeEventos`, `ServicioDeEventos` y `VencimientoDeReservas`.*

Paso a paso:

1. El comprador (rol `user`) manda `POST /api/v1/ventas` con `Idempotency-Key`, el evento, los ítems y un titular (nombre y DNI) por entrada.
2. Ventas busca una venta de ese comprador con esa clave. Si existe y el pedido es el mismo, la devuelve sin reservar nada; si el pedido es otro, responde 409 `clave-reutilizada`.
3. Pide los datos del comprador a Usuarios con `GET /api/usuarios/yo` y su mismo token. Si la identidad no está verificada, responde 403 `identidad-no-verificada`.
4. `ComprasEnCurso` crea por JNDI una instancia nueva de `ServicioDeVentas` (`@Stateful`, una por compra) y llama a `iniciarCompra` (`NOT_SUPPORTED`).
5. Valida: hasta 6 entradas, un titular por entrada, DNI de 7 u 8 dígitos sin repetir (422 `maximo-de-entradas`, `titulares-no-coinciden` o `dni-repetido`).
6. Por cada sector pedido, reserva cupo en Eventos con `POST /api/eventos/{eventoId}/sectores/{sectorId}/cupo` y token de rol `servicio`.
7. Eventos hace un `UPDATE` condicional (`vendidas + cantidad <= cupo`) en su propia transacción y responde 200, o 409 si no alcanza el cupo o el evento ya empezó.
8. Ventas guarda la venta `EN_CHECKOUT` con sus renglones; la reserva vence a los 10 minutos. Lo hace `ReglasEntreCompras.guardarDentroDelTope`, con el candado del comprador y el evento: si otra compra suya se guardó mientras tanto y con las dos pasa el tope, responde 409 `tope-por-evento` y devuelve el cupo (7.2).
9. Responde 201 con `Location` `/api/v1/ventas/{ventaId}`.

**Alternativa: un sector sin cupo o Eventos sin respuesta.** Ventas devuelve el cupo de los sectores que ya había reservado (el mismo `POST .../cupo` con cantidad negativa) y responde 409 `cupo-agotado` o `evento-finalizado`, o 504 `sin-respuesta`. Ante la duda, queda un lugar sin vender y nunca uno vendido dos veces.

**Cancelación y vencimiento** (nota del diagrama): `DELETE /api/v1/ventas/{ventaId}` responde 204 y devuelve el cupo; si la compra no se paga en 10 minutos, `VencimientoDeReservas` (`@Singleton` con `@Schedule` cada minuto) la pasa a `VENCIDA` y devuelve el cupo.

## 13.4 Compra, parte 2: pagar contra Pagos y confirmar (I1 y S1)

![Diagrama de secuencia de la compra, parte 2: pagar contra Pagos y confirmar](diagramas/secuencia-compra-pago-rest.svg)

*Fuente: `docs/documentacion/diagramas/secuencia-compra-pago-rest.svg`. Estado: Implementado; la pasarela es la simulada (`PagosFake`). Sale de `VentasResource`, `ComprasEnCurso`, `ServicioDeVentas`, `VentaRepositoryJpa`, `ClienteDePagos`, `ServicioDePagos` y `PagosFake`.*

Paso a paso:

1. El comprador manda `POST /api/v1/ventas/{ventaId}/pagos` con `Idempotency-Key`, el medio de pago y su token.
2. Ventas lee la venta: si ya está paga con esa clave, la devuelve sin cobrar; paga con otra clave, 409 `venta-ya-paga`; vencida o cancelada, 409 `reserva-vencida`.
3. `guardarClaveDePago` guarda la clave del pago en la venta y la confirma en el momento. Con esa marca puesta, cancelar y vencer ya no tocan la venta.
4. Llama a `confirmarCompra` (`REQUIRED`, `@Remove`: al terminar se descarta la instancia stateful).
5. `respaldarTitulares` copia los titulares a la base, para que la compra pueda terminarse aunque esa instancia ya no exista. Lo llama `ReglasEntreCompras`, en una transacción propia, después de volver a mirar el DNI de cada titular con su candado (7.3). Es la pieza que necesita la conciliación de pagos (ver la nota del pie).
6. Cobra con `POST /api/v1/pagos`, token de rol `servicio`, la clave `<ventaId>:<clave>`, el id de la venta y el monto total.
7. Pagos busca un pago con esa clave (si existe, lo devuelve sin cobrar); si no, cobra con `PagosFake` y guarda el pago, sin transacción abierta mientras espera (`NOT_SUPPORTED`). Responde 201 con el pago `APROBADO` o `RECHAZADO`.
8. Si se aprobó, en la misma transacción Ventas pasa la venta a `CONFIRMADA` con el id del pago y guarda las entradas en `PENDIENTE_EMISION`.
9. Publica `PAGO_CONFIRMADO` (publicador `MANDATORY`, fábrica `java:/JmsXA`) a `cola.emision-entradas` y a `topico.compra-confirmada`; el mensaje sale con el commit.
10. Responde 201 con `Location` `/api/v1/ventas/{ventaId}/pagos/{pagoId}`; las entradas todavía no tienen código.

**Alternativas.** Pago rechazado: 402 `pago-rechazado`; se quita la marca y la instancia sigue viva para pagar con otro medio. Pagos sin respuesta: 504 `sin-respuesta`, Pagos no guarda nada, la marca queda y el reintento va con la misma clave.

La nota del pie describe la conciliación de pagos (`ConciliacionDePagos`), para el caso en que nadie reintenta, y el paso 5 la prepara. La conciliación se probó el 16/10 con el servidor armado desde cero (escenario 14, sección 7.8).

## 13.5 Emisión por la cola (I4)

**Parte 1: publicación y consumo.**

![Diagrama de secuencia de la emisión por la cola, parte 1: publicación y consumo](diagramas/secuencia-emision-cola.svg)

*Fuente: `docs/documentacion/diagramas/secuencia-emision-cola.svg`. Estado: Implementado. Sale de `EmisionDeEntradasMDB`, `EntradaRepositoryJpa`, `VentaRepositoryJpa`, `PublicadorPagoConfirmadoJms`, `DestinosJms` y `configurar.cli`. Evidencia: escenarios 7, 8, 10 y 13.*

Paso a paso:

- **A. Publicación dentro de la transacción del pago.** (1) `confirmarCompra` pasa la venta a `CONFIRMADA` y guarda las entradas en `PENDIENTE_EMISION`. (2) `PublicadorPagoConfirmadoJms.publicar` (`MANDATORY`) arma un `TextMessage` JSON persistente con `tipoEvento` = `PAGO_CONFIRMADO`. Al confirmar, se prepara el broker (recurso XA), se confirma `PalcoDS` (último recurso, no XA) y después el broker. Si la transacción se deshace, el mensaje no sale (escenario 13). El pago ya respondió 201: nadie espera la emisión.
- **B. Consumo.** (3) El broker entrega el mensaje a una sola sesión de `EmisionDeEntradasMDB`, con el selector `tipoEvento = 'PAGO_CONFIRMADO'`. (4) `PagoConfirmadoMensaje.desdeJson` lee y valida el contrato. (5) `emitirPendientes` pasa cada entrada a `VALIDA` con su código QR, solo si sigue `PENDIENTE_EMISION`. (6) `marcarEmitida` pasa la venta a `EMITIDA`, solo si sigue `CONFIRMADA`. Con un mensaje repetido no cambia ninguna fila y se loguea "es un mensaje repetido y no cambió nada". Al confirmar, el mensaje queda consumido.
- **Alternativas.** Si la venta no existe en Ventas, se escribe un `WARNING` y el mensaje se consume sin ir a la DLQ. Si el mensaje es inválido o el consumidor falla, (7) se marca `setRollbackOnly()` o sale una excepción: no queda nada escrito y el broker reentrega (parte 2).

Con el consumidor detenido (`stop-delivery`), el mensaje espera en la cola y se emite al reanudarlo (escenario 10). Un mensaje repetido no emite dos veces (escenario 8).

**Parte 2: reintentos, DLQ y recuperación.**

![Diagrama de secuencia de la emisión por la cola, parte 2: reintentos, DLQ y recuperación](diagramas/secuencia-emision-cola-dlq.svg)

*Fuente: `docs/documentacion/diagramas/secuencia-emision-cola-dlq.svg`. Estado: Implementado. Evidencia: escenario 12.*

Paso a paso:

- **A. Reintentos del broker.** (1) El broker entrega o reentrega el mismo mensaje. (2) El consumidor falla y su transacción se deshace entera. Se repite hasta 5 entregas, cada 2000 ms (`address-setting=#` en `configurar.cli`). (3) Después de la quinta entrega fallida, el mensaje pasa a la DLQ (línea `AMQ222149` en `server.log`). La venta queda `CONFIRMADA` con entradas `PENDIENTE_EMISION` y nada la reintenta sola; los demás mensajes siguen pasando (escenario 12).
- **B. Recuperación manual** (procedimiento de la sección 10.6). (4) El operador busca el mensaje con `list-messages` y `count-messages`, filtrando por `_AMQ_ORIG_QUEUE` = `jms.queue.cola.emision-entradas`. (5) Ve la causa en `server.log` y la corrige. (6) Lo devuelve con `move-messages` y el mismo filtro. (7 y 8) El mensaje vuelve a la cola y se entrega otra vez. (9) Se emite con los mismos `UPDATE` condicionales: es seguro porque la emisión es idempotente. (10) Comprueba que la venta quedó `EMITIDA` y las entradas `VALIDA`.

`move-messages` con filtro se probó el 19/11 hacia la suscripción de Facturación (`checkpoint-3/arca-lento-dlq.txt`, sección 10.6), y sin filtro lo usa el escenario 12. Hacia la cola de emisión es el mismo comando, con `jms-queue=DLQ` y `other-queue-name=cola.emision-entradas` (10.6).

## 13.6 Tópico de compra confirmada: aviso y auditoría (I5)

![Diagrama de secuencia del tópico de compra confirmada: aviso y auditoría](diagramas/secuencia-topico-aviso-auditoria.svg)

*Fuente: `docs/documentacion/diagramas/secuencia-topico-aviso-auditoria.svg`. Estado: Implementado; el aviso es simulado (queda en el log). Sale de `AvisoDeCompraMDB`, `AuditoriaDeComprasMDB`, `ServicioDeNotificaciones`, `ServicioDeAuditoria` y `RegistroDeAuditoria`. Evidencia: escenarios 9 y 11, `suscriptor-durable.txt`. El tercer suscriptor, Facturación, tiene su propio diagrama (sección 13.7).*

Paso a paso:

1. `confirmarCompra` publica `PAGO_CONFIRMADO` en el tópico, dentro de su transacción; sale con el commit.
2. En paralelo, cada suscripción recibe su propia copia, con el mismo selector:
   - **Aviso** (suscripción no durable). `AvisoDeCompraMDB` llama a `enviarConfirmacion`, que le pide a Usuarios el email del comprador y manda el aviso con `Correos`: sin SMTP, lo escribe en el log. Un mensaje inválido se descarta con un `WARNING`, sin rollback.
   - **Auditoría** (durable: `clientId` `palco-auditoria`, `subscriptionName` `compras-confirmadas`). `AuditoriaDeComprasMDB` llama a `registrar` con el `JMSMessageID`; si ya hay una fila con ese id (una reentrega), no inserta; si no, la guarda. Un mensaje inválido marca `setRollbackOnly()`: 5 entregas cada 2 s y después la DLQ.
3. **Suscriptor detenido** (escenario 11). Con Notificaciones detenido, el broker guarda la copia de la suscripción durable; la no durable no tiene copia y ese aviso se pierde, lo que se acepta. Al reanudar, el broker entrega los mensajes guardados en orden y auditoría guarda una fila por mensaje.

La auditoría se consulta con `GET /api/notificaciones/auditoria`, rol `servicio`.

## 13.7 Facturación por SOAP con el ARCA simulado (I3)

![Diagrama de secuencia de la facturación por SOAP con el ARCA simulado](diagramas/secuencia-facturacion-soap.svg)

*Fuente: `docs/documentacion/diagramas/secuencia-facturacion-soap.svg`. Estado: Implementado; probado el 16/10 con el servidor armado desde cero (escenario 15). Sale de `FacturacionDeComprasMDB`, `ServicioDeFacturacion`, `ArcaSoap`, `PuertoArca`, `FabricaDeArca`, `FacturasResource`, `ArcaSimulado` y `Talonario`, y de las tablas `factura`, con `factura_venta_unica`, y `comprobante_rechazado`.*

Paso a paso:

1. Ventas publica `PAGO_CONFIRMADO` en `topico.compra-confirmada`, como siempre: no cambió.
2. El broker entrega una copia a la suscripción durable de Facturación (`clientId` `palco-facturacion`, `subscriptionName` `facturacion-compras-confirmadas`, `maxSession` 1).
3. `FacturacionDeComprasMDB` lee y valida el contrato con `PagoConfirmadoMensaje.desdeJson`.
4. Llama a `emitirFactura`; mientras tanto, la transacción del MDB queda suspendida (`ServicioDeFacturacion` es `NOT_SUPPORTED`).
5. `buscarPorVenta`: si la venta ya tiene factura (mensaje repetido), la devuelve sin llamar a ARCA.
6. Si no tiene, `ArcaSoap` llama a `FECAESolicitar` por SOAP (`CbteTipo` 6, `PtoVta` 1, `ImpTotal`, `CbteFch`, `IdVenta`), con el cliente armado por `Service.create` sobre `/legado/arca?wsdl` y 2 s para conectar y 5 para leer.
7. En el ARCA simulado, el `Talonario` numera por punto de venta y tipo (si esa venta ya tenía comprobante, devuelve el mismo) y responde `A` con número, CAE de 14 dígitos y vencimiento.
8. `guardar` escribe la factura en la transacción corta del repositorio (única por venta) y vuelve el `FacturaDto`. Al confirmar, la copia queda consumida.

**Alternativa: ARCA no responde o SOAP Fault.** El tiempo vencido o la falla se traducen a `SinRespuesta`. (9) El MDB marca `setRollbackOnly()` sin haber guardado nada. (10) El broker reentrega el mismo mensaje, hasta 5 entregas cada 2 s. (11) Agotados los intentos, el mensaje va a la DLQ y se recupera con `move-messages` (sección 10.6).

**Alternativa: `Resultado` R.** El rechazo se traduce a `ReglaIncumplida` `comprobante-rechazado`. Reintentar no lo arregla, porque el pedido sale del mensaje y es el mismo en cada intento (desde el 20/11, DEF-33). (12) `ServicioDeFacturacion` anota el rechazo en `facturacion.comprobante_rechazado` con su motivo, deja un `SEVERE` en el log y vuelve a lanzar la misma excepción. (13) El MDB confirma el mensaje sin `setRollbackOnly()`: no hay reentregas ni DLQ, y la consulta de la factura responde 409 `comprobante-rechazado`.

**Alternativa: Facturación detenido.** (14) La suscripción durable guarda los mensajes y el broker los entrega cuando Facturación vuelve.

La factura se consulta con `GET /api/v1/facturas/{ventaId}`: rol `user` (solo la propia) o `servicio`; 404 si no hay factura y, desde el 20/11, 409 `comprobante-rechazado` si ARCA la rechazó. El XML lo lee el cliente SOAP de WildFly con la DTD y las entidades externas deshabilitadas (SEC-I01, sección 11.6).

## 13.8 Validación de una entrada en la puerta (S3)

![Diagrama de secuencia de la validación de una entrada en la puerta](diagramas/secuencia-validacion-puerta.svg)

*Fuente: `docs/documentacion/diagramas/secuencia-validacion-puerta.svg`. Estado: Implementado; probado el 16/10 con el servidor armado desde cero (escenario 16 y carpeta 5 de Postman). La pantalla `/puerta` la prueba en el navegador `frontend/e2e/puerta.spec.ts`. Sale de `EscaneosResource`, `ServicioDeValidacion`, `ResultadoEscaneo`, `ClienteDeVentas`, `EntradasResource` (`POST /entradas/usos`), `UsoDeEntradas` y `EntradaRepositoryJpa.marcarUsada`.*

Paso a paso:

1. El personal de puerta (rol `staff`, pantalla `/puerta`) manda `POST /api/v1/validacion/escaneos` con el código QR, leído o tipeado, y el evento que controla esa puerta. El código va en el cuerpo y no en la ruta, para que no quede en los logs de acceso.
2. `ServicioDeValidacion` (stateless, `NOT_SUPPORTED`) le pide a Ventas, a través de `ClienteDeVentas`, `POST /api/v1/entradas/usos` con token de rol `servicio`, el código y el evento.
3. `UsoDeEntradas` busca la entrada por código y, si es de ese evento, la marca como usada con un `UPDATE` condicional: pasa a `USADA` solo si sigue `VALIDA`. La base devuelve cuántas filas cambiaron, 1 o 0.
4. Con 1 fila, Ventas responde 200 con la entrada `USADA`, su sector y el titular (nombre y DNI). Validación guarda el escaneo `VALIDA`.
5. La puerta recibe 201 con `resultado` `VALIDA`, `puedeEntrar` `true`, nombre y DNI, y los contrasta con el documento de la persona.

**Alternativas.**

- **El código no existe:** Ventas responde 404 y la puerta recibe 201 con `INEXISTENTE`.
- **Entrada de otro evento, ya usada, o en reventa o anulada:** Ventas responde 409 `entrada-de-otro-evento`, `entrada-ya-usada` o `entrada-no-habilitada`, y la puerta recibe 201 con `OTRO_EVENTO`, `YA_USADA` (con el escaneo anterior que la usó, en `usoAnterior`) o `NO_HABILITADA`. Una entrada de otro evento no se marca: tiene que seguir sirviendo en el suyo. Un 409 con un tipo desconocido cuenta como `NO_HABILITADA`: ante la duda, no se deja entrar.
- **Ventas no responde:** (6) se guarda el escaneo `SIN_RESPUESTA` y la puerta recibe 504 `sin-respuesta` para escanear de nuevo. Si el primer intento sí llegó a usar la entrada, el escaneo siguiente da `YA_USADA` con `usoAnterior` apuntando a ese intento, y el mensaje le avisa al personal que puede ser el mismo titular.

**Por qué 201 con cualquier resultado.** Lo que se crea es el renglón del registro de la puerta; "no puede entrar" es un dato y no un error HTTP. Es el mismo criterio que Pagos con un cobro `RECHAZADO`.

**Dos puertas a la vez.** Si dos puertas escanean la misma entrada al mismo tiempo, el `UPDATE` condicional deja pasar a una sola y la otra recibe `entrada-ya-usada`. Lo prueba `UsoEnH2Test` con dos conexiones contra H2 en modo PostgreSQL.

Sin token, 401 `no-autenticado`; con un rol que no es `staff`, 403 `sin-permiso`. El registro se consulta con `GET /api/v1/validacion/escaneos?eventoId=` (roles `staff` u `organizer`), del más nuevo al más viejo y con el código recortado a sus últimos 6 caracteres (`codigoParcial`). Decisión declarada (sección 21): el código QR es el UUID al azar que genera Ventas, opaco pero sin firma. Desde el 23/10 el registro lo ven solo el staff asignado a esa puerta y el organizador dueño del evento (4.6).

## 13.9 Casos de uso

![Diagrama de casos de uso de Palco](diagramas/casos-de-uso.svg)

*Fuente: `docs/documentacion/diagramas/casos-de-uso.svg`. Estado al 25/11: los quince casos están implementados, tienen pantalla en el frontend y los prueba el CI (la prueba de cada uno, en la tabla de la sección 1.4).*

La cátedra respondió el 16/10 que la documentación es técnica e incluye diagramas de casos de uso y casos de uso. El diagrama muestra los actores Comprador, Organizador y Personal de puerta, y los sistemas externos ARCA (simulado, por SOAP) y la pasarela de pago (`PagosFake`), con los quince casos CU-01 a CU-15. La descripción de cada uno (actor, precondición, flujo principal, flujos alternativos con los códigos y tipos de error reales, postcondición y estado) está en `docs/documentacion/casos-de-uso.md`. El manual de usuario está en `docs/manual/manual-de-usuario.md` y el prototipo navegable de las pantallas principales lo aprobó la cátedra.

## 13.10 Otros diagramas

Los talleres de las clases 6, 8 y 9 (`docs/clases/`) tienen diagramas propios en PNG, que no son de secuencia o son anteriores a la implementación. El de la integración SOA vigente es `docs/documentacion/diagramas/integracion-soa.svg` (secciones 3.6 y 8.1).

Además de los de esta sección, `docs/documentacion/diagramas/` tiene los de la autenticación, la reventa, la cancelación de un evento, la falla después del cobro y el asistente con IA. Los cuatro primeros los usa el documento técnico de la Entrega Obligatoria N.º 2 (`docs/entrega-obligatoria-2/Palco_Documento_Tecnico.md`, anexo A), y el del asistente, el documento técnico del 3.er check point y la documentación final:

| Integración | Diagrama | Qué muestra |
|---|---|---|
| S2 | `secuencia-seguridad-jwt.svg` | Ingreso, token JWT y llamadas entre servicios con el token de rol `servicio` |
| S3 | `secuencia-reventa-rest.svg` | Publicar, comprar, cobrar y transferir en la reventa (7.10) |
| I6 | `secuencia-cancelacion-topico.svg` | Anulación, reembolso y aviso cuando se cancela un evento (10.8) |
| I1 y S1 | `secuencia-falla-conciliacion.svg` | Falla después del cobro: rollback y conciliación (7.8) |
| I7 | `secuencia-asistente-ia.svg` | Una pregunta al asistente: apagado, guardrails de entrada, el modelo con los eventos del catálogo y 5 s como máximo, fallback y guardrails de salida (4.10) |

# 14. Seguridad

La seguridad se resuelve en tres lugares: el servicio de Usuarios (cuentas, contraseñas y emisión de tokens), la
librería compartida `backend/common` (verificación de tokens, CORS, formato de errores, protección del log) y la
configuración de WildFly (`backend/servidor/src/main/wildfly/configurar.cli`). Esta sección describe el código tal
como está hoy y marca lo débil. Los cambios de seguridad del 16/10 (límite de intentos de login, id de error en el
500, PBKDF2 a 600.000 iteraciones, 401 por token inválido en Problem Details y con CORS, y secreto de servicios
propio en desarrollo) están en el repo desde el 16/10 (commits df9f9ed a 0cbb2e9) y se verificaron ese día. Las
operaciones de Reventa, de la validación en puerta, de la consulta de facturas y las internas que esas funciones
agregaron en Ventas y en Pagos tienen sus roles y su control de dueño en 14.3, y el cliente SOAP de Facturación cubre
SEC-I01 (14.9). Estado de todo eso: **Implementado**. Pasan los tests (577, 0 fallas) y, en vivo, el 16/10 a las
11:04 con el servidor armado desde cero, los escenarios 14 a 17 y las carpetas 5 a 7 de Postman (sección 16.6).

## 14.1 Autenticación: registro, verificación del email y login

| Paso | Operación | Qué hace | Estado |
|---|---|---|---|
| Registro | `POST /api/usuarios/registro` (`@PermitAll`) | Crea la cuenta con la contraseña hasheada y genera un código de verificación. Edad mínima: 16 años (`EDAD_MINIMA`). | Implementado |
| Envío del código | Sin operación propia: lo hacen el registro y el reenvío | `ServicioDeUsuarios.enviarCodigoNuevo` lo manda por email con `Correos`: por SMTP si está `PALCO_SMTP_HOST` (`CorreoSmtp`) y, si no, al log del servidor (`CorreoAlLog`), donde el renglón trae el código solo con `PALCO_CORREO_LOG_COMPLETO=true` (14.6). | Simulado sin servidor SMTP |
| Verificación | `POST /api/usuarios/verificacion` (`@PermitAll`) | Compara el hash SHA-256 del código en tiempo constante. Vence a los 15 minutos (`VIGENCIA_CODIGO`) y admite 5 intentos (`MAX_INTENTOS_CODIGO`); después hay que pedir otro. Al verificar, la cuenta queda con el email y la identidad verificados, así que ya puede comprar. | Implementado |
| Reenvío | `POST /api/usuarios/verificacion/reenvio` (`@PermitAll`) | Renueva el código, con 30 segundos de espera mínima entre envíos (`ESPERA_REENVIO`). | Implementado |
| Login | `POST /api/usuarios/login` (`@PermitAll`) | Devuelve un JWT. Si el email no existe, igual calcula un hash contra `HASH_FALSO` para que el tiempo de respuesta no delate qué emails están registrados, y el mensaje de error es siempre el mismo ("Revisá el email y la contraseña."). | Implementado |
| Perfil | `GET /api/usuarios/yo` (`user`, `organizer`, `staff`) | Datos de la cuenta del token. El frontend lo usa para confirmar la sesión. | Implementado |
| Límite de intentos de login | `POST /api/usuarios/login` | Con 5 fallidos en 15 minutos para un mismo email (`MAX_INTENTOS_LOGIN`, `VENTANA_INTENTOS_LOGIN`), el pedido siguiente responde 429 Problem Details (`/problemas/demasiados-intentos`) con `Retry-After` en segundos, aunque la contraseña sea la correcta y también para un email que no existe. Un login correcto borra los fallidos de ese email. | Implementado y probado (tests y en vivo) |

**Límite de intentos de login (SEC-W04).** El control va antes de verificar la contraseña: si la correcta entrara
durante el bloqueo, el sexto intento acertado le abriría la cuenta a quien está probando. Se cuenta por email,
exista o no la cuenta, con el mismo texto en el 401 y en el 429, y el 429 no hace el cálculo PBKDF2, así que no
delata qué emails están registrados. La ventana es deslizante: el bloqueo se levanta cuando el más viejo de los 5
fallidos sale de los 15 minutos, y eso es lo que informa `Retry-After` (redondeado hacia arriba, nunca menos de 1);
los intentos bloqueados no se anotan, para que el bloqueo no se estire con cada reintento. Los fallidos se guardan
en la base, en la tabla `intento_login` (migración `V4__intentos_de_login.sql` de Usuarios), a través del
repositorio `datos/IntentoLoginRepository` (implementado por `IntentoLoginRepositoryJpa`, un Session Bean
stateless que se suma a la transacción del login), y no en memoria: así el límite vale igual con varias instancias y no se reinicia
con un redespliegue. La tabla guarda el SHA-256 del email normalizado y no el email, porque también anota emails sin
cuenta. Como los errores de negocio deshacen la transacción, el 401 del login es `CredencialesIncorrectas`, con
`@ApplicationException(rollback = false)`, para que el intento fallido quede guardado. Las constantes están en
`ServicioDeUsuarios` y no en `ReglasDeNegocio` porque son de seguridad de Usuarios, como `MAX_INTENTOS_CODIGO`, y no
reglas del negocio de venta de entradas. Lo prueban `LimiteDeIntentosDeLoginTest` (con un reloj inyectable),
`MigracionesEnH2Test`, `RepositoriosEnH2Test`, `ErroresDeNegocioTest` y el request SEC-W04 de Postman; en vivo, el 16/10, seis logins
fallidos de un mismo email dieron cinco 401 y un 429 con `Retry-After: 898`. Riesgo aceptado (DEF-41): el bloqueo es
por email, así que alguien que conoce el email de otra persona puede bloquearle el login 15 minutos. Es lo que pide
CA-02.3 de HU-02, y contar por IP necesita la dirección real del cliente, que detrás de nginx no se tiene de forma
segura. Lo acotan que el bloqueo dura como mucho 15 minutos, que `Retry-After` dice cuánto falta y que la dueña de la
cuenta entra enseguida con "¿Olvidaste tu contraseña?", que borra los fallidos de su email.

**Contraseñas.** `usuarios/seguridad/Contrasenas.java` usa PBKDF2-HMAC-SHA256, incluido en Java (sin
dependencias), con sal aleatoria de 16 bytes, 600.000 iteraciones (lo que recomienda hoy OWASP para PBKDF2-SHA256;
antes eran 310.000) y hash de 256 bits. Se guarda como `pbkdf2-sha256$<iteraciones>$<sal>$<hash>` y la verificación
compara en tiempo constante (`MessageDigest.isEqual`). Se eligió PBKDF2 porque viene en la JVM; bcrypt o Argon2
pedían una librería más. Como el formato guarda las iteraciones de cada hash, los hashes viejos de 310.000 siguen
verificando y subirlas no obligó a migrar datos. El hash falso del login (`HASH_FALSO`) se arma con
`Contrasenas.hashear`, así que cuesta lo mismo que uno nuevo; la migración
`V5__cuentas_de_prueba_con_600000_iteraciones.sql` rehashea las cuentas de prueba a 600.000, con la misma
contraseña, para que no verifiquen en la mitad de tiempo que el hash falso y esa diferencia no deje ver qué emails
existen. Desde el 24/11 (DEF-44), una cuenta registrada antes del cambio pasa a 600.000 en su primer ingreso
correcto: `ServicioDeUsuarios.iniciarSesion`, después de verificar la contraseña y el email, guarda un hash nuevo si
`Contrasenas.tieneMenosIteraciones` dice que el guardado tiene menos. Es el único momento en que se tiene la
contraseña, y cuesta un cálculo PBKDF2 más, una sola vez por cuenta (`IteracionesAlIniciarSesionEnH2Test`). La
contraseña nunca vuelve en una respuesta: los DTO de `usuarios/api/Dtos.java` no la incluyen.

## 14.2 Tokens JWT

| Aspecto | Cómo está | Archivo |
|---|---|---|
| Algoritmo | RS256 (`SHA256withRSA`), con `kid` en la cabecera | `usuarios/seguridad/Tokens.java` |
| Claves | Par RSA de 2048 bits. Si existe `PALCO_JWT_CLAVE_PRIVADA` (PKCS#8 en base64) se usa esa; si no, se genera una al desplegar y los tokens anteriores dejan de valer. | `usuarios/seguridad/ClavesJwt.java` |
| Publicación | Clave pública como JWKS en `GET /api/usuarios/jwks` (`@PermitAll`) | `usuarios/api/UsuariosResource.java` |
| Verificación | Cada servicio usa MicroProfile JWT: descarga el JWKS de Usuarios y exige el emisor `palco`. Los roles salen del claim `groups`. | `common/src/main/resources/META-INF/microprofile-config.properties` |
| Duración del token de usuario | 8 horas (`DURACION_SESION`). Sin refresh token ni revocación al cerrar sesión. Vigencia decidida y justificada el 16/10 (ver abajo). | `usuarios/negocio/ServicioDeUsuarios.java` |
| Identidad en el código | `Sesion.usuarioId` toma el `sub` del token; ningún recurso recibe el id del usuario por parámetro. | `common/seguridad/Sesion.java` |

**Por qué RS256 y no HS256.** Con clave asimétrica solo Usuarios puede firmar; los otros ocho servicios verifican
con la clave pública y no guardan ningún secreto. Con HS256 cada servicio tendría la clave con la que se fabrican
tokens.

**Token de servicio entre servicios.** Cuando un servicio llama a otro (por ejemplo, Ventas a Pagos) usa el esquema
de credenciales de cliente: presenta un secreto compartido en `POST /api/usuarios/servicios/token` y Usuarios le
firma un token con el rol `servicio` que dura 5 minutos (`DURACION_TOKEN_DE_SERVICIO`).
`common/seguridad/TokenDeServicio.java` lo guarda, lo renueva cuando le quedan menos de 30 segundos y, si el
servicio llamado responde 401 (pasa cuando se redespliega Usuarios y cambia la clave), lo invalida y reintenta una
sola vez. Es un bean `@ApplicationScoped` y no un Session Bean: hay un único token por servicio, compartido por
todos los pedidos (no es la conversación de un usuario, y un stateless no podría recordarlo), y sus métodos son
`synchronized`. Las operaciones internas llevan `@RolesAllowed("servicio")`: dieciocho en el código al 20/11 (dos
en Usuarios, dos en Eventos, cuatro en Pagos, seis en Ventas y cuatro en Notificaciones), más la consulta de
facturas, que admite `user` o `servicio` (14.3). Un usuario común no puede usarlas aunque conozca la ruta. Usan
este token Ventas (para Eventos y Pagos), Validación (`ClienteDeVentas`, para usar una entrada;
`ClienteDeEventos`, para el dueño de un evento, y `ClienteDeUsuarios`, para el rol de una cuenta), Reventa (su
`ClienteRest`, para Ventas y Pagos) y Notificaciones (`ClienteDeUsuarios`, para el email del comprador). Los clientes
que se sumaron después de los de Ventas repiten el mismo criterio: ante un 401, piden un token nuevo y reintentan
una sola vez.

**Vigencia de la sesión: 8 horas, sin refresh token (decidido el 16/10).** Se evaluó bajar el token a 15-30
minutos con un refresh token revocable, y el grupo decidió mantener las 8 horas y justificarlas. Una sesión de compra
o de un organizador dura una jornada. El token se firma con RS256 y cada servicio lo valida con la clave pública, sin
guardar ningún secreto; si no se define `PALCO_JWT_CLAVE_PRIVADA`, la clave se regenera al redesplegar Usuarios y
todos los tokens anteriores dejan de valer. El token entre servicios, que es el que habilita las operaciones
internas, dura 5 minutos. Un token robado sirve hasta que vence, porque no hay revocación; ese riesgo se acepta para
el alcance del trabajo práctico (DEF-38). El criterio SEC-W05 pasa a "parcial, vigencia justificada" (14.9) y
el request SEC-W05c de Postman pasa a comprobar que el token dura 8 horas.

**Secreto de servicios: requisito de despliegue (decidido el 16/10) y sin valor por defecto (desde el 20/11).** El
código no trae un secreto de reserva: uno escrito en el repo lo conocería cualquiera que lo lea, y con él podría
pedir un token de servicio. Si falta `PALCO_SECRETO_SERVICIOS`, cada servicio arranca con uno al azar de 32 bytes,
distinto en cada WAR y en cada arranque, que nadie conoce, y lo avisa en el log como error (`SEVERE`): ese servicio
no consigue tokens de servicio y, si es Usuarios, no se los entrega a nadie, así que las llamadas entre servicios
fallan en lugar de aceptar un secreto público (`TokenDeServicio.leerSecreto`). Es el criterio de la clave JWT, que si
falta también se genera al azar, con una diferencia: sin el secreto el sistema no anda, y por eso el aviso es un
error. Se evaluó que un servidor fuera de desarrollo no arranque sin secreto (una variable `PALCO_ENTORNO` y una
comprobación al desplegar Usuarios) y no se implementa: tocaba `backend/common` para algo que se resuelve en el
despliegue. Fuera de desarrollo es un requisito de despliegue definir `PALCO_SECRETO_SERVICIOS` con el mismo valor en
todos los servicios. En desarrollo, `dev.sh` usa el de la variable de entorno o el de `backend/.env` (que carga y que
no se versiona) y, si no hay ninguno, genera uno con `openssl rand -hex 32` para ese arranque; el que usa el servidor
queda en `backend/servidor/target/secreto-servicios`, que git ignora. En el compose lo genera
`backend/docker/arrancar.sh`, y en el CI cada corrida genera el suyo. Los guiones de `scripts/pruebas/` lo toman de
la variable, de `backend/.env` o de ese archivo, y la colección de Postman, de la variable `secretoServicios`, que
viene vacía (17.4). Hasta el 19/11, si faltaba la variable el secreto valía `palco-desarrollo`, que estaba escrito en
el repo; se corrigió el 20/11. En la corrida del 16/10 el log no mostró el aviso de ese valor.

## 14.3 Autorización declarativa por rol

Cada operación de cada recurso lleva `@RolesAllowed` o `@PermitAll`, y `configurar.cli` activa
`resteasy-role-based-security` para que esas anotaciones se apliquen. Roles: `user` (comprador), `organizer`,
`staff` (puerta) y `servicio` (llamadas entre servicios).

| Operaciones | Rol |
|---|---|
| Registro, verificación, reenvío, login, JWKS, pedido del token de servicio, catálogo y detalle de eventos, publicaciones activas de un evento (`GET /api/v1/reventas?eventoId=`) | `@PermitAll` |
| Compra, pago, cancelación, `GET /api/v1/ventas`, `GET /api/v1/entradas/{id}` | `user` |
| Reventa: mis publicaciones (`GET /api/v1/reventas/mias`), publicar (`POST /api/v1/reventas`), retirar (`DELETE /api/v1/reventas/{id}`) y comprar (`POST /api/v1/reventas/{id}/compra`) | `user` |
| Factura de una compra (`GET /api/v1/facturas/{ventaId}`) | `user` o `servicio` |
| Alta de eventos (`POST /api/eventos`) y los eventos propios (`GET /api/eventos/mios`) | `organizer` |
| Escanear una entrada en la puerta (`POST /api/v1/validacion/escaneos`) | `staff` |
| Registro de la puerta (`GET /api/v1/validacion/escaneos?eventoId=` y `GET /api/v1/validacion/escaneos/{id}`) | `staff` asignado a esa puerta u `organizer` dueño del evento |
| Puertas propias (`GET /api/v1/validacion/puertas/mias`) | `staff` |
| Equipo de puerta de un evento propio (`GET`, `PUT` y `DELETE` en `/api/v1/validacion/puertas`) | `organizer` dueño del evento |
| Operaciones internas: un usuario por su id y el rol de una cuenta por su email en Usuarios (`GET /api/usuarios/{usuarioId}` y `GET /api/usuarios/cuentas`); el dueño de un evento y el cupo de un sector en Eventos; cobrar, consultar un pago por id, consultar un pago por clave (`GET /api/v1/pagos?claveIdempotencia=`) y reembolsar en Pagos; usar una entrada en Ventas (`POST /api/v1/entradas/usos`); consultar, publicar, retirar y transferir una entrada en Ventas y, desde el 20/11, controlar una compra en la reventa (`/api/v1/operaciones-de-reventa/...`); endpoints de prueba y auditoría de Notificaciones | `servicio` |
| `GET /api/usuarios/yo` | `user`, `organizer`, `staff` |
| `health` de cada servicio | Sin anotación (abierto) |

Por qué cada rol nuevo:

- **`staff` en la puerta.** Escanear usa la entrada (la pasa a `USADA`), así que solo lo hace el personal de puerta.
  Quién escanea sale del token y queda en cada renglón del registro: el id del `sub` y, desde el 20/11, el email del
  `upn` (DEF-32, 4.6). El organizador puede leer el registro
  de su puerta, pero no escanear. Desde el 23/10 el rol no alcanza: el staff tiene que estar asignado a la puerta
  del evento y el organizador tiene que ser su dueño (4.6). Un evento ajeno responde 404 para el organizador y 403
  `puerta-no-asignada` para el staff, que necesita saber que el evento existe para pedir que lo asignen.
- **`user` en la reventa.** El vendedor y el comprador salen siempre del token (`Sesion.usuarioId`); ningún pedido
  trae el id de una persona. El listado de un evento es público, como el catálogo, y no dice quién publicó.
- **`servicio` en lo interno.** Las operaciones de `/api/v1/operaciones-de-reventa` y `POST /api/v1/entradas/usos`
  cambian el estado de una entrada sin pasar por las reglas de Reventa o de Validación, así que solo las llama otro
  servicio. Reciben el id del vendedor o del comprador en el pedido porque el token de servicio no representa a
  ninguna persona; quien lo puso ahí es Reventa, que lo sacó del token del usuario. La consulta de Pagos por clave
  la usa la conciliación de Ventas para saber cómo terminó un cobro sin resultado: solo lee, pero devuelve un pago, y
  por eso tampoco la puede usar un usuario.
- **`user` o `servicio` en facturas.** El comprador consulta la factura de su compra; otro servicio, cualquiera.

Además de quién puede usar cada operación, se controla de quién es el dato. Una venta, una entrada, una publicación o
una factura de otra persona responde 404, igual que si no existiera, así no se puede averiguar qué ids existen
(BOLA):

| Dato | Control | Dónde | Test |
|---|---|---|---|
| Ventas y entradas | Filtro por el `compradorId` del token | `ComprasEnCurso` | `ComprasEnCursoTest.unaVentaAjenaRespondeLoMismoQueUnaQueNoExiste` |
| Entrada que se publica, se retira o se transfiere | El UPDATE condicional exige que la entrada sea de una venta del vendedor (subconsulta sobre `venta.compradorId`); ajena, 404 | `ReventaDeEntradasRepositoryJpa`, en Ventas | `ReventaDeEntradasEnH2Test.publicarYRetirarCambianElEstadoYUnaAjenaEsNoEncontrada` |
| Publicación que se retira | Tiene que ser del vendedor del token; ajena, 404 | `ServicioDeReventa.propia` | `ServicioDeReventaTest.retirarUnaAjenaEsNoEncontradaYUnaPropiaLaDevuelveAValida` |
| Publicación propia que se quiere comprar | 409 `compra-propia` | `ServicioDeReventa.comprar` | `ServicioDeReventaTest.nadieCompraSuPropiaPublicacion` |
| Factura | Con rol `user`, el `sub` tiene que ser el comprador guardado del mensaje `PAGO_CONFIRMADO`; ajena, inexistente o todavía sin facturar dan el mismo 404. Desde el 20/11, el 409 `comprobante-rechazado` de una venta que ARCA rechazó también lo recibe solo su comprador: a otro le sigue respondiendo 404 | `FacturasResource` y `ServicioDeFacturacion` | `FacturasResourceTest`, `ServicioDeFacturacionTest.otroUsuarioNoSeEnteraDeQueUnaVentaAjenaFueRechazada` |
| Eventos del panel (desde el 23/10) | `GET /api/eventos/mios` devuelve solo los eventos cuyo `organizador_id` es el `sub` del token | `ServicioDeEventos.listarDelOrganizador` | `CupoEnH2Test.losEventosDelOrganizadorSonSoloLosSuyosOrdenadosPorFecha` |
| Registro de la puerta (desde el 23/10) | Con rol `organizer`, el evento tiene que ser suyo, y el dueño lo sabe Eventos (`GET /api/eventos/{eventoId}/organizador`, rol `servicio`); uno ajeno responde 404, igual que uno que no existe. Con rol `staff`, la cuenta tiene que tener asignada la puerta de ese evento; si no, 403 `puerta-no-asignada` | `ServicioDeValidacion.listar` y `obtener` | `ServicioDeValidacionTest.elRegistroLoVenElStaffDeEsaPuertaYElDuenoDelEvento` y `unEventoQueNoExisteOSinDuenoOConUnIdRaroEsNoEncontradoParaElOrganizador` |
| Equipo de puerta (desde el 23/10) | Asignar, quitar y listar las cuentas de un evento es solo del organizador dueño; para otro el evento no existe (404) | `ServicioDeValidacion.asignar`, `quitar` y `equipoDePuerta` | `ServicioDeValidacionTest.otroOrganizadorNoVeNiCambiaLaPuertaDeUnEventoAjeno` y `elDuenoVeElEquipoYQuitaUnaCuenta` |

La asignación comprueba el rol de la cuenta: Validación se lo pide a Usuarios (`GET /api/usuarios/cuentas`, rol
`servicio`), y un email que no es de una cuenta `staff` verificada responde 422 `no-es-de-puerta` y no se asigna
(4.6). Así el organizador se entera si se equivocó al escribirlo.

**Una operación sin anotación no pasa los tests** (desde el 24/11, DEF-43). Con seguridad declarativa, un método
nuevo sin anotación quedaría abierto. Por eso cada uno de los nueve servicios con recursos JAX-RS tiene
`SeguridadDeclarativaTest` en su paquete `api`: busca por reflexión las clases con `@Path` entre las compiladas del
servicio, toma los métodos con una anotación marcada con `@HttpMethod` y falla con la lista de las operaciones sin
`@RolesAllowed`, `@PermitAll` ni `@DenyAll`, ni en el método ni en la clase. Un recurso o una operación nuevos entran
solos, y cada copia exige que aparezcan los recursos de su servicio (13 en total), así una búsqueda vacía no pasa en
verde. La excepción
escrita es el `health` (`common/rest/HealthResource`), un método sin anotación a propósito: queda abierto, que es lo
que necesita el control de salud del compose. Hay una copia por servicio porque los servicios no comparten clases,
tampoco las de prueba. Siguen los tests de cada recurso (por ejemplo,
`EscaneosResourceTest.escanearEsSoloParaElPersonalDePuerta` y
`FacturasResourceTest.laConsultaEsParaCompradoresYServiciosYEsLaUnicaOperacion`). El ARCA simulado (`/legado/arca`)
no tiene autenticación: es un WAR aparte que no depende de `backend/common`, y nginx no lo publica (4.9, DEF-45).

**Endpoints de prueba de Notificaciones (decidido el 16/10).** `POST /api/notificaciones/pruebas/pago-confirmado`,
`.../pruebas/pago-confirmado-con-falla` y `.../pruebas/mensaje-invalido` publican mensajes sin hacer una compra. Se
mantienen porque los usan la demo, la evidencia y la colección de Postman. Son solo de desarrollo y piden rol
`servicio`, así que un usuario u organizador no puede publicar pagos falsos (riesgo aceptado, sección 21).

## 14.4 CORS

`common/rest/CorsFilter.java` agrega las cabeceras CORS solo si el `Origin` del pedido está en
`PALCO_CORS_ORIGENES` (separados por coma; sin la variable, `http://localhost:5173`, el servidor de Vite de
`dev.sh`). Desde el 25/11 (DEF-42) el `docker-compose.yml` la define solo con la URL pública
(`PALCO_URL_PUBLICA`, por defecto `https://localhost`): el origen de Vite ya no se acepta por defecto contra el compose,
y quien use `npm run dev` contra ese backend define la variable con los dos orígenes. Permite las
cabeceras `Authorization`, `Idempotency-Key`, `If-None-Match` e `If-Match`, y expone `Location`, `ETag` y `Retry-After`. No se usa el comodín
`*`: con credenciales el navegador no lo acepta y, además, abriría la API a cualquier sitio.

**El 401 por token inválido.** Cuando el token no se puede verificar (firma alterada, mal formado), lo rechaza el
mecanismo MP-JWT del servidor antes de llegar a la aplicación, así que ni `CorsFilter` ni `ManejadoresDeErrores`
intervienen. Hasta el 16/10 ese 401 salía en `text/html`, sin CORS y sin `WWW-Authenticate`, y el navegador lo
informaba como un error de red (se había observado el 14/10). Ahora `backend/common` trae un
`META-INF/web-fragment.xml` que registra una página de error para el 401, `ErrorDeAutenticacionServlet`: responde
`application/problem+json` con `type` `/problemas/token-invalido`, `WWW-Authenticate: Bearer error="invalid_token"`
(RFC 6750) y las cabeceras CORS, que arma el mismo `CorsFilter.cabecerasPara`, así hay una sola lista de orígenes.
Como viene en el jar compartido, vale para todos los servicios sin tocar sus WAR. El 401 sin token y el 403 por rol ya
salían bien, porque los arma la aplicación. Estado: implementado y probado. Lo prueban
`ErrorDeAutenticacionServletTest` y `CorsFilterTest` y, en vivo el 16/10, `GET /api/v1/ventas` con un token
inválido y `Origin: http://localhost:5173` respondió 401, `application/problem+json`, `type`
`/problemas/token-invalido`, `WWW-Authenticate: Bearer error="invalid_token"` y
`Access-Control-Allow-Origin: http://localhost:5173`. Qué cambia para el frontend, en 15.4, punto 7.

## 14.5 Formato de errores

Todos los errores de la aplicación salen como Problem Details (RFC 9457, `application/problem+json`) desde
`common/rest/ManejadoresDeErrores.java`, que traduce cada excepción de `ErroresDeNegocio` a su código HTTP y a su
`type`. El 401 agrega `WWW-Authenticate: Bearer`, y un 403 sin usuario autenticado se convierte en 401. El 429 del
límite de intentos de login agrega `Retry-After` (14.1). El 401 por token inválido, que emite el servidor antes de
llegar a la aplicación, sale con el mismo formato desde `ErrorDeAutenticacionServlet` (14.4). Cualquier otra
excepción termina en un 500 genérico ("Ocurrió un error inesperado."): el detalle va solo al log, así que ninguna
respuesta lleva un stack trace. La validación corre antes que la lógica: por ejemplo, `VentasResource` rechaza una
clave de idempotencia inválida con 400 antes de reservar cupo.

**Mensajes de validación en español (desde el 20/11).** El 400 de un cuerpo inválido lleva en `errores`, por campo,
el mensaje de la anotación de Bean Validation que falló (`ManejadoresDeErrores.Validacion`). Una anotación sin
`message` usa el texto por defecto de Bean Validation, que sale en el idioma del pedido o del servidor: con el
servidor en inglés, "size must be between 0 and 30". Por eso cada anotación de los DTO y de los parámetros de los
recursos de Usuarios y de Eventos lleva su mensaje en español, como los `@Size` del nombre, el apellido, el email y
el teléfono en `usuarios/api/Dtos.java` y los del título, el lugar, la descripción, la URL de la imagen, el sector y el motivo de la
cancelación en `eventos/api/Dtos.java`. `MensajesDeValidacionTest`, en cada uno de los dos servicios, arma el
validador en inglés y falla con el nombre de la anotación y del campo si alguna validación de la API usa el mensaje
por defecto.

**Errores de validación en un orden fijo (desde el 24/11, DEF-62).** `ManejadoresDeErrores.Validacion` recorre las
violaciones ordenadas por campo y, dentro de un campo, por mensaje: `errores` sale en orden alfabético y el mismo
pedido recibe siempre el mismo cuerpo. Antes salía en el orden del conjunto que devuelve Bean Validation, que cambia
entre pedidos iguales, y por eso ZAP daba un falso positivo de inyección SQL en el registro. Lo prueba
`ErroresDeValidacionTest`, en `common`.

**Caracteres de control en los datos que van a la base.** PostgreSQL no acepta el carácter nulo en un texto, y sin
control la respuesta era un 500. Cada servicio lo rechaza antes de la primera consulta: Usuarios en el email, el
teléfono y el id (DEF-59), Ventas en las operaciones de reventa (DEF-60), Validación en el código QR, el evento y el
email (DEF-61) y, desde el 25/11, Pagos en la clave, la venta, el medio de pago y el motivo (DEF-77): 400
`datos-invalidos`, o 404 sin consultar cuando el dato es un id de la ruta. Los encontró la prueba de contrato (16.8).

**Id de error en el 500.** `ManejadoresDeErrores.Inesperado` genera un id nuevo por cada error inesperado (un UUID
al azar, que no dice nada del error), lo escribe en el log en el mismo renglón que la traza
(`Error no manejado (idError <id>)`) y lo devuelve como miembro de extensión `idError` del Problem. Quien reporta el
error pasa el id y la traza se encuentra en el log sin mandarle al cliente ningún detalle interno. Los demás errores
no cambian: `idError` solo se escribe cuando tiene valor. Estado: implementado y probado. Lo prueba
`ErrorInesperadoTest` (el id es un UUID, coincide con el del log, el log lleva la traza, dos errores tienen ids
distintos y un 409 no trae `idError`) y, en vivo el 16/10, el escenario 14 (sección 16.2): una falla provocada después
del cobro respondió 500 con `idError` y sin ningún detalle interno.

## 14.6 Protección del log

- **Renglones falsos.** `common/Textos.enUnaLinea` cambia los caracteres de control y los separadores de línea de
  Unicode por espacios. Se usa en cada línea de log que incluye un dato que vino de afuera (un nombre, un email, un
  id), para que nadie pueda hacer aparecer un renglón inventado en el log, que se usa como evidencia. Ventas, además,
  rechaza nombres e ids que traigan esos caracteres.
- **Datos personales.** Los logs no escriben el DNI (criterio SEC-D01, por la Ley 25.326 de protección de datos
  personales). Desde el 20/11, los renglones del correo y del asistente tampoco escriben el email de nadie fuera del
  modo de desarrollo de abajo: el destinatario de un email que va al log o que no se pudo mandar queda enmascarado (`Email.enmascarar`: la primera
  letra y el dominio, `a***@palco.test`), y `CorreoSmtp` enmascara también el motivo de un envío que falló, porque la
  respuesta del servidor suele repetir la dirección, y no escribe la traza, que la repetiría sin enmascarar. El
  asistente registra a quien pregunta por su id interno, no por su email (4.10).
- **El cuerpo de los emails.** Sin servidor SMTP (`PALCO_SMTP_HOST`), `CorreoAlLog` escribe cada email en el log del
  servidor. Por defecto el renglón trae solo el destinatario enmascarado y el asunto; el cuerpo, que lleva el nombre
  de quien lo recibe, los de los titulares y los códigos de verificación y de reinicio de contraseña, no se escribe.
  Con `PALCO_CORREO_LOG_COMPLETO=true` escribe el email entero, con la dirección tal cual: es el modo de desarrollo,
  porque sin SMTP el log es la única bandeja de entrada y sin el código no se puede verificar una cuenta nueva. Lo
  prende `backend/dev.sh`. El `docker-compose.yml` lo deja en `false` por defecto, como en un despliegue (DEF-36), y
  lo prende solo si se lo levanta con `PALCO_CORREO_LOG_COMPLETO=true` en el entorno o en el `.env` de la raíz. Así lo
  levantan los jobs del CI que leen el código de ese renglón (integración, E2E, Postman, pruebas de seguridad y
  carga), y así hace falta para registrar una cuenta nueva sin SMTP. En un despliegue no se define,
  y cualquier valor que no sea `true` cuenta como apagado. Lo prueban `CorreoAlLogTest`, `EmailTest` y
  `CorreoSmtpTest`.
- **Código QR en la puerta.** El código viaja en el cuerpo de `POST /api/v1/validacion/escaneos` y no en la ruta,
  para que no quede en los logs de acceso, y Validación no lo escribe en sus logs. El registro de escaneos guarda el
  código completo pero lo devuelve recortado a sus últimos 6 caracteres (`codigoParcial`), porque un código de otro
  evento escaneado por error sigue sirviendo en el suyo. El registro no guarda nombre ni DNI: se llega a la entrada
  por `entradaId`. La respuesta del escaneo válido sí trae el nombre y el DNI del titular, porque el personal tiene
  que contrastarlos con el documento.
- **Excepción a propósito.** En desarrollo, el código de verificación del email se escribe en el log porque es la
  forma simulada de mandarlo (`PALCO_CORREO_LOG_COMPLETO=true`). En un despliegue, los emails salen por SMTP
  (`CorreoSmtp`) y, si faltara el servidor, el log queda sin el cuerpo.

## 14.7 Secretos y variables de entorno

Ningún secreto de producción está en el código: todo sale de variables de entorno, y `.env` está en `.gitignore`.

| Variable | Para qué | Si falta |
|---|---|---|
| `PALCO_JWT_CLAVE_PRIVADA` | Clave con que Usuarios firma los tokens | Se genera una al desplegar: tras cada reinicio hay que volver a iniciar sesión |
| `PALCO_SECRETO_SERVICIOS` | Secreto para pedir el token de servicio. En desarrollo, `dev.sh` lo toma del entorno o de `backend/.env` y, si no está, genera uno para ese arranque | No hay valor por defecto: cada servicio usa uno al azar, no puede llamar a los otros y el log lo avisa como error. Fuera de desarrollo, definirla con el mismo valor en todos los servicios es requisito de despliegue (14.2) |
| `PALCO_URL_INTERNA` | Dirección de los otros servicios y del JWKS | `http://localhost:8080` |
| `PALCO_CORS_ORIGENES` | Orígenes que pueden llamar a la API | `http://localhost:5173`; en el compose, la URL pública (DEF-42, 14.4) |
| `PALCO_DB_DRIVER`, `PALCO_DB_URL`, `PALCO_DB_USUARIO`, `PALCO_DB_PASSWORD` | Base de datos PostgreSQL (el compose las define para su servicio `db`) | H2 en memoria con `sa`/`sa` |
| `PALCO_ASISTENTE_OPENAI_CLAVE` | Clave del proveedor de IA, solo con `PALCO_ASISTENTE_MODELO=openai`. Vive solo en el servidor: no viaja al frontend ni va al log (4.10) | Sin clave, la cabecera `Authorization` no se manda (Ollama local no la pide); con el modelo simulado no hace falta |
| `PALCO_USUARIOS_CUENTAS_DE_PRUEBA` | Si sirven las seis cuentas de prueba (`@palco.test`), cuya contraseña es pública. Fuera de desarrollo va en `false`: al arrancar, Usuarios les pone un hash que ninguna contraseña abre (5.6). Volver a `true` no les devuelve la contraseña | Vale `true`: las cuentas sirven, y el log lo avisa al arrancar |

## 14.8 Broker de mensajería

`configurar.cli` crea la cola `cola.emision-entradas` y los tópicos `topico.compra-confirmada` y `topico.eventos-cancelados`, con 5 entregas como
máximo, 2 segundos entre reintentos y cola de mensajes fallidos (DLQ). No define usuarios ni permisos por destino:
el broker queda con la configuración de fábrica de WildFly y cualquier componente desplegado puede publicar o
consumir en cualquier destino. No cumple mínimo privilegio.

**Riesgo aceptado (decidido el 16/10).** Se evaluó un usuario por servicio con permisos por destino y el grupo decidió
registrar el riesgo como aceptado (DEF-39). El broker está embebido en el mismo WildFly, los servicios se conectan por
in-vm y la interfaz pública del servidor queda en 127.0.0.1 por defecto: ningún sistema externo se conecta al broker.
Se revisa si el sistema se despliega fuera de una sola máquina. `configurar.cli` no define permisos por destino.

## 14.9 Matriz de criterios de seguridad (taller de la clase 11)

En la clase 11 el grupo armó una matriz de diez criterios (`docs/clases/clase-11-actividad-seguridad/`), cada uno con
Dado/Cuando/Entonces, referencia OWASP y veredicto por revisión de código y tests (`mvn test` del 16/10, antes de
los cambios de esta sección: 313 tests, 0 fallas). Como no hay app móvil, los dos criterios mobile se reemplazaron
por dos de datos sensibles. La
columna "Estado al 25/11" parte de dos verificaciones del 16/10 y suma lo que cambió después, con el defecto del
registro que lo cuenta. La primera verificación del 16/10, de 09:17 a 09:23, con el servidor armado
desde cero con los cambios de seguridad: 333 tests, los 13 escenarios de entonces y Newman con 74 requests, 252
aserciones y 1 falla (SEC-W05c, que todavía exigía un token de 15 minutos o menos; después de la decisión sobre la
vigencia de la sesión, ese request comprueba que el token dura 8 horas). La última, desde las 11:04, con el servidor
armado desde cero con Facturación, Validación, Reventa y la conciliación de pagos (detalle en 16.6):

- `mvn clean install`: BUILD SUCCESS, 577 tests y 0 fallas; 9 de 9 WAR desplegados (los 8 servicios y el ARCA
  simulado), los 8 `health` en 200, el WSDL de `/legado/arca?wsdl` en 200 y 0 líneas `ERROR` en el log.
- Los 17 escenarios de `scripts/pruebas/escenarios.mjs`: 17 de 17.
- Newman con la colección completa: 126 requests ejecutados, 406 aserciones, 0 fallas.

Los requests de cada criterio forman parte de la colección generada en `docs/api/`: carpeta
"4 Seguridad (clase 11)", con 15 requests, todos dentro de la corrida.

| ID | Criterio | Referencia | Veredicto (clase 11) | Estado al 25/11 |
|---|---|---|---|---|
| SEC-W01 | Una entrada ajena no se puede consultar | OWASP API1:2023 (BOLA) | Cumple, test en verde | Cumple, prueba en verde: filtro por `compradorId` y 404; tests `ComprasEnCursoTest.unaVentaAjenaRespondeLoMismoQueUnaQueNoExiste` y `laEntradaSoloLaVeQuienLaCompro`, y requests SEC-W01 y SEC-W01b |
| SEC-W02 | El rol decide quién usa cada operación | OWASP API5:2023 (BFLA) | Cumple en código | Cumple, prueba en verde: requests SEC-W02 (crear evento con rol `user`: 403) y SEC-W02b (sin token: 401 con `WWW-Authenticate`), y desde el 24/11 `SeguridadDeclarativaTest` en cada servicio, que falla si una operación queda sin anotación de rol (DEF-43, 14.3) |
| SEC-W03 | El login no revela si el email existe | A07:2025 | Cumple en código | Cumple, prueba en verde del mensaje: SEC-W03a y SEC-W03b (mismo `detail` con un email inexistente y con la clave mal). El tiempo lo iguala el código: el hash falso del email inexistente se calcula con las mismas 600.000 iteraciones que las cuentas de prueba (V5) y, desde el 24/11, que toda cuenta después de su primer ingreso (DEF-44). El registro sí dice si un email o un DNI ya tienen cuenta, como pide CA-01.2: riesgo aceptado (DEF-75) |
| SEC-W04 | Bloqueo tras 5 logins fallidos en 15 minutos | A07:2025 | No cumple | Cumple, prueba en verde: `LimiteDeIntentosDeLoginTest`, request SEC-W04 y en vivo (cinco 401 y el 6.º, 429 con `Retry-After: 898`). Riesgo aceptado: quien conoce el email de otra persona puede bloquearle el login 15 minutos (DEF-41, 14.1) |
| SEC-W05 | Tokens alterados, sin firma o de larga vida se rechazan | A07:2025 | Parcial | Parcial. Firma alterada y `alg: none` se rechazan con 401 Problem Details, `WWW-Authenticate: Bearer error="invalid_token"` y CORS (`TokensTest`, `ErrorDeAutenticacionServletTest`, SEC-W05a y SEC-W05b en verde, y en vivo; también el bloque A de `seguridad.mjs`, 14.10). Vigencia justificada: el 16/10 el grupo decidió mantener el token en 8 h, sin refresh token ni revocación, y aceptar el riesgo de un token robado para el alcance del trabajo (DEF-38, 14.2). SEC-W05c pasa a comprobar que el token dura 8 horas |
| SEC-W06 | Entrada inválida: 400 limpio y sin persistir | A05:2025 | Cumple, test en verde | Cumple, prueba en verde (SEC-W06). Además el 500 trae `idError` (`ErrorInesperadoTest`) |
| SEC-I01 | SOAP seguro y con estado controlado ante timeout | A02:2025 | No verificable | Cumple, prueba en verde (ver abajo). Tiempos de espera de 2 s para conectar y 5 s para leer, cualquier falla del SOAP pasa a `SinRespuesta` y el MDB deshace la entrega para que el broker reintente y después la mande a la DLQ, y Facturación no crea el cliente si está habilitado el parser XML inseguro de CXF. Tests en verde: `ArcaSoapTest` y `FabricaDeArcaTest`. En vivo, el 16/10, el cliente CXF de WildFly facturó contra el ARCA simulado (escenario 15 y carpeta 7 de Postman), y el 19/11, con el ARCA sin responder, cortó cada pedido a los 5 s, el mensaje pasó a la DLQ después de 5 entregas y, devuelto, salió una sola factura (`checkpoint-3/arca-lento-dlq.txt`). El 21/11, el bloque D de `seguridad.mjs` mandó al CXF de WildFly tres sobres con DOCTYPE y los tres se rechazaron sin expandir nada (`docs/testing/evidencia/final/seguridad.txt`, 11.6) |
| SEC-I02 | Permisos por destino en el broker | A01:2025 | No cumple | No cumple, riesgo aceptado el 16/10 (DEF-39): sin usuarios ni permisos por destino; el broker está embebido en el mismo WildFly, con conexión in-vm, y ningún sistema externo se conecta a él (14.8). Se revisa si el sistema se despliega fuera de una sola máquina |
| SEC-D01 | Los logs no contienen DNI | Ley 25.326 | Cumple en código | Cumple, con control en el CI: ningún componente escribe el DNI en el log (`ServicioDeNotificacionesTest#elAvisoNoLlevaElDniDeNingunTitular`; el asistente lo enmascara), desde el 24/11 el DNI tampoco viaja en los mensajes ni queda en la auditoría (DEF-37), y desde el 25/11 `scripts/qa/control-de-logs.sh`, que corren los pasos "Control de logs" de `ci.yml`, `contrato.yml`, `pruebas-seguridad.yml` y `zap.yml`, falla si el log del servidor tiene un token, una contraseña de prueba o un DNI (fuera de los emails que el modo de desarrollo escribe enteros) y, con los emails enmascarados, si tiene un email `@palco.test` entero (DEF-40) |
| SEC-D02 | Contraseñas con hash y nunca en respuestas | A04:2025 | Cumple en código | Cumple, prueba en verde: PBKDF2 con 600.000 iteraciones (`ContrasenasTest`, `MigracionesEnH2Test`), un hash con menos se recalcula al iniciar sesión (DEF-44, `IteracionesAlIniciarSesionEnH2Test`), y request SEC-D02 (`/yo` sin contraseña ni hash) |

**SEC-I01 en el cliente SOAP de Facturación.** Facturación llama a `FECAESolicitar` del ARCA simulado
(`/legado/arca`) desde el adaptador `negocio/externos/ArcaSoap`, que arma la configuración en `FabricaDeArca`.
Estado: **Implementado**. Tests en verde y, en vivo el 16/10, el escenario 15: factura B con número `00001-00000001`
y CAE de 14 dígitos, una sola por venta, y la compra siguiente con el número siguiente.

| Parte del criterio | Cómo está | Dónde | Test |
|---|---|---|---|
| Tiempo de espera | 2 s para conectar y 5 s para leer, configurables (`palco.facturacion.arca.espera-conexion-ms` y `palco.facturacion.arca.espera-lectura-ms`). Se ponen con los nombres de CXF, el cliente de WildFly (`jakarta.xml.ws.client.connectionTimeout` y `receiveTimeout`), y con los de Metro, el cliente de los tests. Un valor de 0 o menos se rechaza, porque en los dos clientes es esperar para siempre | `ArcaSoap.puerto`, `FabricaDeArca.crear` | `ArcaSoapTest.siArcaTardaMasQueElTiempoDeLecturaFallaSinEsperarlo`, `FabricaDeArcaTest.unTiempoDeEsperaEnCeroSeRechazaPorqueSeriaEsperarParaSiempre` |
| Estado controlado ante una falla | Tiempo vencido, conexión rechazada, WSDL que no baja y SOAP Fault son todos `WebServiceException` y pasan a `SinRespuesta`: `FacturacionDeComprasMDB` hace `setRollbackOnly()` y el broker reintenta (5 entregas cada 2 s) y después deja el mensaje en la DLQ. Un rechazo del comprobante pasa a `comprobante-rechazado`; desde el 20/11 (DEF-33) queda anotado en `facturacion.comprobante_rechazado` y el mensaje se confirma, sin reintentos ni DLQ. No queda una factura a medias: se guarda solo con el CAE en la mano, y la reentrega busca primero la factura o el rechazo de la venta | `ArcaSoap.autorizar`, `FacturacionDeComprasMDB`, `ServicioDeFacturacion` | `ArcaSoapTest.unSoapFaultEsSinRespuesta`, `FacturacionDeComprasMDBTest` |
| XML sin DTD ni entidades externas (XXE) | La respuesta la lee el cliente SOAP de WildFly (JBossWS con Apache CXF). CXF arma su lector StAX con la DTD y las entidades externas deshabilitadas, salvo que se defina la propiedad de sistema `org.apache.cxf.stax.allowInsecureParser`; el cliente JAX-WS no tiene otra opción que configurar. Palco no la define, y `FabricaDeArca` se niega a crear el cliente si está en `true`. Además, SOAP prohíbe la DTD dentro de un mensaje | `FabricaDeArca.crear` | `FabricaDeArcaTest.conElParserInseguroDeCxfActivadoNoSeCreaElCliente` |
| Llamada fuera de la transacción | `ServicioDeFacturacion` es `NOT_SUPPORTED`: no retiene una conexión de la base mientras espera a ARCA, y la factura se guarda en una transacción corta del repositorio | `ServicioDeFacturacion` | `ServicioDeFacturacionTest` |

Los tests corren sobre Metro y no sobre CXF, así que lo propio de CXF se prueba contra el servidor. La prueba en vivo
del 16/10 mostró que el cliente CXF de WildFly llama al ARCA simulado y guarda la factura; la del 19/11, que sus
cortes de tiempo funcionan como en Metro: con el ARCA sin responder, cada pedido se cortó a los 5 s
(`checkpoint-3/arca-lento-dlq.txt`); y la del 21/11, que el CXF de WildFly rechaza los sobres con DTD o entidades
externas (bloque D de `seguridad.mjs`, 11.6). El ARCA simulado no tiene autenticación (el WSFEv1 real usa WSAA): por
eso no se publica hacia afuera. Desde el 25/11 el nginx del compose responde 404 en `/legado/` (DEF-45), y el backend
se publica solo en 127.0.0.1.

Resumen al 25/11: 8 criterios cumplen con prueba en verde (W01, W02, W03, W04, W06, I01, D01 y D02), 1 es parcial con
la vigencia justificada (W05, DEF-38) y 1 no cumple y queda como riesgo aceptado (I02, DEF-39). Al 16/10 eran 5 con
prueba en verde y 3 que cumplían en el código con prueba parcial o sin prueba propia (W03, D01 e I01). La única
aserción en rojo del 16/10 fue SEC-W05c en la corrida de las 09:17, que comprobaba la vida corta que el grupo después
descartó; en la corrida de las 11:04 la colección completa dio 0 fallas.

## 14.10 Pruebas de seguridad automatizadas

Desde el 20/11 el repositorio tiene pruebas de seguridad que atacan al sistema entero, levantado con
`docker compose`, como lo haría alguien con una cuenta común, un cliente HTTP y un navegador, y un análisis de las
dependencias y de las imágenes. Corren en workflows aparte de `ci.yml`: las que atacan al sistema crean datos y
mandan pedidos manipulados, así que levantan su propio compose. Los tres workflows de la tabla corren en cada push a `master` o `main`, en cada pull
request, con cada tag `v*` y a mano (`workflow_dispatch`), y dejan su reporte como artefacto. Son parte del plan de
pruebas de la Entrega Final (16.8).

| Prueba | Qué intenta | Qué tiene que pasar | Dónde corre |
|---|---|---|---|
| `scripts/pruebas/seguridad.mjs`, bloque A: tokens JWT manipulados | Un token vencido, de otro emisor, sin vencimiento, con `alg` `none`, con la firma alterada, con un rol agregado sin volver a firmar, firmado con otra clave y con HS256 usando la clave pública como secreto, cada uno en cinco servicios, porque cada WAR verifica el token por su cuenta | 401 `token-invalido` en Problem Details en los cinco, con `WWW-Authenticate` y sin traza (`ErrorDeAutenticacionServlet`). Para firmar tokens como el servidor, el workflow genera la clave JWT de la corrida y se la pasa al compose y al guion | `.github/workflows/pruebas-seguridad.yml` ("Pruebas de seguridad") |
| `seguridad.mjs`, bloque B: mass assignment | Campos que el cliente no elige al registrarse (id, rol, `groups`, `emailVerificado`), al crear y editar un evento (dueño, cancelado, destacado, vendidas), al abrir una compra (precios, montos, estado, comprador) y al publicar en la reventa (precio máximo, vendedor, estado) | La respuesta de un pedido común (201 o 200) y, al leer el recurso, esos campos como los fija el servidor: la API lee el cuerpo con JSON-B (`common/rest/JsonProvider`), que descarta lo que el DTO no tiene. La cuenta nueva no inicia sesión sin verificar el email y, verificada, tiene rol `user` | Ídem |
| `seguridad.mjs`, bloque C: XSS almacenado por la API | Un evento con `<script>` e `<img onerror>` en el título y la descripción, y una compra con ese HTML en el nombre del titular | Se guardan y se devuelven como texto, en JSON | Ídem |
| `seguridad.mjs`, bloque D: XXE en el canal SOAP | Sobres a `/legado/arca`, por el puerto del backend (nginx no lo publica, DEF-45), con DOCTYPE y entidades externas, de parámetro e internas (11.6) | Un SOAP Fault sin expandir nada, y el ARCA simulado no atiende ninguno | Ídem |
| `frontend/e2e/xss.spec.ts`: XSS almacenado en el navegador | HTML con JavaScript en cada texto de un evento cargado desde el panel del organizador, un título con HTML guardado por la API y una búsqueda con HTML | En el panel, el catálogo y el detalle, ningún elemento inyectado, ningún `<script>` con las cargas y ningún `alert()` abierto: React escribe los textos como texto | `pruebas-seguridad.yml` y, con las demás pruebas E2E, el job `e2e` de `ci.yml` |
| OWASP ZAP (`scripts/qa/zap.sh`, ZAP 2.17.0) | Un escaneo pasivo (baseline) de la aplicación por nginx y HTTPS: cabeceras, CSP, cookies y datos expuestos; y uno pasivo y activo de la API por nginx, con las operaciones de `docs/api/openapi.yaml`, el token de `titular@palco.test` y la política API-Minimal, con un tope de 7 minutos para el activo | Ninguna alerta High y cada Medium justificada en `.zap/reglas.tsv`. Lo decide `scripts/qa/zap-resumen.mjs`: si no se cumple, el job falla | `.github/workflows/zap.yml` ("ZAP") |
| Trivy v0.76.2 (análisis de dependencias) | Las dependencias de los `pom.xml` (las de scope `compile` y `runtime`) y de `frontend/package-lock.json`, con las de desarrollo, y las imágenes del compose: las dos que arma, backend y web, con los paquetes de su imagen base, WildFly, los WAR y nginx, y desde el 25/11 la de la base, `postgres:17-alpine`, que usa tal como se publica (DEF-73) | Ninguna vulnerabilidad CRITICAL o HIGH, tenga arreglo o no, que no esté justificada en `.trivyignore`. En la imagen backend hay seis, de los módulos de WildFly 41.0.1 y no de una dependencia de Palco: cinco de `jackson-core` y `jackson-databind` 2.21.5 y una de `guava` 33.0.0, que el código de Palco no usa (DEF-68). Las de la imagen de la base (libxml2 y la biblioteca de Go de `gosu`) están justificadas como no alcanzables: el servicio `db` no publica ningún puerto y solo lo alcanza el backend. Todas vencen el 31/12/2026, y cada excepción lleva dónde aparece, por qué se acepta y la fecha de vencimiento. Las acciones van fijadas por el hash de su commit y no por el tag | `.github/workflows/seguridad.yml` ("Seguridad") |

La salida de `seguridad.mjs` y de `xss.spec.ts` se junta en `seguridad.txt`, en el resumen de la corrida y en el
artefacto `pruebas-seguridad`, que es la evidencia que va en `docs/testing/evidencia/final/seguridad.txt`. Al final,
ese workflow corre `scripts/qa/control-de-logs.sh` sobre el log del servidor, como `ci.yml`, `contrato.yml` y
`zap.yml`: sin tokens (tampoco los manipulados), contraseñas de prueba ni DNI (DEF-40, 14.9).

Las cabeceras de nginx suman desde el 25/11 `Cross-Origin-Opener-Policy` y `Cross-Origin-Resource-Policy` en
`same-origin`; `Cross-Origin-Embedder-Policy` no va, porque bloquearía las fotos de los eventos que vienen de otro
origen (justificado en `.zap/reglas.tsv`, DEF-74). El log de accesos de nginx escribe `<email>` en lugar del email de
las rutas de la puerta y de las consultas con `?email=` (DEF-72). La CSP deja estilos en línea e imágenes de cualquier
origen `https:`, justificados en `.zap/reglas.tsv` (DEF-69), y el proceso principal de nginx arranca como root para
escuchar en 80 y 443, como en la imagen oficial (DEF-71): los dos son riesgos aceptados (sección 21).

# 15. Frontend

## 15.1 Tecnología

React 19 con TypeScript 6, Vite 8 como servidor de desarrollo y empaquetador, React Router 7 para las rutas, Tailwind
CSS 3 para los estilos y Framer Motion para las transiciones. El lint es `oxlint`. No se usa una librería de datos
remotos (TanStack Query u otra): son pocas llamadas y se resuelven con `fetch` y `useEffect`, que el grupo puede
explicar entero. La URL del backend sale de `VITE_API_URL` (por defecto `http://localhost:8080`; ver
`frontend/.env.example`). El diseño es responsive; no hay app móvil. Las dos tipografías, Archivo (títulos e interfaz) e
IBM Plex Mono (datos), las sirve el propio sitio desde `frontend/public/fuentes/` (`fuentes.css` y los woff2, con su
licencia SIL Open Font License), sin pedir nada a Google Fonts: esa hoja de estilos cambia según el navegador, así
que no admite Subresource Integrity (la alerta 90003 de ZAP). En el escaneo de ZAP sobre `fbd31dc`, con las fuentes
ya servidas desde el sitio, esa alerta no aparece. Por eso la Content-Security-Policy de
`frontend/docker/nginx.conf` deja `style-src` y `font-src` en `'self'` (más `'unsafe-inline'` en `style-src`, por las
animaciones de Framer Motion).

| Carpeta de `frontend/src/` | Contenido |
|---|---|
| `lib/api.ts` | El único archivo que llama a `fetch`: agrega el token y la cabecera `Idempotency-Key`, y convierte los Problem Details en errores con su `type` |
| `servicios/` | `usuarios.ts`, `eventos.ts`, `ventas.ts`, `reventa.ts`, `validacion.ts`, `facturacion.ts` y `asistente.ts`: una función por endpoint. `ventas.ts` es el único lugar que traduce una venta del backend a los tipos del frontend, y `reventa.ts`, el único que traduce una publicación |
| `lib/useReventas.ts` | Las publicaciones activas de uno o varios eventos, para el detalle del evento, el catálogo, `/reventa` y el panel del organizador |
| `store/` | `SesionContext` (sesión) y `AppContext` (catálogo en memoria y carrito de la compra en curso), separados porque son responsabilidades distintas. Desde el 24/11 sus hooks, `useSesion` y `useApp`, están en archivos propios, así cada archivo de componente exporta solo componentes y `npm run lint` termina sin avisos (DEF-50) |
| `pages/`, `components/` | Pantallas y componentes (entre ellos, `CompraReventaModal`, la compra de una publicación) |
| `data/` | Solo `events.ts`, con las fotos por categoría y por evento de prueba. Los datos fijos de reventa y del organizador (`listings.ts` y `organizer.ts`) se borraron al conectar esas pantallas |

## 15.2 Pantallas y recorridos

| Ruta | Pantalla | Datos |
|---|---|---|
| `/` | Catálogo con búsqueda y filtro por categoría, y franja de reventa | Backend (Eventos y Reventa) |
| `/evento/:slug` | Detalle del evento, selector de entradas por sector y sección "Reventa oficial" | Backend (Eventos y Reventa) |
| `/ingresar`, `/registro` | Ingreso; registro en dos pasos (datos y código) | Backend (Usuarios) |
| `/recuperar` | Recuperar la contraseña en dos pasos: el email, y la contraseña nueva con el código de 6 dígitos (DEF-47) | Backend (Usuarios) |
| `/checkout` (con sesión) | Titulares, reserva con cuenta regresiva y pago | Backend (Ventas) |
| `/confirmacion/:ordenId` (con sesión) | Espera de la emisión y entradas con QR | Backend (Ventas) |
| `/mis-entradas` (con sesión) | Entradas emitidas, compras en curso, publicar y retirar en la reventa | Backend (Ventas y Reventa) |
| `/organizador` (rol `organizer`) | Alta de eventos y panel con ventas, ventas por día y liquidación, reventa, accesos y equipo de puerta | Backend (Eventos, Ventas, Reventa y Validación) |
| `/reventa` (pública; comprar pide sesión) | Listado de la reventa de todos los eventos, con filtros y compra | Backend (Reventa) |
| `/puerta` (rol `staff`) | Escaneo de entradas y registro de la puerta | Backend (Validación) |
| `/asistente` (con sesión) | Preguntas al asistente; cada respuesta dice si salió del modelo, de las preguntas frecuentes o de un bloqueo | Backend (asistente, 4.10) |

El rol de cada ruta lo controla `ProtectedRoute` con el usuario de la sesión, pero eso solo ordena la navegación: lo
que protege los datos es el `@RolesAllowed` de cada operación del backend (14.3).

Recorrido del comprador: busca un evento, elige sectores y cantidades, ingresa, carga los titulares, la reserva
aparta el cupo por 10 minutos, paga, espera la emisión y ve sus entradas con QR en la confirmación y en "Mis
entradas". Desde ahí puede publicar una entrada en la reventa oficial o retirarla, y otro usuario la compra desde el
detalle del evento o desde `/reventa`. Recorrido del organizador: ingresa, crea un evento, que aparece en el
catálogo, y sigue en su panel las ventas, la reventa y los accesos. Recorrido del personal de puerta: elige el evento
y escanea cada entrada.

## 15.3 Qué usa el backend y qué sigue con datos locales

| Función | Estado | Detalle |
|---|---|---|
| Sesión (ingreso, registro con código y reenvío, perfil) | Implementado contra Usuarios | El token se confirma con `GET /api/usuarios/yo` al abrir la aplicación |
| Catálogo, búsqueda y detalle | Implementado contra Eventos | Sin filtros usa la lista ya cargada; con filtros busca el servidor, con 300 ms de demora y descartando respuestas viejas |
| Alta de eventos | Implementado contra Eventos | Con foto propia, que se sube a Eventos (HU-16), o con la foto de su categoría si no se elige una |
| Compra (reserva, pago, cancelación, emisión) | Implementado contra Ventas | El cobro lo hace la pasarela simulada de Pagos (PagosFake) |
| Mis entradas | Implementado contra Ventas y Reventa | Lee `GET /api/v1/ventas`; se ve igual desde otro navegador |
| Reventa | Implementado contra Reventa | Listar, publicar, retirar y comprar (detalle abajo) |
| Puerta | Implementado contra Validación | Escaneo y registro de la puerta (detalle abajo) |
| Panel del organizador | Implementado contra Eventos, Ventas (`GET /api/v1/resumenes`), Reventa y Validación | Solo muestra lo que tiene servicio (detalle abajo) |

Estado de reventa, puerta y panel del organizador: **Implementado y probado en el navegador**. Los servicios que esas
pantallas llaman pasaron en vivo el 16/10 (escenarios 16 y 17 y carpetas 5 y 6 de Postman), y desde el 3.er check
point la suite E2E recorre las pantallas en cada corrida del CI (`reventa.spec.ts`, `puerta.spec.ts` y
`organizador.spec.ts`, 16.7). Desde el 24/11 `organizador.spec.ts` comprueba también las ventas por día y la
liquidación después de una compra (DEF-48).

En el navegador solo queda el carrito de la compra en curso, en `localStorage` bajo la clave `palco.state.v3` (más
la sesión, punto 8 de 15.4). Las entradas, las publicaciones y los escaneos ya no se copian al estado local: se piden
al servicio que es su dueño cada vez que se muestran. El estado guardado de una versión anterior se migra al abrir:
del `palco.state.v2` se conserva el carrito, que tiene el mismo formato, y las claves viejas se borran. Se quitaron
los usuarios y eventos de mentira que había antes, y también los datos fijos de reventa y del organizador:
mantenerlos escondía las fallas del backend.

**Reventa.** Tres pantallas usan las mismas funciones de `servicios/reventa.ts`:

| Dónde | Qué llama | Qué muestra |
|---|---|---|
| Detalle del evento ("Reventa oficial"), franja del catálogo y `/reventa` | `GET /api/v1/reventas?eventoId=`, una vez por evento (hook `useReventas`) | Las publicaciones activas, ordenadas por precio, con el tope (`precioMaximo`) que calcula el backend. `/reventa` filtra por evento y sector. Si falla el pedido de un evento, se muestran los demás y el error |
| "Comprar" (`CompraReventaModal`) | `POST /api/v1/reventas/{id}/compra` con `Idempotency-Key` | Titular (arranca con los datos de la sesión), medio de pago y tarjeta de prueba, con el mismo `tokenDePrueba` que el checkout. Si sale bien, va a "Mis entradas" |
| "Mis entradas" | `GET /api/v1/ventas`, `GET /api/v1/reventas/mias`, `POST /api/v1/reventas` y `DELETE /api/v1/reventas/{id}` | Cada entrada con su estado actual según Ventas (válida, publicada, usada), el formulario para publicar con el mínimo y el tope, el botón para retirar y el historial de las reventas vendidas |

- **Quién decide.** El rango de precio (del 50 % al 110 % del precio de lista del sector, no de lo pagado) y el cierre 3 horas antes del evento se
  controlan también en la pantalla, para avisar sin ir al servidor, con una copia de `ReglasDeNegocio` en
  `src/types` (`MINIMO_REVENTA`, `TOPE_REVENTA`, `HORAS_LIMITE_REVENTA`). La validación que vale es la de Reventa:
  si rechaza, se muestra su `detail` debajo del precio.
- **Compra repetible.** La compra va con clave de idempotencia por la misma razón que el pago de una compra (punto 3
  de 15.4): ante un 504 o un corte de red se repite con la misma clave y el backend no cobra dos veces; ante
  cualquier otro error se usa una clave nueva.
- **Errores.** Con `publicacion-no-activa`, `reventa-cerrada`, `compra-propia` o `no-encontrado`, el modal muestra
  el mensaje y la pantalla vuelve a pedir el listado, porque esa publicación ya no está a la venta para ese usuario.
  Si Reventa no responde, "Mis entradas" muestra las entradas igual (salen de Ventas) y deshabilita publicar con el
  aviso "La reventa no está disponible ahora".
- **Qué se ve en "Mis entradas".** Una entrada comprada en la reventa llega como una venta `EMITIDA` más en
  `GET /api/v1/ventas`, así que la pantalla no distingue el origen. Una entrada vendida en la reventa o anulada ya no
  se muestra; la venta queda en el historial.

**Puerta.** `/puerta` es solo para el rol `staff`. El personal elige el evento de su puerta y tipea el código o lo
entrega un lector que escribe en el campo; el visor de cámara es una imagen fija. La pantalla manda
`POST /api/v1/validacion/escaneos` con `{codigoQr, eventoId}` y no decide nada: muestra el resultado y el `mensaje`
que escribe Validación, con un título propio para cada resultado (`VALIDA`, `YA_USADA`, `INEXISTENTE`, `OTRO_EVENTO`,
`NO_HABILITADA`). Con `VALIDA` muestra titular, DNI y sector, para contrastarlos con el documento; con `YA_USADA`,
la hora y quién hizo el escaneo anterior (`usoAnterior`). Escanear ya marca la entrada como usada, así que se sacaron
el segundo paso de "marcar ingreso" y los botones de demostración. Un 504 (Ventas no respondió y no se sabe si la
entrada quedó usada) se muestra como "No se pudo validar" con la indicación de escanear de nuevo. Los contadores de
ingresos y rechazos y los últimos seis escaneos salen de `GET /api/v1/validacion/escaneos?eventoId=` y se vuelven a
pedir después de cada escaneo; si ese pedido falla, la puerta sigue escaneando, porque el registro es informativo.
Desde el 23/10 el selector ofrece solo los eventos a los que el organizador asignó esa cuenta
(`GET /api/v1/validacion/puertas/mias`); sin ninguno, la pantalla lo avisa y no deja escanear.

**Panel del organizador.** Todo lo que muestra sale del backend y, desde el 23/10, es solo de los eventos del
organizador (`GET /api/eventos/mios`):

| Bloque | De dónde sale |
|---|---|
| Entradas vendidas y recaudado sin el cargo de servicio (lo que se le liquida al organizador): solo compras pagadas, sin las de la reventa | Ventas: `entradas` y `aLiquidarAlOrganizador` de la liquidación de `GET /api/v1/resumenes?eventoId=`, una por evento |
| Cupo restante | Sectores de `GET /api/eventos/mios`: solo los eventos del organizador |
| Publicaciones activas, en total y por evento, con su precio promedio | Reventa (`useReventas`) |
| Ocupación por sector del evento elegido | Eventos |
| Accesos del evento elegido: ingresos, rechazos, por ingresar (vendidas menos ingresos) y tabla de escaneos, con el email de la cuenta que escaneó en la columna "Staff" (desde el 20/11; un escaneo anterior a la migración V4 muestra su id) | `GET /api/v1/validacion/escaneos?eventoId=` |
| Equipo de puerta del evento elegido: las cuentas asignadas, con un formulario para asignar por email y un botón para quitar | `GET`, `PUT` y `DELETE` en `/api/v1/validacion/puertas` |
| "Ventas y liquidación" del evento elegido (desde el 24/11, DEF-48): lo recaudado de los compradores, el cargo de Palco, lo que se le liquida al organizador, las compras pagadas y las entradas, y la tabla "Ventas por día" (día, compras, entradas, subtotal, cargo de Palco y total), con estados de carga, de error con "Reintentar" y sin ventas. El día es el de la confirmación del pago en UTC, como lo agrupa Ventas, y lo aclara una nota debajo de la tabla | Ventas: `liquidacion` y `porDia` de `GET /api/v1/resumenes?eventoId=`, el mismo pedido del primer renglón |

Lo que no tiene servicio (puestos de staff, publicaciones rechazadas por tope) se sacó del panel en vez de mostrarse con datos fijos. Un evento recién creado aparece en el panel porque la lista se vuelve a pedir cuando cambia el catálogo.

## 15.4 Decisiones de experiencia de uso

1. **La reserva se crea al terminar "Titulares".** El backend pide los titulares en el mismo pedido que reserva y no
   deja cambiarlos después. Por eso la cuenta regresiva aparece en "Pago" y, para corregir un titular, se cancela y
   se empieza de nuevo.
2. **Cuenta regresiva con la hora del servidor.** Cada segundo se calcula "vencimiento que mandó el backend menos la
   hora actual", en lugar de restarle uno a un contador, que se atrasa con la pestaña en segundo plano y se reinicia
   al recargar. Al recibir la reserva se mide el desfase entre el reloj del navegador y el del servidor (el
   vencimiento es "hora del servidor más 10 minutos") y se corrige; un desfase menor a 5 segundos se toma como cero.
   Sin esa corrección, con el reloj del navegador 11 minutos adelantado, la página cancelaba la reserva recién
   hecha. En cero la página cancela la compra para devolver el cupo, pero decide el servidor: si el pago responde
   `reserva-vencida`, se muestra lo mismo. El frontend supone que la reserva dura 10 minutos, con la copia de
   `MINUTOS_RESERVA` en `frontend/src/types/index.ts`.
3. **Doble clic y reintentos: claves de idempotencia.** Iniciar la compra y pagar viajan con `Idempotency-Key`, y el
   backend hace la operación una sola vez por clave. Deshabilitar el botón no alcanzaba, porque no cubre un corte de
   red en el que el pedido llegó pero la respuesta se perdió. La clave de la compra se mantiene mientras el pedido no
   cambie; la del pago se guarda en el carrito (sobrevive a recargar) y cambia solo después de un rechazo (402),
   cuando se sabe que no se cobró. La clave de la compra vive en memoria: si se pierde la respuesta de la reserva y
   además se recarga antes de reintentar, esa primera reserva queda sin dueño hasta que vence a los 10 minutos. Se
   acepta porque el cupo vuelve solo y no se cobra nada (7.7 y sección 21).
4. **Pago sin resultado.** Tras un 504 o un corte de red no se sabe si se cobró. La página consulta
   `GET /api/v1/ventas/{id}`: si sigue `EN_CHECKOUT`, dice que no se sabe si el pago se acreditó y que los lugares
   siguen reservados, sin cuenta regresiva y sin cancelar; si quedó paga, va a la confirmación; si se canceló o
   venció, lo dice. Ofrece "Reintentar pago" (mismos datos y misma clave, solo si la venta trae el enlace `pagar`),
   "Ver estado", "Ir a Mis entradas" y "Volver al catálogo". El aviso se guarda en el carrito y sigue después de
   recargar.
5. **Espera de la emisión.** Las entradas se emiten en un proceso asincrónico después del pago. La confirmación
   muestra "Emitiendo tus entradas…" y consulta la venta una vez por segundo, hasta 30 veces; si se agotan, avisa que
   el pago está aprobado y ofrece volver a consultar. "Mis entradas" repite el listado cada 2 segundos, hasta 15
   veces, mientras haya una compra emitiéndose. El backend no ofrece WebSockets.
6. **Errores.** Las páginas deciden por el `type` del Problem Details (`cupo-agotado`, `pago-rechazado`, etc.) y
   muestran el `detail` que manda el backend, así el texto del error se escribe en un solo lugar. Los sectores del
   alta de eventos se validan también en el navegador, porque el servidor no dice en qué fila está el error.
   Las pantallas nuevas siguen el mismo criterio: la compra en la reventa decide por `publicacion-no-activa`,
   `reventa-cerrada`, `compra-propia` y `no-encontrado`, y la puerta muestra el `mensaje` de Validación.
   `api.ts` muestra el `detail` para los estados que tiene en su lista (401, 402, 403, 404, 409, 422, 429, 504 y los
   400 con campos). El 429 del límite de intentos de login (14.1) está en la lista desde el 16/10, y la pantalla de
   ingreso lee `Retry-After` (`ErrorApi.segundosDeEspera`) para decir cuántos minutos esperar. Desde el 24/11 un 502 o
   un 503 dicen "No pudimos conectar con el servidor" (DEF-54, 5.7), y un 500, el mensaje genérico, sin el `idError`:
   ese id es para quien busca la traza en el log (14.5).
7. **Un corte de red no cierra la sesión.** Hasta el 16/10 el 401 por token inválido llegaba sin CORS, y el
   navegador no distinguía "token rechazado" de "sin red". Por eso, si falla un pedido con token sin respuesta
   legible, `api.ts` prueba ese token con `GET /api/usuarios/yo`: si responde, el problema fue la red y la sesión
   sigue, con la misma clave para el reintento; solo si `/yo` tampoco se puede leer y el `health` de Usuarios
   responde, se cierra la sesión con un aviso. Cuesta un pedido más, y solo cuando algo falló. Desde el cambio del
   16/10 (14.4) el 401 llega con CORS y en Problem Details, así que el navegador lo deja leer y `api.ts` cierra la
   sesión por la rama común (`respuesta.status === 401` con token), sin pasar por esa prueba; la prueba queda para
   los cortes de red de verdad. El comentario de `tokenRechazado` en `api.ts` describe los dos casos.
8. **Sesión en `localStorage`.** El token, el usuario y el vencimiento se guardan en `localStorage` para sobrevivir a
   recargar; una cookie HttpOnly exigía cambiar el backend. Es la opción más expuesta ante un XSS y es un riesgo
   aceptado (DEF-58): lo acotan la CSP (`script-src 'self'`), que el frontend no carga scripts de terceros y que React
   escapa el texto, como prueban el bloque C de `seguridad.mjs` y `frontend/e2e/xss.spec.ts` (14.10).
9. **Redirección segura después de ingresar.** El parámetro `next` solo acepta rutas internas simples, para que un
   enlace armado por otro no mande a un sitio externo.
10. **Teléfono.** El selector de entradas se ve en todos los anchos; en pantalla angosta el total y "Continuar" van
    en una barra fija abajo.
11. **Decisiones de reventa, puerta y panel.** Reventa lista por evento, así que el catálogo, `/reventa` y el panel
    hacen un pedido por cada evento del catálogo; si los eventos crecen, conviene que `GET /api/v1/reventas` acepte
    varios eventos. El listado público no dice quién publicó, así que "Comprar" también aparece en la publicación
    propia y el backend responde 409 `compra-propia`. El cargo de servicio del modal de compra es una estimación del
    10%: el importe lo calcula el backend y la respuesta de la compra no lo devuelve. El listado trae también las
    publicaciones de un evento al que le faltan menos de 3 horas; comprarlas responde 409 `reventa-cerrada`.

## 15.5 Cómo se prueba

- **Automático, en cada corrida del CI (16.7):** el job Frontend corre `npm ci`, `npm run lint` (oxlint) y
  `npm run build` (compilación de TypeScript). El frontend no tiene tests unitarios propios: lo prueban la suite E2E
  (`frontend/e2e/`, Playwright con Chromium contra el `docker compose`, por `https://localhost`), que incluye
  accesibilidad y responsive con axe-core (`accesibilidad.spec.ts`), y Lighthouse (`lighthouse.yml`). En la corrida
  del 21/11 sobre `fbd31dc` pasaron las 31 pruebas E2E de entonces, y Lighthouse dio 100 en accesibilidad y en buenas
  prácticas en las cinco pantallas públicas, en teléfono y en escritorio. Desde el 24/11 la suite tiene 39 pruebas: se
  sumaron las de las ventas por día (DEF-48), la carga diferida de las pantallas (DEF-49), el aviso sin conexión
  (DEF-54), la factura rechazada y la demorada (DEF-33) y la foto de la categoría.
- **A mano contra el backend real** (`backend/dev.sh` y `npm run dev`, en `http://localhost:5173`), con las cuentas
  sembradas `titular@palco.test` y `organizador@palco.test` (contraseña `palco1234`): búsqueda, registro (el código
  queda en `backend/servidor/target/server/standalone/log/server.log`), alta de evento, compra de dos entradas,
  recarga durante el pago, tarjeta terminada en `0002` (rechazo) y en `0504` (pago sin resultado; `servicios/ventas.ts` las traduce a los tokens `tok_rechazado` y `tok_sin_respuesta` de PagosFake), confirmación con
  QR y "Mis entradas".
- **Recorridos de reventa, puerta y panel.** Los recorre la suite E2E en cada corrida del CI: `reventa.spec.ts`
  (publicar respetando el tope y que otra cuenta compre la entrada), `puerta.spec.ts` (una entrada válida, el mismo
  código por segunda vez y uno que no existe) y `organizador.spec.ts` (crear un evento con foto, editarlo y
  cancelarlo, y las ventas por día y la liquidación después de una compra). El recorrido a mano es, con las mismas cuentas más
  `otro.titular@palco.test` (segundo comprador) y `puerta@palco.test` (staff): publicar una entrada desde "Mis
  entradas" con un precio fuera de rango y dentro de él, retirarla, comprarla desde el detalle del evento con la otra
  cuenta y verla en sus "Mis entradas" con QR nuevo; escanear en `/puerta` ese QR dos veces (válida y ya usada), uno
  inexistente y uno de otro evento; y ver los accesos y la reventa en el panel del organizador.
- **En navegador automatizado (Playwright):** los casos que no se pueden provocar a mano en una sola computadora (el
  reloj del navegador adelantado 3 y 11 minutos, y la respuesta de la reserva cortada después de que el backend la
  procesó) se probaron así: la reserva siguió en pie, la sesión se conservó y hubo una
  sola venta y un solo pago. Esos guiones no están en el repo.

# 16. Pruebas y evidencia

El sistema se prueba en cinco niveles. Cada uno contesta una pregunta distinta y necesita cosas distintas para
correr:

| Nivel | Qué contesta | Necesita | Dónde está |
|---|---|---|---|
| Tests unitarios (JUnit) | ¿Cada componente cumple sus reglas, aislado del resto? | Solo Maven y un JDK 25; no levantan WildFly | `backend/*/src/test` y `backend/servicios/*/src/test` |
| Integración con dependencias reales (los `*IT`, con failsafe en `mvn verify`) | ¿Los repositorios y las migraciones de Usuarios, Eventos, Ventas y Facturación andan contra PostgreSQL 17, y el consumidor de Facturación contra Artemis 2.54.0? | Maven, un JDK 25 y Docker: Testcontainers levanta cada uno en un contenedor. Sin Docker se saltean con un aviso, y el job "Backend" del CI queda en rojo si alguno se salteó | Las clases `*EnPostgresIT` y `FacturacionDeComprasEnArtemisIT` de `backend/servicios/*/src/test` |
| Escenarios de integración | ¿La compra, la mensajería, la conciliación de pagos, la facturación, la puerta y la reventa funcionan de punta a punta, entre servicios y con el broker real? | WildFly levantado con los 10 WAR (los 9 servicios y el ARCA simulado), Node 18+ y `jboss-cli` | `scripts/pruebas/` |
| Colección de Postman (Newman) | ¿La API responde lo que documenta el contrato, request por request? | WildFly levantado, Postman o Newman | `docs/api/` |
| Pruebas E2E (Playwright) | ¿Las pantallas funcionan en el navegador contra el backend real? | El backend levantado (`dev.sh` o `docker compose`) y Chromium | `frontend/e2e/` (16.7) |

El frontend se controla con `npm run lint` (oxlint) y `npm run build`, que incluye el chequeo de tipos (`tsc -b`), y
tiene una suite E2E con Playwright (`npm run e2e`, sección 16.7).

## 16.1 Tests unitarios por módulo

Se corren con `cd backend && mvn -q test` (o `mvn -f backend\pom.xml test` desde PowerShell). **Última corrida:
16/10/2026, 11:04, `mvn clean install` dentro de la verificación conjunta (sección 16.6): BUILD SUCCESS, 577 tests,
0 fallas.** El conteo por clase sale de contar las anotaciones `@Test` y `@ParameterizedTest` en cada `src/test`.
En las filas sin fecha, el total por módulo coincide con el de esa corrida; las que llevan fecha se contaron ese día.

| Módulo | Tests | Clases de test y qué cubren |
|---|---|---|
| `common` | 82 (al 20/11) | `PagoConfirmadoMensajeTest` (16: armado y lectura del mensaje del contrato), `TokenDeServicioTest` (9), `CorreoSmtpTest` (6, con el `WARNING` de un envío que falla sin el email de nadie ni la traza), `EmailTest` (6, con el destinatario enmascarado), `FabricaDeCorreosTest` (6), `FiltroDeEtagTest` (6), `ErroresDeNegocioTest` (5), `TextosTest` (5), `CorreoAlLogTest` (4: por defecto, el destinatario enmascarado y sin el cuerpo; con `PALCO_CORREO_LOG_COMPLETO=true`, el email entero en un renglón), `ReglasDeNegocioTest` (4), `ProblemaTest` (4: cuerpo Problem Details), `BaseDeDatosTest` (3), `CorsFilterTest` (3), `ErrorInesperadoTest` (3: el 500 con `idError`), `ErrorDeAutenticacionServletTest` (2: el 401 por token inválido) |
| Usuarios | 75 (al 20/11) | `RegistroYVerificacionTest` (20, con el día en que se cumplen los 16 años y el anterior), `ReinicioDePasswordTest` (14), `LimiteDeIntentosDeLoginTest` (10: el 429 después de 5 fallidos, y una cuenta sin verificar: 409 con la clave buena y el mismo 401 con la mala), `RepositoriosEnH2Test` (6), `ContrasenasTest` (6), `MensajesDeValidacionTest` (5: ninguna validación de la API con el mensaje por defecto, 14.5), `MigracionesEnH2Test` (3), `DesactivacionDeCuentasDePruebaEnH2Test` (3), `TokenDeServicioEmitidoTest` (3), `CuentasDePruebaTest` (2), `TokensTest` (2), `RegistroCompletoTest` (1) |
| Eventos | 113 (al 20/11) | `ServicioDeEventosTest` (65, con los eventos del organizador, su dueño y el slug reservado), `CupoEnH2Test` (14: la reserva y la devolución de cupo contra H2, con la clave repetida, y los dueños de los eventos iniciales), `AlmacenEnSupabaseTest` (13), `TiposDeImagenTest` (6), `MensajesDeValidacionTest` (5: ninguna validación de la API con el mensaje por defecto, 14.5), `NuevoEventoDtoTest` (5: validación del alta), `AlmacenEnBaseDeDatosTest` (5) |
| Ventas | 366 (al 20/11) | `ServicioDeVentasTest` (60), `ComprasEnCursoTest` (58), `ClienteDePagosTest` (32), `ConciliacionDePagosTest` (20), `ClienteDeEventosTest` (20), `CancelacionDeEventosTest` (18), `ConciliacionEnH2Test` (14), `ReventaDeEntradasEnH2Test` (14, con el tope por evento y el DNI por evento de la compra en la reventa contra H2), `EmisionDeEntradasMDBTest` (11), `ReintentoDeReembolsosTest` (10), `VencimientoDeReservasTest` (10), `EnlacesTest` (8), `VentasResourceTest` (8), `VentaTest` (8), `DevolucionDeCuposTest` (8), `ResumenDeVentasTest` (8), `EstadoDeVentaTest` (7), `DevolucionPendienteEnH2Test` (6), `ClienteDeUsuariosTest` (6), `UsoDeEntradasTest` (6), `EntradasResourceTest` (5), `ReembolsoPendienteEnH2Test` (5), `ConfirmacionConciliadaTest` (5), `CobrosFakeTest` (5), `CancelacionConReventaEnH2Test` (4), `FabricaDeCobrosTest` (4), `UsoEnH2Test` (3), `ReventaDeEntradasResourceTest` (2: todas las operaciones de reventa piden el rol `servicio`), `EmisionEnH2Test` (1) |
| Pagos | 36 (al 23/10) | `ServicioDePagosTest` (24, con el reembolso), `PagosFakeTest` (5), `PagosResourceTest` (4), `FabricaDePasarelaTest` (3); el repositorio se reemplaza por `PagoRepositoryEnMemoria` |
| Validación | 53 (al 20/11) | `ServicioDeValidacionTest` (25, con la puerta asignada, el dueño y el email de quien escanea en el registro), `ClienteDeVentasTest` (8), `RegistroEnH2Test` (6, con un escaneo guardado antes de la migración V4), `EscaneosResourceTest` (5: roles de cada operación y quién pide), `ClienteDeUsuariosTest` (4), `ClienteDeEventosTest` (3), `PuertasEnH2Test` (2) |
| Reventa | 90 (al 20/11) | `ServicioDeReventaTest` (35: rango de precio, cierre, compra propia, dueño, la transferencia rechazada, y el control del tope y del DNI antes de tomar y cobrar), `ConciliacionDeReventasTest` (15), `ClientesTest` (15), `PublicacionesEnH2Test` (10), `DevolucionDeCobrosTest` (8), `ReembolsosEnH2Test` (4: un reembolso por pago, también con dos a la vez), `RetiroPorCancelacionTest` (3) |
| Notificaciones | 33 | `ServicioDeAuditoriaTest` (6), `AuditoriaDeComprasMDBTest` (5), `PruebasDeMensajeriaTest` (5), `AvisoDeCompraMDBTest` (4), `LogEnUnSoloRenglonTest` (4), `ServicioDeNotificacionesTest` (4), `RegistroDeAuditoriaEnH2Test` (3), `MigracionesEnH2Test` (2) |
| Facturación | 50 (al 20/11) | `ServicioDeFacturacionTest` (16, con el rechazo de ARCA anotado y el 409 a su comprador), `FacturacionDeComprasMDBTest` (9: el rechazo se confirma y no vuelve al broker), `FacturasResourceTest` (7), `ArcaSoapTest` (7: tiempo de espera y SOAP Fault), `FacturaEnH2Test` (6, con la migración V2 y un rechazo por venta), `FabricaDeArcaTest` (5: tiempos y parser inseguro de CXF) |
| ARCA simulado | 18 (al 20/11) | `ArcaSimuladoTest` (8), `ContratoDelWsdlTest` (7: el WSDL publicado contra `docs/api/arca.wsdl` y los mensajes contra su XSD), `ArcaSimuladoPorSoapTest` (3: el servicio publicado y llamado por SOAP) |
| **Total** | **577** | |

Ventas concentra casi la mitad de los tests porque es el componente con más reglas y estados: la compra en dos
llamadas, la reserva de cupo con vencimiento, la idempotencia, el cobro a Pagos, la emisión asincrónica, la
conciliación de pagos y las operaciones internas que usan Validación y Reventa. Las clases `...EnH2Test` corren las
migraciones Flyway y las consultas contra H2 en modo PostgreSQL.

Fuera de JUnit hay una prueba en vivo, `backend/servicios/ventas/src/test/en-vivo/pagos-simultaneos.mjs`, que sí
necesita el servidor levantado: paga varias compras en el mismo instante.

Los cambios de seguridad del 16/10 tienen sus tests: el límite de intentos de login (`LimiteDeIntentosDeLoginTest`),
el 401 por token inválido como Problem Details (`ErrorDeAutenticacionServletTest`), el `idError` del 500
(`ErrorInesperadoTest`) y las 600.000 iteraciones de PBKDF2 (`ContrasenasTest`). `MigracionesEnH2Test` de Usuarios
corre sus migraciones, V4 y V5 incluidas, contra H2.

## 16.2 Los escenarios de integración (17 al 16/10, 21 desde el 23/10, 24 desde el 3.er check point)

`scripts/pruebas/escenarios.mjs` (con sus ayudas en `lib.mjs`) prueba la compra, la mensajería, la conciliación de
pagos, la facturación, la validación en puerta y la reventa de punta a punta, por la API REST y por `jboss-cli`,
contra el WildFly real. Usa el `fetch` de Node 18 o más nuevo y ningún paquete de npm. Cada comprobación imprime qué
esperaba y qué vio; al final hay un resumen por escenario. Sale con código 0 si todo pasa y con 1 si algo falla.

```bash
node scripts/pruebas/escenarios.mjs                                   # los 24 escenarios
node scripts/pruebas/escenarios.mjs 1 2 3 4 5 6 7 9 13 15 16 17       # solo los que no tocan el servidor compartido
node scripts/pruebas/escenarios.mjs 14                                # uno solo
```

| N.º | Escenario | Qué comprueba |
|---|---|---|
| 1 | Compra completa | Iniciar (201, reserva de cupo, vence a los 10 minutos), pagar contra Pagos, emisión asincrónica hasta `EMITIDA` con entradas `VALIDA`, el pago guardado en Pagos y el listado de compras propias |
| 2 | Idempotencia | La misma `Idempotency-Key` al iniciar y al pagar devuelve la misma venta y el mismo pago: un cupo, un cobro, un mensaje |
| 3 | Pago rechazado | 402 en Problem Details, la venta sigue `EN_CHECKOUT` y no sale ningún mensaje; un segundo intento con clave nueva la completa |
| 4 | Reglas de negocio | Máximo de entradas, DNI repetido, titulares que no coinciden, sector inexistente, cupo agotado, datos inválidos (400) y una línea falsa inyectada en el log |
| 5 | Seguridad | 401 sin token o con firma alterada, 404 con el token de otro usuario, 403 con un rol que no corresponde, operaciones internas solo con rol `servicio` |
| 6 | Cancelación | Cancelar libera el cupo una sola vez y la venta ya no se puede pagar |
| 7 | Un mensaje por pago | Un mensaje en `cola.emision-entradas` y una copia por suscripción en `topico.compra-confirmada` (tres: aviso, auditoría y facturación), con el selector `tipoEvento = 'PAGO_CONFIRMADO'` y el cuerpo del contrato |
| 8 | Mensaje repetido | Se pasa una copia ya procesada a la cola de emisión con `move-messages`: el consumidor la reconoce y no cambia nada |
| 9 | Los suscriptores del aviso y la auditoría | El aviso (línea en el log) y la auditoría (fila en la base) reciben cada uno su copia |
| 10 | Consumidor detenido | Con `stop-delivery` el mensaje espera en la cola y la venta queda `CONFIRMADA`; con `start-delivery` se emite |
| 11 | Suscripción durable | Con la auditoría detenida se publican dos mensajes; al reanudarla los recibe |
| 12 | Mensaje inválido | Se reintenta 5 veces, cada 2 segundos, y termina en la DLQ sin trabar las compras: 2 copias, una por suscriptor durable (auditoría y facturación); el aviso, que no es durable, lo descarta una vez |
| 13 | Transacción deshecha | Si la transacción se deshace después de publicar, el mensaje no sale; sin la falla, sí |
| 14 | Falla después del cobro | Con la propiedad `palco.ventas.fallar-despues-del-cobro` activada por `jboss-cli`, el pago responde 500 con `idError` y nada interno; la venta sigue `EN_CHECKOUT`, sin entradas ni mensaje, con el cupo reservado, y el pago queda `APROBADO` en Pagos. La conciliación la confirma con ese mismo pago y emite la entrada; queda un solo pago para la venta |
| 15 | Facturación | La compra confirmada se factura una sola vez: factura B por el total cobrado, número `00001-00000001` y CAE de 14 dígitos de ARCA (simulado); consultada de nuevo es la misma, y la compra siguiente lleva el número siguiente y otro CAE |
| 16 | Validación en puerta | En la puerta de otro evento, `OTRO_EVENTO` y la entrada sigue `VALIDA`; el primer escaneo, `VALIDA` y la entrada queda `USADA`; el segundo, `YA_USADA` con el uso anterior; un código que no existe, `INEXISTENTE`; un comprador, 403; el registro no muestra el código completo y, desde el 20/11, cada renglón trae el email de la cuenta que escaneó, además de su id |
| 17 | Reventa | Publicar al tope exacto (201 `ACTIVA`), la entrada `PUBLICADA` en Ventas y en el listado público, `NO_HABILITADA` en la puerta, la compra con otra cuenta (publicación `VENDIDA`, entrada nueva y su venta), la misma clave sin cobrar de nuevo y la entrada original `VENDIDA` |
| 18 | Devolución de cupo con Eventos caído (desde el 23/10) | Con el WAR de Eventos sacado de servicio por `jboss-cli`, cancelar una compra de 2 entradas responde 204 y Ventas avisa que reintenta (un 404 del servidor no se toma como rechazo); al volver a desplegar Eventos, el barrido `DevolucionDeCupos` devuelve las 2 entradas, y repetir la misma devolución con su clave no resta otra vez. Pasó el 23/10 (13 de 13 comprobaciones). Mientras corre nadie puede usar el catálogo: no va en la lista de los que no tocan el servidor compartido |
| 19 | Reventa con Pagos sin respuesta (desde el 23/10) | Dos compras de reventa con `tok_sin_respuesta` (504); en una no hay cobro y en la otra se registra en Pagos un cobro aprobado con la misma clave. Mientras no se sabe, otra cuenta no compra y el vendedor no retira (409). La conciliación libera la primera (otra cuenta la compra) y transfiere la segunda al titular de la compra, sin cobrar de nuevo. Pasó el 23/10 (14 de 14 comprobaciones). Tarda entre 2 y 4 minutos |
| 20 | Reventa cobrada y sin entrada (desde el 23/10) | Con la entrada devuelta a `VALIDA` en Ventas por detrás de Reventa (`/operaciones-de-reventa/retiros` con el token de servicio), la compra cobra y Ventas rechaza la transferencia: 409 con "Te devolvimos el pago", el pago `REEMBOLSADO` por el total, la publicación `ANULADA` y fuera del listado, y ni el reintento, ni otra cuenta, ni el vendedor la mueven. Una segunda, cobrada sin que Reventa lo supiera, la anula la conciliación y devuelve el pago. Pasó el 23/10 (20 de 20 comprobaciones). Tarda entre 2 y 4 minutos |
| 21 | Dueño del evento (desde el 23/10) | `GET /api/eventos/mios` trae los eventos de la organizadora y ninguno al otro organizador (401 sin sesión, 403 con rol `user`). El registro de la puerta: 200 para la dueña, 404 para el otro organizador y 403 `puerta-no-asignada` para una cuenta de puerta sin asignar, que tampoco escanea y no deja renglón. El otro organizador no ve ni toca el equipo de puerta (404). La dueña asigna la cuenta por email (en minúsculas, repetirlo devuelve la misma), y entonces escanea y ve el registro; al quitarla vuelve el 403. Pasó el 23/10 (24 de 24 comprobaciones) |
| 22 | Cancelación de un evento (desde el 18/11) | Anula las compras, también la hecha en la reventa, devuelve cada pago a quien corresponde, retira la reventa del evento y avisa a cada comprador (10.8) |
| 23 | Reglas entre compras | Hasta 6 entradas por comprador y evento, con el mensaje de cuántas puede comprar todavía, y un DNI con una sola entrada por evento, en la compra del catálogo. Por la reventa las prueban `ReventaDeEntradasEnH2Test`, `ServicioDeReventaTest` y la carpeta 6 de Postman (DEF-30 y DEF-66) |
| 24 | Cuenta y catálogo | Registro (un DNI inválido y un rol que el cliente no puede elegir), login y el 429 después de 5 fallidos, contraseña olvidada, mis compras, catálogo y detalle, alta de un evento y el resumen de ventas de su dueña |

**Corridas.** Desde el 3.er check point los corre el CI en cada push contra el `docker compose`: en la corrida del
21/11 sobre `fbd31dc`, 24 de 24 escenarios y 389 de 389 comprobaciones (`docs/testing/evidencia/final/ci-fbd31dc.txt`,
sección 3). La del 18/11 está en `docs/testing/evidencia/checkpoint-3/escenarios.txt`. **La del 16/10/2026, desde las
11:04, con el servidor armado desde cero con Facturación, Validación, Reventa y la conciliación de pagos: 17 de 17
escenarios.** Del 1 al 13 pasaron en la primera corrida. El 12, el 14,
el 15 y el 16 se repitieron después de corregir el guion de prueba, y pasaron: el token de servicio del guion vencía
durante la espera del 14, la DLQ ahora recibe 2 copias del mensaje inválido (una por suscriptor durable) y el
registro de la puerta se busca por el id del escaneo. El 14 mostró la conciliación en vivo: confirmó la venta con
ese mismo pago y emitió la entrada en menos de 3 minutos. La salida guardada en
`docs/entrega3/evidencia/integracion-13-escenarios.txt` es la de los 13 escenarios del 14/10 (de 17:58 a 18:00, con
el servidor armado desde el tag `entrega-parcial-2`), que es la que acompaña a la Entrega Parcial N.º 2; la del
16/10 no está archivada. El secreto de servicios lo toman de `PALCO_SECRETO_SERVICIOS`, de `backend/.env` o de
`backend/servidor/target/secreto-servicios`, donde lo deja `dev.sh` (sección 17.4).

Decisiones de diseño de la prueba, porque condicionan cómo leer el resultado:

- **Crea su propio evento** (como organizador), con un sector igual a Platea B y otro de 3 lugares. Así el cupo se
  compara exacto aunque otra persona compre en otros eventos.
- **Los contadores del broker son de todo el servidor.** Si alguien compra mientras corre, la prueba busca esas
  ventas en el `server.log` y las descuenta.
- **Los escenarios 8, 10, 11 y 12 detienen un consumidor o mueven mensajes entre colas, y el 14 hace fallar por unos
  segundos todo pago después del cobro**; si hay otra persona probando contra el mismo servidor se corren solo los
  otros doce. Cada uno vuelve a dejar el servidor como estaba (arranca el consumidor que detuvo o quita la propiedad),
  aunque falle.
- **El 14 tarda entre 2 y 4 minutos**, porque espera a la conciliación, que corre una vez por minuto y solo toma
  ventas con más de 2 minutos desde el intento de pago.
- **Deja datos**: ventas emitidas y canceladas de `titular@palco.test`, una cuenta nueva por corrida, el evento de
  prueba con sus facturas y escaneos, una publicación de reventa vendida a `otro.titular@palco.test` y el mensaje
  inválido del escenario 12 en la DLQ. Con `dev.sh` y H2 en memoria eso se borra al reiniciar; contra el compose queda
  en el volumen de PostgreSQL hasta un `docker compose down -v`.

## 16.3 Colección de Postman y Newman

`docs/api/palco-api.postman_collection.json` tiene nueve carpetas: "1 Usuarios (implementado)",
"2 Eventos (implementado)", "3 Diseño v1 (Ventas implementado)", "4 Seguridad (clase 11)" (15 requests que prueban
criterios de la matriz de seguridad), "5 Validación en puerta (implementado)", "6 Reventa (implementado)",
"7 Facturación (implementado)", "8 Operaciones del 30/10 (implementado)" y "9 Asistente (implementado)" (9.6). Al 25/11
tiene 219 requests: 214 en la corrida y 5 en la subcarpeta "Pasos manuales (fuera de la corrida)" de Usuarios, que
no se ejecutan con el resto porque necesitan códigos del log (reenviar el código, verificar el email y cambiar la
contraseña). Los que esperan la emisión o la factura se repiten hasta ver el resultado, por eso una corrida
ejecuta más requests que los que tiene la colección. No se edita a mano: la genera `scripts/postman/generar.js` (solo
Node). No usa ids fijos: los `V-…`, `T-…` y `P-…` se toman de las respuestas, así que se corre entera, en orden y sin
environment. El único valor que hay que pasar es el secreto de servicios, en la variable de colección
`secretoServicios`, que viene vacía (sección 17.4). En el CI la corre el job `api` contra el compose (16.7). La guía
es `docs/api/GUIA_POSTMAN.md`.

```bash
npx newman run docs/api/palco-api.postman_collection.json --env-var "secretoServicios=$(cat backend/servidor/target/secreto-servicios)"
npx newman run docs/api/palco-api.postman_collection.json --folder "3 Diseño v1 (Ventas implementado)"
```

| Corrida | Resultado |
|---|---|
| 14/10, Newman 6.2.3, con un servidor desplegado antes de que existiera la cuenta `otro.titular@palco.test` (registrada en `GUIA_POSTMAN.md`) | 53 requests, 201 tests, 9 tests fallados y un script cortado, todos en los tres requests que usan esa cuenta (su login respondía 401). El resto, 0 fallados |
| 14/10, alrededor de las 16:55, con el servidor redesplegado | 54 requests, 206 aserciones, 0 fallas. La salida no está guardada en el repo |
| 16/10, de 09:17 a 09:23, servidor armado desde cero con los cambios de seguridad, colección completa de entonces | 74 requests ejecutados, 252 aserciones, 1 falla: SEC-W05c, que entonces pedía una vigencia de 15 minutos. Ese mismo día el grupo decidió mantener la sesión en 8 horas (sección 18.5) y SEC-W05c pasó a comprobar esa vigencia |
| **16/10, desde las 11:04, servidor armado desde cero con Facturación, Validación, Reventa y la conciliación, colección completa** | **126 requests ejecutados, 406 aserciones, 0 fallas**, con las carpetas 5, 6 y 7 incluidas. La salida no está guardada en el repo |
| **19/11, Newman 6.2.3 contra WildFly, con las nueve carpetas** (registrada en `GUIA_POSTMAN.md`) | **171 requests (los 166 de la colección y los cinco intentos de login previos de SEC-W04) y 541 aserciones, 0 fallas**; la carpeta 9 es la del asistente (`checkpoint-3/postman.txt`) |
| **24/11, Newman 6.2.3 contra el `docker compose`, con las nueve carpetas** (registrada en `GUIA_POSTMAN.md`) | **219 requests (los 214 de la corrida y los cinco intentos de login previos de SEC-W04) y 682 aserciones, 0 fallas**, con los pedidos de `controles-de-compra` de DEF-66 |

En el CI la corre el job `api` en cada push (16.7).

## 16.4 La compra de punta a punta

La compra con su parte asincrónica la comprueban paso por paso los escenarios de integración (16.2): el 1 (reserva
del cupo, pago y emisión por la cola hasta `EMITIDA`, con las entradas `VALIDA`), el 3 (pago rechazado: 402, la venta
sigue `EN_CHECKOUT` y no sale ningún mensaje), el 7 (un mensaje en la cola y una copia por suscripción en el tópico:
aviso, auditoría y facturación) y el 9 (el aviso y la auditoría reciben cada uno su copia). La factura, la puerta y
la reventa son los escenarios 15, 16 y 17 y las carpetas 5 a 7 de Postman, y en el navegador la recorre la suite E2E
(16.7).

El cobro es el simulado (`PagosFake`: `tok_test` aprueba y `tok_rechazado` rechaza) y Mercado Pago es diseño (12.1).

## 16.5 Evidencia del proceso asincrónico

`docs/entrega3/evidencia/` es la evidencia que acompaña a la Entrega Parcial N.º 2. Las tomas principales son del
**14/10/2026, de 17:56 a 18:00**, con el servidor armado desde cero desde el commit `580dd05` (el del tag
`entrega-parcial-2`). Cada archivo dice arriba qué muestra y, por paso, la fecha, el comando exacto y su salida sin
retocar. Los tokens no se guardan: se guarda el comando con que se pidieron. `LEEME.txt` explica cada archivo. Es
anterior a Facturación: por eso muestra el tópico con dos suscripciones.

**La compra de la toma** (venta `V-25d98b55`, pago `P-dbe21ef4`), escrita por `tomar-evidencia.sh`, que se puede
repetir:

| Archivo | Qué muestra |
|---|---|
| `broker-antes.txt` | Cola, tópico y DLQ antes de la compra (todo en 0: el servidor recién arrancaba) |
| `compra.txt` | Abrir la compra (201, `EN_CHECKOUT`) y pagarla (201, `CONFIRMADA`, entradas `PENDIENTE_EMISION`) |
| `venta.txt` | La venta segundos después: `EMITIDA`, con sus entradas `VALIDA` y sus códigos |
| `entrada.txt` | Una entrada: `VALIDA`, con `codigoQr` y fecha de emisión |
| `broker-despues.txt` | Cola +1 sin pendientes, tópico +2 (una copia por suscriptor de entonces: aviso y auditoría), DLQ sin cambios |
| `server-log-extracto.txt` | Las líneas del log con el id de la venta: compra, pago guardado por `ServicioDePagos` y los tres consumidores |
| `auditoria.txt` | La fila que guardó la auditoría por ese mensaje |

**Guiones que repiten el proceso:**

| Archivo | Qué muestra |
|---|---|
| `reproducir.sh` / `reproduccion.txt` | Configuración del broker (5 entregas, 2000 ms entre una y otra, las de `configurar.cli`), una compra completa con la línea de `FabricaDeCobros` que dice quién cobra, la transacción deshecha (409 y contadores sin cambios), control por rol (401, 403, 200) y la reserva de cupo (+1 con la compra abierta, vuelve al cancelar) |
| `suscriptor-durable.sh` / `suscriptor-durable.txt` | Con la auditoría detenida (`stop-delivery`) los 2 mensajes esperan en la suscripción durable mientras el aviso y la emisión siguen; al reanudarla aparecen las 2 filas |
| `integracion-13-escenarios.txt` | La salida de los 13 escenarios del 14/10 (sección 16.2) |
| `compras-simultaneas.mjs` / `compras-simultaneas.txt` | Del 13/10, con código anterior al tag. 80 compras a la vez, 20 pedidos con la misma clave de idempotencia y 30 pagos a la vez: todos 201. Incluye el antes (28 abiertas y 52 sin respuesta con una versión anterior de Ventas) y muestra que Ventas no retiene una conexión de la base mientras espera a Eventos o a Pagos |

**Tomas de la mañana del 13/10** (servidor anterior, con código anterior al tag; su log ya no existe):

| Archivo | Qué muestra | Aclaración |
|---|---|---|
| `venta-inexistente.txt` | El mensaje de una venta que no existe se descarta con un aviso, sin volver a la cola ni ir a la DLQ | |
| `mensajes-pendientes.txt` | 64 mensajes esperando antes de que existiera el consumidor; al desplegarlo quedaron 0 | Un paso está resumido |
| `mensaje-repetido-y-reintentos.txt` | Un mensaje repetido no emite dos veces; un mensaje que no se entiende va a reintentos y DLQ | Salida de un guion que no está en el repo; no se puede repetir solo con el repo. Ventas cobraba con su simulador local |
| `vencimiento-de-reserva.txt` | Una compra sin pagar pasa a `VENCIDA` a los 10 minutos, el cupo vuelve de 521 a 520 y pagarla después da 409 | Es un resumen sin comandos; no se repitió porque exige esperar 10 minutos |

**Alcance de esta evidencia:** no se mueve plata (pasarela simulada, `PagosFake`, sin llamadas a Mercado Pago); se
tomó con `dev.sh`, con la base H2 en memoria, así que las ventas se pierden al reiniciar (el broker sí guarda sus
mensajes); y los contadores `messages-added` son acumulados desde el arranque, así que vale la diferencia entre antes
y después. Una caída del servidor entre el commit de la base y el del broker no se provoca: es el riesgo aceptado del
último recurso (10.5 y sección 21). La conciliación de pagos cubre la falla después del cobro y antes de confirmar
(escenario 14), no una venta que ya quedó `CONFIRMADA` en la base sin que su mensaje llegara al broker.

## 16.6 La verificación conjunta del 16/10

Las corridas posteriores están en 16.7 y 16.8: las del 3.er check point (18 y 19/11, en
`docs/testing/evidencia/checkpoint-3/`), la del CI del 21/11 sobre `fbd31dc`, con los 19 checks en verde, las de carga
y estabilidad del 24/11 y la de Postman del 24/11 (16.3).

La verificación conjunta del **16/10/2026, desde las 11:04**, fue con el servidor armado desde cero con
Facturación, Validación, Reventa y la conciliación de pagos. Las filas de la cuenta nueva, el 401 por token inválido
y el límite de intentos son de la verificación anterior de ese día (de 09:17 a 09:23), con los cambios de seguridad.

| Qué | Comando | Última corrida | Resultado |
|---|---|---|---|
| Tests del backend | `mvn -f backend/pom.xml clean install` | 16/10, 11:04 | BUILD SUCCESS, 577 tests, 0 fallas (common 52, Usuarios 45, Eventos 41, Ventas 273, Pagos 31, Validación 26, Reventa 27, Notificaciones 33, Facturación 38, ARCA simulado 11) |
| Armado desde cero | `mvn -f backend/pom.xml clean install` y `backend/dev.sh` | 16/10, 11:04 | 9 de 9 WAR desplegados (los 8 servicios y el ARCA simulado), 0 líneas `ERROR` en el log al arrancar |
| Health de los 8 servicios | `curl` a cada `health` (17.2) | 16/10, 11:04 | Los 8 en 200: `/api/usuarios`, `/api/eventos`, `/api/v1`, `/api/v1/pagos`, `/api/v1/validacion`, `/api/v1/reventas`, `/api/notificaciones`, `/api/v1/facturas` |
| WSDL del ARCA simulado | `curl "localhost:8080/legado/arca?wsdl"` | 16/10, 11:04 | 200 |
| 17 escenarios | `node scripts/pruebas/escenarios.mjs` | 16/10, desde las 11:04 | 17 de 17 (el 12, 14, 15 y 16 repetidos después de corregir el guion de prueba; 16.2) |
| Postman | `npx newman run docs/api/palco-api.postman_collection.json` | 16/10, desde las 11:04 | 126 requests ejecutados, 406 aserciones, 0 fallas |
| Demo de la compra | Una compra con `curl`, con el pago aprobado y con el rechazado | 16/10, desde las 11:04 | código 0 en los dos casos; el tópico sumó 3 copias por compra |
| Lint del frontend | `npm run lint --prefix frontend` | 16/10 | 0 errores |
| Build del frontend | `npm run build --prefix frontend` | 16/10 | código 0 |
| Pantallas de puerta, reventa y panel del organizador | `npm run e2e` (Playwright, 16.7) | Desde el 19/11, en cada corrida del CI | `puerta.spec.ts`, `reventa.spec.ts` y `organizador.spec.ts` en verde, dentro de las 31 de 31 pruebas E2E del 21/11 sobre `fbd31dc` |
| Cuenta nueva de punta a punta | Registro, verificación, login y compra | 16/10, 09:17 a 09:23 | La compra llegó a `EMITIDA` |
| 401 por token inválido, en vivo | `GET /api/v1/ventas` con un token inválido y `Origin: http://localhost:5173` | 16/10, 09:17 a 09:23 | 401, `application/problem+json`, `type` `/problemas/token-invalido`, `WWW-Authenticate: Bearer error="invalid_token"`, `Access-Control-Allow-Origin: http://localhost:5173` |
| Límite de intentos, en vivo | Seis logins fallidos con un mismo email | 16/10, 09:17 a 09:23 | Los 5 primeros, 401; el 6.º, 429 con `Retry-After: 898` |

Estado: **Implementado** para todo lo de la tabla. De los tests del backend, de Newman y de las verificaciones del
16/10 no hay salida guardada en el repo: el resultado está anotado, no archivado. La evidencia archivada en
`docs/entrega3/evidencia/` sigue siendo la del 14/10. Desde entonces, la salida de los escenarios quedó archivada
(`docs/testing/evidencia/checkpoint-3/escenarios.txt`, 18/11) y el frontend tiene su suite E2E (16.7).

## 16.7 Pruebas del 3.er check point (20/11)

Cada corrida dejó su salida en `docs/testing/evidencia/checkpoint-3/`. El plan de pruebas
(`docs/testing/plan-de-pruebas.md`) explica cómo repetirlas contra el `docker compose`.

| Prueba | Comando | Resultado | Evidencia |
|---|---|---|---|
| Tests del backend y cobertura (JaCoCo) | `cd backend && mvn verify` y `node scripts/qa/cobertura.mjs backend` | 966 tests, 0 fallas, 115 del asistente; 78,4 % de las líneas y 74,1 % de las ramas (19/11) | `cobertura.md` |
| Escenarios de integración | `node scripts/pruebas/escenarios.mjs` | 24 de 24, 379 comprobaciones (19/11) | `escenarios.txt` |
| Asistente en vivo | `node scripts/pruebas/asistente.mjs` | 11 de 11: modelo, guardrails, tiempo máximo con fallback, apagado y 401 (19/11) | `asistente.txt` |
| Asistente con un proveedor por HTTP | `node scripts/pruebas/asistente-proveedor.mjs --configurar` | 29 de 29: `ModeloCompatibleOpenAI` contra un proveedor local con la API de chat de OpenAI; respuesta, fallback por demora, 401, 500 y JSON roto, guardrails y apagado (19/11) | `asistente-proveedor.txt` |
| Set de evaluación de la IA | `mvn -f backend/pom.xml -pl servicios/asistente -am test` | 26 de 26 y 11 de 11 adversariales; criterio: como mucho 2 fallas y ninguna adversarial (19/11) | `evaluacion-ia.txt` |
| Broker apagado | `node scripts/pruebas/broker-apagado.mjs` | 10 de 10, ningún mensaje perdido (19/11, con H2 en archivo para que la base sobreviva al reinicio) | `broker-apagado.txt` |
| Broker con el contenedor recreado | Con un mensaje pendiente, `docker compose down` (sin `-v`) y `docker compose up -d --wait` (pasos en `scripts/LEEME.md`) | 16 de 16 (19/11): el contenedor del backend es otro y la venta pasa a `EMITIDA`. El journal está en el volumen `datos-servidor` (ADR-005) | `broker-recreado.txt` |
| ARCA sin responder, DLQ y reproceso | Contra el compose, `palco.arca.simular-sin-respuesta=true` con `jboss-cli` y `move-messages` desde la DLQ (10.6) | 29 de 29 (19/11): la compra se emite igual, cada pedido se corta a los 5 s, 5 entregas cada 2 s, la DLQ a los 33 s y, al devolver el mensaje, una sola factura con su CAE (11.9) | `arca-lento-dlq.txt` |
| `ETag` e `If-Match` | `node scripts/pruebas/etag.mjs` | 19 de 19 (19/11) | `etag.txt` |
| Patrón State de la venta | `node scripts/pruebas/patron-state.mjs` | 10 de 10: las transiciones inválidas responden 409 | `patron-state.txt` |
| Colección de Postman | `npx newman run docs/api/palco-api.postman_collection.json` | Nueve carpetas, la 9 del asistente incluida: 171 requests (los 166 de la colección y los cinco intentos de login previos de SEC-W04) y 541 aserciones, sin fallas (19/11) | `postman.txt` y `docs/api/GUIA_POSTMAN.md` |
| Interfaz en el navegador | `cd frontend && npm run e2e` | 17 de 17 con Playwright y Chromium (19/11) | `e2e.txt` |
| Matriz de trazabilidad | `node scripts/qa/matriz.mjs` con la salida de los escenarios y los reportes de surefire | 62 criterios de aceptación de 14 historias, los 62 con caso y en verde (19/11) | `docs/testing/evidencia/matriz-de-trazabilidad.md` |

**CI (al 25/11).** `.github/workflows/ci.yml` corre en cada push a `master`, en cada pull request, en los tags `v*` y
a mano. Sus 10 jobs van en paralelo y, si uno falla, la corrida queda en rojo:

| Job | Qué hace |
|---|---|
| Secretos | `gitleaks` sobre todo el historial |
| Backend | `mvn -B verify` con Temurin 25: los tests; los `*IT`, con failsafe: los repositorios de Usuarios, Eventos, Ventas y Facturación contra PostgreSQL 17 y el consumidor de Facturación contra Artemis 2.54.0, cada uno en un contenedor con Testcontainers; la cobertura con JaCoCo, con el umbral de negocio y dominio (80 % de líneas y 70 % de ramas por módulo, ejecución `umbral-de-negocio-y-dominio` de `backend/pom.xml`: si un módulo queda debajo, el build falla), y el análisis estático con PMD (reglas `quickstart`; los hallazgos se informan y no cortan el build, pero si PMD no puede analizar el código el job queda en rojo). El paso "Integración con dependencias reales en contenedor (los *IT)" pone en el resumen cuántos IT corrieron en cada módulo y deja el job en rojo si alguno se salteó (sin Docker se saltean). `scripts/qa/cobertura.mjs` y `scripts/qa/analisis-estatico.mjs` ponen las tablas de cobertura y de PMD en el resumen de la corrida, y los reportes de surefire, failsafe, JaCoCo y PMD quedan como artefacto |
| SonarCloud | `mvn -B verify`, porque el escáner necesita en la misma máquina las clases compiladas, las dependencias y el `jacoco.xml` de cada módulo, y después el análisis con la acción oficial `sonarqube-scan-action` v8.4.1, fijada al hash de su commit. La configuración está en `sonar-project.properties`. SonarCloud analiza con su alcance nuevo, que toma el repo entero y no separa el código de prueba: por eso el código de prueba se saca con `sonar.exclusions` (`**/src/test/**`, `frontend/e2e/**`, `frontend/playwright.config.ts`, `scripts/**` y `docs/**`), y queda adentro lo que se despliega o arma el sistema: el `src/main` de cada módulo del backend, el frontend, los Dockerfile, el compose, los guiones de arranque, la configuración de nginx y los workflows. La cobertura sale del `jacoco.xml` de cada módulo y se mide solo sobre el Java del backend (`sonar.coverage.exclusions` saca el frontend y la infraestructura). El Quality Gate lo calcula SonarCloud y aparece en GitHub como el check "SonarCloud Code Analysis". No corre en un pull request que viene de un fork, que no recibe `SONAR_TOKEN` |
| Frontend | `npm ci`, `npm run lint` y `npm run build` con Node 22 |
| Integración (4 jobs) | Cada uno levanta su sistema con `docker compose up --build --wait`, con un secreto entre servicios generado para la corrida; prueba por HTTPS las cabeceras de seguridad y los 9 `health`, el del asistente incluido; corre su parte de `escenarios.mjs` y, desde el 25/11, pasa el log del servidor por `scripts/qa/control-de-logs.sh`: sin tokens, contraseñas de prueba ni DNI fuera de los emails del modo completo (DEF-40). Los escenarios 14, 19 y 20 esperan a una conciliación, que corre una vez por minuto, y van en un job cada uno; el general corre los demás, `etag.mjs`, `patron-state.mjs`, `asistente.mjs` y, al final porque reinicia el servidor, `broker-apagado.mjs` |
| E2E | `docker compose up` y `npm run e2e` contra `https://localhost`; el reporte de Playwright queda como artefacto |
| API | `docker compose up` y la colección de Postman con Newman 6.2.3 contra `http://localhost:8080`, con el secreto entre servicios de la corrida en `secretoServicios`: las nueve carpetas y después los "Pasos manuales" (reenviar el código y verificar el email), con el email y el código que lee del log del servidor. Las tablas de Newman quedan en el resumen de la corrida, y la salida, los reportes JUnit y el log, en el artefacto `reporte-api` (`docs/api/GUIA_POSTMAN.md`, sección 7) |

**Los demás workflows y los checks de un commit.** Con cada push a `master` corren, además de `ci.yml`, seis workflows
aparte: `seguridad.yml` (Trivy, 3 jobs), `zap.yml`, `mutacion.yml`, `contrato.yml`, `pruebas-seguridad.yml` (14.10 y
16.8) y `lighthouse.yml` (16.8). Todos menos `lighthouse.yml` corren también en los pull requests. `carga.yml` y
`estabilidad.yml` corren solo con los tags `v*` y a mano. La lista, con lo que hace cada uno, está en la cabecera de
`ci.yml`. Sobre el commit `fbd31dc` (21/11), los 19 checks que dispara un push terminaron en verde: los 10 jobs de
`ci.yml` (run 44867187162), los 3 de `seguridad.yml`, el job de cada uno de los otros cinco workflows y el Quality Gate
de SonarCloud ("Quality Gate passed": 0 security hotspots, 95,0 % de cobertura y 0,0 % de duplicación en el código
nuevo, con 128 issues nuevos abiertos y 1 aceptado). El 24/11 se corrieron a mano sobre el mismo commit `carga.yml`
(run 45439831486) y `estabilidad.yml` (run 45439837443), y los dos terminaron en verde: en las tres corridas de la
suite del backend pasaron los mismos 1186 tests, sin ninguno inestable. El 25/11, sobre `12a08f2`, los siete
workflows que corren con cada push terminaron en verde y el Quality Gate de SonarCloud pasó, después de la corrección
de DEF-76 (el bug `java:S8696` en `AsistenteIA`). Los números de cada corrida sobre el tag `v1.0-final` salen de esos
mismos workflows, con `carga.yml` y `estabilidad.yml`, que corren con cada tag `v*`.

**Suite E2E** (`frontend/e2e/`, configuración en `frontend/playwright.config.ts`). Recorre en Chromium la sesión, el
registro, la recuperación de la contraseña, el catálogo, la compra, la reventa, el panel del organizador, la puerta,
el asistente y los temas, más la accesibilidad (`accesibilidad.spec.ts`) y el XSS almacenado (`xss.spec.ts`), contra
el backend real. En la corrida del CI sobre `fbd31dc` fueron 31 pruebas, las 31 en verde; desde el 24/11 la suite
tiene 39, con las ventas por día, la carga diferida, el aviso sin conexión, la factura rechazada y la demorada y la
foto de la categoría (15.5). Cada prueba crea sus propios datos con nombres únicos, y el código de verificación de las cuentas nuevas lo lee
del `server.log`. Se corre con `npm run e2e` contra el servidor de Vite o con `PALCO_E2E_URL=https://localhost`
contra el `docker compose`, como en la CI.

**Plan de pruebas, matriz y manual.** Las primeras versiones son `docs/testing/plan-de-pruebas.md` (niveles,
entornos, criterios, casos por requisito, cobertura y resultados), `docs/testing/evidencia/matriz-de-trazabilidad.md`
(cada criterio de las historias HU-01 a HU-14, las de la corrida del 19/11, con los escenarios y los tests de JUnit
que lo prueban) y `docs/manual/manual-de-usuario.md` (comprar y revender, consultar al asistente, administrar eventos
y validar en la puerta, con capturas de la aplicación).

**Historias, casos y defectos.** Desde el 20/11, `docs/documentacion/historias-de-usuario.md` tiene 16 historias, de
HU-01 a HU-16 (9 Must, 5 Should y 2 Could), con 89 criterios de aceptación escritos en Gherkin: se sumaron HU-15
(preguntarle al asistente) y HU-16 (subir la foto del evento). `docs/testing/especificacion-de-casos.md` especifica
281 casos de prueba, con al menos uno por criterio: 40 positivos, 147 negativos y 94 de borde, es decir 241 negativos
o de borde (85,8 %), y 272 automatizados (96,8 %). `docs/testing/registro-de-defectos.md` registra cada
defecto conocido con su severidad, prioridad, pasos, resultado esperado y obtenido, evidencia y estado, y lleva los
totales por estado y por severidad. DEF-29 (crítico: al cancelar un evento, quien compró en la reventa no recibía la
devolución) y DEF-31 se cerraron el 20/11 con el escenario 22 contra el compose
(`docs/testing/evidencia/final/cancelacion-con-reventa.txt`, 10.8). DEF-30 (mayor) y DEF-32 (menor) tienen su
corrección en el código desde el 20/11, y DEF-33 (menor), desde el 20/11 y el 24/11; el estado de cada uno, con la
prueba que lo cierra sobre el tag, está en el registro.
DEF-30: la compra en la reventa controla, antes de cobrar, el tope de 6 entradas por evento y el DNI por evento
(`POST /api/v1/operaciones-de-reventa/controles-de-compra`, 7.10). Lo prueban `ReventaDeEntradasEnH2Test` y
`ServicioDeReventaTest`, y la carpeta 6 de Postman desde el 24/11 (DEF-66); el escenario 23 prueba las dos reglas por
el catálogo. Desde el 24/11 (DEF-46), además, la transferencia las vuelve a mirar con los candados del catálogo, para
dos compras a la vez (7.10). DEF-32: el registro de la puerta trae el email de quien escaneó (migración V4 de
Validación, 4.6), y lo comprueba el escenario 16. DEF-33: un comprobante que ARCA rechazó queda
anotado y la consulta responde 409, que la pantalla de la factura muestra como rechazo, sin "Volver a consultar"
(10.6 y 11.4). La otra mitad de DEF-33 es una factura cuyo mensaje quedó en la DLQ, que aparecía como en camino:
desde el 24/11, "Mis entradas" (`MyTicketsPage.tsx`) le pasa la fecha de la compra al aviso de la factura
(`FacturaModal.tsx`), que pasados 5 minutos dice que está demorada. Las dos mitades las prueba
`frontend/e2e/factura-rechazada.spec.ts`. La matriz, regenerada el 24/11, cubre los 89 criterios de las 16
historias: los 89 tienen caso y están en verde.

## 16.8 Pruebas que se suman para la Entrega Final

Desde el 20/11 el repositorio tiene estas pruebas, que forman parte del plan de la Entrega Final. Las que van en un
workflow propio corren con cada tag `v*`, así que corren con el tag de la entrega, `v1.0-final`. Las de seguridad
están en la sección 14.10.

| Prueba | Qué comprueba | Criterio | Dónde y cuándo corre |
|---|---|---|---|
| Contrato REST con Schemathesis 4.32.7 (`.github/workflows/contrato.yml`) | Que lo que responde el sistema sea lo que dice `docs/api/openapi.yaml`. Schemathesis arma pedidos válidos e inválidos para cada operación a partir de sus esquemas y ejemplos (`--mode all`, 25 por operación), en cuatro corridas, una por rol y con el token de su cuenta: comprador (todas las operaciones), organizador (las etiquetas `eventos` y `validacion`, y `/v1/resumenes`), puerta (`validacion`) y servicio (las operaciones entre servicios). Con un solo rol, las operaciones de los demás responderían 403 y su respuesta de éxito no se probaría | Ninguna respuesta 5xx, el código documentado en esa operación y el cuerpo con el esquema documentado para ese código (`not_a_server_error`, `status_code_conformance` y `response_schema_conformance`). Si una corrida encuentra algo, el job falla | Contra su propio compose, en cada push a `master` o `main`, en cada pull request, con cada tag `v*` y a mano. Los reportes JUnit y HTML quedan en el artefacto `reporte-contrato` |
| Contrato SOAP (`ContratoDelWsdlTest`, 4.9) | Que el WSDL que publica el ARCA simulado sea la copia de referencia `docs/api/arca.wsdl`, y que el pedido de Facturación y las respuestas aprobada y rechazada cumplan su XSD | Que coincidan y que el XSD rechace una respuesta que no lo cumple | En el `mvn verify` del job Backend de `ci.yml` |
| Mutación con PIT (`.github/workflows/mutacion.yml`, `scripts/qa/mutacion.mjs`) | PIT muta el paquete de dominio de cada servicio (`com.palco.*.dominio.*`) y corre los tests del módulo contra cada mutante | Que muera al menos el 60 % de los mutantes de cada módulo (`mutacion.minimo` en `backend/pom.xml`). Si un módulo queda debajo, la corrida queda en rojo | Workflow aparte, con los mismos disparadores que `ci.yml`, porque tarda más que los tests |
| Carga con k6 (`scripts/carga/palco.js`, `.github/workflows/carga.yml`) | 50 usuarios virtuales durante 5 minutos contra el compose, por nginx y HTTPS, cada uno con su propia cuenta (las crea `scripts/carga/cuentas.mjs`): el catálogo, el detalle del evento, abrir una compra y cancelarla, con pausas de 0,5 a 1,5 s entre página y página. El login se mide sin umbral, porque PBKDF2 es lento a propósito, y el pago no entra, porque gastaría entradas | En cada endpoint crítico, p95 menor que 500 ms y menos de 1 % de errores, declarados como thresholds de k6: si alguno no se cumple, k6 termina con error | Con cada tag `v*` y a mano, porque tarda más de 15 minutos. El artefacto `reporte-carga` lleva el texto, el JSON y el panel HTML de k6 y las estadísticas del pool de conexiones de la base |
| Accesibilidad y responsive (`frontend/e2e/accesibilidad.spec.ts`) | Las pantallas del flujo principal y las de cada rol a 360, 768 y 1280 px de ancho, con axe-core (`@axe-core/playwright`, WCAG 2.0, 2.1 y 2.2, niveles A y AA) | Ninguna violación de impacto serious o critical y ningún desborde horizontal. Las de impacto menor quedan en el reporte, con una captura de cada pantalla | Con las demás pruebas E2E, en el job `e2e` de `ci.yml` |
| Lighthouse CI 0.16.0 (`.lighthouserc.cjs`, `.github/workflows/lighthouse.yml`) | Accesibilidad y buenas prácticas de las cinco pantallas públicas (catálogo, detalle de un evento, ingresar, registro y recuperar la contraseña), sin sesión, en perfil de teléfono (360 px) y de escritorio (1280 px) | Accesibilidad de 90 o más, y ninguna de las auditorías que axe-core clasifica como críticas fallada: si no, la corrida falla. Buenas prácticas de 90 o más, como advertencia | Contra su propio compose, en cada push a `master` o `main`, con cada tag `v*` y a mano. Artefacto `reportes-lighthouse`. Sobre `fbd31dc` (21/11, run 44867187212): 100 en las dos categorías en las diez combinaciones de pantalla y perfil |
| Estabilidad de la suite del backend (`.github/workflows/estabilidad.yml`, `scripts/qa/estabilidad.mjs`) | Compila una vez, corre `mvn test` tres veces seguidas en la misma máquina y compara los reportes de surefire test por test | Que cada test dé el mismo resultado en las tres corridas y que las tres terminen en verde: un test que cambia, o que falla y pasa al reintentarlo, deja el job en rojo | Con cada tag `v*` y a mano. Sobre `fbd31dc` (24/11, run 45439837443): 1186 tests en cada corrida, los 1186 en verde las tres veces, ninguno inestable |

# 17. Cómo levantar y probar el sistema

## 17.1 Requisitos

| Para | Qué hace falta | Nota |
|---|---|---|
| Backend | JDK 25 y Maven 3.9+ | WildFly 41 no se instala: lo descarga Maven la primera vez. En Windows, cualquier JDK 25 (por ejemplo Temurin) con `JAVA_HOME` apuntando a él |
| Frontend | Node.js `^20.19.0` o `>=22.12.0` (lo exige Vite 8) y npm | |
| Escenarios y Postman | Node 18+ (los escenarios no usan paquetes); Newman se instala aparte o se usa con `npx` | |
| Guiones | `bash`, `curl`, `grep` y `sed` | En Windows, Git Bash. No usan `jq` |

## 17.2 Backend

```bash
cd backend
cp .env.example .env   # solo la primera vez
./dev.sh
```

`dev.sh` carga el `.env`, corre `mvn -q -B install` (compila los 10 WAR, los 9 servicios y el ARCA simulado, y arma WildFly en
`backend/servidor/target/server`, aplicando `configurar.cli`) y levanta el servidor en `http://localhost:8080`. La
primera vez tarda unos minutos por la descarga. Para recompilar y redesplegar un solo servicio sin reiniciar:
`./redesplegar.sh <servicio>`.

Comprobación:

```bash
curl localhost:8080/api/usuarios/health
curl localhost:8080/api/eventos/health
curl localhost:8080/api/notificaciones/health
curl localhost:8080/api/v1/health          # Ventas
curl localhost:8080/api/v1/pagos/health    # Pagos
curl localhost:8080/api/v1/validacion/health   # Validación
curl localhost:8080/api/v1/reventas/health     # Reventa
curl localhost:8080/api/v1/facturas/health     # Facturación
curl localhost:8080/api/v1/asistente/health    # Asistente
curl "localhost:8080/legado/arca?wsdl"         # WSDL del ARCA simulado
```

Los 9 tienen que responder 200, y el WSDL también. En la verificación del 16/10 a las 11:04, antes del asistente,
respondieron así los 8 de entonces y el WSDL (16.6).

**En Windows**, desde Git Bash:

```bash
export JAVA_HOME="/c/Users/<usuario>/.jdks/temurin-25"   # o donde esté el JDK 25
cd backend && ./dev.sh
```

Los tests también corren desde PowerShell: `mvn -f backend\pom.xml test`.

**En Linux y macOS**, los mismos comandos en una terminal Bash (en macOS: `brew install openjdk@25 maven`).

## 17.3 Frontend

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173
npm run lint
npm run build
```

Hay que abrir la app en `http://localhost:5173` tal cual: el backend solo acepta los orígenes de
`PALCO_CORS_ORIGENES`, así que con `127.0.0.1:5173` o con `npm run preview` (puerto 4173) las llamadas fallan. Sin
backend, el catálogo muestra un error con el botón "Reintentar".

## 17.4 Variables de entorno

Ninguna es obligatoria en desarrollo: sin `.env` el sistema arranca con H2 en memoria y valores de desarrollo, y el secreto entre servicios lo genera `dev.sh`.

| Variable | Dónde | Para qué | Por defecto |
|---|---|---|---|
| `PALCO_CORS_ORIGENES` | `backend/.env` | Orígenes que pueden llamar a la API | `http://localhost:5173`; el compose la define con la URL pública (14.4) |
| `PALCO_DB_DRIVER`, `PALCO_DB_URL`, `PALCO_DB_USUARIO`, `PALCO_DB_PASSWORD` | `backend/.env` | Base PostgreSQL | Sin definir: H2 en memoria, que se borra al reiniciar. El compose las define para su PostgreSQL 17 |
| `PALCO_JWT_CLAVE_PRIVADA` | `backend/.env` | Clave RSA con que Usuarios firma los tokens | Se genera una nueva en cada arranque (hay que volver a iniciar sesión) |
| `PALCO_URL_INTERNA` | `backend/.env` | Dónde encuentran los servicios a Usuarios para validar tokens | `http://localhost:8080` |
| `PALCO_SECRETO_SERVICIOS` | `backend/.env` | Secreto compartido para pedir el token de rol `servicio`. Si no está en el entorno ni en `.env`, `dev.sh` genera uno en cada arranque y lo deja en `backend/servidor/target/secreto-servicios` | Sin valor por defecto: cada servicio usa uno al azar, no puede llamar a los otros y el log lo avisa como error |
| `PALCO_PAGOS_PROVEEDOR` | `backend/.env` | Pasarela de Pagos | `fake` (único valor posible hoy) |
| `PALCO_VENTAS_COBROS` | `backend/.env` | Quién cobra en Ventas: `pagos` (REST a Pagos) o `fake` (simulador local, sin pago guardado) | `pagos` |
| `PALCO_FACTURACION_ARCA_WSDL` | `backend/.env` | Dirección del WSDL de ARCA que usa Facturación | `${PALCO_URL_INTERNA}/legado/arca?wsdl`, el ARCA simulado del mismo servidor |
| `PALCO_ASISTENTE_MODELO` | `backend/.env` | Modelo del asistente: `simulado` u `openai` (4.10). Se elige una sola vez por despliegue | `simulado`: respuestas fijas, sin red |
| `PALCO_ASISTENTE_OPENAI_URL`, `PALCO_ASISTENTE_OPENAI_MODELO`, `PALCO_ASISTENTE_OPENAI_CLAVE` | `backend/.env` | Dirección, modelo y clave del proveedor con la API de chat de OpenAI. La clave es secreta | `https://api.openai.com/v1`, `gpt-4o-mini` y sin clave |
| `PALCO_USUARIOS_CUENTAS_DE_PRUEBA` | `backend/.env` | Con `false`, Usuarios desactiva al arrancar las seis cuentas de prueba (17.5): les pone un hash que ninguna contraseña abre. Va en `false` fuera de desarrollo | `true`: las cuentas sirven, como necesitan el compose, el CI y las pruebas |
| `PALCO_CORREO_LOG_COMPLETO` | `backend/.env` | Sin servidor SMTP, con `true` el log del servidor trae cada email entero, con el código de verificación (14.6). Es el modo de desarrollo: lo prende `backend/dev.sh`; el `docker-compose.yml` lo deja en `false` salvo que se lo levante con la variable en `true` (en el entorno o en el `.env` de la raíz), y en un despliegue no se define | Sin definir, o con cualquier valor que no sea `true`: el renglón trae solo el destinatario enmascarado y el asunto |
| `VITE_API_URL` | `frontend/.env` | URL del backend | `http://localhost:8080` |

El `.env` no se sube al repo. Las migraciones están escritas para correr en las dos bases: contra H2 en `dev.sh` y en
los tests, y contra PostgreSQL 17 en el `docker compose` y en los `*IT` de Usuarios, Eventos, Ventas y Facturación
(16). Con las mismas cuatro variables, el datasource apunta a cualquier otro PostgreSQL.

**El secreto de servicios fuera del servidor.** Quien pida un token de rol `servicio` desde afuera tiene que usar el
mismo `PALCO_SECRETO_SERVICIOS` con el que arrancó el servidor. `dev.sh` lo deja en
`backend/servidor/target/secreto-servicios`, venga del entorno, de `backend/.env` o lo haya generado él:

- Los escenarios y los demás guiones de `scripts/pruebas/` lo leen solos: primero de la variable de entorno
  `PALCO_SECRETO_SERVICIOS`, después de la línea de `backend/.env` y, si no hay ninguna de las dos, de ese archivo.
  No tienen un valor por defecto: sin ninguno de los tres, el guion se corta con un error que dice qué falta. Desde el
  25/11 (DEF-56), los guiones de evidencia de `docs/entrega3/evidencia/` lo buscan igual.
- La colección de Postman lo toma de la variable de colección `secretoServicios`, que viene vacía. Con Newman se pasa
  con `--env-var secretoServicios=...` (el valor, con `$(cat backend/servidor/target/secreto-servicios)`); en Postman
  se carga en las variables de la colección.
- Contra el compose, el secreto se define antes del `up` (`PALCO_SECRETO_SERVICIOS` en el entorno o en un `.env`
  junto a `docker-compose.yml`): si no, `backend/docker/arrancar.sh` genera uno y no lo muestra.

**Fuera de desarrollo** (decidido el 16/10): no se implementa que el servidor se niegue a arrancar sin secreto. Es un
requisito de despliegue: hay que definir `PALCO_SECRETO_SERVICIOS` con el mismo valor en todos los servicios. Si
falta, ningún servicio acepta un secreto conocido: las llamadas entre servicios fallan y el log lo avisa.

## 17.5 Cuentas de prueba

Las cargan las migraciones de Usuarios. Todas usan la contraseña `palco1234`. Los seis eventos iniciales son de
`organizador@palco.test`, y `puerta@palco.test` está asignada a la puerta de todos. Fuera de desarrollo se desactivan
con `PALCO_USUARIOS_CUENTAS_DE_PRUEBA=false` (5.6).

| Email | Rol | Acceso |
|---|---|---|
| `titular@palco.test` | `user` | Compra, mis entradas, reventa |
| `organizador@palco.test` | `organizer` | Lo anterior y el panel `/organizador` |
| `puerta@palco.test` | `staff` | Lo anterior y `/puerta` |
| `otro.titular@palco.test` | `user` | Segunda cuenta compradora |
| `otro.organizador@palco.test` | `organizer` | Segundo organizador, sin eventos: no ve los de `organizador@palco.test` (desde el 23/10) |
| `otra.puerta@palco.test` | `staff` | Segunda cuenta de puerta, sin puertas asignadas (desde el 23/10) |

La colección de Postman y los escenarios usan `otro.titular@palco.test` para probar que nadie ve compras ajenas y
para comprar en la reventa una entrada publicada por `titular@palco.test`. Las cuentas nuevas se crean desde `/registro` con rol `user`; el código de verificación de 6 dígitos
queda en el log del servidor, porque sin servidor SMTP el email no sale (**Simulado**). El renglón lo trae con
`PALCO_CORREO_LOG_COMPLETO=true`, que `dev.sh` prende. Con el compose se lo exporta antes del `up` o se lo pone en
el `.env` de la raíz, porque por defecto el compose no lo prende (14.6).

Para el cobro simulado: `tok_test` (o cualquier otro token) aprueba, `tok_rechazado` rechaza y `tok_sin_respuesta` no
contesta.

Desde el 16/10, 5 logins fallidos en 15 minutos con un mismo email bloquean ese email: el pedido siguiente responde
429 con `Retry-After`, aunque la contraseña sea la correcta. Si una cuenta de prueba queda bloqueada, se espera lo que
dice `Retry-After` o se reinicia el servidor (con H2 en memoria, la tabla `intento_login` se vacía).

## 17.6 Recorrido de prueba completo

```bash
cd backend && mvn -q test                      # 1. tests unitarios
cd backend && ./dev.sh                         # 2. servidor (otra terminal)
curl localhost:8080/api/v1/health              # 3. health de cada servicio y el WSDL (lista en 17.2)
node scripts/pruebas/escenarios.mjs            # 4. 24 escenarios (el 14, el 19 y el 20 tardan entre 2 y 4 minutos)
npx newman run docs/api/palco-api.postman_collection.json --env-var "secretoServicios=$(cat backend/servidor/target/secreto-servicios)"   # 5. contrato de la API
bash docs/entrega3/evidencia/reproducir.sh     # 6. evidencia repetible
cd frontend && npm run lint && npm run build   # 7. frontend
```

En Windows, los pasos 2 y 6 van en Git Bash, con `JAVA_HOME` en un JDK 25. Los escenarios 8, 10, 11 y 12 detienen consumidores y el 14 hace
fallar por unos segundos todo pago después del cobro: no conviene correrlos si otra persona está probando contra el
mismo servidor (16.2). Las pantallas las recorre la suite E2E (`cd frontend && npm run e2e`, 16.7), y a mano, el
recorrido de 15.5.

# 18. Gestión del proyecto

## 18.1 Integrantes y responsabilidades

El grupo es de cinco integrantes. La consigna fija de 3 a 4, y el 16/10 la cátedra aceptó el grupo de cinco. El
criterio del reparto es que cada integrante tenga al menos un componente del backend propio que defender.

| Integrante | Hecho (09/10 al 13/10) | 14/10 al 05/11 | 13/11 al 26/11 | Parte que defiende |
|---|---|---|---|---|
| Santiago Loto | Puesta a punto del 09/10; cambios compartidos de `backend/common` y del servidor; coordinación y revisión | Facturación SOAP contra el ARCA simulado, para el 13/11; OpenAPI; revisión | Documento técnico de la Entrega Obligatoria N.º 2 y del 3.er check point; el proveedor compatible con OpenAI y los guardrails del asistente; el tope de la reventa sobre el precio de lista (DEF-16) y la anulación por compra con el reintento de reembolsos (DEF-17 a DEF-19); `If-Match` con 412; la suite E2E; el journal del broker en un volumen (ADR-005); las cuentas de prueba fuera de desarrollo; el secreto entre servicios sin valor por defecto; ADR-001 a ADR-004; la integración del CI en 4 jobs y PMD | Facturación y el servicio SOAP |
| Valentina Frisoli | Pagos; frontend (sesión, catálogo y compra) | Pantalla de puerta; diagrama de secuencia de Mercado Pago (diseño) | El asistente con IA por su gateway (4.10) y, desde el 24/11, con los eventos del catálogo; `ETag`, cobertura con JaCoCo y resiliencia (tiempos de 5 s y broker apagado); las pantallas de factura, editar y cancelar un evento y la subida de fotos; las pruebas de seguridad (`seguridad.mjs`, `xss.spec.ts`) y ZAP; los mensajes de validación en español; las ventas por día y la liquidación en el panel (DEF-48); DEF-63, DEF-67, DEF-70, DEF-76 y DEF-77 | Pagos; el frontend |
| Sebastian Deya | Ventas core; errores y CORS | Sus tareas pasan a Julián Zuchowicki (16/10) | Desde el 20/11: el contrato del OpenAPI con Schemathesis y el del WSDL; la prueba de carga con k6 y su workflow; accesibilidad con axe; SonarCloud y el umbral de cobertura de JaCoCo; la colección de Postman con éxito y error por operación y su guía; la especificación de casos; la factura rechazada (DEF-33); el log de emails enmascarado (DEF-36); el DNI fuera de los mensajes (DEF-37); el rehash al iniciar sesión (DEF-44); las pantallas con `React.lazy` (DEF-49) y el aviso sin conexión (DEF-54); DEF-59 y DEF-65 | Ventas: el componente stateful, la transacción y la emisión |
| Julián Zuchowicki | Mensajería; integración entre Ventas y Pagos; token de servicio | Revisión de la facturación por SOAP; guion de demo; plan y arranque de Reventa. Desde el 16/10, además: la conciliación de pagos con una prueba repetible de una falla a mitad de la compra, y la capa de datos en Usuarios, Eventos y Notificaciones con los tests de Eventos | Desde el 20/11: el reembolso de la compra en la reventa al cancelar un evento (DEF-29 y DEF-31); el control de la compra en la reventa (DEF-30); los candados de compra en el catálogo y en la reventa (DEF-46); la auditoría sin el DNI (DEF-37); los `*IT` con Testcontainers contra PostgreSQL 17 y Artemis; la mutación con PIT y la estabilidad de la suite; las historias de usuario; DEF-60 y DEF-66 | Mensajería y Notificaciones; el cobro de Ventas a Pagos; Reventa |
| Joaquin Molina | Documento de la Entrega Parcial N.º 2 (con Santiago) | Validación QR; documento técnico | Desde el 20/11: Newman en el CI; el registro de defectos y el informe de ejecución; el email de quien escanea en el registro de la puerta (DEF-32); la pantalla para recuperar la contraseña (DEF-47); los temas; Trivy y Lighthouse; la imagen web sobre nginx 1.30 (DEF-51); `SeguridadDeclarativaTest` (DEF-43); los errores de validación en orden (DEF-62); los hooks en archivos propios (DEF-50); el registro sin retroceso en nombre y apellido (DEF-64); los casos de uso, el manual y el plan de pruebas al día; DEF-61 | Validación; el documento técnico |

- La revisión de cada tramo de trabajo la hace alguien distinto de quien escribió el código. La facturación por SOAP
  la revisa Julián, porque es de Santiago.
- Lo que Sebastian Deya ya hizo (Ventas core, errores y CORS) queda a su nombre; desde el 16/10 sus tareas las
  tuvo Julián Zuchowicki, y desde el 20/11 Sebastian volvió a tener tareas (columna del 13/11 al 26/11).
- La columna del 13/11 al 26/11 sale del historial de git (`git log --author`, con el `.mailmap`).
- La facturación por SOAP no lleva una cola de reintento propia: si ARCA no responde, se usan los reintentos del
  broker (decidido el 16/10, sección 1.5).
- Los diagramas de casos de uso y los casos de uso, que la cátedra pidió el 16/10 para la documentación técnica,
  están en `docs/documentacion/casos-de-uso.md` y `docs/documentacion/diagramas/casos-de-uso.svg` (sección 1.4).
- Usuarios y Eventos se construyeron antes del reparto y no figuran en la columna de defensa de nadie.
- Del 06/11 al 12/11 nadie tiene tarea nueva: se cierra, se revisa y se ensaya.
- La tabla es el reparto de responsabilidades y de defensa. Al 16/10 ya están implementados Facturación, Validación,
  Reventa (que estaba prevista para el tramo del 13/11) y la conciliación de pagos, y las pantallas de puerta,
  reventa y panel del organizador usan el backend; el estado de cada componente al 25/11 está en la sección 4 y el de
  las pruebas, en 16.7 y 16.8.

## 18.2 Calendario de la comisión del viernes

El grupo cursa los viernes de 08:45 a 11:45. La consigna está escrita para la comisión del lunes, así que sus fechas
no son las del grupo. **Cada entrega se sube el jueves anterior a la clase**, porque la hora límite (13:30, según la
consigna) es de la otra comisión y no está confirmada.

| Viernes | Hito | Cómo lo llama la consigna | Se congela | Se sube |
|---|---|---|---|---|
| 04/09 y 18/09 | 1.er checkpoint y 1.ª entrega parcial (ya pasaron) | Entrega Parcial N.º 1 y Entrega Obligatoria N.º 1 | | |
| **23/10** | Checkpoint sin defensa. **Fecha sin confirmar** | Entrega Parcial N.º 2 | Tag `entrega-parcial-2` (14/10) | Jue 22/10 |
| **06/11** | Entrega del diseño del TP Obligatorio. Contenido a confirmar | No figura | Mié 04/11 | Jue 05/11 |
| **13/11** | 2.ª entrega parcial, con defensa oral | Entrega Obligatoria N.º 2 | Lun 09/11 | Jue 12/11 |
| **20/11** | 3.er checkpoint: integraciones, resiliencia, IA por su gateway, CI con cobertura parcial, plan de pruebas, matriz y manual de usuario (filas C3 de la sección 2.2) | No figura | Mié 18/11 | Jue 19/11 |
| **27/11** | Prueba del TP Obligatorio y defensa de la Entrega Final (08:40); tag `v1.0-final` en main antes de las 08:00 | Entrega Final (la consigna da fechas contradictorias) | Mar 24/11 | Jue 26/11 |
| 04/12 | Final adelantado y recuperatorio | No figura | | |

El 16/10, el 23/10 y el 30/10 hay clase. El grupo decidió el 16/10 defender la Entrega Final el 04/12 (final
adelantado), no el 11/12 (final regular). El 20/11 la pasó al 27/11, el día que le asigna el cronograma de
exposiciones de la cátedra (mensaje del 12/11).

**Consultas a la cátedra respondidas el 16/10:**

- El grupo de cinco integrantes está aceptado.
- Las integraciones con ARCA son simuladas por ahora, y la pasarela de pagos simulada también se acepta.
- Se entrega con las fechas del cronograma de la comisión del viernes.
- La documentación es técnica, con diagramas de casos de uso y casos de uso (hechos:
  `docs/documentacion/diagramas/casos-de-uso.svg` y `docs/documentacion/casos-de-uso.md`).
  La consigna no pide Figma ni manual de usuario. Después, el 12/11, la cátedra sumó el manual de usuario al
  3.er checkpoint (`docs/manual/manual-de-usuario.md`), y el prototipo navegable de las pantallas principales
  lo aprobó la cátedra.
- Las 8 a 12 páginas del documento técnico son de contenido, sin contar los anexos.

**Fechas sin confirmar:** la hora límite de subida (por eso se sube el jueves anterior). Lo que pide el 20/11 lo
publicó la cátedra: está en las filas C3 de la sección 2.2. Los procesos asincrónicos quedan en tres procesos distintos
(O2.1 y T.7), y la base como último recurso es una decisión con su riesgo aceptado (7.4 y sección 21).

**Líneas de corte decididas de antemano**, para no discutirlas sobre la fecha: si el 23/10 el WSDL de Facturación no
responde, la facturación por SOAP se reduce al mínimo de la consigna; si los patrones y tests de Ventas no cierran el
05/11, pasan al tramo del 13/11 al 26/11; Reventa y Mercado Pago entran el 13/11 solo si están cerradas el 08/11; si
Mercado Pago no funciona el 19/11 queda como diseño; el panel del organizador es lo último y, si no llega el 24/11,
sigue con datos locales. Al 16/10, el WSDL de `/legado/arca` respondía y Facturación facturaba contra él, Reventa
estaba implementada y el panel del organizador ya usaba el backend, así que esas tres líneas de corte no hizo falta
aplicarlas.

**Estado al 19/11:** lo que pide el 3.er check point está en el código y tiene su evidencia (filas C3 de la sección
2.2 y sección 16.7). Por su línea de corte, Mercado Pago queda como diseño, y el cobro sigue con la pasarela simulada
que la cátedra aceptó el 16/10. Los patrones de Ventas quedaron con el State de la venta (6.9, ADR-002).

**Estado al 25/11:** el sistema completo corre con `docker compose` y lo prueban los nueve workflows del CI (16.7 y
16.8). La Entrega Final sale del tag `v1.0-final` (tabla de arriba).

## 18.3 Organización por etapas y tareas

El trabajo se ordena en etapas con fecha. Una etapa agrupa tareas que pueden avanzar en paralelo porque tocan módulos
distintos; una tarea no arranca si su dependencia no está mergeada. Cada tarea tiene un nombre corto que coincide con
su rama.

| Fechas | Tareas | Estado |
|---|---|---|
| 09/10 | Puesta a punto: convenciones, entorno, decisión del proceso asincrónico (`PAGO_CONFIRMADO`) y de la base (H2) | Hecha. El `.mailmap` está desde el 30/10, y desde el 20/11 los cinco commitean con su identidad (18.4) |
| 09/10 | Cambios compartidos: errores Problem Details, CORS, token de servicio, entre otros | Hechos |
| 10/10 al 13/10 | Ventas core, mensajería, Pagos, documento de la Entrega Parcial N.º 2 | Hechas |
| 13/10 | Integración entre Ventas y Pagos, frontend | Hechas. La validación QR y los patrones y tests de Ventas pasaron a la etapa siguiente |
| 14/10 al 05/11 | Facturación SOAP, documento técnico, transacciones, validación QR; después patrones y tests de Ventas, OpenAPI, pantalla de puerta; sin bloquear, Reventa y Mercado Pago | Hechas, salvo Mercado Pago, que quedó como diseño. Implementados y probados en vivo el 16/10: Facturación SOAP contra el ARCA simulado, la conciliación de pagos, Validación QR con su pantalla de puerta y Reventa, que se adelantó. Después, el OpenAPI al día, el documento técnico y los diagramas de casos de uso con los casos de uso |
| 06/11 al 12/11 | Congelar, revisar contra la consigna, armado desde cero, demo, documento, ensayo con preguntas cruzadas | Hecha: el documento técnico de la Entrega Obligatoria N.º 2 está en `docs/entrega-obligatoria-2/` |
| 13/11 al 26/11 | Mercado Pago y webhook, resto del frontend, documentación final | El resto del frontend y la documentación final (`docs/final/`), hechos; Mercado Pago y su webhook quedaron como diseño (12.1). Se sumaron el 3.er check point (20/11) y las correcciones del registro de defectos (18.1) |

El orden de las tareas siguió un criterio explícito: primero lo que la consigna exigía para cada entrega, después lo
que cerraba componentes implementados y por último el resto.

Cada tarea sigue el mismo ciclo:

1. **Plan antes de código.** Se escribe el plan (qué archivos, qué decisiones) y se aprueba antes de implementar.
2. **Archivos disjuntos y un dueño por módulo.** Si una tarea necesita cambiar el módulo de otra persona, se acuerda
   antes. `backend/common` y `backend/servidor` se tocan en un paso aparte, con un solo dueño, y terminan con todos los
   servicios redesplegados y sus `health` comprobados. El contrato del mensaje `PAGO_CONFIRMADO`
   (`docs/api/mensajeria-pago-confirmado.md`) no se cambia sin avisar al grupo.
3. **Verificar antes de dar por hecho.** Toda tarea termina con `mvn -q test` y el `curl` de su servicio, con el
   resultado real anotado.
4. **Aviso al grupo.** Al terminar una tarea se avisa qué cambió, qué archivos se tocaron, cómo se probó (comando y
   resultado real) y lo que quedó dudoso.

## 18.4 Flujo de trabajo con git

| Regla | Cómo se aplica |
|---|---|
| Una tarea, una rama, un dueño | Rama `feat/<servicio>-<tema>` (por ejemplo `feat/ventas-core`), de preferencia en su propio `git worktree` para avanzar en paralelo |
| Commits chicos, en español | Formato `tipo: qué cambió` (`feat:`, `fix:`, `docs:`, `test:`), uno por paso verificable |
| Identidad propia | Cada integrante commitea con su `user.name` y `user.email`; nadie commitea con la identidad de otra persona |
| Sin reescribir historia | No se hace `push --force`; los commits viejos con identidades mal configuradas se atribuyen con un `.mailmap` |
| Sin secretos | No se commitean `.env`, certificados ni tokens |
| Cada entrega sale de un tag | Se congela en la fecha del calendario; desde ahí solo entran arreglos |

**Tag `entrega-parcial-2`:** apunta al commit `580dd05` (14/10/2026, 17:50). La evidencia de la Entrega Parcial N.º 2
se tomó con el servidor armado desde cero desde ese commit, y se agregó en el commit siguiente (`ea5422b`). Es el
único tag del repositorio al 25/11; la Entrega Final sale del tag `v1.0-final` (18.2).

**Estado del historial, sin maquillar.** La consigna pide un historial incremental y participación visible de todos.
Al 19/10 el repositorio tenía una sola rama (`master`) y 40 commits: 24 con la identidad `santttii`, 6 con
`vfrisoli`, 5 con `Improvement` (una identidad mal configurada que hay que atribuir a su dueño real), 3 de Sebastian
Deya y 2 de Julián Zuchowicki. No hay commits a nombre de Joaquin Molina. Lo construido entre el 09/10 y el 14/10
entró en pocos commits grandes de una sola identidad, y lo mismo pasó con Facturación, la conciliación de pagos,
Validación, Reventa y las pantallas conectadas, que entraron juntas en un commit del 19/10 (`bcbc26a`). El grupo lo
registró como el primer riesgo no técnico y fijó que cada dueño o dueña commitea su tarea en su máquina, con su
identidad y en pasos chicos. Desde el 30/10 el `.mailmap` de la raíz muestra con un solo nombre las identidades de
cada persona. Al 25/11, `master` tiene 245 commits y `git shortlog -sn master` da commits de los cinco integrantes;
desde el 20/11 los cinco commitean en commits chicos (T.11 y 18.1).

## 18.5 Tablero de tareas en Notion

Las tareas se siguen en un tablero de Notion ("Tablero de Tareas D Apps 2"): una tarjeta por tarea, que se
corresponde con una rama. Los diez criterios de seguridad de la matriz de la clase 11 están cargados como tarjetas
con la etiqueta "seguridad". El tablero no está en el repositorio. En el repositorio, el estado por fases está en
`TODO.md` y el de cada defecto, en `docs/testing/registro-de-defectos.md`. Para la cátedra, que pide el tablero en Jira, hay una exportación del
tablero de Notion en el formato de Jira; tampoco está en el repositorio.

Las dos decisiones de seguridad que estaban abiertas se tomaron el 16/10:

- **Vigencia de la sesión (SEC-W05, Joaquin Molina):** se mantiene en 8 horas, sin refresh token. Una sesión de
  compra o de un organizador dura una jornada. El token se firma con RS256 y cada servicio lo valida con la clave
  pública; la clave se regenera al redesplegar Usuarios si no se define `PALCO_JWT_CLAVE_PRIVADA`, y el token entre
  servicios dura 5 minutos. El riesgo de un token robado se acepta para el alcance del trabajo. SEC-W05 queda como
  "parcial, vigencia justificada", y la prueba SEC-W05c de Postman pasa a comprobar que el token dura 8 horas.
- **Permisos por destino en el broker (SEC-I02, Julián Zuchowicki):** riesgo aceptado. El broker está embebido en el
  mismo WildFly, los servicios se conectan por in-vm y la interfaz pública del servidor queda en 127.0.0.1 por
  defecto; ningún sistema externo se conecta al broker. Se revisa si el sistema se despliega fuera de una sola
  máquina.

# 19. Evolución del diseño

Entre lo que se presentó (la Entrega Parcial N.º 1, los talleres de las clases 6, 8 y 9 y la actividad de la clase 10) y lo que hoy está construido hay diferencias. En todas vale el código, y cada una tiene su motivo. Esta sección parte de la sección 5 del documento de la Entrega Parcial N.º 2 (`docs/entrega3/Entrega_Parcial_2_Integracion.md`) y la amplía con lo que cambió después y con diferencias que ese documento no listaba.

## 19.1 Componentes e interfaces

| Tema | Qué se presentó | Qué vale hoy | Motivo |
|---|---|---|---|
| `IUsuarios` | Entrega Parcial N.º 1: `registrar`, `login`, `obtenerPerfil`, `asignarRol` | `registrar`, `verificarEmail`, `reenviarCodigo`, `iniciarSesion`, `obtener` y `emitirTokenDeServicio`. `asignarRol` no existe | Hizo falta verificar el email antes de habilitar la cuenta y emitir un token para las llamadas entre servicios (rol `servicio`). Ningún flujo implementado cambia roles: las cuentas de organizador y de staff están sembradas |
| `IVentas` | Cuatro operaciones: `iniciarCompra`, `agregarEntrada`, `confirmarCompra`, `cancelarCompra` | Tres: `iniciarCompra`, `confirmarCompra`, `cancelarCompra`. Las entradas y sus titulares van en el pedido inicial. El uso de una entrada en la puerta (`UsoDeEntradas`) y las operaciones de reventa (`ReventaDeEntradas`) son beans aparte, no operaciones de `IVentas` | El cupo se reserva de una sola vez en Eventos, al iniciar. Agregar entradas de a una obligaba a reservar en varios pasos y a deshacer reservas parciales. `IVentas` es la conversación stateful de una compra, y usar o transferir una entrada no tiene que ver con ella |
| Cómo se ata el componente stateful al usuario | Entrega Parcial N.º 1: "una instancia dedicada por cliente mientras dura el checkout", sin decir el mecanismo | Un índice por `ventaId` (`ComprasEnCurso`), validado con el `sub` del token. `ServicioDeVentas` es `@Stateful(passivationCapable = false)` con `@StatefulTimeout` de 10 minutos (`ReglasDeNegocio.MINUTOS_RESERVA`) | La identidad queda en un solo mecanismo, el token. Una sesión HTTP solo vale dentro de un WAR y los servicios son WAR separados |
| Quién vence la reserva | No estaba definido | Un barrido periódico (`VencimientoDeReservas`, `@Singleton` con `@Schedule`) sobre `vence_reserva`. Los pagos sin resultado los cierra otro barrido, `ConciliacionDePagos` | La especificación avisa que `@PreDestroy` puede no ejecutarse; un solo reloj evita dos caminos para lo mismo |
| `IPagos` | Entrega Parcial N.º 1: `procesarPago`, `confirmarPago`, `reembolsar`, como integración directa con Mercado Pago | `procesarPago`, `obtener`, `buscarPorClave` y `reembolsar`. La pasarela va detrás de `PasarelaDePagos`, con `FabricaDePasarela` eligiendo la implementación; hoy solo existe `PagosFake` | `IPagos` es el componente (guarda y deduplica una sola vez) y la pasarela es lo intercambiable. `buscarPorClave` la usa la conciliación de Ventas para saber si un pago sin resultado se cobró. `reembolsar` existe desde el 23/10 y la usan Reventa (7.10) y Ventas al cancelarse un evento (10.8). `confirmarPago` depende del webhook de Mercado Pago, que quedó como diseño (decisión del grupo del 13/10 sobre Mercado Pago real) |
| `INotificaciones` | `enviarConfirmacion`, `enviarRecordatorio`, `enviarAlertaCancelacion`, "multicanal" | `enviarConfirmacion`, `enviarConfirmacionDeReventa` y `enviarAlertaCancelacion` (la anulación de una compra cuando se cancela su evento, 10.8), por email con `Correos`: por SMTP si se configura y, si no, al log. La auditoría va aparte, en `IAuditoria` | Un solo canal, el email, con un servidor SMTP cualquiera por variables. Los recordatorios no tienen un hecho del sistema que los dispare |
| `IFacturacion` | `emitirFactura`, `consultarEstado`, `reintentar` contra ARCA (ex AFIP) | `emitirFactura`, `consultarFactura` y `consultarFacturaDelComprador`, contra un ARCA simulado por SOAP | `consultarEstado` quedó como las dos consultas (la del comprador filtra por dueño). `reintentar` no hace falta como operación: si ARCA no responde, reintenta el broker (19.2) |
| `IValidacion` | `validarEntrada` y `marcarComoUsada` | Una sola operación de escaneo, `escanear(codigoQr, eventoId, escaneadoPor)`, más `listar` y `obtener` para el registro de la puerta | Con dos pasos, entre "es válida" y "marcala" otra puerta podría usar la misma entrada. El uso único lo decide Ventas, dueña de la entrada, con un UPDATE condicional |
| `IReventa` | `publicarReventa`, `comprarReventa`, `cancelarPublicacion` | `listarActivas`, `listarDelVendedor`, `publicar`, `retirar` y `comprar` | Las pantallas necesitan listar las publicaciones de un evento y las propias. Reventa guarda las publicaciones; las entradas siguen siendo de Ventas, que las publica, retira y transfiere cuando Reventa se lo pide por REST |
| Invalidación del QR anterior en la reventa | Entrega Parcial N.º 1: la invalida Validación | La transferencia en Ventas deja la entrada original `VENDIDA` y crea una entrada nueva con otro código QR; en la puerta, la original da `NO_HABILITADA` | Validación no lleva estado propio de las entradas: lo decide la dueña de los datos, en una sola transacción |
| Capa de datos | Entrega Parcial N.º 1: `EventoRepository` vía JPA, y "tres capas separadas" en Eventos | Los 8 componentes tienen `api/`, `negocio/`, `datos/` y `dominio/`, con un repositorio por entidad. Ningún Session Bean usa el `EntityManager` directo. `negocio/IntentosDeLogin` de Usuarios pasó a `datos/IntentoLoginRepository` | Ventas y Pagos nacieron con la capa separada; Usuarios, Eventos y Notificaciones la sumaron el 16/10, y Validación, Reventa y Facturación se escribieron así desde el principio |
| Verificación de identidad | Entrega Parcial N.º 1: DNI obligatorio para comprar y publicar | Al verificar el email se marca también `identidadVerificada` (desde el 14/10), así que una cuenta registrada puede comprar. No hay un paso aparte de verificación de identidad | El backend no tenía ninguna operación que pusiera ese dato en verdadero, y solo podía comprar la cuenta sembrada. El DNI de cada titular sigue siendo obligatorio en la compra, y en la puerta un escaneo `VALIDA` devuelve el nombre y el DNI del titular para que el staff los contraste con el documento |

## 19.2 Integración y mensajería

| Tema | Qué se presentó | Qué vale hoy | Motivo |
|---|---|---|---|
| Broker | Entrega Parcial N.º 1 y clase 6: entre los sistemas externos | ActiveMQ Artemis embebido en WildFly, configurado en `backend/servidor/src/main/wildfly/configurar.cli`. Es infraestructura propia; los externos son Mercado Pago y ARCA | Viene con el servidor de aplicaciones y no hay que operar otro proceso |
| Modelo de integración | Clase 6: bus de integración (ESB) conceptual | REST directo entre servicios, el broker para lo asincrónico y un adaptador por sistema externo | Con nueve servicios y pocos sistemas externos, un bus suma una pieza más sin resolver un problema que hoy exista |
| Quién publica | Diagrama de la Entrega Parcial N.º 1: la flecha asincrónica sale de Notificaciones | Publica Ventas (`PublicadorPagoConfirmadoJms`, en `common`), en la misma transacción que confirma la venta. Consumen Ventas (la cola), Notificaciones y Facturación (el tópico) | El hecho que dispara todo es la confirmación del pago, que ocurre en Ventas |
| Emisión de la entrada | Clase 8: "hoy es una llamada síncrona interna" | Va por la cola `cola.emision-entradas`. Las entradas nacen `PENDIENTE_EMISION` en la transacción del pago y `EmisionDeEntradasMDB` las pasa a `VALIDA` | La respuesta del pago no espera la emisión, y la cola garantiza que se procese una sola vez aunque haya varias instancias del consumidor. Que las entradas se creen en la transacción del pago (y no en el MDB) permite que `GET /entradas/{id}` responda apenas vuelve el pago |
| Suscriptores del tópico | Clase 9: aviso y auditoría | Tres: el aviso (no durable), la auditoría (durable) y la facturación (durable). Cada compra deja 3 copias en `topico.compra-confirmada` | Sumar un suscriptor no cambia al productor. La facturación es durable por la misma razón que la auditoría: no se puede perder una compra sin facturar |
| A quién se avisa | Clase 9: al comprador y al organizador. Clase 8: por email o SMS | Solo al comprador, por email con `Correos` (`AvisoDeCompraMDB`); sin SMTP, una línea en el log | El mensaje `PAGO_CONFIRMADO` no trae al organizador, y ni el compose ni `dev.sh` configuran un SMTP |
| Garantía entre la venta y el mensaje | No estaba definida | La base no es XA y participa como último recurso; los consumidores son idempotentes (UPDATE condicionales). Un cobro sin resultado lo cierra la conciliación de pagos; una caída entre el commit de la base y el del broker es el riesgo aceptado (7.4 y sección 21) | La consigna pide transacciones declarativas y explicar las fallas, no un commit en dos fases |
| Facturación con ARCA | Clases 9 y 10: SOAP síncrono con espera de 5 segundos y cola de reintento. La clase 9 dice que es "el que vamos a implementar" para la Entrega Parcial N.º 2 | Implementada el 16/10, contra un ARCA simulado: un servicio SOAP propio (`ArcaSimulado`, POJO con `@WebService`) en un WAR aparte, `/legado/arca`, cuyo WSDL publica WildFly desde la clase (el servidor suma la capa `webservices`). Facturación se suscribe de forma durable a `topico.compra-confirmada` (`FacturacionDeComprasMDB`) y, por cada compra confirmada, le pide el CAE al simulado por SOAP y guarda número y CAE en su esquema. La numeración la asigna el ARCA simulado. Si ARCA no responde, el MDB deshace la entrega y reintenta el broker (5 entregas cada 2 segundos y después la `DLQ`): no hay espera de 5 segundos ni cola de reintento aparte. Si ARCA rechaza el comprobante, desde el 20/11 el rechazo queda anotado y no se reintenta (DEF-33, 11.4). El cliente SOAP se arma con una interfaz anotada (`PuertoArca`) y `Service.create` sobre el WSDL, sin generar código | La consigna exige SOAP con WSDL para el 13/11 y la cátedra confirmó el 16/10 que, por ahora, la integración con ARCA puede ser simulada. Se eligió el diseño más simple: reusa el tópico, la suscripción durable y los reintentos del broker, y Ventas no cambia |
| Por qué la llamada a ARCA es síncrona | Clase 9: "la venta no puede darse por válida sin un CAE autorizado" | Desde la clase 10 la compra se confirma aunque ARCA no responda, porque el pago ya está cobrado. Desde el 16/10 la factura se pide fuera de la compra: Ventas no cambia, la factura se consulta en Facturación (`GET /api/v1/facturas/{ventaId}`) y `facturaEstado` de la venta sigue en `NO_APLICA`. La llamada a ARCA es síncrona porque SOAP es pedido y respuesta; lo asincrónico es cómo llega la compra confirmada a Facturación (el tópico) | Bloquear una compra cobrada por una falla de un tercero es peor que facturar después |
| Llamadas de Reventa y Validación | Entrega Parcial N.º 1: Reventa usa Pagos, Ventas y Validación | REST síncrono con el token de servicio: Validación llama a Ventas para usar la entrada; Reventa llama a Ventas (publicar, retirar, transferir y, desde el 20/11, controlar la compra antes de cobrarla), a Pagos (cobrar) y a Eventos (la fecha del evento) | La puerta necesita el resultado en el momento, y la compra en la reventa necesita saber si se cobró antes de transferir |
| Aviso de Mercado Pago (webhook) | Clase 10: tratado como asincrónico | Se clasifica por su canal, REST; para la compra, el resultado llega de forma asincrónica. Es diseño (`docs/entrega-obligatoria-2/diagramas/diagrama_secuencia_mercadopago.svg`) | La clasificación de la consigna es por mecanismo de integración |

## 19.3 API REST

| Tema | Qué se presentó | Qué vale hoy | Motivo |
|---|---|---|---|
| Rutas | Clase 10: todo bajo `/api/v1` | `/api/v1` en Ventas, Pagos (`/api/v1/pagos`), Validación (`/api/v1/validacion`), Reventa (`/api/v1/reventas`) y Facturación (`/api/v1/facturas`). Usuarios, Eventos y Notificaciones siguen en `/api/<servicio>`. El ARCA simulado queda fuera de `/api`, en `/legado/arca` | Los servicios nuevos se escribieron bajo `/api/v1`. Pasar los que ya funcionaban cambiaba rutas que usan el frontend, Postman, los guiones y la evidencia, y la consigna no lo pide |
| Operaciones de Ventas | Catálogo de la clase 10: cuatro operaciones | Además existen `GET /ventas` (las compras de quien pregunta), `GET` y `DELETE /ventas/{id}` y `GET /entradas/{id}`, y para otros servicios (rol `servicio`) `POST /entradas/usos` y `/operaciones-de-reventa/*`. La venta trae `items`, `entradas`, `subtotal`, `cargoServicio`, `pagoId` y `eventoId` | El frontend necesitaba listar las compras ("Mis entradas") y cancelar desde el checkout; Validación y Reventa necesitan que la dueña de las entradas las use y las transfiera |
| Operaciones de Pagos | Clase 10: cobrar y consultar | Además `GET /api/v1/pagos?claveIdempotencia=` (rol `servicio`) | La usa la conciliación de pagos de Ventas |
| Pago pendiente | Clase 10: el pago responde 202 si queda pendiente | No hay 202: el pago responde aprobado, rechazado (402) o sin respuesta (504) | Con `PagosFake` no hay pagos diferidos. El 202 vuelve con el webhook de Mercado Pago, que está diseñado |
| Quién ve la entrada | Clase 10: "solo el titular" | La ve el comprador. Una venta, entrada o factura ajena responde 404, igual que una inexistente | El titular puede no tener cuenta en Palco: es una persona con nombre y DNI cargada por el comprador |
| Idempotencia | Clase 10: el servidor guarda clave → respuesta | La clave (`Idempotency-Key`) se guarda en la venta y se devuelve la venta en su estado actual. La compra en la reventa también pide la cabecera | Devolver el estado actual es más útil que repetir una respuesta vieja, y no exige otra tabla |
| Identificadores | Clases 8 y 10: correlativos, como `V-00231` | Un prefijo y 8 caracteres al azar, como `V-e022b63c` | Con H2 en memoria la numeración vuelve a 1 en cada reinicio mientras el broker conserva mensajes viejos, y se podían repetir ids |
| Formato de los errores | Clase 10, para Eventos: `{estado, mensaje, campos}`, con Problem Details como pendiente | Problem Details (RFC 9457) en todos los servicios: `common/rest/Problema.java` y `ManejadoresDeErrores`. Desde el 16/10 también salen en Problem Details el 401 por token inválido, que antes emitía el servidor en `text/html`, y el 429 del límite de intentos; el 500 suma el miembro `idError` | Un solo formato para los ocho servicios, adoptado antes de que Ventas y Pagos sumaran el suyo. El documento de la clase 10 (`docs/clases/clase-10-actividad-integradora/`) se corrigió el 16/10: dice que Eventos ya responde en Problem Details y que Ventas y Pagos están en `/api/v1` |
| Contrato OpenAPI | Clase 10: contract-first, `openapi-palco-v1.yaml` | `docs/api/openapi.yaml` describe los ocho componentes y el asistente, incluidas Validación, Reventa, Facturación y las operaciones internas de Ventas; Notificaciones no figura (9.5) | La implementación se adelantó al contrato en la primera tanda de trabajo; la diferencia se cerró después |

## 19.4 Nombres

Los documentos viejos usan un nombre interno para la Entrega Parcial N.º 2 y escriben "AFIP/ARCA". Hoy se usa el nombre de la consigna (Entrega Parcial N.º 2, Entrega Obligatoria N.º 2) y "ARCA (ex AFIP)". La carpeta que ya existía conserva su nombre (`docs/entrega3/`).

# 20. Qué es real y qué es simulado hoy

Estado al 25/11, leído en el código. Lo probó primero el servidor armado desde cero el 16/10 a las 11:04: `mvn clean install` con 577 tests y 0 fallas, los 9 WAR desplegados (los 8 servicios y el ARCA simulado) con sus 8 `health` en 200, el WSDL de `/legado/arca?wsdl` en 200 y 0 líneas `ERROR` en el log; los 17 escenarios de `scripts/pruebas/escenarios.mjs`; la colección de Postman con Newman (126 requests, 406 aserciones, 0 fallas), y la demo de la compra con `curl`, con el pago aprobado y con el rechazado, con código 0. Desde el 3.er check point lo prueba el CI en cada push contra el `docker compose`, con PostgreSQL 17: la corrida del 21/11 sobre `fbd31dc` pasó entera, con los 10 WAR y sus 9 `health`, los 24 escenarios, la colección de Postman y la suite E2E (16.7). "Implementado" quiere decir que funciona contra el servidor y tiene evidencia o tests; "Simulado", que funciona con una pieza que reemplaza a un sistema externo; "Diseñado", que solo existe en documentos.

| Parte | Estado | Detalle |
|---|---|---|
| Usuarios: registro, verificación de email, recuperar la contraseña, inicio de sesión, JWT RS256, token de servicio | Implementado | Sin servidor SMTP el código de verificación no se envía: queda en el log del servidor (simulado), y el renglón lo trae solo con `PALCO_CORREO_LOG_COMPLETO=true`, como en desarrollo (14.6). Desde el 24/11, un hash de menos de 600.000 iteraciones se recalcula al iniciar sesión (DEF-44). 75 tests al 20/11 |
| Eventos: catálogo, alta por el organizador, cupo por sector | Implementado | Con capa `datos/` desde el 16/10. 113 tests al 20/11 (detalle en 16.1) |
| Ventas: reserva stateful, pago, cancelación, vencimiento, idempotencia | Implementado | `facturaEstado` vale siempre `NO_APLICA`: la factura está en Facturación. Desde el 24/11, dos compras a la vez del mismo comprador o con el mismo DNI se ordenan con los candados de `candado_de_compra` (DEF-46). 366 tests al 20/11 |
| Pagos: API, guardado, deduplicación por clave, consulta por clave, control por rol | Implementado | La pasarela es `PagosFake`: `tok_rechazado` rechaza, `tok_sin_respuesta` no contesta y cualquier otro aprueba. No mueve plata. La cátedra aceptó la pasarela simulada el 16/10. Desde el 23/10 devuelve un pago aprobado (`reembolsar`), y desde el 25/11 rechaza con 400 los textos con caracteres de control (DEF-77). 36 tests al 23/10 |
| `CobrosFake` en Ventas | Simulado, apagado en el servidor | Existe para trabajar en Ventas sin Pagos (`palco.ventas.cobros=fake`). No está activo en la evidencia, y con él no hay conciliación |
| Broker, cola, tópico, reintentos (5 entregas cada 2000 ms) y `DLQ` | Implementado | ActiveMQ Artemis embebido, configurado en `configurar.cli`. En el servidor, un mensaje inválido llegó a la DLQ en 2 copias, una por cada suscriptor durable (auditoría y facturación). Desde el 19/11, en el compose el journal va en el volumen `datos-servidor` y un mensaje pendiente sobrevive a recrear el contenedor (ADR-005, `checkpoint-3/broker-recreado.txt`) |
| Emisión de entradas por cola (`EmisionDeEntradasMDB`) | Implementado | Idempotente: un `PAGO_CONFIRMADO` repetido no emite de nuevo (`EmisionEnH2Test`) |
| Auditoría por suscripción durable (`AuditoriaDeComprasMDB`) | Implementado | Recibe lo pendiente al volver. Desde el 24/11 guarda el cuerpo sin el DNI de los titulares, y la migración V3 se lo sacó a las filas anteriores (DEF-37). Notificaciones tenía 33 tests al 16/10 |
| Aviso de compra por suscripción no durable (`AvisoDeCompraMDB`) | Implementado; **simulado** sin SMTP | Manda el email con `Correos`: por SMTP si se configura `PALCO_SMTP_HOST` y, si no, escribe una línea en el log. Ni el compose ni `dev.sh` configuran un SMTP |
| Endpoints `/api/notificaciones/pruebas/*` | Implementado, solo para desarrollo | Publican mensajes de prueba (`ventaId` que empieza con `prueba-`); van en el WAR que se despliega y piden rol `servicio`. Se mantienen (decisión del 16/10) porque los usan la demo, la evidencia y la colección de Postman: riesgo aceptado (sección 21) |
| Transacción declarativa de `confirmarCompra` | Implementado | `REQUIRED` en la compra, `MANDATORY` en el publicador. Prueba repetible en el servidor: el escenario 13 deshace una transacción después de publicar y el mensaje no sale; el 14 hace fallar la compra después del cobro con la propiedad de desarrollo `palco.ventas.fallar-despues-del-cobro` |
| Conciliación de pagos sin resultado (`ConciliacionDePagos`) | Implementado | En el escenario 14: 500 con `idError`, la venta siguió `EN_CHECKOUT` sin entradas ni mensaje, el pago quedó `APROBADO` en Pagos, y la conciliación confirmó la venta con ese mismo pago y emitió la entrada en menos de 3 minutos. Solo toma ventas `EN_CHECKOUT`: la caída entre los dos commits es el riesgo aceptado del último recurso (sección 21) |
| Facturación por SOAP | Implementado, contra un ARCA simulado | En el escenario 15: factura B con número `00001-00000001` y CAE de 14 dígitos, una sola por venta, y la compra siguiente con el número siguiente; la carpeta 7 de Postman pasó sin fallas. 50 tests al 20/11. ARCA sin respuesta (corte a los 5 s, reintentos, DLQ y reproceso) se provocó el 19/11 contra el compose (11.9), y el rechazo de un sobre con DTD por el CXF de WildFly, el 21/11 (bloque D de `seguridad.mjs`, 11.6). Desde el 20/11, un comprobante rechazado queda anotado en `facturacion.comprobante_rechazado`, no se reintenta y la consulta responde 409 `comprobante-rechazado` (DEF-33) |
| ARCA (ex AFIP) | Simulado | `ArcaSimulado` en un WAR aparte, `/legado/arca`, con su WSDL generado por WildFly. Sin autenticación (el real usa WSAA) y con la numeración en memoria; desde el 25/11 nginx no lo publica (DEF-45). 18 tests al 20/11, con el contrato del WSDL (`ContratoDelWsdlTest`) |
| Mercado Pago real y su webhook | Diseñado (fuera del alcance) | Diagrama de secuencia en `docs/entrega-obligatoria-2/diagramas/`. Los estados `PENDIENTE` y `VENCIDO` existen en `EstadoPago` y nadie los escribe |
| Validación en puerta | Implementado | En el escenario 16: `OTRO_EVENTO`, `VALIDA` (la entrada quedó `USADA`), `YA_USADA`, `INEXISTENTE` y 403 para un comprador; la carpeta 5 de Postman pasó sin fallas. 53 tests al 20/11. Ventas sin respuesta (504 y renglón `SIN_RESPUESTA`) lo cubren `ServicioDeValidacionTest` y `ClienteDeVentasTest`. Desde el 20/11, cada renglón del registro guarda también el email de quien escaneó (DEF-32) |
| Reventa oficial | Implementado | En el escenario 17: publicar dentro del rango, la compra de otra cuenta y la entrada nueva; la carpeta 6 de Postman pasó sin fallas. 90 tests al 20/11. Desde el 20/11, antes de cobrar, Ventas controla el tope de 6 entradas por evento y el DNI por evento de la compra (DEF-30), y desde el 24/11 la transferencia los vuelve a mirar con los candados del catálogo; si una regla la frena, Reventa devuelve el cobro y libera la publicación (DEF-46). Desde el 23/10 tiene conciliación de las compras sin resultado (escenario 19) y devuelve el pago de una compra que Ventas no pudo transferir (escenario 20). Las reglas (mínimo 50 %, tope +10 %, cierre 3 horas antes) están en `common/ReglasDeNegocio`; el mínimo y el tope se calculan sobre el precio de lista del sector. Desde el 23/10 la compra en la reventa publica `REVENTA_CONFIRMADA` (aviso, auditoría y factura), y al cancelarse un evento se retiran sus publicaciones activas (10.8) |
| Asistente con IA | Implementado, con el modelo **simulado** por defecto | El gateway (guardrails, enmascarado, 5 s con fallback y apagado) es el mismo con cualquier modelo. El modelo real (`ModeloCompatibleOpenAI`, un proveedor compatible con la API de chat de OpenAI) se elige con `PALCO_ASISTENTE_MODELO=openai`, la dirección, el modelo y la clave por variables de entorno; sus tests corren sin red, y el 19/11 `asistente-proveedor.mjs` lo probó contra un proveedor local por HTTP (29 de 29, 16.7). Desde el 24/11 responde por los eventos a la venta, que lee del catálogo público de Eventos (4.10). El 18/11, `asistente.mjs` dio 11 de 11, y el 19/11 el set de evaluación dio 26 de 26, con los 11 adversariales |
| Código QR de la entrada | Implementado, sin firma | Es un UUID al azar que genera Ventas: no se puede adivinar, pero no lleva firma |
| Base de datos | Implementado | PostgreSQL 17 en el `docker compose`, con los datos en un volumen; H2 en memoria en modo PostgreSQL con `dev.sh` y en los tests, que se vacía al reiniciar WildFly. Las migraciones corren en las dos: en el compose y en los `*IT` contra PostgreSQL 17, y en los tests contra H2 |
| Frontend: sesión, recuperar la contraseña, catálogo, alta de eventos, compra y "Mis entradas" | Implementado | Llaman al backend (`frontend/src/servicios/`); los recorre la suite E2E en cada corrida del CI (16.7) |
| Frontend: reventa, puerta y panel del organizador | Implementado y probado en el navegador | Llaman a Reventa, Validación, Eventos y Ventas. Desde el 3.er check point las recorre la suite E2E en cada corrida del CI (16.7), que el 21/11 sobre `fbd31dc` pasó entera. En `localStorage` solo queda el carrito de la compra en curso y la sesión; se quitaron los datos fijos de reventa y del organizador |
| Panel del organizador | Implementado con los servicios que existen | No tiene un servicio propio de estadísticas: se arma con Eventos (cupo y ocupación), Ventas (lo vendido y lo recaudado sin el cargo de servicio, de `GET /api/v1/resumenes`: solo compras pagadas, sin las de la reventa oficial), Reventa y Validación. Desde el 24/11, "Ventas y liquidación" muestra lo recaudado, el cargo de Palco, lo que se le liquida al organizador y la tabla "Ventas por día", con `porDia` del mismo resumen (DEF-48, `OrganizerPage.tsx`). Desde el 23/10 muestra solo los eventos del organizador y arma el equipo de puerta de cada uno |
| Seguridad: roles declarativos, recurso ajeno como 404, contraseñas PBKDF2 (600.000 iteraciones; la migración V5 de Usuarios rehashea las cuentas de prueba y, desde el 24/11, el login rehashea las demás, DEF-44) | Implementado | Matriz de la clase 11 al 25/11 (sección 14.9): 8 criterios cumplen con prueba en verde (W01, W02, W03, W04, W06, I01, D01 y D02), 1 es parcial con la vigencia justificada (W05, DEF-38) y 1 no cumple y queda como riesgo aceptado (I02, DEF-39). Desde el 24/11, `SeguridadDeclarativaTest` falla si una operación queda sin anotación de rol (DEF-43) |
| Límite de intentos de login (SEC-W04) | Implementado | 5 fallidos en 15 minutos para un mismo email → 429 `/problemas/demasiados-intentos` con `Retry-After`, aunque la clave sea correcta y también para emails sin cuenta. Tabla `intento_login` (migración V4, guarda el SHA-256 del email), repositorio `datos/IntentoLoginRepository`; un login correcto borra los fallidos. Probado con `LimiteDeIntentosDeLoginTest`, en vivo (el 6.º intento dio 429 con `Retry-After: 898`) y con la petición SEC-W04 de Postman |
| 401 por token inválido en Problem Details | Implementado | `web-fragment.xml` y `ErrorDeAutenticacionServlet` en `backend/common`: `application/problem+json` (`/problemas/token-invalido`), `WWW-Authenticate: Bearer error="invalid_token"` y cabeceras CORS. Probado en vivo contra `GET /api/v1/ventas` y con SEC-W05a y SEC-W05b de Postman |
| Id de error en el 500 | Implementado | `ManejadoresDeErrores.Inesperado` genera un UUID, lo escribe en el log con la traza y lo devuelve como `idError`. Probado con `ErrorInesperadoTest` y en el servidor con el escenario 14, que recibe el 500 con su `idError` |
| Vigencia del token (SEC-W05) | Parcial, vigencia justificada (16/10) | La sesión se mantiene en 8 horas, sin refresh token: una sesión de compra o de un organizador dura una jornada. El token se firma con RS256 y cada servicio lo valida con la clave pública; la clave se regenera al redesplegar Usuarios si no se define `PALCO_JWT_CLAVE_PRIVADA`, y el token entre servicios dura 5 minutos. El riesgo de un token robado se acepta para el alcance del trabajo (DEF-38). La prueba SEC-W05c de Postman comprueba que el token dura 8 horas |
| Permisos por destino en el broker (SEC-I02) | Riesgo aceptado (16/10, DEF-39) | El broker está embebido en el mismo WildFly, los servicios se conectan por in-vm y la interfaz pública del servidor queda en 127.0.0.1 por defecto: ningún sistema externo se conecta al broker. Se revisa si el sistema se despliega fuera de una sola máquina |

# 21. Decisiones con riesgo aceptado

Cada fila es una decisión del grupo: qué se eligió, qué riesgo se acepta con eso y por qué. Las que tienen un defecto en
`docs/testing/registro-de-defectos.md` lo nombran: ahí están los pasos para verlo, el criterio que toca y la corrección
que se haría si cambia el alcance. Las de las transacciones tienen su detalle en 7.12. Lo que antes figuraba acá y ya
está resuelto se cuenta en la sección de cada tema: la base de la entrega es PostgreSQL 17 en el `docker compose` (3.1),
el DNI de los titulares ya no viaja en los mensajes ni queda en la auditoría (DEF-37, 10.2), el CXF de WildFly rechaza
en vivo un sobre con DTD (11.6), la carga se mide con k6 (16.8) y Eventos caído se provoca en el escenario 18.

**Seguridad**

| Decisión | Riesgo que se acepta | Por qué | Registro |
|---|---|---|---|
| La sesión dura 8 horas, sin refresh token ni revocación al salir (SEC-W05, decidido el 16/10) | Un token robado sirve hasta que vence | Una sesión de compra o de un organizador dura una jornada. El token se firma con RS256 y cada servicio lo valida con la clave pública; el token entre servicios dura 5 minutos (14.2) | DEF-38 |
| El broker no tiene usuarios ni permisos por destino (SEC-I02, decidido el 16/10) | Un WAR desplegado podría publicar o consumir en cualquier destino | El broker está embebido en el mismo WildFly, los servicios se conectan por in-vm y la interfaz pública queda en 127.0.0.1: ningún sistema externo se conecta a él (14.8) | DEF-39 |
| El límite de ingresos cuenta por email (SEC-W04) | Quien conoce el email de otra persona puede bloquearle el ingreso 15 minutos | Lo pide CA-02.3, y contar por IP necesita la dirección real del cliente, que detrás de nginx no se tiene de forma segura. Lo acotan `Retry-After` y "¿Olvidaste tu contraseña?", que borra los fallidos (14.1) | DEF-41 |
| El token de sesión va en `localStorage` | Un script inyectado en la página podría leerlo | Sobrevive a recargar, y una cookie HttpOnly exigía cambiar el backend. Lo acotan la CSP (`script-src 'self'`) y el escapado de React (5.7 y 15.4) | DEF-58 |
| La imagen backend usa los módulos de WildFly 41.0.1 con Jackson 2.21.5 y Guava 33.0.0 | Seis vulnerabilidades altas, justificadas en `.trivyignore` hasta el 31/12/2026 | Ninguna se alcanza en Palco: la API lee los cuerpos con JSON-B y la mensajería es solo texto JSON por in-vm. Pasar a WildFly 42 a una semana de la entrega es un cambio de versión mayor del servidor (14.10) | DEF-68 |
| La CSP permite estilos en línea e imágenes de cualquier origen `https:` | Dos alertas Medium de ZAP, justificadas en `.zap/reglas.tsv` | framer-motion anima con el atributo `style`, y las fotos de los eventos pueden estar en el almacenamiento de Supabase. Lo que ejecuta código sigue cerrado (`script-src 'self'`, `object-src 'none'`) | DEF-69 |
| El proceso principal de nginx arranca como root, como en la imagen oficial | Un proceso con privilegios en el contenedor web | Hace falta para escuchar en 80 y 443 y para escribir el certificado al arrancar; los pedidos los atienden los procesos worker, con el usuario sin privilegios `nginx` | DEF-71 |
| El registro dice si un email o un DNI ya tienen cuenta | Se puede averiguar si existe una cuenta | Lo pide CA-01.2, para que quien se registra sepa que ya tiene cuenta. El ingreso y "olvidé mi contraseña" responden igual exista o no (SEC-W03) | DEF-75 |
| Los endpoints `/api/notificaciones/pruebas/*` van en el WAR que se despliega (decidido el 16/10) | Cada prueba deja un aviso en el log, una fila de auditoría con `ventaId` `prueba-…` y una factura del ARCA simulado; un mensaje inválido deja una copia en la DLQ por cada suscriptor durable | Los usan la demo, la evidencia y la colección de Postman, y piden rol `servicio`, así que un usuario no puede publicar pagos falsos (4.5) | |
| El ARCA simulado no pide autenticación | Quien llegue al puerto del backend puede pedirle un CAE sin validez | Es el sistema externo simulado, sin datos de Palco. nginx no lo publica (DEF-45) y el compose publica el backend solo en 127.0.0.1 (4.9) | |
| El código QR es un UUID al azar, sin firma | Un código copiado sirve hasta que alguien lo usa | No se puede adivinar, se usa una sola vez y en la puerta se contrastan el nombre y el DNI del titular con el documento | |

**Transacciones, datos y frontend**

| Decisión | Riesgo que se acepta | Por qué | Registro |
|---|---|---|---|
| La base entra a la transacción como último recurso y no como datasource XA | Una caída entre el commit de la base y el del broker dejaría una venta `CONFIRMADA` sin mensaje ni emisión | La consigna pide transacciones declarativas y explicar las fallas; la ventana es la de un solo commit y los consumidores son idempotentes (5.4, 7.4 y 10.5) | |
| Ventas reserva el cupo antes de guardar la venta | Si el servidor se cae entre los dos pasos, el cupo queda tomado sin venta | Así ninguna llamada a Eventos espera con una conexión tomada (5.1); nunca se vende dos veces un lugar (7.12) | |
| La compra en curso vive en un componente stateful | Redesplegar Ventas corta las compras sin intento de pago; pagarlas las cancela y devuelve el cupo | Es el diseño elegido (5.1) y la reserva dura 10 minutos; una compra con un intento sin resultado ya tiene la copia de los titulares y la cierra la conciliación (7.12) | |
| La marca de "ocupada" entre la conciliación y un reintento está en memoria | Con varios servidores, dos confirmaciones del mismo pago a la vez dejarían un `SEVERE` falso | Hay un solo WildFly (7.12) | |
| Pagos no tiene un tiempo de espera propio con la pasarela | Una pasarela real que tardara más de 2 minutos haría que la conciliación venciera una venta con el cobro en curso | `PagosFake` responde en el momento; con Mercado Pago iría un tiempo de espera menor que `ConciliacionDePagos.MINUTOS_DE_MARGEN` (7.12) | |
| Dos pedidos simultáneos con la misma clave reservan los dos | Si en el sector queda lugar para uno, el otro recibe 409 `cupo-agotado`; al repetir, recibe la venta | La restricción única deja guardar una sola venta y el que pierde devuelve su cupo (7.2) | |
| La clave de la compra vive en la memoria de la página | Si se pierde la respuesta de la reserva y se recarga antes de reintentar, la primera reserva queda sin dueño hasta que vence | El cupo vuelve solo a los 10 minutos y no se cobra nada (7.7 y 15.4) | |
| El listado de auditoría ordena por la hora truncada a segundos y desempata por el id del mensaje | Dentro de un mismo segundo, el orden no es el de llegada | La clave de la fila es el `JMSMessageID`, que hace idempotente al consumidor (5.4) | |
| Las facturas no tienen anulación ni nota de crédito, y el ARCA simulado numera en memoria | Una reventa o una devolución no corrige la factura original; al redesplegar el simulado se repiten números | Queda fuera del alcance (1.5); por eso no hay restricción única sobre el número (11.7) | |
| Los tiempos de espera son fijos e iguales en todos los clientes | Un servicio lento corta a los 5 s aunque fuera a contestar después | Es el tiempo máximo que pidió la cátedra para el 3.er check point (C3.2), y cada corte tiene su fallback (8.8) | |
| En Reventa, un reembolso que Pagos rechaza queda `RECHAZADO`, y retirar una publicación espera a Ventas con la transacción abierta | El reembolso rechazado lo revisa una persona; el retiro retiene una conexión de Reventa hasta 7 s | Pagos solo rechaza si el pago no está aprobado o es de otra publicación, y repetir daría lo mismo; con la transacción abierta, una falla de Ventas deshace el retiro sin compensación (7.12) | |
| En Validación, el escaneo se guarda después de que Ventas usa la entrada | Si falla ese guardado, el pedido da 500 y el escaneo siguiente da `YA_USADA` sin `usoAnterior` | La entrada no se usa dos veces; solo falta el renglón del registro (4.6) | |

# 22. Trabajo futuro

Lo que queda fuera de este trabajo, con lo que haría falta si se sigue. Las decisiones de la sección 21 dicen qué las
cambiaría.

| Tema | Por qué no entra en este trabajo | Qué haría falta |
|---|---|---|
| Cobro con Mercado Pago y su webhook | La cátedra aceptó la pasarela simulada el 16/10; el diseño está en 12.1 y 13.2 | El adaptador `MercadoPagoPagos`, el webhook con la firma validada y un tiempo de espera en Pagos menor que el margen de la conciliación |
| ARCA real | La cátedra aceptó el ARCA simulado el 16/10 | Autenticación con WSAA, el WSFEv1 real y la consulta `FECompConsultar` antes de reintentar |
| Despliegue en la nube y en varios servidores | La consigna no lo exige; el sistema corre en una sola máquina con `docker compose` | Un usuario por servicio en el broker (DEF-39), la marca de "ocupada" en la base (7.12) y la prueba de escalabilidad horizontal |
| Sesión corta y revocable | La vigencia de 8 horas está decidida y justificada (DEF-38, DEF-58) | Un token de acceso corto con un refresh token revocable, en una cookie `HttpOnly`, `Secure` y `SameSite` |
| Una base XA o una tabla de salida | Se eligió el último recurso (5.4) | Cerrar la ventana entre los dos commits (7.4) |
| WildFly con Jackson y Guava al día | Cambiar de versión mayor del servidor a una semana de la entrega (DEF-68) | Pasar a la versión de WildFly que traiga esas librerías y sacar sus excepciones de `.trivyignore` |
| CSP sin estilos en línea y nginx sin privilegios | Riesgos aceptados (DEF-69, DEF-71) | Estilos con nonce o hash, `img-src` limitado al almacenamiento configurado, y nginx en puertos altos con otro usuario |
| Avisos que hoy no existen | La compra en la reventa no avisa al vendedor, y un reembolso que resuelve la conciliación no se avisa | Un mensaje para el vendedor y otro para el reembolso, en el mismo tópico |
| Modo offline de la puerta, QR firmado y notas de crédito | Fuera del alcance (1.5) | Un código firmado que la puerta valide sin red, y la anulación de facturas con ARCA |
| Tests unitarios del frontend | El frontend se prueba con lint, build, la suite E2E y Lighthouse (15.5) | Vitest para la lógica de `lib/` y `servicios/` |

# Aprendizajes del equipo

Los escribe el grupo, como se decidió el 16/10 (fila D.1 de la sección 2.2), en la documentación final
(`docs/final/documentacion-final.md`, sección 11). Las lecciones de las pruebas, con los tres defectos que llegaron
tarde, están en `docs/testing/lecciones-aprendidas.md`.

# Declaración de uso de herramientas de IA generativa

La consigna permite la IA generativa como asistente de productividad y pide declarar qué se usó y para qué. El grupo presentó además su declaración de uso de IA, que fija estos principios:

- **Uso acotado e instrumental:** un apoyo para tareas técnicas delimitadas, sobre todo el testing y el control de calidad y, de forma complementaria, las automatizaciones con Postman, la integración con Notion por su API y funcionalidades puntuales con tecnologías nuevas para el equipo. No reemplaza la arquitectura, las decisiones de diseño, las pruebas reales ni la revisión del código.
- **Asistencia supervisada y revisión humana:** nada de lo que propone se da por correcto ni se incorpora sin revisión; las decisiones y los cambios son del equipo.
- **Validación técnica:** el código y los scripts se prueban en condiciones controladas y se ajustan según lo que se observa.
- **Datos y secretos:** las credenciales, los tokens y las claves no se incluyen en las consultas, y se evita compartir datos personales o confidenciales. Las cuentas y los datos con que se trabaja son de prueba (`@palco.test`), y los secretos van en variables de entorno fuera del repositorio.
- **Responsabilidad del equipo:** la responsabilidad funcional, técnica, operativa y de seguridad es del equipo, no de la herramienta.

**Qué se usó.** Claude, el modelo de lenguaje de Anthropic, a través de Claude Code, su herramienta para programar desde la terminal y el editor. El historial lo muestra de forma explícita en seis commits con la línea `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; dos de ellos, del 23/10 (`d7a4241` y `5c465ec`), quedaron registrados con la identidad de la herramienta como autor. El uso no se limita a esos commits: se usó a lo largo del cuatrimestre para las tareas que siguen.

**Para qué.** Asistencia en el desarrollo de código y de tests (propuestas de implementación, de casos y de datos de prueba); scripts de prueba contra el servidor y la generación de la colección de Postman; revisión de código, para buscar errores, casos sin cubrir y diferencias entre el código y la documentación; redacción y revisión de la documentación, contrastada con el código y con la salida de las pruebas, y la automatización del tablero de tareas en Notion por su API.

Todo lo que se incorporó pasó por la revisión del grupo y por los mismos controles que el resto del código: los tests de `mvn verify`, los escenarios contra el servidor en marcha y la CI en cada push. El grupo responde por cada parte del código y del diseño y la defiende en la instancia oral, como pide la consigna.

**No es la IA del producto.** La declaración se refiere a la IA como herramienta de trabajo del equipo. El asistente de la sección 4.10 es otra cosa: una funcionalidad de Palco que pide la cátedra para el 20/11, con su propio gateway, sus guardrails y su set de evaluación, que por defecto usa un modelo simulado y no depende de ninguna herramienta de desarrollo.


# Anexos

## A. Endpoints de la API

Generado el 25/11 desde los recursos JAX-RS y el `context-root` de cada WAR (las 56 operaciones de los nueve servicios). Además, cada servicio responde `GET <ruta del servicio>/health` sin anotación de rol, a propósito, para el control de salud del compose (`common/rest/HealthResource`, 14.3). El ARCA simulado no es parte de la API de Palco: su contrato es el WSDL de `/legado/arca?wsdl` (11.3). El contrato completo de la API, con cuerpos y errores, está en `docs/api/openapi.yaml`.

| Servicio | Método | Ruta | Rol |
|---|---|---|---|
| asistente | POST | `/api/v1/asistente/preguntas` | user, organizer, staff |
| eventos | GET | `/api/eventos/mios` | organizer |
| eventos | POST | `/api/eventos/{eventoId}/cancelacion` | organizer |
| eventos | GET | `/api/eventos/{eventoId}/organizador` | servicio |
| eventos | POST | `/api/eventos/{eventoId}/sectores/{sectorId}/cupo` | servicio |
| eventos | PUT | `/api/eventos/{eventoId}` | organizer |
| eventos | GET | `/api/eventos/{slug}` | público (@PermitAll) |
| eventos | GET | `/api/eventos/imagenes/{id}` | público (@PermitAll) |
| eventos | POST | `/api/eventos/imagenes` | organizer |
| eventos | GET | `/api/eventos` | público (@PermitAll) |
| eventos | POST | `/api/eventos` | organizer |
| facturacion | GET | `/api/v1/facturas/{ventaId}` | user, servicio |
| notificaciones | GET | `/api/notificaciones/auditoria` | servicio |
| notificaciones | POST | `/api/notificaciones/pruebas/mensaje-invalido` | servicio |
| notificaciones | POST | `/api/notificaciones/pruebas/pago-confirmado-con-falla` | servicio |
| notificaciones | POST | `/api/notificaciones/pruebas/pago-confirmado` | servicio |
| pagos | POST | `/api/v1/pagos/{pagoId}/reembolso` | servicio |
| pagos | GET | `/api/v1/pagos/{pagoId}` | servicio |
| pagos | GET | `/api/v1/pagos` | servicio |
| pagos | POST | `/api/v1/pagos` | servicio |
| reventa | POST | `/api/v1/reventas/{publicacionId}/compra` | user |
| reventa | DELETE | `/api/v1/reventas/{publicacionId}` | user |
| reventa | GET | `/api/v1/reventas/mias` | user |
| reventa | GET | `/api/v1/reventas` | público (@PermitAll) |
| reventa | POST | `/api/v1/reventas` | user |
| usuarios | GET | `/api/usuarios/{usuarioId}` | servicio |
| usuarios | GET | `/api/usuarios/cuentas` | servicio |
| usuarios | GET | `/api/usuarios/jwks` | público (@PermitAll) |
| usuarios | POST | `/api/usuarios/login` | público (@PermitAll) |
| usuarios | POST | `/api/usuarios/password/olvide` | público (@PermitAll) |
| usuarios | POST | `/api/usuarios/password/reinicio` | público (@PermitAll) |
| usuarios | POST | `/api/usuarios/registro` | público (@PermitAll) |
| usuarios | POST | `/api/usuarios/servicios/token` | público (@PermitAll) |
| usuarios | POST | `/api/usuarios/verificacion/reenvio` | público (@PermitAll) |
| usuarios | POST | `/api/usuarios/verificacion` | público (@PermitAll) |
| usuarios | GET | `/api/usuarios/yo` | user, organizer, staff |
| validacion | GET | `/api/v1/validacion/escaneos/{escaneoId}` | staff, organizer |
| validacion | GET | `/api/v1/validacion/escaneos` | staff, organizer |
| validacion | POST | `/api/v1/validacion/escaneos` | staff |
| validacion | DELETE | `/api/v1/validacion/puertas/{eventoId}/{email}` | organizer |
| validacion | PUT | `/api/v1/validacion/puertas/{eventoId}/{email}` | organizer |
| validacion | GET | `/api/v1/validacion/puertas/mias` | staff |
| validacion | GET | `/api/v1/validacion/puertas` | organizer |
| ventas | GET | `/api/v1/entradas/{entradaId}` | user |
| ventas | POST | `/api/v1/entradas/usos` | servicio |
| ventas | POST | `/api/v1/operaciones-de-reventa/controles-de-compra` | servicio |
| ventas | GET | `/api/v1/operaciones-de-reventa/entradas/{entradaId}` | servicio |
| ventas | POST | `/api/v1/operaciones-de-reventa/publicaciones` | servicio |
| ventas | POST | `/api/v1/operaciones-de-reventa/retiros` | servicio |
| ventas | POST | `/api/v1/operaciones-de-reventa/transferencias` | servicio |
| ventas | GET | `/api/v1/resumenes` | organizer |
| ventas | POST | `/api/v1/ventas/{ventaId}/pagos` | user |
| ventas | DELETE | `/api/v1/ventas/{ventaId}` | user |
| ventas | GET | `/api/v1/ventas/{ventaId}` | user |
| ventas | GET | `/api/v1/ventas` | user |
| ventas | POST | `/api/v1/ventas` | user |

## B. Contrato del mensaje PAGO_CONFIRMADO

El contrato completo está en `docs/api/mensajeria-pago-confirmado.md`. Lo publica `PublicadorPagoConfirmadoJms` (`backend/common`) dentro de la transacción que confirma la venta, con la propiedad `tipoEvento = PAGO_CONFIRMADO`, a la cola `cola.emision-entradas` y al tópico `topico.compra-confirmada`. La sección de mensajería explica sus consumidores.

## C. Glosario

| Término | Significado en este documento |
|---|---|
| **Venta** | Una compra de un comprador para un evento. Tiene estados: `EN_CHECKOUT` (reserva tomada), `CONFIRMADA` (pago aprobado), `EMITIDA` (entradas emitidas), `CANCELADA` y `VENCIDA`. Su id tiene la forma `V-e022b63c` |
| **Entrada nominativa** | Entrada ligada a una persona con nombre y DNI. La identidad se contrasta en la puerta. Sus estados: `PENDIENTE_EMISION`, `VALIDA`, `USADA`, `PUBLICADA`, `VENDIDA` y `ANULADA` |
| **Titular** | La persona cuyo nombre y DNI figuran en la entrada. Puede no ser el comprador ni tener cuenta en Palco |
| **Comprador** | Usuario con rol `user` que inicia y paga la venta. Es quien puede consultar la venta, sus entradas y su factura |
| **Organizador** | Usuario con rol `organizer`: da de alta eventos y consulta el panel y el registro de la puerta |
| **Staff** | Usuario con rol `staff`: valida entradas en la puerta |
| **Rol `servicio`** | Rol del token que un servicio pide a Usuarios para llamar a otro servicio (por ejemplo, Ventas a Eventos y a Pagos, o Validación a Ventas). Dura 5 minutos |
| **Sector** | Parte de un evento con su precio y su capacidad |
| **Cupo** | Lugares disponibles de un sector. Eventos lo descuenta al reservar y lo devuelve al cancelar o vencer |
| **Reserva** | El cupo tomado al iniciar una compra. Dura 10 minutos (`ReglasDeNegocio.MINUTOS_RESERVA`); si no se paga, el barrido la vence |
| **Checkout** | El proceso de compra en pasos (titulares, pago, confirmación). En el backend lo mantiene el componente stateful `ServicioDeVentas` |
| **Stateful / stateless** | Un componente stateful guarda estado de un cliente entre llamadas (la compra en curso); uno stateless no recuerda nada entre llamadas y el servidor atiende con un pool de instancias |
| **Barrido** | Tarea periódica (`VencimientoDeReservas`) que vence las reservas no pagadas y devuelve su cupo |
| **Reventa oficial** | Venta de una entrada entre usuarios dentro de Palco, entre el 50 % y el 110 % del precio de lista de su sector, hasta 3 horas antes del evento. La entrada original queda `VENDIDA` y el comprador recibe una entrada nueva con otro código QR |
| **Publicación** | Una entrada ofrecida en la reventa. Estados: `ACTIVA`, `VENDIDA`, `RETIRADA` y `ANULADA` (cobrada y sin transferir: el pago se devuelve) |
| **Tope** | Precio máximo de reventa: el precio de lista del sector más 10 % (`ReglasDeNegocio.TOPE_REVENTA_PORCENTAJE`) |
| **Cargo por servicio** | Lo que cobra Palco sobre el subtotal de la venta: 10 % (`TASA_SERVICIO_PORCENTAJE`) |
| **Emisión** | Paso de las entradas de `PENDIENTE_EMISION` a `VALIDA`, con su código QR. La hace `EmisionDeEntradasMDB` desde la cola |
| **Escaneo** | Lectura del código de una entrada en la puerta. Da `VALIDA`, `YA_USADA`, `INEXISTENTE`, `OTRO_EVENTO` o `NO_HABILITADA` (o `SIN_RESPUESTA` si Ventas no contesta), y cada uno queda en el registro de la puerta |
| **`PAGO_CONFIRMADO`** | El mensaje que publica Ventas cuando se aprueba el pago. Su contrato está en `docs/api/mensajeria-pago-confirmado.md` |
| **Broker** | Servidor de mensajería. Acá, ActiveMQ Artemis embebido en WildFly |
| **Cola** | Destino de mensajería donde cada mensaje lo procesa un solo consumidor (`cola.emision-entradas`) |
| **Tópico** | Destino de mensajería donde cada suscripción recibe su copia del mensaje (`topico.compra-confirmada`, con tres suscriptores: aviso, auditoría y facturación) |
| **MDB** | Message-Driven Bean: componente del servidor que consume mensajes de una cola o un tópico |
| **Suscripción durable** | Suscripción a un tópico que el broker conserva mientras el consumidor está caído: al volver recibe lo pendiente (auditoría y facturación). Una no durable pierde lo que llegó mientras no estaba (aviso) |
| **Selector** | Filtro del consumidor sobre las propiedades del mensaje (`tipoEvento = 'PAGO_CONFIRMADO'`) |
| **DLQ** | Dead Letter Queue: cola donde el broker deja un mensaje que falló en todas sus entregas (5, cada 2000 ms) |
| **Idempotencia** | Propiedad de una operación que, repetida, deja el mismo resultado. En la API se pide con la cabecera `Idempotency-Key`; en los consumidores se logra con UPDATE condicionales |
| **UPDATE condicional** | Sentencia que cambia una fila solo si sigue en el estado esperado (por ejemplo, `where estado = 'EN_CHECKOUT'`). Evita aplicar dos veces el mismo cambio |
| **Transacción declarativa** | Transacción que define el contenedor según una anotación (`REQUIRED`, `MANDATORY`, `NOT_SUPPORTED`), sin código de commit ni rollback |
| **Último recurso** | Forma en que un recurso no XA (la base) participa en una transacción junto con uno XA (el broker): se confirma al final. Si el servidor cae entre los dos commits, el resultado puede quedar a medias |
| **XA / commit en dos fases** | Protocolo que confirma varios recursos a la vez de forma atómica. No se usa |
| **Conciliación** | Barrido (`ConciliacionDePagos`) que le pregunta a Pagos por la clave de cada venta `EN_CHECKOUT` con un pago sin resultado y, según la respuesta, la confirma o la vence y devuelve el cupo |
| **Problem Details** | Formato estándar de errores HTTP (RFC 9457), con `type`, `title`, `status`, `detail` e `instance`, en `application/problem+json` |
| **JWT** | Token firmado (RS256) que lleva la identidad (`sub`) y los roles (`groups`) del usuario. Los servicios lo validan con la clave pública de `/api/usuarios/jwks` |
| **Pasarela de pago** | Sistema externo que cobra. Hoy es `PagosFake` (simulada); Mercado Pago está diseñado |
| **Webhook** | Aviso que la pasarela manda por REST cuando un pago cambia de estado. Diseñado, sin implementar (12.1) |
| **SOAP / WSDL** | Protocolo de servicios web basado en XML y el documento que describe su contrato. Lo usa la facturación contra el ARCA simulado (`/legado/arca?wsdl`) |
| **ARCA (ex AFIP)** | Organismo que autoriza las facturas electrónicas y devuelve un CAE. En Palco es un sistema simulado: no hay conexión con el ARCA real |
| **CAE** | Código de Autorización Electrónico con que ARCA autoriza una factura |
| **Sistema legado** | Sistema existente con tecnología previa (SOAP) que hay que integrar sin cambiarlo. En Palco lo representa ARCA, simulado con un servicio SOAP propio en un WAR aparte (`ArcaSimulado`) |
| **Adapter, Factory, Repository, Facade** | Patrones aplicados: Adapter traduce una interfaz ajena a una propia (`ClienteDePagos`, `ArcaSoap`; `MercadoPagoPagos`, diseñado); Factory elige una implementación por configuración (`FabricaDePasarela`, `FabricaDeCobros`, `FabricaDeArca`, `FabricaDeModelo`); Repository aísla el acceso a la base (un repositorio por entidad en los ocho componentes); Facade ofrece una entrada simple a un subsistema (`ComprasEnCurso`; en el asistente, el gateway `AsistenteIA`) |
| **Health** | Endpoint `GET .../health` que responde 200 si el servicio está desplegado |
| **WAR** | Archivo con el que se despliega cada servicio en WildFly. Hay diez: uno por cada uno de los ocho componentes, el del asistente y el del ARCA simulado |
| **Gateway de IA** | El único punto por el que el sistema llega al modelo de lenguaje (`AsistenteIA`): guardrails, enmascarado, tiempo máximo de 5 s, fallback y apagado (4.10) |
| **Guardrail** | Control antes o después del modelo: frena una inyección de prompt o un pedido de datos personales, y no deja mostrar una respuesta con datos personales |
| **Fallback** | Lo que se responde cuando el otro lado no contesta a tiempo. En el asistente, las respuestas fijas de `RespuestasFrecuentes` |

## D. Documentos del repo

| Documento | Qué contiene |
|---|---|
| `docs/consigna/` | La consigna de la cátedra |
| `docs/entrega1/` | Entrega Parcial N.º 1: arquitectura y diagrama de capas |
| `docs/entrega3/` | Entrega Parcial N.º 2: documento de integración, diagrama SOA y evidencia |
| `docs/entrega-obligatoria-2/` | Entrega Obligatoria N.º 2 (13/11): el documento técnico y el diagrama de secuencia de Mercado Pago |
| `docs/checkpoint-3/` | 3.er check point (20/11): el documento técnico, con la declaración de uso de IA (sección 15) |
| `docs/final/` | Entrega Final (27/11): la documentación final (`documentacion-final.md`) y la declaración de uso de IA (`declaracion-uso-ia.md`) |
| `docs/documentacion/diagramas/` | Diagrama de integración SOA vigente, casos de uso y diagramas de secuencia |
| `docs/documentacion/casos-de-uso.md`, `docs/documentacion/historias-de-usuario.md` | Los casos de uso, y las 16 historias de usuario con sus 89 criterios de aceptación en Gherkin |
| `docs/testing/` | Plan de pruebas, especificación de casos (`especificacion-de-casos.md`), registro de defectos (`registro-de-defectos.md`), matriz de trazabilidad, lecciones aprendidas, evidencia del 3.er check point (`evidencia/checkpoint-3/`) y de la Entrega Final (`evidencia/final/`) |
| `docs/manual/` | Manual de usuario, con capturas de la aplicación |
| `docs/clases/` | Talleres y actividades de las clases 6, 8 y 9, y las actividades de las clases 10 (integración, `clase-10-actividad-integradora/`) y 11 (seguridad, `clase-11-actividad-seguridad/`) |
| `docs/api/` | OpenAPI, contratos de mensajería (`PAGO_CONFIRMADO`, y `EVENTO_CANCELADO` con `COMPRA_ANULADA`), colección de Postman y su guía |
| `docs/adr/` | Los cinco registros de decisiones de arquitectura, de ADR-001 a ADR-005 |
| `TODO.md` | Estado del proyecto por fases y calendario de entregas |
| `README.md`, `scripts/LEEME.md` | Cómo levantar y probar el sistema, y los guiones de prueba |
