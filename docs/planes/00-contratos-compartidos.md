# Contratos compartidos de la Ola 1

Versión del 09/10/2026. Es la fuente única que citan los planes A1 (ventas), A2 (mensajería), A3 (pagos) y A4 (documentación).

## 1. Para qué sirve este archivo

A1, A2 y A3 se implementan en paralelo y A4 documenta el resultado. Para que las partes encajen, cada nombre y cada interfaz que cruza de una tarea a otra tiene que existir en una sola versión. Este archivo fija esa versión.

- Si un plan y este archivo dicen cosas distintas, vale este archivo. Para cambiar un contrato, primero se cambia acá y se avisa al grupo.
- **Fechas** (corregidas el 13/10/2026). El grupo cursa en la comisión de los viernes y la consigna está escrita para la del lunes, así que este archivo usa las fechas del cronograma de los viernes.
  - El viernes 23/10 es la Entrega Parcial N.º 2 de la consigna (el plan viejo la llamaba "Entrega 3"): checkpoint formativo, sin defensa. La consigna la fecha el lunes 19/10 y el cronograma de los viernes no la nombra. El 23/10 sale de sumarle los cuatro días que separan a las otras fechas de las dos comisiones: es la fecha de trabajo y **falta confirmarla con la cátedra**.
  - El viernes 13/11 es la entrega obligatoria con defensa oral: la Entrega Obligatoria N.º 2 de la consigna, que la fecha el 09/11, y la "2° Entrega PARCIAL" del cronograma (el plan viejo la llamaba "Entrega 4").
  - El calendario de trabajo y el resto de los hitos de la comisión están en `docs/PLAN_AGENTES.md`.
  - Este archivo y los planes A1 a A4 se escribieron cuando la entrega del 13/11 era la siguiente. Donde dicen que algo "queda para el 13/11", quiere decir que no entraba en el checkpoint: la fecha de cada pendiente es la de `docs/PLAN_AGENTES.md`, que el 13/10 replanificó lo que falta. Para el 13/11 van la factura SOAP (C1), la conciliación de cobros (C3), los diagramas de secuencia (C5) y `openapi.yaml` (C4). Reventa (C2) cierra el 17/11, y Mercado Pago, el webhook y el reembolso (D1) tienen como límite el 19/11. El email real, `/api/v1` en Usuarios y Eventos y el ETag quedaron sin fecha.
- **(propuesta Pn)** marca una decisión de arquitectura que es del grupo. Se implementa con la opción recomendada, como se acordó hoy; las alternativas y el porqué están en la sección 11. Todas quedan pendientes de aprobación hasta que el dueño las lea.
- Lo que nadie ejecutó todavía en este servidor está en la sección 12 y no se afirma como hecho en el resto.
- **Git.** Se trabaja en el árbol actual del repo. Las ramas y los commits los maneja solo Santiago: un commit por paso verificable, con mensaje `tipo: qué cambió`.

**Siglas y términos.** WAR: el archivo desplegable de cada servicio. Context root: la ruta de la que cuelga un WAR. Session Bean: componente de negocio que administra el servidor; `@Stateless` no recuerda nada entre llamadas y `@Stateful` mantiene una conversación. CDI: la inyección de dependencias de Jakarta EE. JMS: la API de mensajería. Broker: el servidor de mensajes (ActiveMQ Artemis, dentro de WildFly). MDB (Message-Driven Bean): el componente que consume mensajes. DLQ: la cola a la que van los mensajes que fallaron todos sus intentos. JNDI: el nombre con que el servidor publica un recurso. JTA: la API de transacciones. XA: el protocolo que deja a un recurso participar de un commit en dos fases. JWT: el token firmado de sesión; claim: cada dato que viaja dentro del token; JWKS: la clave pública con que se lo verifica. DTO: objeto que entra o sale de la API. JSON-B: el conversor estándar entre objetos y JSON. CORS: el permiso para que el navegador llame a la API desde otro origen. Problem Details: el formato estándar de errores HTTP (RFC 9457). ETag: la etiqueta de versión de una respuesta, para no volver a descargarla. Pasarela: el servicio externo que cobra; webhook: el aviso HTTP que la pasarela le manda a nuestra API. Idempotente: repetir el pedido no repite el efecto. UTC: hora universal, sin zona. Runner: la función de Postman que corre la colección entera y muestra qué tests pasaron.

## 2. Qué entra para el 23/10 y qué queda para el 13/11

| Tema | Para el 23/10 | Queda para el 13/11 |
|---|---|---|
| Compra (A1) | `POST /api/v1/ventas`, pago con cobro simulado, `GET` y `DELETE` de la venta, `GET` de la entrada, vencimiento de la reserva | Cobro contra Mercado Pago, factura SOAP, pago pendiente con webhook, reembolso, conciliación de cobros sin confirmar |
| Mensajería (A2 y A1) | `PAGO_CONFIRMADO` por cola (emisión de entradas) y por tópico (aviso en el log y auditoría durable), reintentos y DLQ | Email real, cola de reintento de facturación, diagramas de secuencia |
| Pagos (A3) | `POST` y `GET /api/v1/pagos` con la pasarela simulada | Webhook, reembolso, `MercadoPagoPagos` |
| Transversal | Token de rol `servicio`, errores en Problem Details, CORS, `/api/v1` en Ventas y Pagos | `/api/v1` en Usuarios y Eventos, ETag, completar `openapi.yaml` |
| Otros servicios | Validación, solo si B3 llega en la Ola 2 | Reventa (C2), Facturación (C1) |

Camino crítico del 23/10: T1 y T2 → A2 → A1 hasta el MDB de emisión → evidencia para A4. La consigna pide para esa fecha un proceso con mensajería real; A1 cobra con un simulador local, así que A3 no está en ese camino.

## 3. Cambios transversales previos

Tocan módulos que ninguna tarea A1 a A4 puede tocar. Los dueños son una propuesta (P11).

| Paso | Qué | Dónde | Quién lo defiende | Tiene que estar antes de |
|---|---|---|---|---|
| T0 | Dependencias de test y de configuración | `backend/pom.xml` | Santiago | los tests de A1 y el primer paso de A3 |
| T1 | Errores en Problem Details y su lectura en el frontend, excepciones nuevas y CORS | `backend/common`, `frontend/src/lib/api.ts` | Sebastian | T2, y que A1 o A3 respondan 402, 403, 422 o 504 |
| T2 | Token de rol `servicio` | `backend/servicios/usuarios` y `backend/common` | Julian | la aceptación de A2, la reserva de cupo de A1 y los `curl` de A3 |
| T3 | Fechas futuras en los eventos de prueba | `backend/servicios/eventos`, `frontend/src/data/events.ts` | Santiago | la demo y las capturas |
| T4 | Ajustes de la colección de Postman | `docs/api` | Santiago | la captura del Runner |
| T5 | Documentos que quedan viejos | `CLAUDE.md`, `README.md`, `TODO.md`, `backend/.env.example` | Santiago | el cierre de la Ola 1 |
| T6 | Quitar los `check` de lista de valores | `backend/servicios/usuarios`, `backend/servicios/eventos` | Santiago | que A1, A2 y A3 escriban sus migraciones, y cualquier prueba de registro o de alta de eventos |

`common` viaja dentro de los 8 WAR: después de T1, de T2 y de la parte de A2 que vive en `common` hay que reconstruir y redesplegar los 8, y comprobar los 8 `health`.

### T0 · Dependencias en el pom padre

En `backend/pom.xml`, dentro de `dependencyManagement`, que es donde el repo fija las versiones:

| Dependencia | Versión | Alcance | Quién la declara, sin versión |
|---|---|---|---|
| `org.mockito:mockito-core` | 5.24.0 | test | A1, en `servicios/ventas/pom.xml` |
| `org.eclipse.microprofile.config:microprofile-config-api` | 3.1.1 | provided | A3, en `servicios/pagos/pom.xml` |

- `org.eclipse:yasson` con alcance test ya está gestionada: A2 solo la declara en `common/pom.xml`.
- No se usa `mockito-junit-jupiter`: trae `junit-jupiter-api` 5.13.4 y el repo usa JUnit 6.1.3. Los dobles se crean con `Mockito.mock(...)`, sin extensiones.
- Antes de escribir tests: `mvn -q dependency:tree -pl servicios/ventas -Dincludes=org.junit.jupiter,org.junit.platform` tiene que mostrar todo en 6.1.3.
- Quedan dos estilos de doble de prueba, cada uno con su motivo: Mockito cuando alcanza con simular una respuesta (A1), y un repositorio en memoria cuando el doble necesita comportamiento, como rechazar una clave repetida (`PagoRepositoryEnMemoria`, A3).

### T1 · Errores comunes y CORS (propuesta P8)

**Formato.** Todos los errores de todos los servicios salen con `Content-Type: application/problem+json`:

```json
{
  "type": "/problemas/cupo-agotado",
  "title": "Conflicto",
  "status": 409,
  "detail": "No quedan suficientes entradas en Platea A.",
  "instance": "/api/v1/ventas"
}
```

- `detail` es el mensaje de la excepción (lo que hoy es `mensaje`). `instance` es la ruta del pedido. `title` es un texto fijo por clase de excepción.
- `errores` (lo que hoy es `campos`) aparece solo cuando hay errores por campo.
- Los miembros extra de la OpenAPI (`topeMaximo`, `disponibles`) no entran ahora: los suma C2 cuando los necesite.

**Excepciones de `com.palco.common.ErroresDeNegocio`.** Todas llevan `@ApplicationException(rollback = true)`. Las que existen conservan su constructor, así Eventos y Usuarios no cambian.

