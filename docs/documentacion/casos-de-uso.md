# Casos de uso de Palco

![Diagrama de casos de uso de Palco](diagramas/casos-de-uso.svg)

*Fuente: `docs/documentacion/diagramas/casos-de-uso.svg`. Óvalo blanco: implementado; óvalo celeste: implementado,
sin probar en el servidor; óvalo punteado: diseñado, pendiente.*

## Actores

| Actor | Rol | Tipo |
|---|---|---|
| Comprador | `user` | Persona. Con la identidad verificada puede además revender una entrada propia: no es otra cuenta, es una capacidad del comprador |
| Organizador | `organizer` | Persona |
| Personal de puerta | `staff` | Persona |
| Pasarela de pago | — | Sistema externo, **simulado** (`PagosFake`, dentro de Pagos) |
| ARCA | — | Sistema externo legado, **simulado** (`ArcaSimulado`, un servicio SOAP en un WAR aparte, `/legado/arca`) |

Los errores salen como Problem Details (`application/problem+json`) con `"type": "/problemas/<tipo>"`; en cada flujo
alternativo se da el código HTTP y el tipo. Una operación con `@RolesAllowed` responde 401 `no-autenticado` sin
token y 403 `sin-permiso` con un rol que no corresponde; eso vale para todos los casos que piden sesión y no se
repite en cada uno.

## Resumen

| N.º | Caso de uso | Actor principal | Estado |
|---|---|---|---|
| CU-01 | Registrarse | Comprador | Implementado |
| CU-02 | Iniciar sesión | Comprador, organizador, personal de puerta | Implementado |
| CU-03 | Consultar el catálogo | Comprador (también sin sesión) | Implementado |
| CU-04 | Comprar entradas | Comprador | Implementado (la factura: implementado, sin probar en el servidor) |
| CU-05 | Ver mis entradas | Comprador | Implementado |
| CU-06 | Publicar en reventa | Comprador | Diseñado, pendiente |
| CU-07 | Comprar en reventa | Comprador | Diseñado, pendiente |
| CU-08 | Crear evento | Organizador | Implementado |
| CU-09 | Ver estadísticas | Organizador | Diseñado, pendiente |
| CU-10 | Validar entrada en puerta | Personal de puerta | Implementado, sin probar en el servidor |

---

## CU-01 Registrarse

- **Actor:** comprador (todavía sin cuenta).
- **Precondición:** no tiene una cuenta con ese email ni con ese DNI.
- **Flujo principal:**
  1. Carga nombre, apellido, DNI, email, teléfono, fecha de nacimiento y contraseña en `/registro`.
  2. El frontend llama a `POST /api/usuarios/registro` (`@PermitAll`).
  3. Usuarios valida los datos, guarda la cuenta con rol `user`, pendiente de verificar, y genera un código de 6
     dígitos. Responde 201. El código se escribe en el log del servidor (**simulado**: no hay envío de emails).
  4. El comprador ingresa el código; el frontend llama a `POST /api/usuarios/verificacion`.
  5. Usuarios marca el email y la identidad como verificados y responde 200 con la sesión (JWT).
- **Flujos alternativos:**
  - 2a. DNI que no tiene 7 u 8 dígitos, menor de 16 años, email ya registrado o DNI ya registrado: 400
    `datos-invalidos`, con el campo.
  - 2b. Falta un dato o tiene un formato inválido (Bean Validation): 400 `datos-invalidos`, con el detalle por campo.
  - 4a. Código incorrecto, vencido o con 5 intentos fallidos: 400 `datos-invalidos` (campo `codigo`).
  - 4b. El email ya estaba verificado: 409 `conflicto`.
  - 4c. Pide otro código (`POST /api/usuarios/verificacion/reenvio`, 204); si lo pide enseguida del anterior: 409
    `conflicto` ("Esperá unos segundos").
- **Postcondición:** cuenta con rol `user`, email e identidad verificados, y sesión abierta.
- **Estado:** Implementado. La verificación de identidad con foto del DNI no existe: al verificar el email la cuenta
  queda con la identidad verificada.

## CU-02 Iniciar sesión

- **Actores:** comprador, organizador y personal de puerta.
- **Precondición:** tiene una cuenta con el email verificado. Las cuentas de organizador y de puerta las cargan las
  migraciones de Usuarios; el registro solo crea compradores.
