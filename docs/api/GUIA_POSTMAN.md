# Guía Postman — API de Palco

La colección `docs/api/palco-api.postman_collection.json` se corre entera, en orden y sin intervención, contra el backend local. Se puede repetir las veces que haga falta sin reiniciar el servidor.

La colección no se edita a mano: la genera `scripts/postman/generar.js` (sección 12).

Última comprobación: 16/10/2026, con Newman 6.2.3 contra WildFly en `http://localhost:8080`: 69 requests de la corrida y 252 tests ejecutados, con 6 tests fallados, todos en la carpeta 4 (Seguridad) y por cambios que todavía no están desplegados o no están decididos (sección 13). Las carpetas 1 a 3 dieron 54 requests y 206 tests, 0 fallados. La comprobación anterior, del 14/10/2026, está en la sección 7.

## 1. Qué hace falta

1. Postman (postman.com/downloads). Se puede usar sin cuenta ("Skip and go to the app").
2. El backend levantado: `cd backend && ./dev.sh`. Para comprobarlo, abrí en el navegador `http://localhost:8080/api/eventos` (el catálogo) y `http://localhost:8080/api/v1/health` (Ventas).

## 2. Importar

`Import` → arrastrá `docs/api/palco-api.postman_collection.json`. No hace falta un environment: todo lo que cambia está en las variables de la colección.

## 3. Qué trae

| Carpeta | Requests de la corrida | Qué prueba |
|---|---|---|
| **1 Usuarios (implementado)** | 10 | Registro, registro inválido, login (comprador y staff), login incorrecto, login sin verificar el email, perfil con y sin token, token de servicio con secreto incorrecto, JWKS |
| **2 Eventos (implementado)** | 13 | Listar, filtrar, detalle, 404, crear evento sin token, con rol organizer e inválido, y cambio de cupo con rol servicio (reservar, pasarse del cupo, liberar) |
| **3 Diseño v1 (Ventas implementado)** | 31 | La compra contra `/api/v1`: abrir la compra con reserva de cupo, idempotencia (también la misma clave con otro pedido, 409), pago rechazado, pago aprobado, venta y entrada, emisión asincrónica de la entrada, listado de las compras propias (con y sin token), aislamiento entre usuarios, control de rol y cancelación |
| **4 Seguridad (clase 11)** | 15 | Los criterios de la matriz de seguridad de la clase 11 que se prueban por HTTP, cada uno con su id (`SEC-…`): recurso ajeno, rol equivocado, sin token, mensajes de login genéricos, token alterado o sin firma, vigencia del token, errores sin información interna, perfil sin contraseña y límite de intentos de login. Ver la sección 13 |

Además hay dos subcarpetas que **quedan fuera de la corrida**:

- **1 Usuarios → Pasos manuales** (2 requests): verificar el email y reenviar el código. Necesitan un dato que solo sale en el log del servidor. Ver la sección 8.
- **3 Diseño v1 → Pendientes: reventa y validación** (4 requests): reventa y validación en puerta. Están diseñadas, pendientes: esas rutas todavía no existen en el backend y hoy responden 404. Ver la sección 9.

Cómo quedan afuera: el último request automático de la carpeta 1 ("JWKS claves públicas (200)") le dice a la corrida que siga en la carpeta 2, y el último de la carpeta 3 ("POST /v1/ventas/{id}/pagos de una venta cancelada (409)") la hace seguir en la carpeta 4. Está en la pestaña Scripts de esos dos requests. La carpeta 4 es la última: la corrida termina con ella. Si en el Runner destildás alguno de los dos, la corrida entra en la subcarpeta que le sigue.

Los nombres de los requests dicen el resultado esperado: el código entre paréntesis es el que tiene que responder el servidor.

## 4. Cuentas de prueba

Las crea el backend al arrancar (migraciones `V2__usuarios_de_prueba.sql` y `V3__segundo_comprador_de_prueba.sql` de Usuarios). La contraseña de todas es `palco1234`.