| HTTP | Clase | `type` por defecto | Constructores |
|---|---|---|---|
| 400 | `DatosInvalidos` (existe) | `/problemas/datos-invalidos` | `(String campo, String mensaje)` |
| 401 | `NoAutenticado` (existe) | `/problemas/no-autenticado` | `(String mensaje)` |
| 402 | `PagoRechazado` (nueva) | `/problemas/pago-rechazado` | `(String mensaje)` |
| 403 | `SinPermiso` (nueva) | `/problemas/sin-permiso` | `(String mensaje)`, `(String tipo, String mensaje)` |
| 404 | `NoEncontrado` (existe) | `/problemas/no-encontrado` | `(String mensaje)` |
| 409 | `Conflicto` (existe) | `/problemas/conflicto` | `(String mensaje)`, y nuevo `(String tipo, String mensaje)` |
| 422 | `ReglaIncumplida` (nueva) | `/problemas/regla-incumplida` | `(String mensaje)`, `(String tipo, String mensaje)` |
| 429 | `DemasiadosIntentos` (agregada el 16/10) | `/problemas/demasiados-intentos` | `(String mensaje, long segundosDeEspera)`; la respuesta lleva `Retry-After` con esos segundos |
| 504 | `SinRespuesta` (nueva) | `/problemas/sin-respuesta` | `(String mensaje)` |
| 500 | cualquier otra | `/problemas/error-inesperado` | no aplica; desde el 16/10 el cuerpo suma `idError`, el id con el que quedó la traza en el log |

`tipo` es el final de la ruta, sin prefijo: `new Conflicto("cupo-agotado", "No quedan…")` responde `type` `/problemas/cupo-agotado`.

**429 del login** (agregado el 16/10, SEC-W04). `POST /api/usuarios/login` responde 429 `demasiados-intentos` cuando ese email (sin importar mayúsculas ni espacios) tuvo 5 fallidos en los últimos 15 minutos, aunque la contraseña sea la correcta y aunque el email no tenga cuenta, siempre con el mismo texto. `Retry-After` trae los segundos que faltan para que el fallido más viejo salga de la ventana. Un login correcto borra los fallidos de ese email. Los cuenta Usuarios en la tabla `intento_login` (V4).

Tipos con constructor `(String tipo, String mensaje)` que se suman a los de la tabla:

| Tipo | Servicio | HTTP | Cuándo |
|---|---|---|---|
| `evento-finalizado` | Eventos y Ventas | 409 (`Conflicto`) | Se reserva cupo, o se inicia una compra, de un evento que ya empezó. Ventas lo reenvía tal cual le llega de Eventos |
| `clave-reutilizada` | Ventas y Pagos | 409 (`Conflicto`) | La misma `Idempotency-Key` llega con otro pedido |

**CORS**, en `CorsFilter`:
- `Access-Control-Allow-Headers: Content-Type, Authorization, Idempotency-Key, If-None-Match`
- `Access-Control-Expose-Headers: Location, ETag`

**Clave duplicada.** `com.palco.common.datos.BaseDeDatos.esClaveDuplicada(Throwable e)` devuelve `true` si alguna causa es una `java.sql.SQLException` con SQLState `23505`, el código estándar de clave duplicada. La usan los repositorios de A1 y A3 para no convertir en 409 cualquier falla de base.

**Qué no cambia.** Los códigos de estado. Los tests de las carpetas 1 y 2 de Postman miran solo el código, y ningún test del backend lee el cuerpo de un error. El frontend sí lo lee, y por eso entra en este paso.

**Frontend, en el mismo paso.** El frontend ya llama a Usuarios y a Eventos, y lee el error en un solo lugar: `frontend/src/lib/api.ts`, un archivo nuevo que todavía no está commiteado. T1 lo ajusta junto con `common`:

- Lee `detail` y `errores` donde hoy lee `mensaje` y `campos`. `errores` tiene la misma forma que `campos`: un objeto con un texto por campo.
- Acepta `application/problem+json` al decidir si la respuesta es JSON. Hoy solo acepta `application/json`, y con el formato nuevo descartaría el cuerpo entero.
- `ErrorApi` conserva `estado` y `campos`, así que no se toca ninguna pantalla.

Sin este ajuste, según el código, el registro, la verificación del código y el alta de eventos dejan de mostrar los errores por campo (`RegisterPage.tsx`, `CreateEventModal.tsx`), y los textos que escribe el backend, como "Verificá tu email antes de iniciar sesión.", se reemplazan por uno genérico. Son pantallas de los recorridos R1 y R4 de A4.

**Límite conocido.** El 401 por token inválido lo emite el servidor en HTML antes de llegar a estos manejadores. Los clientes entre servicios deciden por el código de estado, nunca por el cuerpo.

**Cómo se comprueba.** `curl -i localhost:8080/api/eventos/no-existe` muestra 404, `Content-Type: application/problem+json` y el cuerpo de arriba. En `frontend/`, `npm run lint && npm run build` terminan sin errores. En el navegador, registrarse con el email `titular@palco.test`, que ya existe, sigue mostrando "Ya hay una cuenta con ese email." debajo del campo Email. Una contraseña incorrecta no sirve como prueba: la pantalla de login muestra un texto propio ante cualquier 401.

### T2 · Token de rol `servicio` (propuesta P6)

Es el esquema de credenciales de cliente: un servicio presenta un secreto compartido y Usuarios le firma un token corto.

**Endpoint nuevo en Usuarios**

| | |
|---|---|
| Operación | `POST /api/usuarios/servicios/token`, `@PermitAll` (lo protege el secreto) |
| Cuerpo | `{"secreto": "<valor>"}` |
| 200 | `{"token": "<JWT>", "expiraEnSegundos": 300}` |
| Errores | 400 sin cuerpo; 401 (`NoAutenticado`) si el secreto no coincide |
| Token | Firmado con la misma clave que las sesiones. Claims: `iss` = `palco`, `sub` = `servicio`, `upn` = `servicio@palco.interno`, `groups` = `["servicio"]`, `iat`, `exp` = 5 minutos, `jti` |
| Secreto | Variable de entorno `PALCO_SECRETO_SERVICIOS`. Si falta, vale `palco-desarrollo` y el log lo avisa, igual que hoy con la clave JWT. Se compara con `MessageDigest.isEqual` |
| Interfaz | `IUsuarios` suma `TokenDeServicioDto emitirTokenDeServicio(String secreto)` |

**Cliente en `common`**

```java
package com.palco.common.seguridad;

@ApplicationScoped
public class TokenDeServicio {
    /** Dirección de los otros servicios: PALCO_URL_INTERNA o http://localhost:8080. */
    public static final String URL_INTERNA;
    /** PALCO_SECRETO_SERVICIOS o el valor de desarrollo. Lo leen este cliente y ServicioDeUsuarios. */
    public static String secretoCompartido();
    /** Token vigente. Si no hay o le quedan menos de 30 segundos, pide uno a Usuarios. */
    public String obtener();
    /** Descarta el token guardado; el próximo obtener() pide uno nuevo. */
    public void invalidar();
}
```

- No depende del pedido HTTP en curso: se usa también desde un temporizador.
- Si el servicio llamado responde 401, quien llama hace `invalidar()`, `obtener()` y reintenta una sola vez. Pasa cada vez que se redespliega Usuarios, porque cambia la clave.
- Si Usuarios no responde, lanza `ErroresDeNegocio.SinRespuesta`.

**Cómo se comprueba.** Pedir el token con `curl` y llamar a `POST /api/eventos/ev-1/sectores/sec-1-4/cupo` con `{"cantidad": 0}`: tiene que dar 200 sin cambiar el cupo. Con el token de `titular@palco.test` tiene que dar 403.

### T3 · Fechas de los eventos de prueba

- `backend/servicios/eventos/src/main/resources/db/migracion/V3__correr_fechas.sql`: seis `UPDATE` que llevan los eventos `ev-1` a `ev-6` a diciembre de 2026 o después. La V2 no se edita, porque Flyway (la herramienta de migraciones) compara su contenido con el ya aplicado.
- Las mismas fechas en `frontend/src/data/events.ts`, que hoy tiene cambios sin commitear.
- Motivo: los seis eventos van del 12/09 al 16/10. El 23/10 ya pasaron todos, y la reventa exige que falten más de 3 horas.
- Ventas no valida la fecha del evento: el contrato no tiene esa regla.

### T4 · Colección de Postman

El detalle, para las tres carpetas, está en la sección 8.4. La colección vive en `docs/api` y la mantiene Santiago.

### T5 · Documentos que quedan viejos

| Archivo | Qué corregir |
|---|---|
| `CLAUDE.md` | La regla `/api/<servicio>` (líneas 14 y 35), las capas (se suman `datos/` y `mensajeria/`) y la sección Git (commitea Santiago) |
| `README.md` | Formato de error, tabla de endpoints y regla de rutas |
| `TODO.md` | Fase 6: "XA entre la base y el broker" pasa a "último recurso". Fase 4: `/api/pagos/webhook` y `/api/ventas/{id}`. Fase 10: `/api/auth/*` pasa a `/api/usuarios/*`. Fase 1: el formato de ids queda resuelto |
| `backend/.env.example` | `PALCO_SECRETO_SERVICIOS` y `PALCO_PAGOS_PROVEEDOR`, comentadas |
| `backend/dev.sh`, `backend/servicios/pom.xml` | El comentario y la descripción que dicen `/api/<servicio>` |

### T6 · Sin `check` de lista de valores

**El defecto.** Comprobado en este servidor el 09/10: en H2 2.4.240 un `check` de lista (`check (rol in ('user', 'organizer', 'staff'))`) queda atado a la conexión que corrió la migración. Cuando el pool cierra esa conexión, todo `INSERT` o `UPDATE` de la tabla falla con «The database has been closed [90098]» dentro de `ConstraintCheck.checkRow`, y el servicio responde 500. Por eso, pasado un rato del arranque, `POST /api/usuarios/registro` y `POST /api/eventos` fallan con datos válidos.