- **Flujo principal:**
  1. Ingresa email y contraseña en `/ingresar`.
  2. El frontend llama a `POST /api/usuarios/login` (`@PermitAll`).
  3. Usuarios verifica la contraseña (PBKDF2) y responde 200 con un JWT firmado con RS256, con el rol en el claim
     `groups` y 8 horas de vigencia.
  4. El frontend confirma el token con `GET /api/usuarios/yo` al abrir la aplicación.
- **Flujos alternativos:**
  - 3a. Email o contraseña incorrectos: 401 `no-autenticado`. El intento fallido queda guardado.
  - 3b. Cinco intentos fallidos en 15 minutos para el mismo email: el pedido siguiente responde 429
    `demasiados-intentos` con `Retry-After`, aunque la contraseña sea correcta. Vale también para emails sin cuenta.
  - 3c. Email sin verificar: 409 `conflicto` ("Verificá tu email antes de iniciar sesión").
- **Postcondición:** sesión abierta con el rol de la cuenta; los demás servicios validan el token con la clave
  pública de `GET /api/usuarios/jwks`.
- **Estado:** Implementado.

## CU-03 Consultar el catálogo

- **Actor:** comprador, con o sin sesión.
- **Precondición:** ninguna.
- **Flujo principal:**
  1. Abre `/`; el frontend llama a `GET /api/eventos` (`@PermitAll`), con `categoria` y `q` si filtra o busca.
  2. Eventos responde la lista.
  3. Elige un evento; el frontend llama a `GET /api/eventos/{slug}` y muestra los sectores con precio y cupo.
- **Flujos alternativos:**
  - 3a. El evento no existe: 404 `no-encontrado`.
  - 1a. Sin resultados: 200 con una lista vacía; la pantalla muestra el estado vacío.
- **Postcondición:** ninguna (solo lectura).
- **Estado:** Implementado.

## CU-04 Comprar entradas

- **Actor:** comprador. **Sistemas externos:** pasarela de pago (simulada) y ARCA (simulado).
- **Precondición:** sesión iniciada con rol `user` e identidad verificada; el evento no empezó y hay cupo.
- **Flujo principal** (diagramas de secuencia de la compra, partes 1 y 2):
  1. Elige sectores y cantidades y carga un titular (nombre y DNI) por entrada, hasta 6 por compra.
  2. El frontend llama a `POST /api/v1/ventas` con `Idempotency-Key`. Ventas consulta a Usuarios (`/yo`), abre la
     compra en una instancia de `ServicioDeVentas` (`@Stateful`), reserva el cupo en Eventos sector por sector y
     guarda la venta `EN_CHECKOUT`. Responde 201 con la reserva, que vence en 10 minutos.
  3. El comprador paga: `POST /api/v1/ventas/{ventaId}/pagos` con `Idempotency-Key`, medio de pago y token.
  4. Ventas cobra a través de Pagos (`POST /api/v1/pagos`, rol `servicio`), que usa la pasarela simulada.
  5. Con el cobro aprobado, en una sola transacción: la venta pasa a `CONFIRMADA`, se crean las entradas en
     `PENDIENTE_EMISION` y se publica `PAGO_CONFIRMADO`. Responde 201.
  6. La emisión es asincrónica: `EmisionDeEntradasMDB` (cola) pone el código QR a cada entrada, que pasa a
     `VALIDA`, y la venta a `EMITIDA`. Por el tópico salen el aviso al comprador (en el log, **simulado**), el registro
     de auditoría y la factura: `FacturacionDeComprasMDB` le pide el CAE al ARCA simulado por SOAP y la guarda; se
     consulta con `GET /api/v1/facturas/{ventaId}`.
