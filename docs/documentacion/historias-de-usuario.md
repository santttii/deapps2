# Historias de usuario de Palco

Las historias salen de los casos de uso (`casos-de-uso.md`) y fijan el alcance que se prueba en la Prueba del TP.
Cada criterio de aceptación está escrito en Gherkin con códigos HTTP, estados y números concretos: lo que dice
el criterio es lo que comprueba el caso de prueba.

## Convención de trazabilidad

| Nivel | Formato | Ejemplo | Dónde se usa |
|---|---|---|---|
| Historia | `HU-xx` | `HU-04 Comprar entradas` | Este documento y Notion |
| Criterio | `CA-xx.y` | `CA-04.2 Pago rechazado` | Este documento (Gherkin) |
| Caso de prueba | `CP-xx.y.z` | `CP-04.2.1` | Nombre de cada test automatizado o manual |

En el código, el ID va en el nombre del test, así la matriz se arma sola a partir de los reportes
(`scripts/qa/matriz.mjs`):

- **JUnit:** `@Tag("CA-04.2")` y `@DisplayName("CP-04.2.1 Pago rechazado: 402 y la venta sigue EN_CHECKOUT")`.
- **Postman:** `"CP-04.2.1 POST /v1/ventas/{id}/pagos rechazado → 402"`.
- **Playwright:** `test('CP-04.1.1 Compra completa y entrada en Mis entradas', …)`.
- **scripts/pruebas/escenarios.mjs:** el título del escenario empieza con los `CA` que cubre.

Un criterio sin ningún `CP` aparece en rojo en la matriz.

**Prioridad (MoSCoW).** Must: sin esto el producto no sirve y una falla bloquea la entrega. Should: importante,
con alternativa. Could: suma, se recorta primero. Won't: fuera del alcance de esta entrega.

## Resumen

| HU | Historia | Prioridad | CU | Integra con |
|---|---|---|---|---|
| HU-01 | Registrarme y verificar mi email | Must | CU-01 | — |
| HU-02 | Iniciar sesión | Must | CU-02 | — |
| HU-03 | Recuperar mi contraseña | Should | CU-02 | Correo (simulado en el log) |
| HU-04 | Consultar el catálogo | Must | CU-03 | — |
| HU-05 | Comprar entradas | Must | CU-04 | Eventos, Pagos (pasarela simulada) |
| HU-06 | Recibir la factura de mi compra | Should | CU-04 | ARCA (SOAP, simulado) |
| HU-07 | Ver mis entradas | Must | CU-05 | — |
| HU-08 | Publicar una entrada en la reventa | Must | CU-06 | Ventas |
| HU-09 | Comprar en la reventa | Must | CU-07 | Pagos, Ventas |
| HU-10 | Crear y editar un evento | Must | CU-08 | — |
| HU-11 | Cancelar un evento | Should | CU-08 | Ventas, Pagos (mensajería) |
| HU-12 | Ver el resumen de ventas de mis eventos | Could | CU-09 | Ventas |
| HU-13 | Validar una entrada en la puerta | Must | CU-10 | Ventas |
| HU-14 | Asignar personal a la puerta de mi evento | Should | CU-10 | Validación |

Won't (fuera de esta entrega): verificación de identidad con foto del DNI y pasarela de pago real
(Mercado Pago). Las dos quedan simuladas y así se dice en la documentación.

---

## HU-01 Registrarme y verificar mi email · Must

**Como** persona sin cuenta **quiero** registrarme con mis datos y verificar mi email **para** poder comprar
entradas nominativas.