**La regla, para todas las migraciones.** Ninguna tabla lleva `check` de lista de valores. Los valores válidos de un estado, un rol o una categoría los controla el enumerado de Java, que es quien escribe la columna. Los `check` de comparación (`precio > 0`, `vendidas <= cupo`) siguen permitidos: el defecto se vio solo con listas.

**El arreglo en lo que ya existe.** Se quitan `check (rol in (...))` de la V1 de Usuarios y `check (categoria in (...))` de la V1 de Eventos. Se edita la V1, en lugar de sumar una migración que borre la restricción, porque esas restricciones no tienen nombre y cada base les pone uno distinto. Editar una migración ya aplicada solo se puede porque todavía no existe una base permanente (la de desarrollo es en memoria y Supabase no está creado). Exige reiniciar WildFly: con un redespliegue en caliente Flyway rechaza la V1 cambiada.

**Cómo se comprueba, en cualquier servicio.** Vaciar el pool y después insertar:

```
jboss-cli.bat --connect --command="/subsystem=datasources/data-source=PalcoDS:flush-all-connection-in-pool"
```

Después de eso, registrar un usuario y crear un evento tienen que responder 201. Cada tarea repite esta prueba sobre sus propias tablas antes de darlas por buenas.

## 4. Mensajería

### 4.1 Clases de `com.palco.common.mensajeria` (las crea A2)

```java
public record PagoConfirmadoMensaje(String ventaId, String compradorId, String eventoId,
        List<Entrada> entradas, long montoTotal, Instant timestamp) {

    public record Entrada(String entradaId, String sector, Titular titular) { }
    public record Titular(String nombre, String dni) { }

    public String aJson();
    public static PagoConfirmadoMensaje desdeJson(String json);   // lanza MensajeInvalido
    public void validar();                                        // lanza MensajeInvalido
}

@ApplicationException(rollback = true)
public class MensajeInvalido extends RuntimeException { }

public final class DestinosJms {
    public static final String FABRICA_XA = "java:/JmsXA";
    public static final String COLA_EMISION = "java:/jms/queue/emisionEntradas";
    public static final String TOPICO_COMPRA = "java:/jms/topic/compraConfirmada";
    public static final String PROPIEDAD_TIPO_EVENTO = "tipoEvento";
    public static final String PAGO_CONFIRMADO = "PAGO_CONFIRMADO";
    public static final String SELECTOR_PAGO_CONFIRMADO = "tipoEvento = 'PAGO_CONFIRMADO'";
}

@Local
public interface PublicadorPagoConfirmado {
    void publicar(PagoConfirmadoMensaje mensaje);
}

@Stateless
@TransactionAttribute(TransactionAttributeType.MANDATORY)
public class PublicadorPagoConfirmadoJms implements PublicadorPagoConfirmado { }
```

- `publicar` exige que quien llama ya tenga una transacción (`MANDATORY`). No abre una propia: si la venta se deshace, el mensaje no sale.
- `publicar` llama a `validar()`, convierte a JSON una sola vez y manda el mismo texto a la cola y al tópico. Un mensaje inválido lanza `MensajeInvalido`, la venta se deshace y el cliente recibe 500: es un error de programación de quien publica.
- Los métodos auxiliares no empiezan con `get` ni `is`, porque JSON-B los escribiría como campos.
- A1 no crea ninguna interfaz provisoria: A2 se implementa antes. En sus tests reemplaza `PublicadorPagoConfirmado` con una lambda.
- Solo publica `ServicioDeVentas.confirmarCompra`, y los endpoints de prueba de Notificaciones.

### 4.2 Nombres JNDI

| Nombre JNDI | Qué es | Uso |
|---|---|---|
| `java:/JmsXA` | Fábrica de conexiones transaccional | La única que se usa para publicar |
| `java:/jms/queue/emisionEntradas` | Cola `cola.emision-entradas` | Emisión de entradas: un solo consumidor por mensaje |
| `java:/jms/topic/compraConfirmada` | Tópico `topico.compra-confirmada` | Aviso y auditoría: una copia por suscriptor |
| `java:/jms/queue/DLQ` | Cola `DLQ` | Mensajes que fallaron todos sus intentos |
| `java:jboss/datasources/PalcoDS` | Base de datos | `BaseDeDatos.JNDI` |

No se usa `java:/ConnectionFactory`: queda fuera de la transacción.

### 4.3 El mensaje

`TextMessage` con el cuerpo en JSON, la propiedad `tipoEvento` = `PAGO_CONFIRMADO` y entrega persistente. JSON-B escribe las propiedades en orden alfabético.

```json
{
  "compradorId": "user-titular",
  "entradas": [
    { "entradaId": "T-9c41d07e", "sector": "Platea B", "titular": { "dni": "30123456", "nombre": "Ana Gómez" } },
    { "entradaId": "T-51b7a2f0", "sector": "Platea B", "titular": { "dni": "31234567", "nombre": "Luis Pérez" } }
  ],
  "eventoId": "ev-1",
  "montoTotal": 60500,
  "timestamp": "2026-10-16T21:30:00Z",
  "ventaId": "V-3f9a1c2b"
}
```

| Campo | Cómo lo llena A1 |
|---|---|
| `ventaId`, `entradas[].entradaId` | Los ids reales (sección 7.1) |
| `compradorId` | El claim `sub` del token del comprador |
| `eventoId` | El que vino en el pedido |
| `entradas[].sector` | El nombre del sector, no su id |
| `montoTotal` | El total cobrado, con el cargo de servicio |
| `timestamp` | El instante de la confirmación, en UTC y truncado a segundos |

`validar()` exige: ids no vacíos de hasta 40 caracteres; entre 1 y `MAX_ENTRADAS_POR_ORDEN` entradas, ninguna nula; cada titular con nombre y DNI de 7 u 8 dígitos; `montoTotal` mayor que 0; `timestamp` presente. `desdeJson` convierte en `MensajeInvalido` un texto nulo, vacío, `null` o que no es JSON.

A2 actualiza `docs/superpowers/specs/mensajeria-pago-confirmado.md` con este formato de `timestamp`, estos ids de ejemplo, los nombres de las clases y la garantía de la sección 4.7. Los campos no cambian. Este archivo es el aviso que pide `CLAUDE.md` antes de tocar ese contrato.

### 4.4 Quién es dueño de cada MDB

Los MDB van en el paquete `mensajeria/` de su servicio, con sufijo `MDB`. Son un punto de entrada delgado, como el recurso REST: leen el mensaje y delegan.

| `@MessageDriven(name = …)` | Clase | Tarea | Propiedades de activación |
|---|---|---|---|
| `EmisionDeEntradas` | `com.palco.ventas.mensajeria.EmisionDeEntradasMDB` | A1 | `destinationLookup` = `DestinosJms.COLA_EMISION`; `destinationType` = `jakarta.jms.Queue`; `messageSelector` = `DestinosJms.SELECTOR_PAGO_CONFIRMADO` |
| `AvisoDeCompra` | `com.palco.notificaciones.mensajeria.AvisoDeCompraMDB` | A2 | `destinationLookup` = `DestinosJms.TOPICO_COMPRA`; `destinationType` = `jakarta.jms.Topic`; `messageSelector` igual. Suscripción no durable |
| `AuditoriaDeCompras` | `com.palco.notificaciones.mensajeria.AuditoriaDeComprasMDB` | A2 | Las de `AvisoDeCompra` más `subscriptionDurability` = `Durable`; `clientId` = `palco-auditoria`; `subscriptionName` = `compras-confirmadas`; `maxSession` = `1` |

- El par `clientId` más `subscriptionName` no se repite en ningún otro MDB.
- Las propiedades usan las constantes de `DestinosJms`. El servidor no reemplaza `${…}` dentro de las anotaciones.
- Cada MDB escribe una línea `INFO` por mensaje procesado, con el `ventaId`. A4 la cita como evidencia.
- Auditoría es un módulo dentro del WAR de Notificaciones, con su propia suscripción.

### 4.5 Qué hace cada consumidor

| Caso | `EmisionDeEntradasMDB` | `AuditoriaDeComprasMDB` | `AvisoDeCompraMDB` |
|---|---|---|---|
| Mensaje válido | Por cada entrada `PENDIENTE_EMISION`: código QR, `VALIDA` y fecha de emisión. La venta pasa de `CONFIRMADA` a `EMITIDA` | Inserta una fila; la clave es el `JMSMessageID` | Escribe el aviso al comprador en el log |
| El broker reentrega | El `UPDATE` condicional afecta 0 filas; confirma sin error | La fila ya existe; no inserta | Repite el aviso; se acepta |
| Cuerpo inválido o no es `TextMessage` | `setRollbackOnly()`: 5 intentos y DLQ | `setRollbackOnly()`: 5 intentos y DLQ | Lo descarta con un aviso |
| `ventaId` que no existe | Lo descarta con un aviso y confirma el mensaje | Lo registra: audita lo que recibió | Avisa igual |

- La emisión hace un `UPDATE` por entrada (`where id = ? and estado = 'PENDIENTE_EMISION'`), porque cada una lleva un código distinto.
- El caso "venta que no existe" es real: el broker guarda los mensajes en disco y la base H2 de desarrollo se vacía al reiniciar. También lo producen los mensajes de prueba.
- Auditoría evita registrar dos veces el mismo mensaje. Evitar que una venta se publique dos veces es de A1: `publicar` se llama una sola vez, en la transacción que pasa la venta a `CONFIRMADA`.