- **Flujos alternativos:**
  - 2a. Más de 6 entradas: 422 `maximo-de-entradas`. Distinta cantidad de titulares que de entradas: 422
    `titulares-no-coinciden`. Dos titulares con el mismo DNI: 422 `dni-repetido`. Sector que no es del evento: 422
    `sector-inexistente`. Nombre vacío o DNI sin 7 u 8 dígitos: 400 `datos-invalidos`.
  - 2b. Identidad sin verificar: 403 `identidad-no-verificada`, antes de reservar.
  - 2c. Sin cupo: 409 `cupo-agotado`; el evento ya empezó: 409 `evento-finalizado`. Se devuelve el cupo de los
    sectores ya reservados.
  - 2d. Falta la cabecera `Idempotency-Key` o no es un UUID: 400 `datos-invalidos`. La misma clave con otro pedido:
    409 `clave-reutilizada`. El mismo pedido repetido devuelve la misma venta, sin reservar otra vez.
  - 2e/4a. Eventos, Usuarios o Pagos no responden: 504 `sin-respuesta`. En el pago, el reintento con la misma clave
    no cobra dos veces; si nadie reintenta, la conciliación de pagos consulta a Pagos y confirma o vence la venta.
  - 3a. Pasaron los 10 minutos o la compra se canceló: 409 `reserva-vencida`. Ya estaba paga con otra clave: 409
    `venta-ya-paga`. Otra llamada en curso sobre la misma compra: 409 `operacion-en-curso`.
  - 4b. Pago rechazado (`tok_rechazado`): 402 `pago-rechazado`; la compra sigue abierta para pagar con otro medio.
  - 5a. El comprador cancela antes de pagar: `DELETE /api/v1/ventas/{ventaId}`, 204, y el cupo vuelve a Eventos.
    Si deja pasar los 10 minutos, `VencimientoDeReservas` la pasa a `VENCIDA` y devuelve el cupo.
  - 6a. La emisión falla: el broker reintenta 5 veces cada 2 segundos y después el mensaje va a la DLQ, desde donde
    se devuelve a mano (sección 10.6). ARCA no responde: la factura se reintenta con la misma política.
- **Postcondición:** venta `EMITIDA`, entradas `VALIDA` con su código QR, una fila de auditoría y la factura con
  número y CAE.
- **Estado:** Implementado, con pasarela simulada. La facturación y la conciliación de pagos: implementado, sin
  probar en el servidor.

## CU-05 Ver mis entradas

- **Actor:** comprador.
- **Precondición:** sesión iniciada con rol `user`.
- **Flujo principal:**
  1. Abre `/mis-entradas`; el frontend llama a `GET /api/v1/ventas`.
  2. Ventas responde las compras del dueño del token, de la más nueva a la más vieja, con sus entradas.
  3. Abre una entrada: `GET /api/v1/entradas/{entradaId}` devuelve la entrada con su `codigoQr` cuando ya está
     `VALIDA`.
- **Flujos alternativos:**
  - 2a. Sin compras: 200 con una lista vacía.
  - 3a. La entrada no existe o es de otro usuario: 404 `no-encontrado` (las dos responden lo mismo).
  - 3b. Recién pagada, todavía `PENDIENTE_EMISION`: la entrada viene sin código; la pantalla espera la emisión.
- **Postcondición:** ninguna (solo lectura).
- **Estado:** Implementado.

## CU-06 Publicar en reventa

- **Actor:** comprador (vendedor), con la identidad verificada.
- **Precondición:** es el titular de una entrada `VALIDA` y faltan más de 3 horas para el evento.
- **Flujo principal:**
  1. En `/reventa` elige una entrada propia y el precio.
  2. Reventa valida el precio en el servidor: entre el 50 % (`MINIMO_REVENTA_PORCENTAJE`) y el 110 %
     (`TOPE_REVENTA_PORCENTAJE`) del precio pagado, con `ReglasDeNegocio.topeReventa` y `minimoReventa`.
  3. Reventa le pide a Ventas, con rol `servicio`, que marque la entrada como `PUBLICADA`; la publicación queda
     activa.
- **Flujos alternativos:**
  - 2a. Precio sobre el tope o bajo el mínimo: 422.
  - 2b. Faltan 3 horas o menos para el evento (`HORAS_LIMITE_REVENTA`): se rechaza.
  - 3a. La entrada no es del vendedor o no está `VALIDA` (usada, ya publicada): se rechaza.
  - 4. El vendedor retira la publicación: la entrada vuelve a `VALIDA`.
- **Postcondición:** publicación activa; la entrada `PUBLICADA` no sirve para entrar (la puerta responde
  `NO_HABILITADA`).
- **Estado:** Diseñado, pendiente. Ruta prevista: `/api/v1/reventas`. Las reglas ya están en `ReglasDeNegocio` y el
  estado `PUBLICADA` en `EstadoEntrada`.

## CU-07 Comprar en reventa

- **Actor:** comprador. **Sistema externo:** pasarela de pago (simulada).
- **Precondición:** sesión con rol `user` e identidad verificada; hay una publicación activa de otro usuario y
  faltan más de 3 horas para el evento.
- **Flujo principal:**
  1. Elige una publicación del evento y carga el titular (nombre y DNI).
  2. Reventa cobra a través de Pagos.
  3. Con el cobro aprobado, Ventas emite una entrada nueva a nombre del comprador y la original queda invalidada
     (`VENDIDA`); el vendedor recibe un aviso.