```gherkin
Escenario: CA-01.1 Registro y verificación correctos
  Dado que no existe una cuenta con el email "nuevo@palco.test" ni con el DNI "30111222"
  Cuando envío POST /api/usuarios/registro con nombre, apellido, DNI, email, teléfono, fecha de nacimiento y contraseña válidos
  Entonces la respuesta es 201
  Y la cuenta queda con rol "user", pendiente de verificar
  Cuando envío POST /api/usuarios/verificacion con el código de 6 dígitos generado
  Entonces la respuesta es 200 con un token de sesión
  Y la cuenta queda con el email y la identidad verificados

Escenario: CA-01.2 Email o DNI ya registrados
  Dado que existe una cuenta con el email "titular@palco.test"
  Cuando envío POST /api/usuarios/registro con ese email
  Entonces la respuesta es 400 con type "/problemas/datos-invalidos" y el campo "email"
  Y no se crea ninguna cuenta

Escenario: CA-01.3 Datos inválidos
  Cuando envío POST /api/usuarios/registro con un DNI de 6 dígitos o una fecha de nacimiento de alguien menor de 16 años
  Entonces la respuesta es 400 con type "/problemas/datos-invalidos" y el detalle del campo

Escenario: CA-01.4 Código incorrecto
  Dado que me registré y no verifiqué el email
  Cuando envío POST /api/usuarios/verificacion con un código distinto al generado
  Entonces la respuesta es 400 con type "/problemas/datos-invalidos" y el campo "codigo"
  Y la cuenta sigue sin verificar

Escenario: CA-01.5 Campos que no se pueden elegir (mass assignment)
  Cuando envío POST /api/usuarios/registro con un campo "rol": "organizer" en el cuerpo
  Entonces la cuenta creada tiene rol "user"
```

## HU-02 Iniciar sesión · Must

**Como** comprador, organizador o personal de puerta **quiero** iniciar sesión con mi email y contraseña **para**
usar las funciones de mi rol.

```gherkin
Escenario: CA-02.1 Login correcto
  Dado que existe la cuenta verificada "titular@palco.test" con la contraseña "palco1234"
  Cuando envío POST /api/usuarios/login con esas credenciales
  Entonces la respuesta es 200 con un JWT firmado con RS256
  Y el claim "groups" contiene "user"
  Y GET /api/usuarios/yo con ese token responde 200 con los datos de la cuenta

Escenario: CA-02.2 Contraseña incorrecta
  Cuando envío POST /api/usuarios/login con la contraseña "otra-cosa"
  Entonces la respuesta es 401 con type "/problemas/no-autenticado"

Escenario: CA-02.3 Fuerza bruta
  Dado que hubo 5 intentos fallidos en los últimos 15 minutos para "titular@palco.test"
  Cuando envío POST /api/usuarios/login, aunque la contraseña sea la correcta
  Entonces la respuesta es 429 con type "/problemas/demasiados-intentos" y la cabecera Retry-After

Escenario: CA-02.4 Token inválido
  Cuando llamo a GET /api/usuarios/yo con un token de firma alterada, con alg "none", vencido o de otro emisor
  Entonces la respuesta es 401 con type "/problemas/no-autenticado"

Escenario: CA-02.5 Email sin verificar
  Dado que me registré y no verifiqué el email
  Cuando envío POST /api/usuarios/login con mis credenciales
  Entonces la respuesta es 409 con type "/problemas/conflicto"
```

## HU-03 Recuperar mi contraseña · Should

**Como** usuario que olvidó la contraseña **quiero** pedir un código y elegir una nueva **para** volver a entrar.

```gherkin
Escenario: CA-03.1 Reinicio correcto
  Dado que existe la cuenta "titular@palco.test"
  Cuando envío POST /api/usuarios/password/olvide con ese email
  Entonces la respuesta es 204 y se genera un código
  Cuando envío POST /api/usuarios/password/reinicio con el código y una contraseña nueva
  Entonces la respuesta es 204
  Y POST /api/usuarios/login con la contraseña nueva responde 200 y con la anterior responde 401

Escenario: CA-03.2 Email que no existe
  Cuando envío POST /api/usuarios/password/olvide con "nadie@palco.test"
  Entonces la respuesta es 204, igual que para una cuenta existente (no revela qué emails están registrados)

Escenario: CA-03.3 Código incorrecto
  Cuando envío POST /api/usuarios/password/reinicio con un código que no es el generado
  Entonces la respuesta es 400 con type "/problemas/datos-invalidos"
  Y la contraseña no cambia
```

## HU-04 Consultar el catálogo · Must

**Como** visitante, con o sin sesión, **quiero** ver y buscar los eventos **para** elegir a cuál ir.