### 4.6 Reintentos y DLQ (A2, en `configurar.cli`)

```
/subsystem=messaging-activemq/server=default/address-setting=#:write-attribute(name=max-delivery-attempts,value=5)
/subsystem=messaging-activemq/server=default/address-setting=#:write-attribute(name=redelivery-delay,value=2000)
```

- Vale para todas las colas: 5 entregas separadas por 2 segundos y después la DLQ. La dirección de la DLQ ya viene configurada.
- `configurar.cli` corre solo cuando se arma el servidor, y el armado se saltea si `backend/servidor/target/server` ya existe. Cada integrante tiene que aplicar las dos líneas con `jboss-cli`, o detener WildFly y correr `mvn clean install`, que borra los mensajes guardados. A2 corrige el comentario de cabecera del archivo, que dice lo contrario.

### 4.7 Garantía transaccional (propuesta P3)

Es el texto único para el contrato del mensaje, las notas y el documento. Sale de la configuración y del código del servidor; todavía no se probó en ejecución (sección 12), y no se afirma ante la cátedra lo que no se haya probado.

- Confirmar la venta, crear las entradas y publicar el mensaje ocurren en una transacción JTA declarativa: `@TransactionAttribute(REQUIRED)` en `ServicioDeVentas.confirmarCompra` y `MANDATORY` en el publicador.
- El broker participa como recurso XA. La base no es XA y participa como último recurso. No es un commit en dos fases.
- Si hay una excepción o falla el commit de la base, no queda ni venta confirmada ni mensaje.
- Si el servidor se cae entre el commit de la base y el del broker, queda una venta `CONFIRMADA` con entradas `PENDIENTE_EMISION` y sin mensaje. Es un estado visible; el reenvío es de C3.
- La transacción cubre solo el tercer paso del flujo:

| Paso | Cómo se protege |
|---|---|
| Reservar cupo | Llamada REST a Eventos, que confirma en el acto. Si algo falla después, Ventas devuelve el cupo a mano |
| Cobrar | Es externo: no se deshace con un rollback. Se protege con la clave de idempotencia y, más adelante, con el reembolso |
| Confirmar, crear entradas y publicar | Transacción JTA declarativa |

### 4.8 Notificaciones (A2)

Sigue en `/api/notificaciones`: sus endpoints son internos y de desarrollo, y no van a la OpenAPI.

| Interfaz `@Local` | Operaciones | Session Bean |
|---|---|---|
| `com.palco.notificaciones.negocio.INotificaciones` | `void enviarConfirmacion(PagoConfirmadoMensaje mensaje)` | `ServicioDeNotificaciones` (`@Stateless`) |
| `com.palco.notificaciones.negocio.IAuditoria` | `void registrar(String mensajeId, PagoConfirmadoMensaje mensaje, String cuerpo)`; `List<RegistroDeAuditoriaDto> listar()` | `ServicioDeAuditoria` (`@Stateless`) |

- `enviarConfirmacion` es el nombre que tiene la Entrega Parcial 1. `enviarRecordatorio` y `enviarAlertaCancelacion` se declaran cuando se implementen.
- El aviso va solo al comprador: el mensaje no trae al organizador.

| Endpoint, todos con rol `servicio` | Qué hace | Respuesta |
|---|---|---|
| `POST /api/notificaciones/pruebas/pago-confirmado` | Publica con el publicador real un mensaje de ejemplo con `ventaId` `prueba-` más 8 caracteres | 202 con el mensaje |
| `POST /api/notificaciones/pruebas/pago-confirmado-con-falla` | Publica y después lanza `Conflicto`: la transacción se deshace y no tiene que llegar nada | 409 |
| `POST /api/notificaciones/pruebas/mensaje-invalido` | Manda solo al tópico un texto que no es JSON | 202 |
| `GET /api/notificaciones/auditoria` | Últimos 50 registros: `{mensajeId, tipoEvento, ventaId, compradorId, eventoId, montoTotal, recibidoEn}` | 200 |

No hay interruptor por variable de entorno: los protege el rol `servicio`, que solo consigue quien tiene el secreto.

## 5. API entre servicios

### 5.1 Reglas para toda llamada entre servicios

- La dirección base es `TokenDeServicio.URL_INTERNA`.
- Cliente JAX-RS con 2 segundos de conexión y 5 de lectura (10 de lectura de Ventas a Pagos). Un cliente compartido por clase, que se cierra al destruirla, y cada respuesta dentro de `try-with-resources`.
- El cuerpo se manda y se lee como `String` y se convierte con JSON-B.
- Se decide por el código de estado, nunca por el texto del error.
- Ante un 401 con token de servicio: `invalidar()`, `obtener()` y un solo reintento.
- Sin respuesta, timeout o código inesperado: `ErroresDeNegocio.SinRespuesta` (504).
- Las clases cliente son `@ApplicationScoped`. El repo no tiene `beans.xml`, así que una clase sin esa anotación no se puede inyectar.
- Entre WAR no hay transacción: cada llamada confirma por su cuenta. Regla del cupo: ante la duda queda un lugar sin vender, nunca uno vendido dos veces.

### 5.2 Ventas → Eventos (ya existe)

| | |
|---|---|
| Operación | `POST /api/eventos/{eventoId}/sectores/{sectorId}/cupo`, rol `servicio` |
| Cuerpo | `{"cantidad": n}`: positivo reserva, negativo libera |
| 200 | `{id, nombre, precio, cupo, vendidas}`. De acá sale el precio de la venta |

| Eventos responde | Ventas responde |
|---|---|
| 404 | 422 `sector-inexistente` |
| 409 `evento-finalizado` | 409 `evento-finalizado` |
| Otro 409 | 409 `cupo-agotado` |
| Otro código o sin respuesta | 504 `sin-respuesta` |

Si falla un sector, Ventas devuelve los que ya habían respondido 200. Un sector cuya llamada no respondió no se devuelve, porque no se sabe si se aplicó.

### 5.3 Ventas → Usuarios (ya existe)

`GET /api/usuarios/yo` con el token del comprador. Devuelve `{id, nombre, apellido, dni, email, rol, identidadVerificada}`. Si `identidadVerificada` es `false`, Ventas responde 403 `identidad-no-verificada`. La consulta se hace antes de abrir la transacción.

### 5.4 Ventas → Pagos (lo expone A3, lo conecta B1)

El pago público sigue en Ventas, como en la OpenAPI. Pagos expone una API interna con una sola palabra para el recurso: pago.

| | |
|---|---|
| Operación | `POST /api/v1/pagos`, rol `servicio` |
| Cabeceras | `Authorization: Bearer <token de servicio>`; `Idempotency-Key`: la misma clave que mandó el comprador al pagar |
| Cuerpo | `{"ventaId": "V-3f9a1c2b", "monto": 60500, "medioDePago": "tarjeta", "token": "tok_test"}` |
| 201 | `Location: /api/v1/pagos/{pagoId}` y `{pagoId, ventaId, monto, estado, motivo, idExterno}` |
| `estado` | `APROBADO` o `RECHAZADO`. Un rechazo también es 201: el pago se procesó y ese es su resultado |
| Misma clave, misma venta y monto | 201 con el mismo pago, sin volver a llamar a la pasarela. Vale también si los dos pedidos llegan a la vez |
| Errores | 400 datos inválidos o falta la clave; 401; 403 otro rol; 409 `clave-reutilizada`, clave ya usada con otra venta u otro monto; 409 `pago-duplicado`, dos pedidos con la misma clave chocaron al guardarse; 504 la pasarela no respondió (no se guarda nada y se puede reintentar con la misma clave) |
| Consulta | `GET /api/v1/pagos/{pagoId}`, rol `servicio`: 200 o 404 |

Rutas reservadas para después del 23/10: `POST /api/v1/pagos/{pagoId}/reembolsos` y `POST /api/v1/pagos/webhook`.

Pagos garantiza un solo pago por clave. No garantiza un solo pago por venta: eso lo garantiza Ventas (sección 7.3).

| Pagos responde | Ventas responde al comprador |
|---|---|
| 201 `APROBADO` | 201, venta `CONFIRMADA` |
| 201 `RECHAZADO` | 402 `pago-rechazado`; la venta sigue `EN_CHECKOUT` |
| 409 `clave-reutilizada` | 402 `pago-rechazado` con motivo `clave_reutilizada`: Pagos no cobró; la venta sigue `EN_CHECKOUT` |
| 409 `pago-duplicado` (u otro 409 sin `type` legible) | Ventas repite el pedido una vez con la misma clave y Pagos devuelve el pago que guardó: se sigue como con ese 201. Si vuelve a chocar, 504 `sin-respuesta` (`ClienteDePagos`) |
| 504 o sin respuesta | 504 `sin-respuesta`; la venta sigue `EN_CHECKOUT` y se reintenta con la misma clave |
| 400 | 402 `pago-rechazado` con motivo `datos_invalidos`: Pagos no cobró |
| 401, 403 o 404 | Con 401 se renueva el token de servicio y se reintenta una vez. Si sigue, o con 403 o 404: 504 `sin-respuesta`, sin cobro, y la venta se puede cancelar |
| Cualquier otro | 504 `sin-respuesta`: no se sabe si se cobró, y se reintenta con la misma clave |

El estado `PENDIENTE` y el 202 llegan con el webhook, después del 23/10. Hasta entonces ningún simulador devuelve `PENDIENTE`.

**Tokens de prueba.** Valen igual en `PagosFake` (Pagos) y en `CobrosFake` (Ventas):

| `token` | Resultado |
|---|---|
| `tok_rechazado` | `RECHAZADO`, motivo `fondos_insuficientes` |
| `tok_sin_respuesta` | `SinRespuesta` (504) |
| Cualquier otro, por ejemplo `tok_test` | `APROBADO` |

