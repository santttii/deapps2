# Guía Postman: API de Palco

La colección `docs/api/palco-api.postman_collection.json` se corre entera, en orden y sin intervención, contra el backend local. Se puede repetir las veces que haga falta sin reiniciar el servidor. En el CI la corre el job `api` en cada corrida, contra el sistema levantado con `docker compose` (sección 7, "En el CI").

La colección no se edita a mano: la genera `scripts/postman/generar.js` (sección 12).

Última comprobación: 24/11/2026, con Newman 6.2.3 contra el sistema levantado con `docker compose` (el backend en `http://localhost:8080`): las nueve carpetas, 219 requests (los 214 de la colección y los cinco intentos de login previos de SEC-W04) y 682 aserciones, sin fallas. Las comprobaciones anteriores, del 14/10, del 16/10 y del 19/11/2026, están en las secciones 7 y 13.

## 1. Qué hace falta

1. Postman (postman.com/downloads). Se puede usar sin cuenta ("Skip and go to the app").
2. El backend levantado: `cd backend && ./dev.sh`. Para comprobarlo, abrí en el navegador `http://localhost:8080/api/eventos` (el catálogo) y `http://localhost:8080/api/v1/health` (Ventas).
3. El secreto entre servicios con el que arrancó el servidor, para la variable `secretoServicios`, que viene vacía (sección 7).

## 2. Importar

`Import` → arrastrá `docs/api/palco-api.postman_collection.json`. No hace falta un environment: todo lo que cambia está en las variables de la colección.

## 3. Qué trae

| Carpeta | Requests de la corrida | Qué prueba |
|---|---|---|
| **1 Usuarios (implementado)** | 18 | Registro, registro inválido, login (comprador y staff), login incorrecto, login sin verificar el email, pedir otro código antes de los 30 segundos (409), verificar una cuenta ya verificada (409), perfil con y sin token, token de servicio con secreto incorrecto y con el bueno; con rol `servicio`, un usuario por su id (`GET /usuarios/{usuarioId}`: 200, y 403 con rol `user`) y el rol de una cuenta por su email (`GET /usuarios/cuentas`: 200, y 404 para una sin verificar); JWKS (200) y JWKS con el método equivocado (405, `about:blank`) |
| **2 Eventos (implementado)** | 18 | Listar, filtrar, filtrar por una categoría que no existe (400), detalle, 404, crear evento sin token, con rol organizer e inválido, los eventos propios del organizador (`/mios`, con y sin token), el dueño de un evento (`GET /eventos/{eventoId}/organizador`: 200 con rol `servicio`, 403 con el de organizador), y cambio de cupo con rol servicio (reservar, pasarse del cupo, liberar) |
| **3 Diseño v1 (Ventas implementado)** | 32 | La compra contra `/api/v1`, en un evento que crea la carpeta en cada corrida (Platea B a 27.500, como el de la demo): abrir la compra con reserva de cupo, idempotencia (también la misma clave con otro pedido, 409), pago rechazado, pago aprobado, venta y entrada, emisión asincrónica de la entrada, listado de las compras propias (con y sin token), aislamiento entre usuarios, control de rol y cancelación |
| **4 Seguridad (clase 11)** | 15 | Los criterios de la matriz de seguridad de la clase 11 que se prueban por HTTP, cada uno con su id (`SEC-…`): recurso ajeno, rol equivocado, sin token, mensajes de login genéricos, token alterado o sin firma, vigencia del token, errores sin información interna, perfil sin contraseña y límite de intentos de login. Ver la sección 13 |
| **5 Validación en puerta (implementado)** | 27 | `organizador@palco.test` asigna `puerta@palco.test` a la puerta del evento de la compra (`PUT /v1/validacion/puertas/{eventoId}/{email}`, 200) y no puede asignar una cuenta de comprador (422 `no-es-de-puerta`); el equipo de puerta del evento (`GET /v1/validacion/puertas`, 200, y 404 el de un evento ajeno) y las puertas propias del staff (`/puertas/mias`, 200, y 403 con rol `organizer`). Esa cuenta escanea el código QR de la entrada que pagó la carpeta 3: sin token (401), con rol `user` (403), sin evento (400), un código inexistente (`INEXISTENTE`), la puerta de otro evento (`OTRO_EVENTO`), el primer escaneo (`VALIDA`: la persona entra y Ventas marca la entrada `USADA`) y el segundo (`YA_USADA`, con el uso anterior). Después, la entrada `USADA` en Ventas y el registro de la puerta (lista, el de un evento ajeno pedido por el organizador, 404, sin `eventoId`, un renglón y uno que no existe, 404). Al final, la operación interna de Ventas que usa Validación (`POST /v1/entradas/usos`, rol `servicio`) con la otra entrada de la compra (200 `USADA`, y 409 `entrada-ya-usada` al repetirla), quitar la cuenta de la puerta (403 con rol `staff`, 204 con el organizador) y el escaneo sin la puerta asignada (403 `puerta-no-asignada`). Todo escaneo responde 201: si la persona entra lo dicen `resultado` y `puedeEntrar` |
| **6 Reventa (implementado)** | 46 | La reventa de punta a punta, en un evento que crea la carpeta en cada corrida: `titular@palco.test` compra 2 entradas de Platea B, espera la emisión y publica la primera a 29.000 (el rango va de 13.750 a 30.250). Errores: sin token (401), entrada inexistente (404), sobre el tope y bajo el mínimo (422 `precio-fuera-de-rango`), la misma entrada otra vez (409 `entrada-no-disponible`), compra propia (409 `compra-propia`), compra sin `Idempotency-Key` (400) y cobro rechazado (402). `otro.titular@palco.test` la compra a nombre de Sofía Paz (201), la repite con la misma clave (201, la misma) y con otra (409 `publicacion-no-activa`: ya vendida). La lista sin `eventoId` (400) y `/mias` sin token (401). Al final, la original `VENDIDA`, la nueva `VALIDA` del comprador, y la segunda entrada publicada y retirada (404 ajena, 204, 409 repetido, y la entrada de nuevo `VALIDA`). Después, con rol `servicio`, las operaciones internas de Ventas que usa Reventa (`/v1/operaciones-de-reventa`): consultar la entrada (200, y 404 con otro vendedor), publicarla (200 `PUBLICADA`, y 409 `entrada-no-disponible` otra vez), controlar una compra antes de cobrarla (204 si `otro.titular@palco.test` la comprara a nombre de Luis, porque la entrada que se revende no cuenta para su DNI; 409 `dni-con-entrada` a nombre de Sofía, que ya tiene la suya, y 403 con rol `user`), retirarla (200 `VALIDA`, y 409 `entrada-no-publicada` para la que se vendió) y repetir la transferencia de la compra (201, la misma venta y la misma entrada; 403 con rol `user`) |
| **7 Facturación (implementado)** | 11 | La factura de la compra de la carpeta 3: aparece con número (`00001-…`), CAE de 14 dígitos, factura B (6) y el total cobrado; consultada de nuevo es la misma. Sin token (401), de otro comprador (404), inexistente (404), con rol `staff` (403) y con rol `servicio` (200) |
| **8 Operaciones del 30/10 (implementado)** | 42 | Sobre un evento y una cuenta que crea la carpeta: editar el evento propio (`PUT`, 200), el de otro organizador (404) y con rol `user` (403); una compra pagada y emitida, y la edición con `If-Match` y el ETag de antes de esa compra (412 `evento-modificado`); las ventas por día y la liquidación (`GET /v1/resumenes`, 200, 404 ajeno y 403 por rol); cancelar el evento ajeno (404), con rol `user` (403), el propio (200) y otra vez (409); la venta anulada (`CANCELADA`, entradas `ANULADA`, asincrónico), su pago `REEMBOLSADO` y, con rol `servicio`, lo que Pagos les ofrece a los demás servicios: el pago pedido con el token del comprador (403), buscado por su clave (200, y 404 una clave sin pago), el cobro repetido con la misma clave (201, el mismo pago; 409 `clave-reutilizada` con otro monto) y el reembolso repetido (200; 409 `pago-no-reembolsable` por otro monto); el resumen en cero, el detalle del evento cancelado y una compra nueva en él (409 `evento-cancelado`). La foto: se sube y se guarda en la base de Palco (200, con su URL), esa URL responde sin token (200) y una que no existe, 404; un texto declarado como PNG se rechaza por su firma (400 `datos-invalidos`) y un SVG por su tipo (415). Olvidé mi contraseña de una cuenta que existe y de un email que no (204 las dos, la misma respuesta) y sin email (400), el reinicio con un código incorrecto (400) y el login que muestra que la contraseña no cambió. Ver la sección 14 |
| **9 Asistente (implementado)** | 5 | El asistente de preguntas (`/api/v1/asistente`): el health (200), una pregunta con sesión que responde el modelo (200, origen `MODELO`), la misma sin token (401 `no-autenticado`, con `WWW-Authenticate`) y un intento de inyección de prompt que corta el guardrail de entrada. El corte no es un error: responde 200 con origen `BLOQUEADA` y motivo `inyeccion`, y no Problem Details. En el backend local el modelo es el simulado |