```gherkin
Escenario: CA-04.1 Listado y detalle
  Cuando abro "/" (GET /api/eventos)
  Entonces la respuesta es 200 con los eventos que no empezaron, cada uno con sus sectores, precio y cupo
  Cuando abro un evento (GET /api/eventos/{slug})
  Entonces la respuesta es 200 con los sectores, su precio y los lugares disponibles

Escenario: CA-04.2 Búsqueda sin resultados
  Cuando busco "zzzz-no-existe" (GET /api/eventos?q=zzzz-no-existe)
  Entonces la respuesta es 200 con una lista vacía
  Y la pantalla muestra el estado vacío

Escenario: CA-04.3 Evento inexistente
  Cuando pido GET /api/eventos/no-existe
  Entonces la respuesta es 404 con type "/problemas/no-encontrado"

Escenario: CA-04.4 Entrada maliciosa en la búsqueda
  Cuando busco "' OR '1'='1" o "<script>alert(1)</script>"
  Entonces la respuesta es 200 o 400, nunca 500
  Y el texto se muestra escapado y no se ejecuta en el navegador
```

## HU-05 Comprar entradas · Must

**Como** comprador con la identidad verificada **quiero** comprar hasta 6 entradas nominativas y pagarlas **para**
tener mis entradas emitidas con su código QR.

```gherkin
Escenario: CA-05.1 Compra completa
  Dado que inicié sesión como "titular@palco.test" y el sector tiene cupo
  Cuando envío POST /api/v1/ventas con Idempotency-Key, 2 entradas y un titular por entrada
  Entonces la respuesta es 201 con la venta EN_CHECKOUT y la reserva vence en 10 minutos
  Y el cupo del sector baja en 2
  Cuando envío POST /api/v1/ventas/{ventaId}/pagos con Idempotency-Key y un token de pago aprobado
  Entonces la respuesta es 201 y la venta pasa a CONFIRMADA con las entradas en PENDIENTE_EMISION
  Y en 15 segundos o menos la venta pasa a EMITIDA y cada entrada a VALIDA con su código QR

Escenario: CA-05.2 Pago rechazado
  Dado que tengo una venta EN_CHECKOUT
  Cuando pago con el token "tok_rechazado"
  Entonces la respuesta es 402 con type "/problemas/pago-rechazado"
  Y la venta sigue EN_CHECKOUT, sin entradas, y puedo pagar con otro medio

Escenario: CA-05.3 Reglas de la compra
  Cuando pido 7 entradas
  Entonces la respuesta es 422 con type "/problemas/maximo-de-entradas"
  Cuando mando 2 entradas con 1 titular
  Entonces la respuesta es 422 con type "/problemas/titulares-no-coinciden"
  Cuando mando dos titulares con el mismo DNI
  Entonces la respuesta es 422 con type "/problemas/dni-repetido"
  Cuando el sector no tiene cupo suficiente
  Entonces la respuesta es 409 con type "/problemas/cupo-agotado" y no queda cupo reservado

Escenario: CA-05.4 Idempotencia
  Cuando repito POST /api/v1/ventas con la misma Idempotency-Key y el mismo cuerpo
  Entonces recibo la misma venta y el cupo no baja otra vez
  Cuando uso la misma Idempotency-Key con otro cuerpo
  Entonces la respuesta es 409 con type "/problemas/clave-reutilizada"
  Cuando repito el pago con la misma Idempotency-Key
  Entonces no se cobra dos veces

Escenario: CA-05.5 Falla externa: Pagos no responde
  Dado que la pasarela no contesta (token "tok_sin_respuesta")
  Cuando pago
  Entonces la respuesta es 504 con type "/problemas/sin-respuesta"
  Y la conciliación de pagos confirma o vence la venta sin cobrar dos veces

Escenario: CA-05.6 Falla externa: Eventos no responde al reservar
  Dado que Eventos no está disponible
  Cuando envío POST /api/v1/ventas
  Entonces la respuesta es 504 con type "/problemas/sin-respuesta" y no queda ninguna venta abierta

Escenario: CA-05.7 Reserva vencida o cancelada
  Dado que cancelé la compra (DELETE /api/v1/ventas/{ventaId} → 204) o pasaron 10 minutos
  Cuando intento pagarla
  Entonces la respuesta es 409 con type "/problemas/reserva-vencida"
  Y el cupo volvió al sector

Escenario: CA-05.8 Identidad sin verificar
  Dado que inicié sesión con una cuenta sin la identidad verificada
  Cuando envío POST /api/v1/ventas
  Entonces la respuesta es 403 con type "/problemas/identidad-no-verificada" y no se reserva cupo

Escenario: CA-05.9 Mensajería: emisión una sola vez
  Cuando se confirma un pago
  Entonces sale 1 mensaje PAGO_CONFIRMADO a la cola y 1 al tópico
  Y si el mensaje llega dos veces, las entradas se emiten una sola vez
  Y un mensaje inválido se reintenta 5 veces y termina en la DLQ sin trabar a los demás
```