| Rol | Cuenta | Para qué se usa | Variable donde queda el token |
|---|---|---|---|
| `user` (comprador) | `titular@palco.test` | Perfil y todas las compras; en Seguridad, la cuenta A | `token`; en Seguridad, `tokenA` |
| `user` (comprador) | `otro.titular@palco.test` | Aislamiento: no ve las compras de `titular@palco.test`; en Seguridad, la cuenta B | `tokenOtroComprador`; en Seguridad, `tokenB` |
| `organizer` | `organizador@palco.test` | Crear eventos; en Ventas, el 403 por rol | `tokenOrganizador` |
| `staff` | `puerta@palco.test` | Validación en puerta (pendiente); en Ventas, el 403 por rol | `tokenStaff` |
| `servicio` | No es una cuenta: `POST /api/usuarios/servicios/token` con `{"secreto":"{{secretoServicios}}"}` (por defecto `palco-desarrollo`) | Cambiar el cupo de un sector | `tokenServicio` (dura 5 minutos) |

Cada request manda el token de su rol en la cabecera `Authorization: Bearer {{...}}`. No hay que copiar tokens a mano: los guardan los requests de login.

## 5. Orden

La colección va de arriba hacia abajo: **1 Usuarios → 2 Eventos → 3 Diseño v1 → 4 Seguridad**. Dentro de cada carpeta el orden importa, porque cada request usa lo que guardó uno anterior:

1. **Usuarios.** "Registro (201)" genera un email y un DNI nuevos. "Login (200) guarda token" entra con `titular@palco.test` y guarda `token`; lo usa "Mi perfil (200)".
2. **Eventos.** "Listar eventos" guarda `slug` para el detalle. "Login organizador" guarda `tokenOrganizador` para crear el evento. "Crear evento" guarda `eventoCreadoId` y `sectorCreadoId`, y "Token de servicio" guarda `tokenServicio`: con los tres se prueba el cupo sobre el evento recién creado, sin tocar los eventos de la demo.
3. **Ventas.** "Login comprador" guarda `token`. "POST /v1/ventas (201)" guarda `ventaId`; el pago guarda `pagoId` y `entradaId`; después se consultan la entrada y la venta, y "GET /v1/ventas mis compras (200)" comprueba que esa venta figure en el listado del comprador, con sus entradas y sus códigos, y guarda los ids de sus ventas en `ventasDelTitular`. Después vienen el aislamiento y el control de rol (abajo). Al final se abre otra compra y se cancela.
4. **Seguridad.** Los login de A y de B guardan `tokenA` y `tokenB`. "SEC GET /v1/ventas de A" toma la compra más nueva de A con entradas (la que pagó la carpeta 3) y guarda `ventaIdA` y `entradaIdA`, que B pide en SEC-W01. SEC-W03a guarda el mensaje del email inexistente para compararlo en SEC-W03b. SEC-W04 va último.

**Aislamiento entre usuarios y control de rol.** Son los requests que muestran que cada comprador ve solo lo suyo y que Ventas atiende solo al rol `user`:

| Request | Con qué token | Responde | Por qué |
|---|---|---|---|
| "Login segunda cuenta compradora (200)" | — | 200 | Guarda `tokenOtroComprador` (`otro.titular@palco.test`) |
| "GET /v1/ventas/{id} de otro comprador (404)" | `tokenOtroComprador` | 404 `no-encontrado` | La venta del titular, pedida por otra cuenta. Ventas responde igual que a una venta que no existe, así no se puede saber si el id existe; el cuerpo trae solo los campos del error |
| "GET /v1/ventas de otro comprador (200, sin ventas del titular)" | `tokenOtroComprador` | 200 | Ninguna de las ventas de `ventasDelTitular` aparece en la lista: de quién son lo dice el token |
| "GET /v1/ventas con token de organizador (403)" | `tokenOrganizador` | 403 `sin-permiso` | Token válido, rol equivocado: es 403, no 401 |
| "GET /v1/ventas/{id} con token de staff (403)" | `tokenStaff` | 403 `sin-permiso` | La venta existe, pero el rol `staff` no la puede consultar |