Además hay una subcarpeta que **queda fuera de la corrida**: **1 Usuarios → Pasos manuales** (5 requests): reenviar el código, verificar el email y, con la cuenta ya verificada, cambiarle la contraseña (pedir el código, reiniciarla e iniciar sesión con la nueva). Necesitan códigos que solo salen en el log del servidor. Ver la sección 8. En el CI corren aparte, después de la corrida, el reenvío y la verificación, con el código que el job lee del log (sección 7, "En el CI").

Cómo queda afuera: el último request automático de la carpeta 1 ("JWKS claves públicas (200)") le dice a la corrida que siga en la carpeta 2. Está en la pestaña Scripts de ese request. Si en el Runner lo destildás, la corrida entra en la subcarpeta. La carpeta 9 es la última: la corrida termina con ella.

Los nombres de los requests dicen el resultado esperado: el código entre paréntesis es el que tiene que responder el servidor.

## 4. Cuentas de prueba

Las crea el backend al arrancar (migraciones `V2__usuarios_de_prueba.sql` y `V3__segundo_comprador_de_prueba.sql` de Usuarios). La contraseña de todas es `palco1234`.

| Rol | Cuenta | Para qué se usa | Variable donde queda el token |
|---|---|---|---|
| `user` (comprador) | `titular@palco.test` | Perfil y todas las compras; en Seguridad, la cuenta A; las preguntas al asistente | `token`; en Seguridad, `tokenA` |
| `user` (comprador) | `otro.titular@palco.test` | Aislamiento: no ve las compras ni la factura de `titular@palco.test`; en Reventa, quien compra; en Seguridad, la cuenta B | `tokenOtroComprador`; en Seguridad, `tokenB` |
| `organizer` | `organizador@palco.test` | Crear eventos, también los de las compras de las carpetas 3 y 6; asignar y quitar la puerta del evento de la compra (carpeta 5); en Ventas, el 403 por rol; en la carpeta 8, el dueño del evento que se edita y se cancela, y quien sube la foto | `tokenOrganizador` |
| `organizer` | `otro.organizador@palco.test` | Carpeta 8: el evento de `organizador@palco.test` no lo puede editar, cancelar ni pedir su resumen | `tokenOtroOrganizador` |
| `staff` | `puerta@palco.test` | Validación en puerta (carpeta 5); en Ventas y en Facturación, el 403 por rol | `tokenStaff` |
| `servicio` | No es una cuenta: `POST /api/usuarios/servicios/token` con `{"secreto":"{{secretoServicios}}"}`, el secreto con el que arrancó el servidor (la variable viene vacía: ver la sección 7) | Las operaciones entre servicios: un usuario por su id y el rol de una cuenta (carpeta 1), el dueño de un evento y el cupo de un sector (carpeta 2), marcar una entrada usada (carpeta 5), las operaciones de reventa de Ventas (carpeta 6), cualquier factura (carpeta 7) y Pagos (carpeta 8) | `tokenServicio` (dura 5 minutos) |

Cada request manda el token de su rol en la cabecera `Authorization: Bearer {{...}}`. No hay que copiar tokens a mano: los guardan los requests de login.

## 5. Orden

La colección va de arriba hacia abajo: **1 Usuarios → 2 Eventos → 3 Diseño v1 → 4 Seguridad → 5 Validación en puerta → 6 Reventa → 7 Facturación → 8 Operaciones del 30/10 → 9 Asistente**. Dentro de cada carpeta el orden importa, porque cada request usa lo que guardó uno anterior:

1. **Usuarios.** "Registro (201)" genera un email y un DNI nuevos; los dos 409 que siguen usan esa cuenta, que todavía no verificó el email, y `titular@palco.test`, que ya lo verificó. "Login (200) guarda token" entra con `titular@palco.test` y guarda `token` y el id de la cuenta en `titularId`; los usa "Mi perfil (200)". "Token de servicio (200)" guarda `tokenServicio`, con el que se piden el usuario `titularId` y el rol de una cuenta por su email.
2. **Eventos.** "Listar eventos" guarda `slug` para el detalle. "Login organizador" guarda `tokenOrganizador` para crear el evento y el id de la cuenta en `organizadorId`. "Crear evento" guarda `eventoCreadoId` y `sectorCreadoId`, y "Token de servicio" guarda `tokenServicio`: con ellos se pide el dueño del evento recién creado (tiene que ser `organizadorId`) y se prueba el cupo sobre ese evento, sin tocar los eventos de la demo.
3. **Ventas.** "Login comprador" guarda `token` y "Login organizador en Ventas", `tokenOrganizador`. "Crear evento para comprar (201)" crea el evento de la compra y guarda `eventoId` y `sectorId`; antes de enviarse genera los DNI de los titulares (`dniAna`, `dniLuis` y `dniMarta`). "POST /v1/ventas (201)" guarda `ventaId`; el pago guarda `pagoId` y `entradaId`; después se consultan la entrada y la venta, que deja en `codigoQrLuis` el código de la segunda entrada, y "GET /v1/ventas mis compras (200)" comprueba que esa venta figure en el listado del comprador, con sus entradas y sus códigos, y guarda los ids de sus ventas en `ventasDelTitular`. Después vienen el aislamiento y el control de rol (abajo). Al final se abre otra compra, a nombre de Marta, y se cancela.
4. **Seguridad.** Los login de A y de B guardan `tokenA` y `tokenB`. "SEC GET /v1/ventas de A" toma la compra más nueva de A con entradas (la que pagó la carpeta 3) y guarda `ventaIdA` y `entradaIdA`, que B pide en SEC-W01. SEC-W03a guarda el mensaje del email inexistente para compararlo en SEC-W03b. SEC-W04 va último de la carpeta.
5. **Validación en puerta.** "Login staff" guarda `tokenStaff` y "Login organizador", `tokenOrganizador`. Solo escanea quien trabaja en la puerta del evento, y el de la compra es nuevo: "PUT /v1/validacion/puertas/{eventoId}/{email}" le asigna su puerta a `puerta@palco.test`. Después escanea el `codigoQr` de la entrada `entradaId` que dejó la carpeta 3; el escaneo `VALIDA` guarda `escaneoId`, que el repetido compara con `usoAnterior`. Con un `tokenServicio` nuevo marca usada la segunda entrada (`codigoQrLuis`) con la operación interna de Ventas, y al final el organizador le quita la puerta a `puerta@palco.test`, así que el último escaneo responde 403. Va después de Seguridad porque deja esa entrada `USADA`.
6. **Reventa.** No depende de las anteriores: crea su propio evento ("Crear evento para revender", que guarda `eventoReventaId` y `sectorReventaId` y antes de enviarse genera `dniAna`, `dniLuis` y `dniSofia`), hace su propia compra (`ventaReventaId`, `entradaReventaId` y `entradaRetiroId`), espera la emisión ("GET /v1/ventas/{id} emitida", que se repite hasta 10 veces, como la de la carpeta 3), publica (`publicacionId`), y la compra de `otro.titular@palco.test` guarda `entradaCompradaId` y `ventaCompradaId`. La segunda entrada se publica (`publicacionRetiroId`) y se retira. Los dos login guardan además los ids de las cuentas, `titularId` (el vendedor) y `otroCompradorId` (el comprador), que piden las operaciones internas del final; "GET /v1/ventas/{id} de la compra en la reventa" guarda `pagoReventaId`, para repetir la transferencia con los mismos datos.
7. **Facturación.** Consulta la factura de `ventaId`, la compra de la carpeta 3. **La factura es asincrónica**: Facturación la emite al consumir el tópico de compras confirmadas, después de pedirle el CAE a ARCA (simulado) por SOAP. El primer request espera 1 segundo y, si responde 404, en una corrida se repite solo, hasta 10 veces. Guarda `comprobante` y `cae`, que el segundo compara: una compra tiene una sola factura.
8. **Operaciones del 30/10.** No depende de las anteriores. "Crear evento para editar y cancelar" guarda `eventoPropioId`, `sectorPropioId` y `slugPropio`, y antes de enviarse genera `dniAna` y `dniLuis`; la edición guarda su ETag en `etagPropio`, que después de la compra ya no es el del evento; la compra guarda `ventaPropiaId` y `montoPropio`, y el pago, `pagoPropioId` y su clave de idempotencia, `clavePagoPropio`, con la que se lo busca en Pagos. Espera la emisión antes del resumen y de cancelar. **La anulación es asincrónica**: Ventas anula la compra al consumir `EVENTO_CANCELADO`, así que "GET /v1/ventas/{id} anulada" espera 1 segundo y, si la venta todavía no está `CANCELADA`, en una corrida se repite solo, hasta 10 veces. La subida de la foto guarda `imagenId`, que el request siguiente pide sin token. La recuperación de la contraseña usa su propia cuenta (`emailOlvide`, `dniOlvide`), que queda sin verificar.
9. **Asistente.** No depende de las anteriores. Empieza por el health, que no pide token; "Login comprador" guarda `token` para las preguntas.

**Aislamiento entre usuarios y control de rol.** Son los requests que muestran que cada comprador ve solo lo suyo y que Ventas atiende solo al rol `user`:

| Request | Con qué token | Responde | Por qué |
|---|---|---|---|
| "Login segunda cuenta compradora (200)" | Ninguno | 200 | Guarda `tokenOtroComprador` (`otro.titular@palco.test`) |
| "GET /v1/ventas/{id} de otro comprador (404)" | `tokenOtroComprador` | 404 `no-encontrado` | La venta del titular, pedida por otra cuenta. Ventas responde igual que a una venta que no existe, así no se puede saber si el id existe; el cuerpo trae solo los campos del error |
| "GET /v1/ventas de otro comprador (200, sin ventas del titular)" | `tokenOtroComprador` | 200 | Ninguna de las ventas de `ventasDelTitular` aparece en la lista: de quién son lo dice el token |
| "GET /v1/ventas con token de organizador (403)" | `tokenOrganizador` | 403 `sin-permiso` | Token válido, rol equivocado: es 403, no 401 |
| "GET /v1/ventas/{id} con token de staff (403)" | `tokenStaff` | 403 `sin-permiso` | La venta existe, pero el rol `staff` no la puede consultar |

La carpeta 3 pide de nuevo los tokens de organizador y de staff ("Login organizador en Ventas" y "Login staff en Ventas"), para poder correrse sola.

Cada carpeta empieza pidiendo sus propios tokens, así que también se puede correr una sola.

La compra de la carpeta 3 es de 2 entradas de Platea B del evento que crea la carpeta, al precio del Platea B de la demo (`sec-1-4` de `ev-1`): 2 × 27.500 más el 10 % de cargo de servicio da 60.500.

**Cada compra en un evento nuevo y con DNI nuevos.** Ventas aplica dos reglas entre compras: una cuenta tiene hasta 6 entradas por evento, sumando todas sus compras (409 `tope-por-evento`), y un DNI tiene una sola entrada por evento (409 `dni-con-entrada`). Por eso las carpetas que compran (3, 6 y 8) crean su evento en cada corrida: con un evento fijo, las compras de una corrida se sumarían a las de las anteriores hasta que `titular@palco.test` llegara al tope. Y antes de crearlo generan los DNI de sus titulares a partir de la hora, distintos entre sí. Dentro de una carpeta, cada compra va a nombre de personas que todavía no tienen entrada en ese evento: la compra que se cancela en la carpeta 3 va a nombre de Marta, porque Ana y Luis ya tienen la suya, y la entrada revendida en la carpeta 6 queda a nombre de Sofía. Ningún evento de la demo vende entradas en la corrida.

**Para comprar alcanza con el registro.** Ventas solo le vende a una cuenta con `identidadVerificada` en verdadero, y la cuenta queda así cuando completa el registro, al verificar el email con el código (sección 8). No hay un paso aparte. Una cuenta que se registró y no verificó el email no puede iniciar sesión, así que tampoco comprar.

**Cada venta trae su fecha y sus entradas completas.** La venta suma `creada` (cuándo se abrió, en UTC), y cada entrada dentro de la venta trae `sectorId`, `precioPagado` y `codigoQr` (`null` hasta que se emite), para no tener que pedir cada entrada por separado.

**La emisión es asincrónica.** El pago responde con las entradas en `PENDIENTE_EMISION`. El request "GET /v1/entradas/{id} emitida (200, VALIDA con codigoQr)" espera 1 segundo y pregunta; si la entrada todavía no se emitió, en una corrida se repite solo, hasta 10 veces. Enviado a mano no se repite: hay que volver a enviarlo.