## HU-06 Recibir la factura de mi compra · Should

**Como** comprador **quiero** que mi compra se facture con CAE de ARCA **para** tener el comprobante fiscal.

```gherkin
Escenario: CA-06.1 Factura emitida
  Dado que mi compra quedó CONFIRMADA
  Cuando consulto GET /api/v1/facturas/{ventaId}
  Entonces en 15 segundos o menos la respuesta es 200 con número de comprobante y CAE

Escenario: CA-06.2 Una sola factura por compra
  Cuando el mensaje de compra confirmada llega dos veces a Facturación
  Entonces existe una sola factura para esa venta

Escenario: CA-06.3 Falla externa: ARCA no responde o rechaza
  Dado que el ARCA simulado no responde en 5 segundos o devuelve un Fault
  Cuando se procesa la compra confirmada
  Entonces la factura no queda emitida con datos inventados, el Fault no llega al comprador
  Y el mensaje se reintenta según la política del broker

Escenario: CA-06.4 Factura de otra persona
  Cuando consulto GET /api/v1/facturas/{ventaId} de una venta que no es mía
  Entonces la respuesta es 404 con type "/problemas/no-encontrado"
```

## HU-07 Ver mis entradas · Must

**Como** comprador **quiero** ver mis compras y entradas con su código QR **para** presentarlas en la puerta.

```gherkin
Escenario: CA-07.1 Mis compras
  Dado que compré entradas
  Cuando abro "/mis-entradas" (GET /api/v1/ventas)
  Entonces la respuesta es 200 con mis compras de la más nueva a la más vieja, con sus entradas
  Y cada entrada VALIDA tiene su código QR

Escenario: CA-07.2 Sin compras
  Dado que nunca compré
  Cuando abro "/mis-entradas"
  Entonces la respuesta es 200 con una lista vacía y la pantalla dice "Todavía no tenés entradas emitidas."

Escenario: CA-07.3 Entrada de otra persona (BOLA)
  Cuando pido GET /api/v1/entradas/{entradaId} de una entrada de otro usuario
  Entonces la respuesta es 404 con type "/problemas/no-encontrado", igual que para una que no existe
```

## HU-08 Publicar una entrada en la reventa · Must

**Como** comprador con la identidad verificada **quiero** publicar una entrada propia en la reventa oficial
**para** recuperar lo que pagué si no puedo ir.

```gherkin
Escenario: CA-08.1 Publicación dentro del rango
  Dado que tengo una entrada VALIDA que pagué $10.000 y faltan más de 3 horas para el evento
  Cuando envío POST /api/v1/reventas con un precio de $11.000 (110 %)
  Entonces la respuesta es 201 con la publicación ACTIVA
  Y la entrada pasa a PUBLICADA

Escenario: CA-08.2 Precio fuera de rango
  Cuando publico a $11.001 (más del 110 %) o a $4.999 (menos del 50 %)
  Entonces la respuesta es 422 con type "/problemas/precio-fuera-de-rango"
  Y la entrada sigue VALIDA

Escenario: CA-08.3 Entrada que no es mía o no está disponible
  Cuando publico una entrada de otro usuario
  Entonces la respuesta es 404 con type "/problemas/no-encontrado"
  Cuando publico una entrada ya USADA o ya PUBLICADA
  Entonces la respuesta es 409

Escenario: CA-08.4 Retirar la publicación
  Dado que tengo una publicación ACTIVA
  Cuando envío DELETE /api/v1/reventas/{publicacionId}
  Entonces la respuesta es 204 y la entrada vuelve a VALIDA

Escenario: CA-08.5 Entrada publicada en la puerta
  Dado que mi entrada está PUBLICADA
  Cuando la escanean en la puerta
  Entonces el resultado es NO_HABILITADA y no puede entrar
```