**Configuración de Pagos.** Propiedad `palco.pagos.proveedor` (variable `PALCO_PAGOS_PROVEEDOR`), con valor por defecto `fake`. Se lee con `ConfigProvider.getConfig().getOptionalValue(…)`: una propiedad que falta no puede impedir el despliegue.

### 5.5 El cobro visto desde Ventas (A1)

```java
package com.palco.ventas.negocio.externos;

public interface Cobros {
    ResultadoDeCobro cobrar(String ventaId, long monto, String medioDePago, String token, String claveIdempotencia);
}

public record ResultadoDeCobro(String pagoId, String estado, String motivo) { }   // estado: APROBADO o RECHAZADO
```

A1 la implementa con `CobrosFake`. B1 la reemplaza por la implementación REST contra `POST /api/v1/pagos`; no conviven dos implementaciones inyectables.

## 6. API pública de Ventas (A1)

El WAR de Ventas cuelga de `/api/v1` y sirve dos recursos: `ventas` y `entradas`. Todas las operaciones llevan `@RolesAllowed("user")`. Un recurso que no existe o es de otro usuario responde 404, para no revelar que existe.

| Operación | Éxito | Errores: código y `type` |
|---|---|---|
| `POST /api/v1/ventas` con `Idempotency-Key` | 201, `Location` y la Venta en `EN_CHECKOUT` | 400 `datos-invalidos` (incluye clave ausente o que no es UUID); 401; 403 `identidad-no-verificada`; 409 `cupo-agotado`, `evento-finalizado` (el evento ya empezó) o `clave-reutilizada` (la clave ya abrió otra compra); 422 `maximo-de-entradas`, `dni-repetido`, `titulares-no-coinciden` o `sector-inexistente`; 504 `sin-respuesta` |
| `GET /api/v1/ventas` | 200 con la lista de las Ventas de quien hace el pedido; vacía si no compró nada | 401; 403 con otro rol |
| `GET /api/v1/ventas/{ventaId}` | 200 con la Venta | 404 |
| `DELETE /api/v1/ventas/{ventaId}` | 204; repetirlo también da 204 | 404; 409 `venta-ya-paga`; 409 `operacion-en-curso` (hay un pago en curso, o uno anterior quedó sin resultado) |
| `POST /api/v1/ventas/{ventaId}/pagos` con `Idempotency-Key` | 201, `Location` y la Venta en `CONFIRMADA` | 400; 402 `pago-rechazado`; 404; 409 `reserva-vencida`, `venta-ya-paga` u `operacion-en-curso`; 504 `sin-respuesta` |
| `GET /api/v1/entradas/{entradaId}` | 200 con la Entrada | 404 |

Después de un 504 del pago, la venta queda marcada con ese intento sin resultado: solo se puede reintentar el pago con la misma `Idempotency-Key`. Otra clave, o cancelarla, responde 409 `operacion-en-curso`.

Si A1 necesita otro `type`, lo agrega y lo lista en su nota.

**Quién puede comprar.** Una cuenta con `identidadVerificada` en verdadero, y Usuarios la marca así cuando la cuenta completa el registro (verifica el email): alcanza con registrarse, sin un paso aparte (decisión del 14/10).

**Listado de las compras propias** (agregado el 13/10, en la integración). `GET /api/v1/ventas` devuelve las ventas del usuario del token, de la más nueva a la más vieja, cada una con el mismo formato que `GET /ventas/{ventaId}` y con sus entradas adentro. De quién son lo dice el claim `sub` del token: la operación no recibe ningún id de usuario, así que nadie puede pedir las de otro. Vienen en todos los estados, también `CANCELADA` y `VENCIDA`; el cliente filtra las que quiere mostrar. No tiene paginación ni filtros. Lo usa "Mis entradas" del frontend, para no depender de lo que recuerda el navegador.

**Idempotencia.** Repetir la clave en `POST /ventas` con el mismo pedido devuelve la misma venta sin reservar otra vez. Repetirla en el pago devuelve la venta ya confirmada. Se devuelve el estado actual del recurso, no una copia guardada de la primera respuesta.

**Los 409 agregados el 14/10.**

- `POST /ventas` con una clave que ya abrió una compra con otro evento u otros renglones (sector y cantidad) responde 409 `clave-reutilizada`, "Esa clave ya se usó para otra compra". Los titulares no se comparan: no se guardan en la base.
- `POST /ventas` sobre un evento que ya empezó responde 409 `evento-finalizado`, "Ese evento ya empezó o terminó". Lo decide Eventos al reservar (también 409, con ese `type`) y Ventas lo traduce para el comprador; devolver cupo sigue andando aunque el evento haya empezado.
- `POST /ventas/{ventaId}/pagos` sobre una compra vigente que perdió su instancia (redespliegue, o el servidor la descartó) sigue respondiendo 409 `reserva-vencida`, "Empezá la compra de nuevo", pero ahora antes de responder la venta pasa a `CANCELADA` y su cupo vuelve a Eventos. Si un pedido simultáneo ya la había cerrado, el cupo no se devuelve dos veces.

**Venta**

```json
{
  "id": "V-3f9a1c2b",
  "estado": "EN_CHECKOUT",
  "facturaEstado": "NO_APLICA",
  "eventoId": "ev-1",
  "items": [ { "sectorId": "sec-1-4", "sector": "Platea B", "cantidad": 2, "precioUnitario": 27500 } ],
  "subtotal": 55000,
  "cargoServicio": 5500,
  "montoTotal": 60500,
  "creada": "2026-10-19T21:00:00Z",
  "venceReserva": "2026-10-19T21:10:00Z",
  "pagoId": null,
  "entradas": [],
  "_links": {
    "self": { "href": "/api/v1/ventas/V-3f9a1c2b", "method": "GET" },
    "pagar": { "href": "/api/v1/ventas/V-3f9a1c2b/pagos", "method": "POST" },
    "cancelar": { "href": "/api/v1/ventas/V-3f9a1c2b", "method": "DELETE" }
  }
}
```

- `creada` es el instante en que se abrió la compra, en UTC (agregado el 13/10).
- `entradas` está vacío hasta el pago. Después trae `{id, sectorId, sector, titular: {nombre, dni}, precioPagado, estado, codigoQr, _links: {self}}` por cada una. `sectorId`, `precioPagado` y `codigoQr` se agregaron el 13/10 para que el cliente no tenga que pedir cada entrada por separado; valen lo mismo que en la Entrada completa, y `codigoQr` es `null` hasta la emisión.
- `_links`: `self` siempre; `pagar` y `cancelar` solo en `EN_CHECKOUT`. Los `href` son rutas sin host, armadas a partir del pedido.
- El titular lleva un solo `nombre`, con nombre y apellido, como en la OpenAPI y en el mensaje.

**Entrada:** `{id, ventaId, eventoId, sectorId, sector, titular: {nombre, dni}, precioPagado, estado, codigoQr, emitida, _links: {self}}`.

- `precioPagado` es el precio del sector, sin el cargo de servicio. Es la base del tope de reventa.
- `codigoQr` y `emitida` son `null` mientras la entrada está en `PENDIENTE_EMISION`.
- `codigoQr` es un texto opaco, único y no adivinable, de hasta 200 caracteres. B3 define si además va firmado.

## 7. Modelo de datos

### 7.1 Reglas comunes

- **Ids (propuesta P5).** `String` asignado en código, columna `varchar(40)`: prefijo, guion y 8 caracteres de un UUID, como ya hacen Eventos y Usuarios. `V-` para ventas, `T-` para entradas, `P-` para pagos y, en C2, `R-` para publicaciones. Los ids de Eventos y Usuarios no se renombran. Un id desconocido responde 404. Los `V-00231` y `T-000981` de los documentos eran ejemplos.
- **Fechas.** Columna `timestamp`, `LocalDateTime` en UTC truncado a segundos, publicado como instante con `Z`. El "ahora" sale de un `java.time.Clock` (por defecto `Clock.systemUTC()`) y viaja siempre como parámetro. No se usa `LocalDateTime.now()` a secas, `current_timestamp` ni `default current_timestamp`: el servidor corre en hora argentina. `fechaISO` de Eventos no cambia.
- **Dinero.** `long` en Java y `bigint` en la base.
- **Estados.** Texto en mayúsculas. Los valores válidos son los de la sección 7.3 y los controla el enumerado de Java. La base no lleva `check` de lista de valores: en este servidor se rompen (paso T6).
- **Migraciones.** El SQL de la V1 se cierra antes del primer despliegue.
- **Clave repetida.** Con el id asignado en código, el `INSERT` sale recién en el `flush`. El repositorio llama a `em.flush()` y traduce a `Conflicto` solo si `BaseDeDatos.esClaveDuplicada(e)`.

### 7.2 Esquemas y tablas

Cada servicio es dueño de su esquema y nadie lee tablas ajenas.

| Servicio | Esquema y unidad de persistencia | Tablas | Restricciones únicas |
|---|---|---|---|
| Ventas (A1) | `ventas` | `venta`, `venta_item`, `entrada` | `venta (comprador_id, clave_idempotencia)`; `entrada (venta_id, titular_dni)`; `entrada (codigo_qr)` |
| Notificaciones (A2) | `notificaciones` | `registro_auditoria` | Clave primaria `mensaje_id` (el `JMSMessageID`) |
| Pagos (A3) | `pagos` | `pago` | `clave_idempotencia`; `id_externo` |
| Eventos (T3) | `eventos` | Sin tablas nuevas; la V3 solo cambia fechas | |