La carpeta 3 pide de nuevo los tokens de organizador y de staff ("Login organizador en Ventas" y "Login staff en Ventas"), para poder correrse sola.

Cada carpeta empieza pidiendo sus propios tokens, así que también se puede correr una sola.

La compra de la carpeta 3 es de 2 entradas de Platea B (`sec-1-4`) del evento `ev-1`: 2 × 27.500 más el 10 % de cargo de servicio da 60.500.

**Para comprar alcanza con el registro.** Ventas solo le vende a una cuenta con `identidadVerificada` en verdadero, y la cuenta queda así cuando completa el registro, al verificar el email con el código (sección 8). No hay un paso aparte. Una cuenta que se registró y no verificó el email no puede iniciar sesión, así que tampoco comprar.

**Cada venta trae su fecha y sus entradas completas.** La venta suma `creada` (cuándo se abrió, en UTC), y cada entrada dentro de la venta trae `sectorId`, `precioPagado` y `codigoQr` (`null` hasta que se emite), para no tener que pedir cada entrada por separado.

**La emisión es asincrónica.** El pago responde con las entradas en `PENDIENTE_EMISION`. El request "GET /v1/entradas/{id} emitida (200, VALIDA con codigoQr)" espera 1 segundo y pregunta; si la entrada todavía no se emitió, en una corrida se repite solo, hasta 10 veces. Enviado a mano no se repite: hay que volver a enviarlo.

## 6. Variables

Están en la colección → pestaña **Variables**. Solo las tres primeras filas se cambian a mano; el resto lo completa la corrida.

| Variable | Valor | Quién la carga |
|---|---|---|
| `baseUsuarios`, `baseEventos`, `baseApi` | `http://localhost:8080/api/usuarios`, `…/api/eventos`, `…/api/v1` | A mano, si el backend está en otra dirección |
| `eventoId`, `sectorId` | `ev-1`, `sec-1-4` | A mano. Son el evento y el sector que se compran. Si los cambiás, el test del total 60.500 deja de valer |
| `codigoVerificacion` | vacío | A mano, solo para el paso manual de verificar el email |
| `token`, `tokenOtroComprador`, `tokenOrganizador`, `tokenStaff`, `tokenServicio` | vacío | Los requests de login y "Token de servicio" |
| `emailRegistro`, `dniRegistro` | vacío | "Registro (201)", antes de enviarse |
| `slug` | vacío | "Listar eventos" |
| `eventoCreadoId`, `sectorCreadoId` | vacío | "Crear evento, rol organizer (201)" |
| `claveVenta`, `clavePago`, `claveVentaCancelada` | vacío | El request que abre la compra o paga, antes de enviarse: un UUID nuevo en cada corrida. El request "repetido" y el de "misma clave con otro pedido" mandan la misma clave |
| `ventaId`, `montoTotal`, `precioUnitario` | vacío | "POST /v1/ventas (201)" |
| `pagoId`, `entradaId` | vacío | "POST /v1/ventas/{id}/pagos (201)" |
| `codigoQr` | vacío | "GET /v1/entradas/{id} emitida" |
| `ventasDelTitular` | vacío | "GET /v1/ventas mis compras (200)": los ids de las ventas de `titular@palco.test`, en JSON |
| `ventaCanceladaId` | vacío | "POST /v1/ventas para cancelar (201)" |
| `intentosEmision` | `0` | Contador interno de la espera de la emisión |
| `publicacionId` | vacío | Pendiente: la va a cargar el request de reventa |
| `tokenA`, `tokenB` | vacío | Los login de la carpeta 4 |
| `ventaIdA`, `entradaIdA` | vacío | "SEC GET /v1/ventas de A (200)" |
| `emailInexistente`, `detalleInexistente` | vacío | SEC-W03a: el email se genera antes de enviarse; el mensaje sale de la respuesta |
| `tokenNone` | vacío | SEC-W05b, antes de enviarse |
| `emailIntentos`, `codigosPrevios` | vacío | SEC-W04, antes de enviarse: el email propio de la prueba y los códigos de los cinco intentos previos |