## 6. Variables

Están en la colección → pestaña **Variables**. Solo las tres primeras filas se cambian a mano; el resto lo completa la corrida.

| Variable | Valor | Quién la carga |
|---|---|---|
| `baseUsuarios`, `baseEventos`, `baseApi`, `baseValidacion`, `baseReventas`, `baseFacturas`, `baseAsistente` | `http://localhost:8080/api/usuarios`, `…/api/eventos`, `…/api/v1`, `…/api/v1/validacion`, `…/api/v1/reventas`, `…/api/v1/facturas`, `…/api/v1/asistente` | A mano, si el backend está en otra dirección |
| `secretoServicios` | vacío | A mano, antes de correr: el `PALCO_SECRETO_SERVICIOS` con el que arrancó el servidor (sección 7). En Newman, con `--env-var secretoServicios=...` |
| `codigoVerificacion`, `codigoReinicio` | vacío | A mano, solo para los pasos manuales de verificar el email y de reiniciar la contraseña |
| `token`, `tokenOtroComprador`, `tokenOrganizador`, `tokenStaff`, `tokenServicio` | vacío | Los requests de login y "Token de servicio" |
| `titularId`, `otroCompradorId`, `organizadorId` | vacío | Los ids de las cuentas, para las operaciones entre servicios: `titularId` lo guardan "Login (200)" de la carpeta 1 y "Login comprador" de la 6; `otroCompradorId`, "Login segunda cuenta compradora" de la 6; `organizadorId`, "Login organizador" de la 2 |
| `emailRegistro`, `dniRegistro` | vacío | "Registro (201)", antes de enviarse |
| `slug` | vacío | "Listar eventos" |
| `eventoCreadoId`, `sectorCreadoId` | vacío | "Crear evento, rol organizer (201)" |
| `eventoId`, `sectorId` | vacío | "Crear evento para comprar (201)" (carpeta 3): el evento de la compra y su sector Platea B, a 27.500 |
| `dniAna`, `dniLuis`, `dniMarta`, `dniSofia` | vacío | Los DNI de los titulares. Los genera, antes de enviarse, el request que crea el evento de cada carpeta que compra: los tres primeros en la carpeta 3, Ana, Luis y Sofía en la 6, y Ana y Luis en la 8 |
| `claveVenta`, `clavePago`, `claveVentaCancelada` | vacío | El request que abre la compra o paga, antes de enviarse: un UUID nuevo en cada corrida. El request "repetido" y el de "misma clave con otro pedido" mandan la misma clave |
| `ventaId`, `montoTotal`, `precioUnitario` | vacío | "POST /v1/ventas (201)" |
| `pagoId`, `entradaId` | vacío | "POST /v1/ventas/{id}/pagos (201)" |
| `codigoQr` | vacío | "GET /v1/entradas/{id} emitida" |
| `codigoQrLuis` | vacío | "GET /v1/ventas/{id} (200, EMITIDA)": el código de la segunda entrada, que la carpeta 5 marca usada con `POST /v1/entradas/usos` |
| `ventasDelTitular` | vacío | "GET /v1/ventas mis compras (200)": los ids de las ventas de `titular@palco.test`, en JSON |
| `ventaCanceladaId` | vacío | "POST /v1/ventas para cancelar (201)" |
| `intentosEmision` | `0` | Contador interno de la espera de la emisión |
| `tokenA`, `tokenB` | vacío | Los login de la carpeta 4 |
| `ventaIdA`, `entradaIdA` | vacío | "SEC GET /v1/ventas de A (200)" |
| `emailInexistente`, `detalleInexistente` | vacío | SEC-W03a: el email se genera antes de enviarse; el mensaje sale de la respuesta |
| `tokenNone` | vacío | SEC-W05b, antes de enviarse |
| `emailIntentos`, `codigosPrevios` | vacío | SEC-W04, antes de enviarse: el email propio de la prueba y los códigos de los cinco intentos previos |
| `escaneoId` | vacío | "POST /v1/validacion/escaneos (201, VALIDA)" |
| `eventoReventaId`, `sectorReventaId` | vacío | "Crear evento para revender (201)" (carpeta 6) |
| `claveVentaReventa`, `ventaReventaId`, `entradaReventaId`, `entradaRetiroId` | vacío | La compra y el pago de la carpeta 6 |
| `publicacionId`, `publicacionRetiroId` | vacío | Los dos "POST /v1/reventas (201)" de la carpeta 6 |
| `claveCompraReventa`, `entradaCompradaId`, `ventaCompradaId` | vacío | "POST /v1/reventas/{id}/compra (201)": la clave se genera antes de enviarse; el resto sale de la respuesta |
| `pagoReventaId` | vacío | "GET /v1/ventas/{id} de la compra en la reventa (200)": el pago de esa compra, para repetir su transferencia |
| `comprobante`, `cae` | vacío | "GET /v1/facturas/{ventaId} (200)" |
| `intentosFactura` | `0` | Contador interno de la espera de la factura |
| `tokenOtroOrganizador` | vacío | "Login otro organizador" de la carpeta 8 |
| `eventoPropioId`, `sectorPropioId`, `slugPropio` | vacío | "Crear evento para editar y cancelar (201)" |
| `etagPropio` | vacío | "PUT /eventos/{id} propio (200)": el ETag de la respuesta, para el `If-Match` que responde 412 |
| `ventaPropiaId`, `montoPropio` | vacío | "POST /v1/ventas en el evento propio (201)" |
| `clavePagoPropio`, `pagoPropioId` | vacío | "POST /v1/ventas/{id}/pagos en el evento propio (201)": la clave se genera antes de enviarse y el pago sale de la respuesta |
| `intentosAnulacion` | `0` | Contador interno de la espera de la anulación |
| `imagenId` | vacío | "POST /eventos/imagenes (200)": el final de la `imagenUrl` de la foto subida |
| `emailOlvide`, `dniOlvide` | vacío | "Registro para recuperar la contraseña (201)", antes de enviarse |
| `emailSinCuenta` | vacío | "POST /password/olvide de un email que no existe", antes de enviarse |
| `respuestaOlvide` | vacío | "POST /password/olvide de una cuenta que existe": la respuesta, para compararla con la del email que no existe |

No hay ids fijos: los `V-…`, `T-…`, `P-…`, `R-…` y los de los escaneos salen de las respuestas.

## 7. Correr todo

### Con el Runner de Postman

1. Click derecho en la colección **Palco API** → `Run collection`.
2. Dejá todos los requests tildados y en el orden en que están. No hace falta poner demora ni iteraciones.
3. `Run Palco API`.

Tienen que ejecutarse al menos los 214 requests de la colección (más si la emisión, la factura o la anulación tardan y su request se repite), con todos los tests en verde. Newman cuenta además los cinco intentos de login que SEC-W04 manda antes de enviarse: 219. Los 5 de "Pasos manuales" no se ejecutan. Esa pantalla es la captura para el documento de la entrega.

La corrida se comprobó con Newman, que usa el mismo motor que el Runner.

### Con Newman (línea de comandos)

Newman es el mismo motor del Runner, sin ventana. Se instala una vez, en una carpeta fuera del repo, y hace falta Node.js:

```bash
mkdir palco-newman && cd palco-newman
npm init -y
npm install newman
```

Desde esa carpeta, con la ruta a la colección entre comillas (tiene espacios):

```bash
npx newman run docs/api/palco-api.postman_collection.json   # desde la raíz del repo
```

La colección no trae el secreto entre servicios: la variable `secretoServicios` viene vacía, porque el servidor tampoco tiene un valor por defecto. Hay que pasarle el mismo `PALCO_SECRETO_SERVICIOS` con el que arrancó el servidor: el de `backend/.env` o el de la variable de entorno, si se definió, o el que generó `dev.sh` para ese arranque. En los tres casos `dev.sh` deja el que usa en `backend/servidor/target/secreto-servicios`, así que desde la raíz del repo alcanza con:

```bash
npx newman run docs/api/palco-api.postman_collection.json --env-var "secretoServicios=$(cat backend/servidor/target/secreto-servicios)"
```

En Postman, ese valor se carga en la variable `secretoServicios`. Contra el compose, el secreto se define antes del `up` (`PALCO_SECRETO_SERVICIOS` en el entorno o en un `.env` junto a `docker-compose.yml`): si no, el contenedor genera uno y no lo muestra. Sin el secreto, el token de servicio da 401 y fallan los requests que lo usan.

Para correr una sola carpeta se agrega `--folder` con el nombre exacto:

```bash
npx newman run "<ruta de la colección>" --folder "3 Diseño v1 (Ventas implementado)"
```

Resultado de la última comprobación, la del encabezado, con los mismos parámetros que el job `api` del CI (el secreto del `up` en `--env-var "secretoServicios=$PALCO_SECRETO_SERVICIOS"`):

```
│              iterations │                 1 │                 0 │
│                requests │               219 │                 0 │
│            test-scripts │               214 │                 0 │
│      prerequest-scripts │                20 │                 0 │
│              assertions │               682 │                 0 │
```

En esa corrida Newman contó 219 requests porque suma los cinco intentos de login que SEC-W04 manda antes de enviarse; la colección tiene 214. Ni la emisión, ni la factura ni la anulación tardaron más de un segundo, así que ninguna espera se repitió. Por carpeta: Usuarios 18 requests y 53 tests; Eventos 18 y 49; Ventas 32 y 145; Seguridad 15 y 46; Validación en puerta 27 y 85; Reventa 46 y 127; Facturación 11 y 31; Operaciones del 30/10 42 y 126; Asistente 5 y 20. (El reporte JSON de Newman repite los 5 tests de SEC-W04 en cada uno de sus cinco intentos previos: si se suman sus renglones, Seguridad da 71.) Después, como en el CI, el reenvío y la verificación de "Pasos manuales" con el código del log: 1 request y 1 aserción, y 1 request y 2 aserciones, sin fallas.

En la corrida del 19/11/2026, contra WildFly en `http://localhost:8080`, la colección tenía 166 requests (171 para Newman) y dio 541 aserciones, sin fallas. En la del 16/10/2026 había 69 requests de la colección (74 para Newman) y 252 tests, con 6 fallados en la carpeta 4, por cambios que todavía no estaban desplegados o decididos (sección 13).

Contra un servidor desplegado antes de agregar `otro.titular@palco.test` (comprobado el 14/10/2026) fallan exactamente los tres requests que usan esa cuenta: su login responde 401 y los dos que siguen reciben 401 en lugar de 404 y 200. Son 9 tests fallados y un script cortado, con 201 tests ejecutados. Se arregla redesplegando Usuarios, que corre la migración nueva. Si la emisión tarda más de un segundo, el request que la espera se repite y el total de requests sube; el de tests no cambia.

### En el CI

El job `api` de `.github/workflows/ci.yml` corre la colección con Newman 6.2.3 en cada corrida del CI, con su propio sistema levantado con `docker compose up` (PostgreSQL, no H2), en paralelo con los demás jobs. Newman le pega al backend por `http://localhost:8080`, donde el compose lo publica, que es la dirección que ya tiene la colección. El secreto entre servicios es uno nuevo en cada corrida: se le pasa al compose por `PALCO_SECRETO_SERVICIOS` y a Newman con `--env-var secretoServicios=...`.

Después de la corrida, el job hace los dos primeros "Pasos manuales", el reenvío y la verificación, como se hacen a mano (sección 8), con el log del servidor en `./logs/server.log` (por `PALCO_DIR_LOGS`) y el compose levantado con `PALCO_CORREO_LOG_COMPLETO=true`, para que el renglón del email traiga el código: toma de ahí el email de "Registro (201)" y lo carga en la variable `emailRegistro` de una copia de la colección, envía "MANUAL · Reenviar código (204)" cuando pasaron 30 segundos del registro, lee del log el código nuevo y envía "MANUAL · Verificar email (200)" con `--env-var codigoVerificacion=...`. El email sale del log porque Newman no exporta las variables que cargó la corrida. La tabla final de cada corrida queda en el resumen del job, y la salida de Newman, los reportes JUnit y el log del servidor, en el artifact `reporte-api`.

### Qué deja cada corrida en el servidor

La base es en memoria: todo esto desaparece al reiniciar WildFly.

- Una cuenta sin verificar, `postman-…@palco.test`.
- Tres eventos en el catálogo, de `organizador@palco.test` y con fecha 31/12/2027 para que queden últimos: "Evento de prueba Postman" (carpeta 2), "Evento de prueba Postman para comprar" (carpeta 3) y "Evento de prueba Postman para revender" (carpeta 6). No hay operación para borrar eventos. Ningún evento de la demo vende entradas.
- En el evento de la carpeta 3, una venta `EMITIDA` de `titular@palco.test` con 2 entradas de Platea B, a nombre de Ana Gómez y Luis Pérez con los DNI de la corrida. El pago publica un mensaje `PAGO_CONFIRMADO`, que deja una fila en la auditoría de Notificaciones.
- En el mismo evento, una venta `CANCELADA` a nombre de Marta Díaz, que devuelve su lugar.
- `puerta@palco.test` tiene la puerta del evento de la carpeta 3 mientras corre la carpeta 5, que al final se la quita. Las dos entradas de esa compra quedan `USADA`: la de Ana por el escaneo y la de Luis por la operación interna de Ventas. El registro de esa puerta suma los escaneos de la carpeta 5 que llevan ese evento (tres; el del final, sin la puerta asignada, no deja renglón) y el de `ev-2`, el escaneo `OTRO_EVENTO`.
- La factura de la venta de la carpeta 3, con su número del talonario de ARCA simulado.
- En el evento de la carpeta 6, otra venta `EMITIDA` de `titular@palco.test` con 2 entradas de Platea B: una queda `VENDIDA` en la reventa y la otra `VALIDA`, con su publicación `RETIRADA`. `otro.titular@palco.test` queda con una venta nueva y una entrada `VALIDA` a nombre de Sofía Paz.
- Un evento "Evento de prueba Postman, editado", de `organizador@palco.test`, cancelado: no sale en el catálogo, pero sí en el panel de su dueño y en su detalle. Una venta `CANCELADA` de `titular@palco.test` en ese evento, con 2 entradas `ANULADA` y su pago `REEMBOLSADO`. La cancelación deja en Notificaciones el aviso al comprador (en el log, sin SMTP) y sus filas de auditoría. La factura de esa compra queda emitida: Facturación no escucha las cancelaciones.
- Una foto en la base de Eventos (la cabecera de WEBP que sube la carpeta 8, guardada como `image/webp`), que no queda en ningún evento.
- Una segunda cuenta sin verificar, `olvide-…@palco.test`, sin código para reiniciar la contraseña.
- Dos líneas en el log del asistente, una por cada pregunta con sesión, con la pregunta enmascarada.