Columnas de `venta` que otras tareas dan por ciertas: `estado`, `factura_estado`, `vence_reserva`, `clave_idempotencia`, `clave_pago`, `pago_id`. El token de la tarjeta no se guarda en ninguna tabla.

### 7.3 Estados

| Dato | Valores | Se usan para el 23/10 |
|---|---|---|
| `Venta.estado` | `EN_CHECKOUT`, `CONFIRMADA`, `EMITIDA`, `CANCELADA`, `VENCIDA` | Todos |
| `Venta.facturaEstado` | `NO_APLICA`, `AUTORIZADA`, `PENDIENTE`, `RECHAZADA` | Solo `NO_APLICA`, hasta C1 |
| `Entrada.estado` | `PENDIENTE_EMISION`, `VALIDA`, `USADA`, `PUBLICADA`, `VENDIDA`, `ANULADA` | Los dos primeros |
| `Pago.estado` | `APROBADO`, `RECHAZADO`, `PENDIENTE`, `VENCIDO`, `REEMBOLSADO` | Los dos primeros |

**Transiciones de la venta.** Todas son un `UPDATE … where estado = 'EN_CHECKOUT'` (o `'CONFIRMADA'` para la emisión). Solo sigue quien modificó una fila.

| De → a | Quién | Cupo |
|---|---|---|
| nueva → `EN_CHECKOUT` | `iniciarCompra` | Reserva en Eventos |
| `EN_CHECKOUT` → `CONFIRMADA` | `confirmarCompra`, en la transacción que crea las entradas y publica | Queda vendido |
| `CONFIRMADA` → `EMITIDA` | `EmisionDeEntradasMDB` | Sin cambio |
| `EN_CHECKOUT` → `CANCELADA` | `cancelarCompra` | Lo libera después de su commit |
| `EN_CHECKOUT` → `VENCIDA` | `VencimientoDeReservas` | Lo libera después de su commit |

**Reglas de Ventas que cierran contradicciones entre los borradores y su revisión**

1. **El componente con estado (propuesta P1).** `ServicioDeVentas` es `@Stateful`, una instancia por compra. `ComprasEnCurso` (`@ApplicationScoped`) la crea por JNDI con `java:module/ServicioDeVentas!com.palco.ventas.negocio.IVentas`, guarda la referencia por `ventaId` y comprueba que el `sub` del token sea el comprador. La instancia guarda lo que todavía no está en la base: los titulares. `IVentas` tiene tres operaciones: `iniciarCompra`, `confirmarCompra` y `cancelarCompra`.
2. **Un solo reloj (propuesta P2).** La reserva vence por `vence_reserva`, en la base. `com.palco.ventas.negocio.VencimientoDeReservas`, un `@Singleton` con `@Schedule(persistent = false)`, corre cada minuto, pasa a `VENCIDA` las ventas `EN_CHECKOUT` vencidas y devuelve su cupo. `@StatefulTimeout(MINUTOS_RESERVA)` y `@Remove` siguen: son el ciclo de vida que administra el servidor. `@PreDestroy` solo escribe en el log y quita la referencia; no escribe en la base ni llama por REST.
3. **Pagar tarde o sin instancia.** Si la reserva venció o la instancia se perdió (por ejemplo, tras un redespliegue), el pago responde 409 `reserva-vencida`. Si la reserva todavía estaba vigente pero la instancia se perdió, antes de responder Ventas cancela la venta con su `UPDATE` condicional y, si la fila cambió, devuelve el cupo a Eventos. Si la reserva ya venció, el cupo lo libera el barrido. Si la venta tiene `clave_pago` (un intento anterior sin resultado), responde 409 `operacion-en-curso`, salvo el reintento con la misma clave mientras viva la instancia.
4. **Las entradas nacen con el pago (propuesta P4).** `confirmarCompra` las crea en `PENDIENTE_EMISION`, con su id, porque el mensaje ya lleva `entradaId` y el `GET` de la entrada tiene que responder apenas vuelve el 201. El MDB las emite.
5. **`clave_pago`.** `ComprasEnCurso` comprueba que la reserva siga vigente y que la instancia exista; recién ahí guarda la clave, en su propia transacción y antes de cobrar. La borra si el cobro fue rechazado. Una venta `EN_CHECKOUT` con `clave_pago` es un intento de cobro sin resultado conocido: el barrido no la vence ni libera su cupo, y la concilia C3.
6. **Solo excepciones de negocio.** El bean lanza únicamente excepciones de `ErroresDeNegocio`. Con cualquier otra, el servidor destruye la instancia y el comprador no puede reintentar tras un 402.
7. **Llamadas simultáneas** sobre la misma compra: 409 `operacion-en-curso`.

### 7.4 Capas y paquetes

| Paquete | Qué va | Clases de la Ola 1 |
|---|---|---|
| `api/` | Recurso JAX-RS y DTO | `VentasResource`, `EntradasResource`, `PagosResource`, `NotificacionesResource` |
| `negocio/` | Interfaz `I<Servicio>`, Session Bean y `Migraciones<Servicio>` | `IVentas`, `ServicioDeVentas`, `ComprasEnCurso`, `VencimientoDeReservas`; `IPagos`, `ServicioDePagos`; `INotificaciones`, `IAuditoria` |
| `negocio/externos/` | Clientes de otros servicios (Ventas) | `ClienteDeEventos`, `ClienteDeUsuarios`, `Cobros`, `CobrosFake` |
| `datos/` | Repository: interfaz más implementación `…Jpa` (`@Stateless`, `@Local`) | `VentaRepository`, `EntradaRepository`; `PagoRepository` |
| `dominio/` | Entidades JPA y enumerados | `Venta`, `VentaItem`, `Entrada`; `Pago`; `RegistroDeAuditoria` |
| `mensajeria/` | MDB | Los tres de la sección 4.4 |
| `pasarela/` | Salida hacia la pasarela externa (Pagos) | `PasarelaDePagos`, `PagosFake`, `FabricaDePasarela` |

- `datos/` usa los nombres de la Entrega Parcial 1 (`VentaRepository`, `EntradaRepository`). No existe `ArchivoDeVentas`. Notificaciones puede usar `EntityManager` directo en `ServicioDeAuditoria`, como Eventos y Usuarios hoy; B4 unifica.
- **Pagos (propuesta P9).** `IPagos` es la interfaz del componente y la implementa `ServicioDePagos`. Para el 23/10 declara `procesarPago` y `obtener`; `confirmarPago` y `reembolsar` se declaran cuando se implementen. `PasarelaDePagos` es la interfaz propia hacia el exterior. `MercadoPagoPagos` va a ser un Adapter (traduce la API de Mercado Pago a `PasarelaDePagos`), `PagosFake` es una implementación simulada de esa interfaz y `FabricaDePasarela` es la Factory que elige cuál se crea.
- Pagos no define excepción ni manejador propio para el 504: usa `ErroresDeNegocio.SinRespuesta`.

## 8. Alineación con la OpenAPI y con Postman

### 8.1 Rutas después de la Ola 1 (propuesta P7)

En WildFly un WAR tiene un único context root y, según el código del servidor, se elige el más largo que coincide (falta probarlo: sección 12). Los servicios nuevos nacen versionados; lo que ya funciona no se toca.

| Servicio | Context root | Health |
|---|---|---|
| Ventas | `/api/v1` (antes `/api/ventas`) | `/api/v1/health`; informa servicio `v1` |
| Pagos | `/api/v1/pagos` (antes `/api/pagos`) | `/api/v1/pagos/health` |
| Notificaciones, Usuarios, Eventos, Facturación | `/api/<servicio>`, sin cambios | `/api/<servicio>/health` |
| Validación | `/api/validacion`; pasa a `/api/v1/validacion` en B3 | |
| Reventa | `/api/reventa`; pasa a `/api/v1/reventas` en C2 | |

Ventas no define recursos llamados `pagos`, `validacion` ni `reventas` en su raíz. Si el anidado fallara, el que vuelve a su ruta vieja es Pagos; Ventas se queda en `/api/v1`.

### 8.2 Qué se adopta de `openapi.yaml` en la Ola 1

- `POST /ventas` y `POST /ventas/{ventaId}/pagos` tal como están: compra en dos llamadas, con los esquemas `NuevaVenta`, `NuevoPago`, `Titular` e `ItemVenta`.
- `Idempotency-Key` obligatorio y con efecto real.
- Errores `application/problem+json` con el esquema `Problema`, en todos los servicios.
- El esquema `Venta` con sus estados, `facturaEstado` y `_links`.
- El prefijo `/api/v1` en Ventas.

### 8.3 Desvíos y postergaciones

`openapi.yaml` no se toca en la Ola 1. C4 le suma lo que falta, a mano, porque el grupo lo presentó como contrato escrito antes que el código.

| Tema | Qué se hace en la Ola 1 | Por qué | Se completa en |
|---|---|---|---|
| `GET` y `DELETE /ventas/{id}`, `GET /entradas/{id}` | Se implementan aunque no figuran en `paths` | Están en `_links` y en el catálogo de la clase 10; los necesitan Postman y B2 | C4 los agrega al YAML |
| Campos extra en `Venta` | `eventoId`, `items`, `subtotal`, `cargoServicio`, `pagoId`, `entradas` | Sin `entradas` el cliente no conoce los ids; agregar campos no rompe la versión | C4 |
| Factura dentro del pago | No se factura; `facturaEstado` queda en `NO_APLICA` | La consigna pide SOAP para la entrega obligatoria del 13/11 | C1 |
| 202 y 504 del pago | 504 sí; 202 no | No hay estado de venta para un pago pendiente | Ola 3, con el webhook |
| Respuesta guardada por clave | Se devuelve el estado actual | Dos columnas en lugar de una tabla de respuestas | Se documenta como diferencia |
| `GET` del `Location` del pago | No existe | Ningún cliente lo usa todavía | C4 |
| Entrada: "solo el titular" y ETag | Solo el comprador; sin ETag | El titular nominativo no siempre es un usuario | C4 |
| `/sesiones` en `bearerAuth` | El login sigue en `POST /api/usuarios/login` | `/sesiones` no es una ruta del contrato | C4 corrige la frase |
| `/eventos/{slug}` con ETag y bajo `/api/v1` | Sin cambios en Eventos | Funciona y no es módulo de la Ola 1 | C4 |
| `fechaISO` con zona | Sin cambios | Cambiarla rompe el alta de eventos y el frontend | C4 ajusta el YAML |
| `/reventas` | Nada | Es de la Ola 3 | C2 |
| Estado `EMITIDA` | Es de la venta; la entrada pasa a `VALIDA` | El diagrama de la clase 10 lo ponía en la entrada | A4 lo anota |