No hay ids fijos: los `V-…`, `T-…` y `P-…` salen de las respuestas.

## 7. Correr todo

### Con el Runner de Postman

1. Click derecho en la colección **Palco API** → `Run collection`.
2. Dejá todos los requests tildados y en el orden en que están. No hace falta poner demora ni iteraciones.
3. `Run Palco API`.

Tienen que ejecutarse 74 requests. Las carpetas 1 a 4, con todos sus tests en verde salvo SEC-W05c (sección 13), que queda en rojo a propósito hasta que el grupo decida la vigencia del token. Los 6 de las subcarpetas "Pasos manuales" y "Pendientes" no se ejecutan. Esa pantalla es la captura para el documento de la entrega.

La corrida se comprobó con Newman, que usa el mismo motor que el Runner. Falta repetirla una vez en la ventana de Postman, con el servidor redesplegado (para que exista `otro.titular@palco.test`), para sacar la captura.

### Con Newman (línea de comandos)

Newman es el mismo motor del Runner, sin ventana. Se instala una vez, en una carpeta fuera del repo, y hace falta Node.js:

```bash
mkdir palco-newman && cd palco-newman
npm init -y
npm install newman
```

Desde esa carpeta, con la ruta a la colección entre comillas (tiene espacios):

```bash
npx newman run "C:\Users\santi\OneDrive\Documentos\Facultad\Desarrollo de Aplicaciones II\TP Integrador\docs\api\palco-api.postman_collection.json"
```

Si el servidor arrancó con un `PALCO_SECRETO_SERVICIOS` propio (en `backend/.env`), hay que pasarle el mismo valor a la colección: `--env-var secretoServicios=<el valor>` en Newman, o cambiar la variable `secretoServicios` en Postman. Sin eso, el token de servicio da 401 y fallan los requests que lo usan.

Para correr una sola carpeta se agrega `--folder` con el nombre exacto:

```bash
npx newman run "<ruta de la colección>" --folder "3 Diseño v1 (Ventas implementado)"
```

Resultado de la corrida completa del 16/10/2026 (los 6 fallados son los de la sección 13):

```
│              iterations │                 1 │                0 │
│                requests │                74 │                0 │
│            test-scripts │                69 │                0 │
│      prerequest-scripts │                 8 │                0 │
│              assertions │               252 │                6 │
```

Newman cuenta 74 requests porque suma los cinco intentos de login que SEC-W04 manda antes de enviarse; los de la colección son 69. Por carpeta: Usuarios 10 requests y 29 tests; Eventos 13 y 34; Ventas 31 y 143; Seguridad 15 y 46.

Contra un servidor desplegado antes de agregar `otro.titular@palco.test` (comprobado el 14/10/2026) fallan exactamente los tres requests que usan esa cuenta: su login responde 401 y los dos que siguen reciben 401 en lugar de 404 y 200. Son 9 tests fallados y un script cortado, con 201 tests ejecutados. Se arregla redesplegando Usuarios, que corre la migración nueva. Si la emisión tarda más de un segundo, el request que la espera se repite y el total de requests sube; el de tests no cambia.

### Qué deja cada corrida en el servidor

La base es en memoria: todo esto desaparece al reiniciar WildFly.

- Una cuenta sin verificar, `postman-…@palco.test`.
- Un evento "Evento de prueba Postman" en el catálogo, con fecha 31/12/2027 para que quede último. No hay operación para borrar eventos.
- Una venta `EMITIDA` de `titular@palco.test` con 2 entradas de Platea B: el sector `sec-1-4` queda con 2 lugares vendidos más. El pago publica un mensaje `PAGO_CONFIRMADO`, que deja una fila en la auditoría de Notificaciones.
- Una venta `CANCELADA`, que devuelve su lugar.

La segunda cuenta compradora solo lee: no deja nada. La carpeta 4 tampoco deja datos: SEC-W02 no crea el evento (responde 403) y los login fallidos usan emails que no existen, salvo SEC-W03b, que falla una vez con `otro.titular@palco.test`. Cuando esté el límite de intentos de login, esos fallidos van a contar en él (sección 13).

