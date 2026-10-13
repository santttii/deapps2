# Pasos transversales T0 a T3

Nota para la defensa oral. T0 y T3: Santiago Loto. T1: Sebastian Deya. T2: Julian Zuchowicki. Hecho el 09/10/2026 y corregido ese mismo día después de la revisión.

## Qué se construyó

- **T0 (`backend/pom.xml`).** El pom padre fija la versión de `mockito-core` 5.24.0 (tests) y de `microprofile-config-api` 3.1.1 (la trae WildFly). Cada servicio las declara sin versión.
- **T1 (`backend/common`).** Los errores de los 8 servicios salen como `application/problem+json`, el formato estándar Problem Details (RFC 9457): `{type, title, status, detail, instance}` y, si hay errores por campo, `errores`. `ErroresDeNegocio` suma `PagoRechazado` (402), `SinPermiso` (403), `ReglaIncumplida` (422) y `SinRespuesta` (504). CORS acepta `Idempotency-Key` e `If-None-Match` y deja leer `Location` y `ETag`. `BaseDeDatos.esClaveDuplicada` reconoce una clave repetida.
- **T2.** `POST /api/usuarios/servicios/token` entrega un token de rol `servicio` a quien presenta el secreto compartido. `TokenDeServicio`, en `common`, lo pide, lo guarda y lo renueva.
- **T3.** `V3__correr_fechas.sql` lleva los seis eventos de prueba del 26/12/2026 al 29/01/2027. Está aplicada.
- `common` tiene además el paquete `mensajeria`, de la tarea A2: se explica en `docs/notas/mensajeria.md`.

## Decisiones: qué se eligió, contra qué alternativa y por qué

1. **Versiones en el pom padre** y no en cada servicio: quedan en un solo lugar. No se usa `mockito-junit-jupiter`, que arrastra JUnit 5: el repo usa JUnit 6.
2. **Problem Details** en lugar del formato propio `{estado, mensaje, campos}`: es un estándar, es lo que promete la OpenAPI, y `type` distingue dos errores con el mismo código (un 409 por cupo agotado de uno por reserva vencida) sin leer el texto.
3. **La excepción lleva el tipo de problema; el código HTTP y el título los pone el manejador.** Guardar el código en la excepción haría que el negocio conozca HTTP. Hay un manejador por clase porque JAX-RS ya elige el más específico.
4. **`errores` aparece solo si hay errores por campo.** Para un error que no es nuestro (un 405 o un 415) `type` vale `about:blank`, como indica el estándar, y no lleva `detail`: el texto de esa excepción es interno del servidor, no uno escrito para el usuario. El 405 conserva la cabecera `Allow`, y los 401 que arman los manejadores llevan `WWW-Authenticate: Bearer`, como pide HTTP.
5. **`JsonProvider` declara que también escribe `application/problem+json`**, que para JAX-RS es otro tipo; si no, los errores los escribiría otro conversor del servidor. La alternativa era pasar el cuerpo a texto en cada manejador.
6. **`esClaveDuplicada` busca el código SQL 23505** entre las causas de la excepción. Responder 409 ante cualquier falla al guardar escondería errores reales de la base.
7. **Token de servicio por secreto compartido** (credenciales de cliente). Las alternativas eran un usuario "servicio" que hace login, al que hay que inventarle DNI y contraseña, o firmar tokens a mano con una clave fija.
8. **El token dura 5 minutos y `TokenDeServicio` lo guarda** en un bean `@ApplicationScoped`: una instancia por servicio, con el mismo token para todos los pedidos. Un stateless no lo recuerda entre llamadas y un stateful es para la conversación de un usuario. Se renueva cuando le quedan menos de 30 segundos; pedir uno por llamada duplicaba las llamadas a Usuarios.
9. **El secreto se compara con `MessageDigest.isEqual`** y no con `equals`: tarda lo mismo acierte o no, y no se lo puede adivinar midiendo tiempos. El endpoint es `@PermitAll` porque quien lo llama todavía no tiene token.
10. **Fechas en una migración nueva** y no editando la V2: Flyway rechaza un script ya aplicado que cambió. Los eventos se corren 15 semanas exactas y conservan día de la semana, hora y orden.
11. **La regla del DNI (7 u 8 dígitos, sin puntos) está una sola vez, en `ReglasDeNegocio.esDniValido`.** La usan el registro de usuarios y el mensaje `PAGO_CONFIRMADO`. Estaba escrita en los dos lados, y Ventas iba a sumar una tercera copia para los titulares: con copias distintas, una puede aceptar lo que otra rechaza.

## Lo que falló en el camino y cómo se resolvió