### 8.4 Colección de Postman

Carpeta "3 Diseño v1":

| Request | Tiene que pasar | Depende de |
|---|---|---|
| 1 `POST /v1/ventas` (201) | Al terminar la Ola 1 | A1, T2, T4 |
| 2 `POST /v1/ventas/{id}/pagos` (201) | Al terminar la Ola 1 | A1, A2, T4 |
| 3 `GET /v1/entradas/{id}` (200) | Al terminar la Ola 1 | A1, T4 |
| 7 `POST /v1/validacion/escaneos` (200) | Ola 2, si llega B3 | B3 |
| 4, 5 y 6, reventas | Ola 3 | C2 |

Las carpetas 1 y 2 no cambian de resultado por la Ola 1: sus tests miran solo el código de estado. Eso no quiere decir que hoy pasen todos. En una corrida del Runner fallan siete de sus 17 requests, por cómo está armada la colección (la cuenta, el token, un código fijo, la espera entre pedidos) y no por el backend. Sale de leer el JSON de la colección y `ServicioDeUsuarios`; la corrida completa no se ejecutó.

**Cambios de T4.** Sin ellos no pueden pasar esos siete ni los requests 2 y 3 de la carpeta 3, aunque el backend esté bien.

- Variables nuevas: `ventaId`, `entradaId`, `tokenServicio`, `tokenOrganizador`, `tokenStaff`.
- Primer request de la carpeta 3: "Login comprador", con `titular@palco.test` y `palco1234`, que guarda `token`. La cuenta `ana@test.com` no sirve: la registra la carpeta 1 y nadie verifica su email, así que el login responde 409. Usuarios marca la identidad verificada recién al verificar el email (sección 6, "Quién puede comprar").
- Request 1: compra `sec-1-4` en lugar de `sec-1-1` (2 × 27.500 más 10 % da 60.500, el total del ejemplo) y guarda `ventaId` con `pm.response.json().id`.
- Request 2: URL con `{{ventaId}}`; guarda `entradaId` con `pm.response.json().entradas[0].id`.
- Request 3: URL con `{{entradaId}}`.
- Requests 4 a 7: a una subcarpeta aparte, fuera de la corrida de la Ola 1. Cuando se usen, llevan variables en lugar de `T-000981`, `R-0012` y `QR-ABC123`, y el 7 usa `tokenStaff`.
- Request 5: cambiar la aserción por `pm.expect(pm.response.headers.get('Content-Type')).to.include('application/problem+json')`. Según el código de la librería de aserciones de Postman, la actual compara el valor de la cabecera con una expresión regular por igualdad y no puede pasar.
- Carpeta 1, "Login (200) guarda token": usa `titular@palco.test` y `palco1234`. Hoy usa `ana@test.com`, que se acaba de registrar y, sin verificar el email, responde 409. Con ese token pasa también "Mi perfil (200)". Comprobado hoy en el servidor: ese login y `GET /api/usuarios/yo` con su token responden 200.
- Carpeta 1, "Verificar email (200)" y "Reenviar codigo (204)": a una subcarpeta de pasos manuales, fuera de la corrida, igual que los requests 4 a 7. El primero manda el código fijo `123456`, y el real es aleatorio y solo sale en el log del servidor. El segundo responde 409 si pasaron menos de 30 segundos desde el registro. Si alguien verifica `ana@test.com` a mano, el "Registro (201)" de la corrida siguiente da 400 hasta reiniciar WildFly, que vacía la base.
- Carpeta 2: "Crear evento organizer" y "Crear evento invalido" usan `tokenOrganizador` (login con `organizador@palco.test`). El inválido también lo necesita, porque el rol se controla antes que el cuerpo: comprobado hoy en el servidor, con el token de un comprador responde 403 y con el de un organizador, 400. "Cambiar cupo, rol servicio" usa `tokenServicio`, que sale de un request nuevo a `POST /api/usuarios/servicios/token`, con `eventoId` `ev-1` y `sectorId` `sec-1-4`.
- `GUIA_POSTMAN.md`: anotar las cuentas por rol, el orden de corrida y los pasos manuales.

## 9. Qué toma A4 de este archivo

- **Nombres.** Los de las secciones 4 a 7. En particular: "adaptadores detrás de `PasarelaDePagos`", no detrás de `IPagos`; `POST /api/v1/pagos`, no `/cobros`.
- **Estado.** "Implementado" o "Diseñado, pendiente", mirando el código y el servidor el miércoles 14/10, el día en que se crea el tag `entrega-parcial-2` (calendario de `docs/PLAN_AGENTES.md`). Quedan "Diseñado, pendiente": factura SOAP, Mercado Pago, webhook, pago pendiente, reembolso, email real, reventa y, si B3 no llega, validación.
- **Proceso asincrónico.** Dos procesos disparados por el mismo evento: "Emisión de entradas" (cola) y "Aviso y auditoría de la compra" (tópico).
- **Diagrama.** El consumidor de la cola va dentro de Ventas y Auditoría dentro de Notificaciones. Se suma la flecha Ventas → Facturación, punteada.
- **Evidencia.** No espera a B1. Apenas A1 llega al MDB de emisión se guarda la primera: contadores de la cola y del tópico antes y después, las líneas `INFO` de los tres MDB, la fila de `GET /api/notificaciones/auditoria` y la entrada en `VALIDA`. Si B1 llega, se reemplaza.
- **Postman.** La captura del Runner incluye la carpeta 1 sin los dos pasos manuales, la carpeta 2 completa y, de la carpeta 3, "Login comprador" y los requests 1 a 3. Quedan afuera "Verificar email", "Reenviar codigo" y los requests 4 a 7. El plan de A4 dice "carpetas 1 y 2 y requests 1 a 3": vale esta lista.
- **Diferencias con documentos anteriores** que A4 lista: `IVentas` con tres operaciones; `INotificaciones` con una sola implementada e `IAuditoria` aparte; `IPagos` más `PasarelaDePagos`; ids sin numeración corrida; `/api/v1` solo en los servicios nuevos; los desvíos de la sección 8.3; la garantía de la sección 4.7 en lugar de "XA".

## 10. Orden de implementación y de commit

`PLAN_AGENTES.md` dice A2 → A3 → A1 → A4. Se cambia, porque el camino crítico del 23/10 no pasa por A3 y porque T1 y T2 tienen que existir antes que todo.

```
T0 → T1 → T2 → A2 → A1 hasta el MDB de emisión → primera evidencia (A4) → resto de A1
               └── A3 (cobro con PagosFake), en paralelo; no bloquea el 23/10
T3 y T4: antes de la captura del Runner y de la demo
T5: al cerrar la Ola 1
```

| Condición | Motivo |
|---|---|
| T1 antes que T2 | `TokenDeServicio` lanza `SinRespuesta` |
| T2 antes de dar por aceptada A2 | Los endpoints de prueba exigen rol `servicio`. A2 no se da por aceptada sin haber visto llegar un mensaje |
| T2 antes de que A1 reserve cupo | El endpoint de Eventos exige rol `servicio` |
| A2 antes de que A1 confirme pagos | `confirmarCompra` usa el publicador |
| Después de cada cambio en `common` | Reconstruir y redesplegar los 8 WAR y comprobar los 8 `health` |

Calendario: vale el de `docs/PLAN_AGENTES.md`. El que se proponía acá, día por día del 10/10 al 16/10, apuntaba al lunes 19/10, la fecha que da la consigna para la comisión del lunes, y se quitó.

**Archivos con un solo dueño**

| Archivo | Lo toca |
|---|---|
| `backend/pom.xml` | Solo T0 |
| `backend/common`: `ErroresDeNegocio`, `rest/`, `datos/BaseDeDatos` | Solo T1 |
| `frontend/src/lib/api.ts` | Solo T1 en la Ola 1. B2 lo retoma en la Ola 2 |
| `backend/common/…/seguridad/TokenDeServicio.java`; `backend/servicios/usuarios` | Solo T2 |
| `backend/common/…/mensajeria/`; `backend/common/pom.xml`; `configurar.cli`; `backend/servicios/notificaciones`; el contrato del mensaje | Solo A2 |
| `backend/servicios/ventas` | Solo A1 |
| `backend/servicios/pagos` | Solo A3 |
| `backend/servicios/eventos`, `frontend/src/data/events.ts` | Solo T3 |
| `docs/notas/ventas-core.md`, `mensajeria.md`, `pagos.md`, `docs-entrega3.md` | Cada tarea la suya, con el mismo formato |
| `README.md`, `CLAUDE.md`, `TODO.md`, `backend/.env.example`, `docs/api` | Solo Santiago (T4 y T5). Las tareas piden el cambio; no lo hacen |

**Para verificar, en las tres tareas**