Fuera de la carpeta 6, la segunda cuenta compradora solo lee. La carpeta 4 tampoco deja datos: SEC-W02 no crea el evento (responde 403) y los login fallidos usan emails que no existen, salvo SEC-W03b, que falla una vez con `otro.titular@palco.test`. Cuando esté el límite de intentos de login, esos fallidos van a contar en él (sección 13).

### Si algo falla y no es un error de la API

- **Se redesplegó Ventas en medio de la carpeta 3.** La compra abierta pierde su conversación y el pago responde 409 `reserva-vencida`. Volvé a correr la carpeta.
- **Se redesplegó Usuarios.** Cambia la clave con la que se firman los tokens y los guardados responden 401. La corrida completa vuelve a iniciar sesión; enviando a mano, repetí el login.
- **Pasaron más de 5 minutos desde "Token de servicio".** Enviando a mano, los requests de cupo responden 401. Volvé a enviar "Token de servicio".
- **La factura no aparece.** Si después de 10 intentos sigue en 404, mirá el log: Facturación no pudo hablar con ARCA (simulado) o el mensaje está en reintentos.
- **Una compra responde 409 `dni-con-entrada` o `tope-por-evento`.** Pasa al reenviar a mano una compra cuyos titulares ya tienen entrada en ese evento, o cuando las compras reenviadas de `titular@palco.test` en el mismo evento pasan de 6 entradas: el evento y los DNI son los de la corrida. Volvé a enviar el "Crear evento para …" de la carpeta, que crea otro evento y genera otros DNI, o corré la carpeta entera.
- **La venta de la carpeta 8 no queda `CANCELADA`.** Si después de 10 intentos sigue `EMITIDA`, mirá el log: Ventas no consumió `EVENTO_CANCELADO`, o el reembolso falló y el mensaje está en reintentos (la anulación y el reembolso van en la misma transacción).
- **La foto no responde como en el backend local.** Con Supabase configurado (`PALCO_SUPABASE_URL` y `PALCO_SUPABASE_CLAVE`), o con `PALCO_IMAGENES=supabase`, la foto va a Supabase Storage y `imagenUrl` es la dirección pública de allá: fallan el test de la URL relativa y el GET que la pide. Con `PALCO_IMAGENES=ninguno` la subida está deshabilitada y responde 504 `sin-respuesta`. Esos dos requests son para el backend local, que guarda la foto en la base; el del SVG responde 415 en todos los casos.
- **El asistente no responde `MODELO` o no bloquea.** Con el asistente apagado (`palco.asistente.habilitado=false`) toda pregunta responde 200 con origen `FALLBACK` y motivo `apagado`, sin pasar por los guardrails; con una demora del modelo simulado de más de 5 segundos (`palco.asistente.simulado.demora-ms`), la pregunta normal responde `FALLBACK` con motivo `timeout`. Las dos son configuraciones para demostrar el plan de apagado y el timeout; la corrida va sin ellas.

## 8. Pasos manuales: verificar el email y cambiar la contraseña

Al registrarse, el servicio genera un código de 6 dígitos al azar. Sin SMTP configurado (`PALCO_SMTP_HOST`), el email no se manda: queda en el log del servidor. `backend/dev.sh` prende el modo de desarrollo (`PALCO_CORREO_LOG_COMPLETO=true`), que escribe cada email entero en un renglón así:

```
Email para postman-…@palco.test (Tu código de Palco): Hola Ana: tu código para verificar el email es 482913. Vence en 15 minutos.
```

El `docker-compose.yml`, no (desde el 24/11, DEF-36): sin esa variable, como en un despliegue, el renglón solo dice a quién iba, enmascarado (la primera letra y el dominio), y el asunto, porque el cuerpo lleva nombres, titulares y códigos, que no van a un log:

```
Email para p***@palco.test (Tu código de Palco), sin el cuerpo: lo escribe PALCO_CORREO_LOG_COMPLETO=true.
```

Para hacer estos pasos contra el compose y sin SMTP, se lo levanta con `PALCO_CORREO_LOG_COMPLETO=true` en el `.env` de la raíz o en el entorno, antes del `up`, como hacen los jobs del CI que leen el código del log.

Como el código solo sale en el log, estos requests no pueden correr solos en la corrida. En el CI el job `api` corre los dos de la verificación después de la corrida, con el código que lee del log (sección 7, "En el CI"). Están en **1 Usuarios → Pasos manuales (fuera de la corrida)**:

1. Enviá "Registro (201)" (o corré la carpeta 1). El email queda en `emailRegistro`.
2. Buscá el código en el log de WildFly (con el compose, "Email para" en `docker compose logs backend`, o en `./logs/server.log` si se levantó con `PALCO_DIR_LOGS=./logs`).
3. Cargalo en la variable `codigoVerificacion` de la colección y guardá.
4. Enviá "MANUAL · Verificar email (200)". Responde la sesión de la cuenta nueva, con `identidadVerificada` en verdadero: el registro quedó completo y la cuenta ya puede comprar.

"MANUAL · Reenviar código (204)" pide un código nuevo para esa cuenta, que también sale en el log. Hay que esperar 30 segundos desde el registro o desde el reenvío anterior: antes responde 409, que es lo que prueba "Reenviar código antes de los 30 segundos (409)" en la corrida.

Con la cuenta ya verificada se puede cambiar la contraseña con el código bueno, que también sale en el log, en el email "Volvé a poner tu contraseña de Palco":

5. Enviá "MANUAL · Pedir el código para cambiar la contraseña (204)".
6. Buscá el código nuevo en el log y cargalo en la variable `codigoReinicio`.
7. Enviá "MANUAL · Reiniciar la contraseña (204)": la contraseña pasa a ser `Nueva12345`. No inicia la sesión.
8. Enviá "MANUAL · Login con la contraseña nueva (200)".

Verificar una cuenta a mano, o cambiarle la contraseña, no rompe la corrida siguiente: el registro usa un email y un DNI nuevos cada vez.

## 9. Qué cubre la colección

Cada operación de `docs/api/openapi.yaml` tiene al menos un request de éxito y uno de error (de 400 a 429, según la operación), con tests que comprueban el código y que el error viene en Problem Details. Están también las de uso entre servicios, con rol `servicio` (etiquetas `pagos` e `interno`): el cobro, la consulta y el reembolso de Pagos (carpeta 8), las operaciones internas de Ventas que usan Validación (`/api/v1/entradas/usos`, carpeta 5) y Reventa (`/api/v1/operaciones-de-reventa/*`, carpeta 6, con el control de una compra antes de cobrarla, que no cambia nada), el usuario por su id y el rol de una cuenta (carpeta 1) y el dueño de un evento (carpeta 2). Las que cambian un estado se mandan sobre lo que la corrida ya hizo, para no dejar datos sueltos: el cobro y el reembolso se repiten con la misma clave y la misma venta, la entrada que se publica se vuelve a retirar y la transferencia se repite con los datos de la compra en la reventa.