### Si algo falla y no es un error de la API

- **Se redesplegó Ventas en medio de la carpeta 3.** La compra abierta pierde su conversación y el pago responde 409 `reserva-vencida`. Volvé a correr la carpeta.
- **Se redesplegó Usuarios.** Cambia la clave con la que se firman los tokens y los guardados responden 401. La corrida completa vuelve a iniciar sesión; enviando a mano, repetí el login.
- **Pasaron más de 5 minutos desde "Token de servicio".** Enviando a mano, los requests de cupo responden 401. Volvé a enviar "Token de servicio".
- **Se acabó el cupo de `sec-1-4`.** Cada corrida vende 2 lugares; cuando no quedan, la compra responde 409 `cupo-agotado`. Se arregla reiniciando el servidor.

## 8. Pasos manuales: verificar el email

Al registrarse, el servicio genera un código de 6 dígitos al azar. El envío por email todavía no está: el código solo sale en el log del servidor, en una línea así:

```
Código de verificación para postman-…@palco.test: 482913
```

Por eso estos dos requests no pueden correr solos. Están en **1 Usuarios → Pasos manuales (fuera de la corrida)**:

1. Enviá "Registro (201)" (o corré la carpeta 1). El email queda en `emailRegistro`.
2. Buscá el código en el log de WildFly.
3. Cargalo en la variable `codigoVerificacion` de la colección y guardá.
4. Enviá "MANUAL · Verificar email (200)". Responde la sesión de la cuenta nueva, con `identidadVerificada` en verdadero: el registro quedó completo y la cuenta ya puede comprar.

"MANUAL · Reenviar código (204)" pide un código nuevo para esa cuenta, que también sale en el log. Hay que esperar 30 segundos desde el registro o desde el reenvío anterior: antes responde 409.

Verificar una cuenta a mano no rompe la corrida siguiente: el registro usa un email y un DNI nuevos cada vez.

## 9. Pendientes: reventa y validación en puerta

Están en **3 Diseño v1 → Pendientes: reventa y validación (fuera de la corrida)**, con el nombre empezado en "PENDIENTE":

| Request | Estado | Cuándo |
|---|---|---|
| `POST /v1/reventas` (201), sobre el tope (422) y `DELETE /v1/reventas/{id}` (204) | Diseñado, pendiente | Ola 3 |
| `POST /v1/validacion/escaneos` (200) | Diseñado, pendiente | Ola 2 |

Hoy los cuatro responden 404, porque esas rutas no existen (comprobado el 13/10/2026). Ya están preparados para cuando existan: usan `entradaId`, `codigoQr` y `publicacionId` en lugar de ids fijos, y el escaneo usa `tokenStaff`. Cuando se implementen hay que completar sus tests con los campos del contrato real y cambiar el salto del último request automático de la carpeta 3, que hoy los saltea.

Tampoco están en la colección, aunque el backend ya los tiene, el cobro interno de Pagos (`/api/v1/pagos`) ni la auditoría de Notificaciones (`/api/notificaciones/auditoria`): son para llamadas entre servicios, con rol `servicio`.

## 10. Cómo crear un request a mano (los 4 tipos)

`New` → `HTTP`, elegí el método y pegá la URL.

| Tipo | Qué cargar |
|---|---|
| **GET** | Solo la URL; los filtros van en `Params` (`categoria`, `q`). |
| **POST** | `Body` → `raw` → `JSON`, con la cabecera `Content-Type: application/json`. Al abrir una compra y al pagar, además `Idempotency-Key` con un UUID. |
| **PUT / PATCH** | Igual que POST. La API de hoy no tiene ninguno. |
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

Los tests no se aflojan para que pasen. Si el backend responde distinto del contrato (`docs/planes/00-contratos-compartidos.md`, sección 6), el test queda en rojo y se avisa.

## 12. Regenerar la colección