- **Un `check` de lista de valores rompía la base de desarrollo.** En H2 2.4.240, un `check (columna in (...))` queda atado a la conexión que corrió la migración. Cuando el servidor la cierra, la base rechaza todo `insert` o `update` de esa tabla. `usuario.rol` y `evento.categoria` tenían uno: pasado un rato del arranque, el registro, la verificación del email y el alta de eventos respondían 500.
- **Por eso falló la V3 al desplegarla en caliente.** Su `update` chocó con el check de `evento` y Eventos estuvo caído unos 10 minutos (14:53 a 15:03), hasta desplegar el mismo WAR sin la V3.
- **El arreglo fue el paso T6.** Se quitaron los dos checks de las V1 de Usuarios y de Eventos: los valores válidos los controla el enumerado de Java, que es quien escribe la columna. Se editó la V1 porque esos checks no tenían nombre y cada base les pone uno distinto; se pudo porque todavía no hay una base permanente. Flyway rechaza una V1 cambiada, así que hubo que reiniciar WildFly (15:16): la base arrancó vacía y se aplicaron V1, V2 y V3.
- **Cómo se comprobó.** `GET /api/eventos` muestra `ev-1` a `ev-6` con las fechas nuevas. Más de cinco horas después del reinicio hubo registros y altas de eventos que funcionaron, y el error no volvió a aparecer en el log. A las 21:05 se redesplegaron los 8 servicios en caliente, con Eventos y su V3 incluidos, sin fallas.
- **La regla que queda** (paso T6 y sección 7.1 del archivo de contratos): ninguna migración lleva `check` de lista de valores, con ninguna sintaxis; los de comparación (`precio > 0`) sí. La variante `= any (array[...])`, que en H2 no fallaba, quedó descartada: vale la regla del contrato, y contra PostgreSQL nunca se probó.

## Qué quedó pendiente y por qué

- **El secreto por defecto no es el mismo caso que la clave JWT por defecto.** Si falta `PALCO_JWT_CLAVE_PRIVADA` se genera una clave al azar, que nadie conoce. Si falta `PALCO_SECRETO_SERVICIOS` vale `palco-desarrollo`, que está escrito en el repo: quien lo lea puede pedir un token de servicio. Fuera de la máquina de desarrollo la variable es obligatoria. Falta sumarla a `backend/.env.example`, y que el grupo decida si Usuarios rechaza el valor de desarrollo cuando la base no es la de desarrollo.
- **Ventas tiene que validar a los titulares antes de cobrar**, con `ReglasDeNegocio.esDniValido` y nombre no vacío. El mensaje se valida recién al publicar, después del cobro: un titular que no pase dejaría la compra cobrada y sin confirmar.
- **El frontend lee el formato nuevo, pero le falta lo del checkout.** `api.ts` muestra el `detail` solo en 401, 403, 404 y 409 (no en 402, 422 ni 504), no guarda `type` y no puede mandar `Idempotency-Key` ni hacer `DELETE`. Lo completa el equipo de frontend al conectar el checkout.
- Un token inválido sigue respondiendo 401 en HTML, sin CORS: lo emite el servidor antes de llegar a los manejadores.
- Las migraciones no se probaron contra PostgreSQL (Supabase). Tampoco Mockito con Java 25.

## Cómo probarlo a mano

En Git Bash, desde `backend/`, con WildFly levantado:

```bash
export JAVA_HOME=/c/Users/santi/.jdks/temurin-25
mvn -q -B -pl '!servidor' install     # 48 tests en verde: 39 en common y 9 en usuarios
U=localhost:8080/api; J='Content-Type: application/json'
curl -i $U/eventos/no-existe          # T1: 404, application/problem+json y los cinco campos
curl -i $U/usuarios/servicios/token   # 405: about:blank, sin "detail" y con la cabecera Allow
curl -i -X POST -H "$J" -d '{"email":"titular@palco.test","password":"incorrecta1"}' $U/usuarios/login   # 401 con "detail"
S=$(curl -s -X POST -H "$J" -d '{"secreto":"palco-desarrollo"}' $U/usuarios/servicios/token | sed -E 's/.*"token":"([^"]+)".*/\1/')
C=$(curl -s -X POST -H "$J" -d '{"email":"titular@palco.test","password":"palco1234"}' $U/usuarios/login | sed -E 's/.*"token":"([^"]+)".*/\1/')
curl -i -X POST -H "$J" -H "Authorization: Bearer $S" -d '{"cantidad":0}' $U/eventos/ev-1/sectores/sec-1-4/cupo   # T2: 200 y "vendidas" no cambia
curl -i -X POST -H "$J" -H "Authorization: Bearer $C" -d '{"cantidad":0}' $U/eventos/ev-1/sectores/sec-1-4/cupo   # 403: es un comprador
curl -i -X POST -H "$J" -d '{"secreto":"otro"}' $U/usuarios/servicios/token                                       # 401
curl -s $U/eventos | grep -o '"fechaISO":"[^"]*","id":"ev-[1-6]"'   # T3: de 2026-12-26 a 2027-01-29
```