- **Flujos alternativos:**
  - 1a. La publicación ya se vendió o se retiró: se rechaza.
  - 1b. Nombre vacío o DNI sin 7 u 8 dígitos: 400 `datos-invalidos`.
  - 2a. Pago rechazado: 402 `pago-rechazado`; la publicación sigue activa.
  - 2b. Pagos no responde: 504 `sin-respuesta`.
- **Postcondición:** entrada nueva del comprador, original `VENDIDA`, publicación vendida.
- **Estado:** Diseñado, pendiente.

## CU-08 Crear evento

- **Actor:** organizador.
- **Precondición:** sesión con rol `organizer`.
- **Flujo principal:**
  1. En `/organizador` carga título, categoría, lugar, fecha, descripción y los sectores con precio y cupo.
  2. El frontend llama a `POST /api/eventos` (rol `organizer`).
  3. Eventos valida, genera un slug único y guarda el evento a nombre del organizador. Responde 201 con `Location`.
  4. El evento aparece en el catálogo.
- **Flujos alternativos:**
  - 3a. Falta un dato, un sector sin nombre, o precio o cupo menor que 1: 400 `datos-invalidos`, con el campo.
  - 3b. La fecha no es futura: 400 `datos-invalidos` (campo `fechaISO`). Categoría que no existe: 400
    `datos-invalidos` (campo `categoria`).
- **Postcondición:** evento publicado con sus sectores y `vendidas` en 0.
- **Estado:** Implementado. La edición y la cancelación de eventos están pendientes.

## CU-09 Ver estadísticas

- **Actor:** organizador.
- **Precondición:** sesión con rol `organizer` y al menos un evento propio.
- **Flujo principal:**
  1. Abre el panel de `/organizador`.
  2. El sistema muestra, por evento propio, entradas vendidas, recaudación, ocupación por sector, monitor de reventa
     y accesos.
- **Flujos alternativos:**
  - 2a. Un evento de otro organizador: no se muestra (cada organizador ve solo los suyos).
- **Postcondición:** ninguna (solo lectura).
- **Estado:** Diseñado, pendiente. No hay un servicio que calcule las estadísticas; el panel del frontend usa datos
  fijos. Los accesos se pueden leer del registro de la puerta (`GET /api/v1/validacion/escaneos?eventoId=`, que
  también acepta el rol `organizer`).

## CU-10 Validar entrada en puerta

- **Actor:** personal de puerta.
- **Precondición:** sesión con rol `staff`; la puerta controla un evento (`eventoId`).
- **Flujo principal** (diagrama de secuencia de la validación en puerta):
  1. Escanea el QR o tipea el código en `/puerta`.
  2. El frontend llama a `POST /api/v1/validacion/escaneos` con `codigoQr` y `eventoId`.
  3. `ServicioDeValidacion` le pide a Ventas `POST /api/v1/entradas/usos` (rol `servicio`). `UsoDeEntradas` busca la
     entrada por código y la pasa a `USADA` con un `UPDATE` condicional (solo si sigue `VALIDA`).
  4. Validación guarda el escaneo y responde 201 con `resultado` `VALIDA`, `puedeEntrar` `true` y el nombre y DNI
     del titular.
  5. El personal contrasta nombre y DNI con el documento y deja entrar.
- **Flujos alternativos** (todos responden 201: el escaneo queda registrado; el resultado dice si entra):
  - 3a. El código no existe: `INEXISTENTE` (Ventas respondió 404).
  - 3b. La entrada es de otro evento: `OTRO_EVENTO` (409 `entrada-de-otro-evento`); la entrada sigue sirviendo en
    el suyo.
  - 3c. Ya se usó, también si dos puertas la escanean a la vez: `YA_USADA` (409 `entrada-ya-usada`), con el escaneo
    anterior.
  - 3d. Está publicada o vendida en la reventa, o anulada: `NO_HABILITADA` (409 `entrada-no-habilitada`).
  - 3e. Ventas no responde: el escaneo se guarda como `SIN_RESPUESTA` y la respuesta es 504 `sin-respuesta`; hay que
    escanear de nuevo.
  - 2a. Falta el código o el evento: 400 `datos-invalidos`.
- **Postcondición:** con `VALIDA`, la entrada queda `USADA` y no vuelve a servir; en todos los casos queda un renglón
  en el registro de la puerta (`GET /api/v1/validacion/escaneos?eventoId=`).
- **Estado:** Implementado, sin probar en el servidor.