Dos operaciones se prueban de otra manera. `GET /usuarios/jwks` es pública y de solo lectura y no tiene un error propio, así que su caso de error es el método equivocado sobre la misma ruta (`POST /jwks`, 405 `about:blank`). Y el caso que sale bien de la verificación del email y del reinicio de la contraseña necesita el código del log, así que está en "Pasos manuales" (sección 8); sus errores sí están en la corrida. Lo mismo el reenvío del código, que pide esperar 30 segundos desde el registro.

Contadas por método y ruta contra los pedidos de la comprobación del 24/11 (la corrida y los dos pasos manuales que corre el CI, el reenvío y la verificación), 50 de las 52 operaciones de `openapi.yaml` tienen al menos un pedido de éxito (2xx) y uno de error (4xx). Las dos que faltan son las del párrafo anterior: el reinicio de la contraseña, cuyo éxito está en los pasos manuales que el CI no corre, y `GET /usuarios/jwks`, cuyo error es el 405 de `POST /usuarios/jwks`, que no es una operación del contrato. En la corrida sola son 48: el éxito del reenvío y el de la verificación están en los pasos manuales.

Las operaciones de desarrollo de Notificaciones (`/api/notificaciones/pruebas/*` y `auditoria`) no son parte del contrato y no están en la colección. La falla después del cobro y su conciliación se prueban en `scripts/pruebas/escenarios.mjs` (escenario 14), porque necesitan jboss-cli; la subida de la foto a Supabase Storage y el aviso por email al comprador de un evento cancelado, con el sistema configurado para eso (la colección prueba el almacén del backend local, que guarda la foto en la base, y no consulta la auditoría de Notificaciones).

## 10. Cómo crear un request a mano (los 4 tipos)

`New` → `HTTP`, elegí el método y pegá la URL.

| Tipo | Qué cargar |
|---|---|
| **GET** | Solo la URL; los filtros van en `Params` (`categoria`, `q`). |
| **POST** | `Body` → `raw` → `JSON`, con la cabecera `Content-Type: application/json`. Al abrir una compra y al pagar, además `Idempotency-Key` con un UUID. |
| **PUT / PATCH** | Igual que POST. La API tiene dos PUT y ningún PATCH: editar un evento (`PUT /api/eventos/{id}`, carpeta 8), que manda el evento completo, y asignar una cuenta a la puerta de un evento (`PUT /api/v1/validacion/puertas/{eventoId}/{email}`, sin cuerpo, carpeta 5). |
| **DELETE** | Solo la URL; responde 204 sin cuerpo. |

Autenticación: cabecera `Authorization` con el valor `Bearer {{token}}` (o pestaña `Authorization` → `Bearer Token` → `{{token}}`).

## 11. Cómo están escritos los tests (pestaña Scripts → Post-response)

Cada request comprueba el código, las cabeceras que importan y los campos del contrato:

```js
pm.test('responde 201', () => pm.response.to.have.status(201));

const v = pm.response.json();
pm.test('Location apunta a la venta', () => {
  const location = pm.response.headers.get('Location');
  pm.expect(location.endsWith('/api/v1/ventas/' + v.id)).to.be.true;
});
pm.test('está EN_CHECKOUT', () => pm.expect(v.estado).to.eql('EN_CHECKOUT'));

// El id sale de la respuesta y queda para los requests que siguen.
pm.collectionVariables.set('ventaId', v.id);
```

Los errores se comprueban con el código, el tipo de contenido `application/problem+json` y el campo `type`, que es lo que mira un cliente para decidir qué mostrar:

```js
pm.test('responde 409', () => pm.response.to.have.status(409));
pm.test('el error viene como application/problem+json', () =>
  pm.expect(pm.response.headers.get('Content-Type')).to.include('application/problem+json'));
pm.test('type es /problemas/venta-ya-paga', () =>
  pm.expect(pm.response.json().type).to.eql('/problemas/venta-ya-paga'));
```

La clave de idempotencia se genera antes de enviar (pestaña Scripts → Pre-request):

```js
pm.collectionVariables.set('claveVenta', pm.variables.replaceIn('{{$guid}}'));
```

Los tests no se aflojan para que pasen. Si el backend responde distinto del contrato (`docs/api/openapi.yaml`), el test queda en rojo y se avisa.

## 12. Regenerar la colección

El JSON de la colección sale de `scripts/postman/generar.js`: los requests, los tests y las variables se cambian ahí, y después se regenera desde la raíz del repo (hace falta Node, sin paquetes de npm):

```bash
node scripts/postman/generar.js
```

Escribe `docs/api/palco-api.postman_collection.json` y dice cuántos requests tiene en total (hoy 219: los 214 de la corrida y los 5 de "Pasos manuales"). Con una ruta como argumento la escribe en otro lado, por ejemplo para compararla con la del repo. Los tokens quedan vacíos, porque los guardan los requests de login.

Si se cambia algo desde la ventana de Postman, hay que pasarlo al generador: la próxima regeneración pisa el archivo.

## 13. Seguridad (carpeta 4)

Comprueba desde afuera los criterios de la matriz de seguridad de la clase 11 (`actividad_integradora_clase11/`). Cada request lleva en el nombre el id del criterio. Usa dos cuentas compradoras verificadas de las migraciones de Usuarios: **A** es `titular@palco.test` y **B**, `otro.titular@palco.test`. No registra cuentas ni crea compras: toma una compra emitida de A, como la que deja la carpeta 3. Para correrla sola (`--folder "4 Seguridad (clase 11)"`), A tiene que tener al menos una compra con entradas.

| Request | Cuenta | Qué prueba | Estado al 16/10/2026 |
|---|---|---|---|
| Login A y login B (200) | A, B | Guardan `tokenA` y `tokenB` | Pasa |
| "SEC GET /v1/ventas de A (200)" | A | Toma una compra de A con entradas y guarda sus ids | Pasa |
| SEC-W01 y SEC-W01b | B | La entrada y la venta de A, pedidas por B: 404 `no-encontrado`, igual que si no existieran, y sin datos de la entrada en el cuerpo | Pasa |
| SEC-W02 | B | Rol `user` en una operación de `organizer` (crear evento): 403 `sin-permiso` | Pasa |
| SEC-W02b | Ninguna | Sin token: 401 `no-autenticado` con `WWW-Authenticate: Bearer` | Pasa |
| SEC-W03a y SEC-W03b | B | Login con un email que no existe y con la clave mal: el mismo 401 con el mismo mensaje, así no se puede saber qué cuentas hay. El email inexistente es nuevo en cada corrida | Pasa |
| SEC-W05a y SEC-W05b | A | Token con la firma alterada y token `alg=none` (sin firma, que dice ser de organizer): 401 | Pasa: 401 en `application/problem+json` (`/problemas/token-invalido`), con `WWW-Authenticate` y CORS |
| SEC-W05c | A | La sesión dura 8 horas | Pasa. El criterio de la matriz pide una vigencia corta; el grupo decidió el 16/10 mantener 8 horas sin refresh token y justificarlo, y la prueba comprueba esa vigencia |
| SEC-W06 | A | `POST /v1/ventas` sin `Idempotency-Key`: 400 `datos-invalidos`, sin nombres de clases, trazas ni SQL en el cuerpo | Pasa |
| SEC-D02 | A | `GET /yo` no devuelve la contraseña ni el hash | Pasa |
| SEC-W04 | Email propio | Cinco login fallidos seguidos y el sexto: 429 `demasiados-intentos` con `Retry-After` | Pasa: los cinco primeros responden 401 y el sexto 429 con `Retry-After` |