El JSON de la colección sale de `scripts/postman/generar.js`: los requests, los tests y las variables se cambian ahí, y después se regenera desde la raíz del repo (hace falta Node, sin paquetes de npm):

```bash
node scripts/postman/generar.js
```

Escribe `docs/api/palco-api.postman_collection.json` y dice cuántos requests tiene en total (hoy 75: los 69 de la corrida y los 6 de "Pasos manuales" y "Pendientes"). Con una ruta como argumento la escribe en otro lado, por ejemplo para compararla con la del repo. Los tokens quedan vacíos, porque los guardan los requests de login.

Si se cambia algo desde la ventana de Postman, hay que pasarlo al generador: la próxima regeneración pisa el archivo.

## 13. Seguridad (carpeta 4)

Comprueba desde afuera los criterios de la matriz de seguridad de la clase 11 (`actividad_integradora_clase11/`). Cada request lleva en el nombre el id del criterio. Usa dos cuentas compradoras verificadas de las migraciones de Usuarios: **A** es `titular@palco.test` y **B**, `otro.titular@palco.test`. No registra cuentas ni crea compras: toma una compra emitida de A, como la que deja la carpeta 3. Para correrla sola (`--folder "4 Seguridad (clase 11)"`), A tiene que tener al menos una compra con entradas.

| Request | Cuenta | Qué prueba | Estado al 16/10/2026 |
|---|---|---|---|
| Login A y login B (200) | A, B | Guardan `tokenA` y `tokenB` | Pasa |
| "SEC GET /v1/ventas de A (200)" | A | Toma una compra de A con entradas y guarda sus ids | Pasa |
| SEC-W01 y SEC-W01b | B | La entrada y la venta de A, pedidas por B: 404 `no-encontrado`, igual que si no existieran, y sin datos de la entrada en el cuerpo | Pasa |
| SEC-W02 | B | Rol `user` en una operación de `organizer` (crear evento): 403 `sin-permiso` | Pasa |
| SEC-W02b | — | Sin token: 401 `no-autenticado` con `WWW-Authenticate: Bearer` | Pasa |
| SEC-W03a y SEC-W03b | B | Login con un email que no existe y con la clave mal: el mismo 401 con el mismo mensaje, así no se puede saber qué cuentas hay. El email inexistente es nuevo en cada corrida | Pasa |
| SEC-W05a y SEC-W05b | A | Token con la firma alterada y token `alg=none` (sin firma, que dice ser de organizer): 401 | Pasa: 401 en `application/problem+json` (`/problemas/token-invalido`), con `WWW-Authenticate` y CORS |
| SEC-W05c | A | El token dura 15 minutos o menos | **Falla**: dura 8 horas. Está a decidir por el equipo si se baja la vigencia o se justifica la de hoy; si se justifica, este test se ajusta a esa decisión |
| SEC-W06 | A | `POST /v1/ventas` sin `Idempotency-Key`: 400 `datos-invalidos`, sin nombres de clases, trazas ni SQL en el cuerpo | Pasa |
| SEC-D02 | A | `GET /yo` no devuelve la contraseña ni el hash | Pasa |
| SEC-W04 | Email propio | Cinco login fallidos seguidos y el sexto: 429 `demasiados-intentos` con `Retry-After` | Pasa: los cinco primeros responden 401 y el sexto 429 con `Retry-After` |

**SEC-W04 no toca las cuentas de prueba.** Antes de enviarse genera un email nuevo, `intentos-…@palco.test`, y manda con él los cinco intentos fallidos, uno después del otro; el request es el sexto. Así el límite no bloquea las cuentas que usan los demás requests, y una corrida no arrastra los intentos de la anterior. Va último en la colección.

Corrida del 16/10 con el servidor armado desde cero con esos cambios: 74 requests, 252 aserciones y 1 falla, SEC-W05c, que queda en rojo hasta la decisión sobre la vigencia del token (`docs/decisiones/0001-vigencia-de-la-sesion.md`). Si el servidor arrancó con un `PALCO_SECRETO_SERVICIOS` propio, Newman se corre con `--env-var secretoServicios=<el valor>`.