- `JAVA_HOME` apuntando a `C:\Users\santi\.jdks\temurin-25`. Un Maven por vez. Nunca `mvn clean` con WildFly levantado.
- Después de redesplegar, esperar al health del servicio (para Ventas: `until curl -sf localhost:8080/api/v1/health >/dev/null; do sleep 2; done`) y revisar que no haya archivos `.failed` en `standalone/deployments`.
- `jboss-cli.bat` con `NOPAUSE=true`; sin eso queda esperando una tecla.
- Los valores se comprueban por diferencia (antes y después). Los ids salen del `Location` o del cuerpo, no se escriben fijos. Cada clave de idempotencia se genera: `node -e "console.log(crypto.randomUUID())"`.
- Redesplegar Usuarios cambia la clave JWT: hay que volver a iniciar sesión. Reiniciar WildFly vacía la base y no los mensajes: antes de una demo, `remove-messages` sobre `cola.emision-entradas` y `DLQ`.

## 11. Decisiones pendientes de aprobación

El grupo acordó implementar con la opción recomendada. Quien defiende cada parte la lee acá; si no le cierra, se cambia primero este archivo.

| N.º | Decisión | Alternativas | Recomendada | Por qué |
|---|---|---|---|---|
| P1 | Cómo se ata el componente `@Stateful` al usuario | (a) Sesión HTTP con `@SessionScoped`. (b) Índice por `ventaId`, validado con el `sub` del token. (c) Sin stateful | (b), con `IVentas` de tres operaciones | La identidad sigue en un solo mecanismo (el token). La cookie de sesión solo vale dentro de un WAR. La reserva temporal es el ejemplo de stateful que da la consigna |
| P2 | Quién vence la reserva | (a) El `@PreDestroy` del stateful escribe en la base y llama a Eventos. (b) Un barrido con `@Schedule` sobre `vence_reserva` | (b) | La especificación avisa que `@PreDestroy` puede no ejecutarse y recomienda una limpieza periódica. Queda un solo reloj |
| P3 | Garantía entre la venta y el mensaje | (a) Base sin XA, como último recurso, y consumidores idempotentes. (b) Base XA. (c) Tabla de salida con un proceso que publica | (a) | La consigna pide transacciones declarativas y explicar las fallas, no dos fases. (b) no está verificado contra Supabase |
| P4 | Dónde se crean las entradas | (a) En la transacción del pago; el MDB las emite. (b) Las crea el MDB, como dice la guía de la tarea | (a) | El mensaje ya lleva `entradaId`, y el `GET` de la entrada tiene que responder apenas vuelve el pago |
| P5 | Formato de ids | (a) Texto con prefijo y 8 caracteres de UUID. (b) Número de la base mostrado como `V-00001`. (c) Numeración que calce con los ejemplos | (a) | Es la convención que ya funciona en el repo. (b) suma un mapeo sin probar, y la numeración vuelve a 1 en cada reinicio mientras los mensajes viejos siguen guardados |
| P6 | Cómo se obtiene el token de servicio | (a) Endpoint en Usuarios con secreto compartido. (b) Cuenta de servicio que usa el login. (c) Firmar a mano con una clave fija | (a) | Se explica en una frase. (b) obliga a cambiar el `check` de roles, que no tiene nombre, y a crear un usuario con DNI y contraseña para algo que no es una persona |
| P7 | Cómo se versiona la URL | (a) Context root `/api/v1` en los servicios nuevos; Usuarios y Eventos en C4. (b) Reescritura en el servidor. (c) Esperar a C4. (d) Un WAR que haga de puerta de entrada | (a) | Cambia una línea por servicio y no toca lo que funciona. Con (c) los requests de la carpeta 3 dan 404 |
| P8 | Problem Details en `common` ahora | (a) T1, ahora. (b) Excepciones propias en Ventas. (c) Esperar a C4 | (a) | Con (b) conviven dos formatos y Pagos suma un tercero. Rompe la regla "`common` solo lo toca A2", por eso es un paso aparte |
| P9 | Interfaz y patrón en Pagos | (a) `IPagos` es el componente y `PasarelaDePagos` tiene las dos implementaciones. (b) Las dos implementan `IPagos`, como dice la guía de la tarea | (a), contada como Adapter más Factory | Guardar y deduplicar se escribe una sola vez. Coincide con la Entrega Parcial 1 |
| P10 | Qué hace A3 antes del 23/10 | (a) Solo el cobro con `PagosFake`; webhook, reembolso y `MercadoPagoPagos` después. (b) Todo lo que lista la guía de la tarea, ahora | (a) | Esas piezas no responden a ningún punto del 23/10, y la firma del webhook y los campos de Mercado Pago no se pudieron verificar |
| P11 | Quién defiende los pasos transversales | T1 Sebastian, T2 Julian y el resto Santiago, u otro reparto | El de la sección 3 | T1 es el paso previo de Ventas que el grupo anunció en la clase 10. T2 adelanta una parte de B1 |
| P12 | Alcance del documento del 23/10 | (a) Solo lo que pide la consigna. (b) También anexos funcional, manual y Figma | (a), salvo que la cátedra los haya pedido por otro canal | La consigna no los nombra y ocupan cuatro días de trabajo de A4. Queda que el grupo confirme si la cátedra los pidió |

## 12. Riesgos y dudas abiertas

Nada de esta lista se ejecutó en este servidor. Cada punto dice quién lo comprueba y qué se hace si falla.

| Qué no está probado | Quién lo comprueba | Si falla |
|---|---|---|
| Que un token con `groups` = `["servicio"]` pase `@RolesAllowed("servicio")`. Nunca existió un token así | T2, con el `curl` de cupo en 0 | Revisar los claims contra los de `sesion()` |
| Que `/api/v1` y `/api/v1/pagos` convivan. Sale de leer el código del servidor | A1 y A3, con los dos `health` | Pagos vuelve a `/api/pagos` |
| Que el cuerpo `application/problem+json` salga por `JsonProvider` | T1, con `curl -i` | El manejador convierte el cuerpo a `String` |
| Que el frontend siga mostrando los errores con el formato nuevo. Lo que se rompe sin el ajuste sale de leer `api.ts` y las pantallas | T1, registrando un email que ya existe | Revisar cómo `api.ts` lee `detail` y `errores` |
| Que un `@Stateless` dentro del jar de `common` se despliegue en los 8 WAR | A2, con los 8 `health` | El publicador pasa a ser un bean CDI con `@Transactional(MANDATORY)` |
| Que el mensaje no salga si la transacción se deshace | A2, con el endpoint con falla. A1, con una excepción forzada después de `publicar`, una vez y a mano | Revisar que se use `java:/JmsXA` |
| El comportamiento ante una caída entre los dos commits | Nadie, por ahora | Se describe como "no probado" |
| Crear la suscripción durable: el rol `guest` del broker no tiene ese permiso, aunque las conexiones internas lo saltean | A2 | Una línea más en `configurar.cli` que da el permiso |
| `stop-delivery`, `start-delivery` y la reentrega cada 2 segundos hasta la DLQ | A2 | Ajustar la demo |
| Que las dos líneas de la sección 4.6 funcionen dentro de `configurar.cli` | A2, con un armado limpio | Aplicarlas con `jboss-cli` y documentarlo |
| Mockito 5.24.0 con Java 25 y JUnit 6.1.3 | A1, con un primer test mínimo | Dobles escritos a mano; las dependencias de A1 son interfaces |
| MicroProfile Config dentro del WAR de Pagos | A3 | `System.getenv`, como el resto del repo |
| El ciclo de vida del stateful: instancia nueva por búsqueda JNDI, timeout por inactividad, `@Remove`. Leído en el código del servidor | A1, mirando el log | Revisar la propuesta P1 |
| `@Schedule(persistent = false)` en el WAR de Ventas | A1, esperando 11 minutos una vez | Revisar la propuesta P2 |
| Las migraciones contra PostgreSQL de Supabase | Nadie, por ahora | Se corrige el SQL cuando se conecte Supabase |
| Que editar una V1 ya aplicada trabe el redespliegue hasta reiniciar | Nadie | Cerrar el SQL antes del primer despliegue |
| La colección de Postman: el análisis sale de leer el JSON, no de correrla. Tampoco se probó que el Runner deje afuera las subcarpetas de pasos manuales | Santiago, en T4 | Ajustar la lista de la sección 8.4. Si el Runner las corre, se desmarcan antes de sacar la captura |

Queda para que lo confirme el grupo: T1 toca `frontend/src/lib/api.ts`, que `PLAN_AGENTES.md` asigna a B2 (Valentina, Ola 2). Va en T1 para que el formato nuevo y su lectura entren en el mismo paso. Si el grupo prefiere otro reparto, se cambia la propuesta P11.

Límites que se conocen y se aceptan para el 23/10:

- La base admite 10 conexiones para los 8 WAR, y una compra retiene una mientras llama a otro servicio: unas 5 compras simultáneas.
- Puede quedar cupo tomado sin dueño si el servidor se cae entre la reserva y el commit, o si Eventos no responde al liberar. Queda en el log; la compensación completa es de C3.
- Una venta con `clave_pago` y sin resultado queda `EN_CHECKOUT` hasta la conciliación de C3. Con los simuladores solo pasa con `tok_sin_respuesta` o con una caída.
- El estado de las compras en curso vive en la memoria de un servidor: un redespliegue de Ventas las corta.
- El health de Ventas informa `v1` en lugar de `ventas`.
- El DNI del titular viaja completo en el tópico y queda en la auditoría. La decisión es de la Fase 12 del `TODO.md`.
- Una sola máquina con unos 2 GB libres y el repo dentro de OneDrive: conviene pausar la sincronización mientras corren WildFly o Maven.
- La consigna mira la participación visible de cada integrante en git en la Entrega Final, no el 23/10.