**SEC-W04 no toca las cuentas de prueba.** Antes de enviarse genera un email nuevo, `intentos-…@palco.test`, y manda con él los cinco intentos fallidos, uno después del otro; el request es el sexto. Así el límite no bloquea las cuentas que usan los demás requests, y una corrida no arrastra los intentos de la anterior. Va último en la colección.

Corrida del 16/10 con el servidor armado desde cero con esos cambios y la decisión de mantener la sesión en 8 horas: 74 requests, 252 aserciones, 0 fallas. Newman se corre con `--env-var secretoServicios=<el secreto del servidor>` (sección 7).

## 14. Operaciones del 30/10 (carpeta 8)

Prueba por la API, contra el servidor, las operaciones que se sumaron para el 30/10. Trabaja solo sobre lo que crea: un evento de `organizador@palco.test` y una cuenta nueva. Nunca cancela los eventos de la demo (`ev-1` a `ev-6`). Para correrla sola: `--folder "8 Operaciones del 30/10 (implementado)"`.

| Operación | Qué prueba | Estado en la última comprobación |
|---|---|---|
| Editar un evento (`PUT /api/eventos/{id}`) | El propio: 200, con el mismo id y slug, los datos nuevos y el sector con su id, otro nombre y más cupo. El de otro organizador: 404 `no-encontrado`, y al final el detalle muestra que esa edición no quedó. Con rol `user`: 403 `sin-permiso` | Pasa |
| Ventas por día y liquidación (`GET /api/v1/resumenes?eventoId=`) | Con la compra emitida: 1 venta y 2 entradas en un solo día, 22.000 cobrados, 2.000 de cargo de Palco y 20.000 para el organizador. El de otro organizador: 404. Con rol `user`: 403. Después de cancelar: sin días y todo en cero | Pasa |
| Cancelar un evento (`POST /api/eventos/{id}/cancelacion`) | El de otro organizador: 404. Con rol `user`: 403. El propio: 200, cancelado y con el motivo. Otra vez: 409 `evento-cancelado`, así nadie reembolsa dos veces | Pasa |
| Anulación y reembolso | La venta queda `CANCELADA`, con sus 2 entradas `ANULADA` (asincrónico: se espera como la emisión), y su pago, consultado en Pagos con rol `servicio`, `REEMBOLSADO` por el total cobrado. El detalle del evento sigue respondiendo, cancelado y con la edición del dueño | Pasa |
| Comprar en el evento cancelado (`POST /api/v1/ventas`) | 409 `evento-cancelado`, sin reservar cupo (ver abajo) | Pasa |
| Subir la foto (`POST /api/eventos/imagenes`) y pedirla (`GET /api/eventos/imagenes/{id}`) | Sin `PALCO_IMAGENES` ni Supabase, Eventos la guarda en su base: 200 con solo `imagenUrl`, relativa (`/api/eventos/imagenes/<uuid>.webp`, con la extensión del `Content-Type`). Esa URL responde sin token: 200, `image/webp`, lo mismo que se subió y `Cache-Control: public, max-age=31536000, immutable`. Un texto declarado como PNG: 400 `datos-invalidos` con `errores.imagen`. Un SVG: 415 | Pasa |
| Olvidé mi contraseña (`POST /api/usuarios/password/olvide` y `/reinicio`) | 204 sin cuerpo para una cuenta que existe y para un email que no: la misma respuesta. El reinicio con un código incorrecto: 400 con `errores.codigo` "El código no es correcto.", y el login con la contraseña de siempre muestra que no cambió | Pasa |

**Lo ajeno responde 404, no 403.** Para otro organizador, el evento de `organizador@palco.test` no existe: editarlo, cancelarlo o pedir su resumen responde 404 `no-encontrado`, igual que un id que no existe, así no se puede saber qué eventos hay. Es el criterio de SEC-W01 con las compras, y el que documenta `openapi.yaml` para la edición y la cancelación (el resumen todavía no está en ese archivo). El 403 `sin-permiso` es del rol equivocado: los mismos pedidos con el token de un comprador.

**La compra en un evento cancelado.** Es una compra de `titular@palco.test` a nombre de Ana, en el evento donde la cuenta tenía la compra que se anuló, con una entrada de Ana. Llega hasta Eventos porque una compra anulada no cuenta para el tope de 6 por evento, ni sus entradas `ANULADA` para la regla de un DNI por evento. Eventos rechaza la reserva con 409 `evento-cancelado`, y Ventas (`ClienteDeEventos`) le pasa ese mismo tipo al comprador, como pasa `evento-finalizado`: el `detail` dice que el evento se canceló y que no se venden más entradas.

**La foto se sube como texto.** El cuerpo raw de Postman es texto y no puede llevar los bytes de una imagen, así que la carpeta sube la cabecera de un WEBP (`RIFF`, cuatro caracteres de tamaño y `WEBP`), que es toda ASCII, con el tipo `image/webp`. Eventos saca la extensión del `Content-Type` y mira la firma de los primeros bytes, no que la imagen se pueda dibujar: por eso un texto cualquiera declarado como `image/png` responde 400.

**La edición con `If-Match`.** "PUT /eventos/{id} propio (200)" guarda el `ETag` de su respuesta en `etagPropio`. Después la compra reserva 2 lugares y cambia `vendidas`, que entra en el ETag, así que el evento ya no es el que leyó el organizador: "PUT /eventos/{id} con el ETag de antes de la compra (412)" manda ese valor en `If-Match` y responde 412 `evento-modificado` sin guardar nada. El detalle del final de la carpeta muestra que la descripción sigue siendo la de la edición.

**Pagos entre servicios.** Con el pago ya `REEMBOLSADO` por la cancelación, la carpeta prueba lo que Pagos les ofrece a Ventas y a Reventa sin cobrar ni devolver nada nuevo: la búsqueda por clave de idempotencia (la que Ventas le manda a Pagos es el id de la venta, dos puntos y la `Idempotency-Key` del comprador, que la carpeta guarda en `clavePagoPropio`), el cobro repetido con esa clave y el mismo pedido, que devuelve el mismo pago, y el reembolso repetido por el pago entero, que devuelve el pago como quedó. Con otro monto, los dos responden 409.

**La recuperación de la contraseña usa una cuenta sin verificar.** La carpeta no puede verificar su cuenta (el código sale en el log) y una cuenta sin verificar no recibe código para reiniciar, así que en el servidor la cuenta que existe y el email que no existe hacen el mismo camino. Lo que se comprueba es lo que ve quien llama: la misma respuesta en los dos casos, y en el reinicio el mismo error que un código equivocado.