## HU-09 Comprar en la reventa · Must

**Como** comprador **quiero** comprar una entrada publicada por otra persona **para** ir a un evento agotado.

```gherkin
Escenario: CA-09.1 Compra en la reventa
  Dado que hay una publicación ACTIVA de otro usuario y faltan más de 3 horas
  Cuando envío POST /api/v1/reventas/{publicacionId}/compra con un titular válido
  Entonces la respuesta es 201
  Y se emite una entrada nueva a mi nombre y la original queda VENDIDA
  Y la publicación queda vendida y sale el mensaje REVENTA_CONFIRMADA

Escenario: CA-09.2 Pago rechazado
  Cuando compro con un token de pago rechazado
  Entonces la respuesta es 402 con type "/problemas/pago-rechazado"
  Y la publicación sigue ACTIVA

Escenario: CA-09.3 Publicación propia o ya vendida
  Cuando intento comprar mi propia publicación
  Entonces la respuesta es 409 con type "/problemas/compra-propia"
  Cuando la publicación ya se vendió o se retiró
  Entonces la respuesta es 409 con type "/problemas/publicacion-no-activa"

Escenario: CA-09.4 Falla externa: Pagos no responde
  Dado que la pasarela no contesta
  Cuando compro
  Entonces la respuesta es 504 y nadie más puede comprar ni retirar esa publicación mientras tanto
  Y la conciliación libera la publicación o transfiere la entrada, sin cobrar dos veces

Escenario: CA-09.5 Cobrado y sin entrada
  Dado que el pago se aprobó pero Ventas rechaza la transferencia
  Entonces la publicación se anula y el pago se devuelve
```

## HU-10 Crear y editar un evento · Must

**Como** organizador **quiero** crear un evento con sus sectores y corregirlo **para** venderlo en Palco.

```gherkin
Escenario: CA-10.1 Alta de evento
  Dado que inicié sesión como "organizador@palco.test"
  Cuando envío POST /api/eventos con título, categoría, lugar, fecha futura y 2 sectores con precio y cupo
  Entonces la respuesta es 201 con Location y un slug único
  Y el evento aparece en GET /api/eventos con "vendidas" en 0 en cada sector

Escenario: CA-10.2 Datos inválidos
  Cuando la fecha no es futura, la categoría no existe o un sector tiene precio o cupo menor que 1
  Entonces la respuesta es 400 con type "/problemas/datos-invalidos" y el campo

Escenario: CA-10.3 Permisos (BFLA y BOLA)
  Cuando un usuario con rol "user" envía POST /api/eventos
  Entonces la respuesta es 403 con type "/problemas/sin-permiso"
  Cuando un organizador edita (PUT /api/eventos/{eventoId}) un evento de otro organizador
  Entonces la respuesta es 404 con type "/problemas/no-encontrado"

Escenario: CA-10.4 Panel con mis eventos
  Cuando abro "/organizador" (GET /api/eventos/mios)
  Entonces veo solo los eventos que creé yo
```

## HU-11 Cancelar un evento · Should

**Como** organizador **quiero** cancelar un evento **para** que se anulen las entradas y se devuelva el dinero.

```gherkin
Escenario: CA-11.1 Cancelación con reembolso
  Dado que mi evento tiene compras EMITIDA
  Cuando envío POST /api/eventos/{eventoId}/cancelacion con un motivo
  Entonces la respuesta es 200 y el evento queda cancelado
  Y por mensajería se anulan las entradas, se pide el reembolso a Pagos y se avisa a cada comprador

Escenario: CA-11.2 Evento de otro organizador
  Cuando cancelo un evento que no es mío
  Entonces la respuesta es 404 con type "/problemas/no-encontrado"

Escenario: CA-11.3 Comprar un evento cancelado
  Dado que el evento está cancelado
  Cuando envío POST /api/v1/ventas para ese evento
  Entonces la respuesta es 409 con type "/problemas/evento-cancelado"
```

## HU-12 Ver el resumen de ventas de mis eventos · Could

**Como** organizador **quiero** ver cuántas entradas vendí y cuánto recaudé por evento **para** seguir la venta.

