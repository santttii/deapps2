# ADR 0001 · Vigencia de la sesión

- **Estado:** Propuesto, a decidir por el grupo.
- **Criterio:** SEC-W05 (matriz de seguridad de la clase 11), hoy PARCIAL. Responsable: Joaquin Molina.
- **Fecha:** 16/10.

## Contexto (comprobado en el código)

- El token de sesión es un JWT RS256 que firma Usuarios y verifica cada servicio con el JWKS de Usuarios
  (`mp.jwt.verify.*`). Firma alterada y `alg: none` ya se rechazan (`TokensTest.unTokenModificadoNoVerifica`).
- **Vence a las 8 horas:** `DURACION_SESION = Duration.ofHours(8)` (`ServicioDeUsuarios.java:55`), que se usa para
  el claim `exp` (`:239`) y para `expiraEnSegundos` en la respuesta del login (`:246`).
- **No hay refresh token ni revocación.** No existe endpoint de cierre de sesión: el frontend "sale" borrando el
  token de `localStorage` (`SesionContext.tsx:50`, `lib/sesion.ts`). Un token robado sirve hasta su `exp`.
- **La clave se regenera al desplegar Usuarios** si falta `PALCO_JWT_CLAVE_PRIVADA` (`ClavesJwt.java`): cada
  redespliegue invalida todos los tokens. Es la única "revocación" que hay hoy, y es total.
- El criterio pide `exp - iat ≤ 900 s` (15 minutos). Con 8 h, la prueba W05c de la matriz da rojo.

## Opciones

**(a) Sesión de 15 a 30 minutos + refresh token revocable al cerrar sesión.**
- *Usuarios:* `DURACION_SESION` a 15 min. Refresh token opaco (aleatorio, 256 bits), guardado **hasheado** en una
  tabla nueva (`sesion`: usuario, hash, vence, revocada; migración Flyway que corra en H2 y PostgreSQL), con
  vencimiento absoluto de 8 h y rotación en cada uso. Dos operaciones nuevas: renovar (`@PermitAll`, la protege el
  refresh token) y cerrar sesión (`@RolesAllowed` user/organizer/staff, marca la fila como revocada). Tests de
  rotación, reuso de un refresh ya usado y revocación.
- *Frontend:* `lib/sesion.ts` guarda también el refresh token; `lib/api.ts`, ante un 401, renueva una sola vez y
  repite el pedido; el botón de salir llama al cierre de sesión. Hay que cuidar que la compra no se corte a mitad
  del checkout.
- *Postman:* el login guarda `{{refreshToken}}`; se agregan pedidos de renovar y de cerrar sesión, y el test
  W05c (`exp - iat ≤ 900`). La colección se genera con `scripts/postman/generar.js`: el cambio va ahí.
- *Defensa:* cumple el criterio tal como está escrito. Hay que explicar el límite que queda: un access token ya
  emitido sigue valiendo hasta 15 minutos después del cierre de sesión, porque los otros siete servicios lo
  verifican sin consultar a Usuarios (es el precio de no tener estado de sesión en cada servicio).

**(b) Dejar 8 horas y justificarlo por escrito.**
- *Código:* ninguno. Solo este ADR y el criterio registrado como riesgo aceptado.
- *Defensa:* argumentos posibles: es un TP sin datos reales, la duración cubre una jornada de puerta (staff) y la
  clave rotativa en desarrollo corta las sesiones en cada despliegue. Pero el criterio sigue en PARCIAL y la
  cátedra lo puede marcar: 8 h sin forma de revocar es justo el caso que el criterio describe como "de larga vida".

Descartada: bajar a 15 minutos **sin** refresh token. Cumple el número, pero obliga a volver a ingresar cada
15 minutos y no resuelve la revocación.

## Recomendación

**(b) para el 23/10 y (a) para el 13/11.** El 23/10 es un checkpoint sin defensa y falta una semana: (a) toca
Usuarios, una migración, el frontend y la colección, y no conviene meterlo sin margen. El 13/11 tiene defensa
oral y ahí el criterio tiene que estar en verde, no justificado: (a) es lo que se puede defender sin discusión, y
el trabajo está acotado a Usuarios y a dos archivos del frontend. Hasta entonces, SEC-W05 queda como riesgo
aceptado con este ADR como respaldo.

## Qué falta decidir

1. (a), (b) o la combinación recomendada, y quién implementa (a) si se elige.
2. Duración exacta del access token (15 min cumple el criterio tal cual; 30 min no lo cumple).
3. Dónde guarda el frontend el refresh token: `localStorage` (simple, expuesto a XSS) o cookie `HttpOnly`
   (más segura, pero obliga a CORS con credenciales entre `localhost:5173` y `:8080`).
4. Si `PALCO_JWT_CLAVE_PRIVADA` pasa a ser obligatoria fuera de desarrollo (relacionado con T5).