```gherkin
Escenario: CA-12.1 Resumen propio
  Dado que vendí entradas de mi evento
  Cuando consulto el resumen de ventas (GET /api/v1/resumenes)
  Entonces la respuesta es 200 con entradas vendidas y recaudación de mis eventos

Escenario: CA-12.2 Eventos ajenos
  Cuando consulto el resumen
  Entonces no aparecen los eventos de otros organizadores

Escenario: CA-12.3 Rol sin permiso
  Cuando un usuario con rol "user" consulta el resumen
  Entonces la respuesta es 403 con type "/problemas/sin-permiso"
```

## HU-13 Validar una entrada en la puerta · Must

**Como** personal de puerta **quiero** escanear el QR de cada entrada **para** dejar entrar solo a quien tiene una
entrada válida, una sola vez.

```gherkin
Escenario: CA-13.1 Primer escaneo
  Dado que estoy asignado a la puerta del evento y la entrada está VALIDA
  Cuando envío POST /api/v1/validacion/escaneos con el codigoQr y el eventoId
  Entonces la respuesta es 201 con resultado "VALIDA", puedeEntrar true y el nombre y DNI del titular
  Y la entrada pasa a USADA

Escenario: CA-13.2 Segundo escaneo
  Dado que la entrada ya se usó
  Cuando la escaneo otra vez
  Entonces la respuesta es 201 con resultado "YA_USADA" y puedeEntrar false

Escenario: CA-13.3 Código inexistente o de otro evento
  Cuando escaneo un código que no existe
  Entonces el resultado es "INEXISTENTE"
  Cuando escaneo una entrada de otro evento
  Entonces el resultado es "OTRO_EVENTO" y la entrada sigue sirviendo en el suyo

Escenario: CA-13.4 Falla externa: Ventas no responde
  Dado que Ventas no está disponible
  Cuando escaneo
  Entonces la respuesta es 504 con type "/problemas/sin-respuesta" y el escaneo queda registrado como SIN_RESPUESTA

Escenario: CA-13.5 Personal no asignado
  Dado que no estoy asignado a la puerta de ese evento
  Cuando escaneo una entrada del evento
  Entonces el escaneo se rechaza y la entrada no cambia de estado
```

## HU-14 Asignar personal a la puerta de mi evento · Should

**Como** organizador **quiero** elegir qué personal controla la puerta de mi evento **para** que nadie más pueda
validar entradas.

```gherkin
Escenario: CA-14.1 Asignar y quitar
  Dado que inicié sesión como organizador dueño del evento
  Cuando envío PUT /api/v1/validacion/puertas/{eventoId}/{email} con el email de una cuenta "staff"
  Entonces esa cuenta ve el evento en GET /api/v1/validacion/puertas/mias
  Cuando envío DELETE sobre la misma ruta
  Entonces deja de verlo y no puede escanear entradas de ese evento

Escenario: CA-14.2 Evento de otro organizador
  Cuando asigno personal a un evento que no es mío
  Entonces la respuesta es 404 con type "/problemas/no-encontrado"

Escenario: CA-14.3 Cuenta que no es de puerta
  Cuando asigno el email de una cuenta con rol "user"
  Entonces la respuesta es 400 o 422 y no queda ninguna asignación
```

## Cobertura actual por la prueba de integración

Lo que ya comprueba `scripts/pruebas/escenarios.mjs` (los números son los de sus escenarios). Es el punto de
partida de la matriz; los criterios que no están en esta tabla todavía necesitan su caso.

| Criterio | Escenario |
|---|---|
| CA-05.1 | 1 |
| CA-05.4 | 2 |
| CA-05.2 | 3 |
| CA-05.3 | 4 |
| CA-02.4, CA-07.3, CA-10.3 | 5 (seguridad: sin token, token de otro usuario, otro rol) |
| CA-05.7 | 6 |
| CA-05.9 | 7, 8, 9, 10, 11, 12, 13 |
| CA-05.5 | 14 |
| CA-06.1, CA-06.2 | 15 |
| CA-13.1, CA-13.2 | 16 |
| CA-08.1, CA-09.1 | 17 |
| CA-05.6 | 18 |
| CA-09.4 | 19 |
| CA-09.5 | 20 |
| CA-10.4, CA-13.5, CA-14.1 | 21 |
