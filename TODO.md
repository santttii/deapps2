# Palco — TODO

Lista de trabajo para pasar de la demo solo frontend a un sistema real con
backend. Está ordenada por fases: cada fase depende, en general, de las
anteriores. Las decisiones marcadas como **(a confirmar)** son propuestas por
defecto; si se elige otra cosa, el resto de la lista sigue valiendo.

Convención: `[ ]` pendiente · `[x]` hecho · `[~]` en curso.

**Puesta al día del 23/10/2026.** Se revisó cada fase contra el código y contra
`docs/documentacion/Palco_Documentacion_Completa.md`, que es la fuente del
detalle; acá queda el estado de cada tarea. Los 8 componentes están
implementados y probados en el servidor: 761 tests del backend y 21 escenarios
de integración (`scripts/pruebas/escenarios.mjs`; el 21, del dueño de cada
evento y de la puerta asignada, todavía no se corrió en el servidor). Lo que falta para las
entregas está en la sección 22 del documento maestro y, resumido, en
[Entregas de la materia](#entregas-de-la-materia).

## Contexto (documentos de la materia)

Desarrollo de Aplicaciones II. Grupo: Deya, Frisoli, Loto, Molina, Zuchowicki.
La cátedra aceptó el grupo de cinco el 16/10.

- **Entrega Parcial N.º 1 — Arquitectura general** (exposición 18/09):
  arquitectura en tres capas, 8 componentes de negocio con una interfaz cada
  uno, stack Jakarta EE sobre WildFly.
- **Taller — Arquitectura de integración**: bus de integración (ESB
  conceptual, sin producto dedicado) apoyado en el broker JMS y en clientes
  SOAP/REST detrás de interfaces propias (`IPagos`, `IFacturacion`).
  `ServicioDeUsuarios` es el servicio reutilizable (principio SOA).
- **Taller — Escenario de mensajería**: el evento `PAGO_CONFIRMADO` se publica
  en una cola (emisión de entradas) y en un tópico (notificaciones y
  auditoría). Ver [Fase 6](#fase-6--mensajería-jms-pago-confirmado).

### Stack definido

| Capa | Tecnología |
|---|---|
| Presentación | React (SPA) consumiendo la API REST — es el front de este repo |
| Negocio | Jakarta EE — EJB + CDI, Session Beans desplegados en WildFly |
| Datos | JPA + Hibernate, un repositorio por entidad |
| API REST propia | JAX-RS |
| Integración | JAX-WS (ARCA simulado, SOAP) · cliente JAX-RS (Pagos por REST; Mercado Pago quedó como diseño) · JMS (ActiveMQ Artemis) |

### Los 8 componentes

| Componente | Interfaz | Tipo | Responsabilidad |
|---|---|---|---|
| `ServicioDeUsuarios` | `IUsuarios` | Stateless | Registro, autenticación y roles (comprador, organizador, staff) |
| `ServicioDeEventos` | `IEventos` | Stateless | Catálogo de eventos, tipos de entrada y cupos |
| `ServicioDeVentas` | `IVentas` | **Stateful** | Orquesta el checkout de punta a punta y mantiene la compra en curso |
| `ServicioDePagos` | `IPagos` | Stateless | Cobra y reembolsa pagos contra la pasarela (simulada: `PagosFake`) |
| `ServicioDeValidación` | `IValidacion` | Stateless | Autenticidad y uso único de la entrada en puerta |
| `ServicioDeReventa` | `IReventa` | Stateless | Publicación y compra de reventa oficial con tope |
| `ServicioDeNotificaciones` | `INotificaciones` | Stateless + MDB | Aviso de compra y auditoría, asincrónicos por el tópico |
| `ServicioDeFacturación` | `IFacturacion` | Stateless + MDB | Factura electrónica contra ARCA (simulado, por SOAP) |

Reglas de diseño: una responsabilidad por componente, los componentes se
hablan solo por interfaz, nunca contra la implementación del otro.

---

## Fase 0 — Decisiones y setup

- [x] `ServicioDeEventos`: no hay código previo, se arranca de cero.
- [x] Versiones: **Java 25 (LTS)** y **WildFly 41** (Jakarta EE 11).
- [x] Base de datos: **Supabase** (PostgreSQL gestionado). Se conecta desde
      WildFly como datasource JDBC de PostgreSQL.
- [x] Sin Docker: se usan servicios web para la infraestructura.
- [~] Instalar JDK 25 y Maven en cada máquina del grupo (hecho en la de Julián).
- [ ] Crear el proyecto en Supabase y compartir la cadena de conexión
      (usar el *session pooler*, puerto 5432, que funciona por IPv4).
- [x] Envío de emails resuelto sin atarse a un proveedor (30/10): `Correos` en
      `common/email` con `CorreoSmtp` (Jakarta Mail, que ya trae la
      plataforma) y `CorreoAlLog`, y `FabricaDeCorreos` elige según
      `PALCO_SMTP_HOST`. Sin servidor configurado los emails quedan en el log
      con un WARNING; con él, salen de verdad.
- [ ] Crear la cuenta del proveedor y poner las variables `PALCO_SMTP_*`
      **(a confirmar)**: Mailtrap (atrapa los emails en una bandeja web,
      reemplaza a MailHog). Es lo único que falta para que los emails lleguen.
- [ ] Crear el bucket `eventos` en Supabase Storage (público) y poner
      `PALCO_SUPABASE_URL` y `PALCO_SUPABASE_CLAVE`: el código de la subida de
      imágenes está hecho y probado con dobles, pero no contra un bucket real.
- [x] Empaquetado: **8 WAR independientes (uno por servicio) en un solo
      WildFly**, más el WAR del ARCA simulado. Cada uno tiene su API y su
      esquema de base, y se comunican solo por REST, SOAP o JMS. Se pueden
      repartir en varios servidores más adelante sin cambiar código.
- [ ] Hostear el backend temprano (Render o Railway **a confirmar**) para que
      el grupo use una sola instancia y no tenga que correrlo en cada
      máquina. Requiere un `Dockerfile` que la plataforma construye en la
      nube a partir de `mvn package` (no hace falta Docker local).
- [x] Estructura del repo: `frontend/` (el front actual) y `backend/`
      (proyecto Maven).
- [x] Mover el front actual a `frontend/` sin romper `npm run dev` ni `build`.
- [x] Scaffold de `backend/`: `pom.xml` padre, `common` (lo compartido),
      `servicios/` (8 WAR y el ARCA simulado) y `servidor/` (arma WildFly y
      los despliega).
- [x] Healthcheck `GET /api/<servicio>/health` (incluye la base) y manejo
      centralizado de errores con Problem Details (`ExceptionMapper` de
      JAX-RS), todo en `common`.
- [x] JSON con JSON-B en todos los servicios (`JsonProvider` en `common`).
- [x] Filtro CORS configurable con `PALCO_CORS_ORIGENES`.
- [x] Pasar a Java las constantes de negocio de `src/types/index.ts`
      → `ReglasDeNegocio` en `common`, con tests. El servidor pasa a ser la
      fuente de verdad.
- [x] WildFly sin instalarlo a mano: el módulo `servidor` lo arma con
      `wildfly-maven-plugin` solo con las capas que usamos (`jaxrs-server`,
      `ejb`, `embedded-activemq`, `microprofile-jwt`, `webservices`, drivers
      H2 y PostgreSQL). `dev.sh` lo levanta y `redesplegar.sh` actualiza un
      servicio sin reiniciar.
- [x] Cola `cola.emision-entradas` y tópico `topico.compra-confirmada`
      creados por `servidor/src/main/wildfly/configurar.cli`.
- [x] Configuración de WildFly versionada (`configurar.cli`): datasource
      `PalcoDS` (H2 por defecto, PostgreSQL con variables `PALCO_DB_*`), JMS,
      reintentos y DLQ. Desde el 23/10 la base de desarrollo espera hasta 10
      segundos un bloqueo (`LOCK_TIMEOUT`): con el segundo que trae H2, una
      migración con `ALTER TABLE` podía fallar al desplegar los 8 a la vez.
- [x] Seguridad en `configurar.cli`: roles en los recursos REST.
- [x] `backend/.env.example` con lo que cambia por ambiente: CORS, base,
      clave de firma de los JWT, URL interna, secreto de servicios y la
      pasarela de Pagos y de Ventas. No lleva credenciales de Mercado Pago,
      Mailtrap ni certificados de ARCA porque esas integraciones son
      simuladas. `VITE_API_URL` es del frontend.

## Fase 1 — Base de datos y entidades

- [x] Entidades JPA: `Usuario`, `CodigoVerificacion`, `IntentoLogin`,
      `Evento`, `Sector`, `DevolucionDeCupo`, `Venta`, `VentaItem`, `Entrada`,
      `TitularDeVenta`, `Pago`, `Escaneo`, `Publicacion`, `Reembolso`,
      `RegistroDeAuditoria` y `Factura`. La reserva no es una entidad: es la
      venta `EN_CHECKOUT` con su vencimiento.
- [x] Repositorios: los 8 servicios tienen su capa `datos/` con una interfaz
      y su implementación JPA.
- [x] Restricciones en la base, no solo en código:
  - [x] `email` y `dni` únicos en usuarios.
  - [x] `slug` único en eventos.
  - [x] `vendidas <= cupo` en sectores.
  - [x] DNI único por venta en entradas (`entrada_titular_unico`).
  - [x] Una sola publicación activa por entrada: la garantiza Ventas, que pasa
        la entrada de `VALIDA` a `PUBLICADA` con un UPDATE condicional. No hay
        una restricción en la tabla `publicacion`.
  - [x] Total = subtotal + cargo, precios y cantidades positivos, una factura
        por venta, una clave de idempotencia por pago.
- [x] Estado de emisión en `Entrada` (`PENDIENTE_EMISION` → `VALIDA`), porque
      con mensajería la entrada se emite después del pago.
- [x] Montos en pesos enteros (`long`), nunca en `double`, en todos los
      servicios.
- [x] Formato de ids: son los ids reales, con prefijo y 8 caracteres de un
      UUID (`V-3f9a1c2b`, `T-…`, `P-…`, `R-…`). No hay un código público
      aparte.
- [x] Migraciones con Flyway: cada servicio corre al desplegar sus scripts de
      `src/main/resources/db/migracion` en su propio esquema. SQL compatible
      con PostgreSQL; en desarrollo corren sobre H2 en modo PostgreSQL.
- [ ] Probar las migraciones contra un PostgreSQL real (Supabase) apenas esté
      (limitación L7).
- [x] Datos iniciales como migración: los 6 eventos con sus sectores y las
      cuentas de prueba. Las publicaciones de reventa no se cargan: se crean
      desde la pantalla o los escenarios.

## Fase 2 — `ServicioDeUsuarios` (servicio reutilizable)

- [x] Interfaz `IUsuarios` y Session Bean `@Stateless`.
- [x] `POST /api/usuarios/registro`: valida datos (mismas reglas que
      `src/lib/validate.ts`, 16 años o más) y hashea la contraseña con
      PBKDF2-SHA256.
- [x] Verificación de email con código de 6 dígitos: se guarda solo el hash,
      vence a los 15 minutos, máximo 5 intentos, reenvío cada 30 segundos.
- [x] Mandar el código por email (30/10): sale por `Correos`. Sin
      `PALCO_SMTP_HOST` queda en el log, como antes.
- [x] `POST /api/usuarios/login` y `GET /api/usuarios/yo`. Logout = el front
      descarta el token.
- [x] Sesión con JWT (decidido: con 8 WAR separados una sesión HTTP no se
      comparte entre servicios). Usuarios firma con RS256 y publica la clave en
      `/api/usuarios/jwks`; los demás la usan vía MicroProfile JWT.
- [x] Seguridad declarativa: `@RolesAllowed` / `@PermitAll` en todos los
      servicios (`resteasy-role-based-security` en `configurar.cli`). 401 sin
      token o con uno inválido, 403 con rol incorrecto, los dos con Problem
      Details.
- [x] Las cuentas de prueba como migración.
- [x] Tests de hash de contraseñas y firma de tokens (Usuarios tiene 45).
- [ ] `PALCO_JWT_CLAVE_PRIVADA` fija en el hosting (si no, cada reinicio
      invalida las sesiones).
- [x] Tokens para llamadas entre servicios (rol `servicio`, 5 minutos), con
      el secreto compartido `PALCO_SECRETO_SERVICIOS`.
- [x] Operaciones que consumen los otros servicios: Ventas consulta
      `GET /api/usuarios/yo` con el token del comprador. Reventa y Validación
      no lo necesitaron: el dueño de una entrada y su titular están en Ventas.
- [x] Verificación de identidad (`identidadVerificada`), decidida: en esta
      etapa el email verificado es la verificación de identidad, y se marca al
      completar el registro. No se pide foto del DNI.
- [x] Recuperar contraseña en el backend (30/10):
      `POST /api/usuarios/password/olvide` manda un código de 6 dígitos y
      `POST /api/usuarios/password/reinicio` la cambia. Tabla
      `reinicio_password` aparte de `codigo_verificacion` (migración V7), así
      un código de contraseña no sirve para verificar un email. Siempre
      responde 204, exista o no la cuenta, y pedirlo de nuevo antes de los 30
      segundos tampoco es error: sería la única respuesta distinta según si el
      email existe. Al cambiarla se olvidan los intentos de login fallidos.
      14 tests.
- [ ] La pantalla de "olvidé mi contraseña" en el frontend (el backend ya está).
- [ ] Al cambiar la contraseña, las sesiones abiertas siguen valiendo hasta
      que vencen: el token es un JWT firmado y no hay lista de revocados.
      Queda como limitación, igual que la vigencia de 8 horas (L10).
- [x] Límite de intentos de login: 5 fallidos y 15 minutos de espera, con 429
      y `Retry-After`. Es por email, no por IP (limitación L25). Registro y
      escaneos siguen sin límite (Fase 12).
- [x] Vigencia de la sesión (SEC-W05), decidido el 16/10: se mantiene en 8
      horas, sin refresh token. Una sesión de compra o de un organizador dura
      una jornada; el token se firma con RS256 y cada servicio lo valida con la
      clave pública; la clave se regenera al redesplegar Usuarios si no se
      define `PALCO_JWT_CLAVE_PRIVADA`; el token entre servicios dura 5
      minutos. El riesgo de un token robado se acepta para el alcance del
      trabajo. SEC-W05 queda "parcial, vigencia justificada" y la prueba
      SEC-W05c de Postman comprueba que el token dura 8 horas.

## Fase 3 — `ServicioDeEventos`

- [x] Interfaz `IEventos` y Session Bean `@Stateless` con las operaciones de
      la Entrega 1 (`crearEvento`, `listarEventos`, `obtenerDetalle`,
      `actualizarCupo`).
- [x] `GET /api/eventos` con filtros por categoría y búsqueda insensible a
      acentos.
- [x] `GET /api/eventos/{slug}` con sectores y cupo disponible.
- [x] `POST /api/eventos` con validaciones y slug único.
- [x] `POST /api/eventos/{eventoId}/sectores/{sectorId}/cupo`: suma o libera
      vendidas en un solo UPDATE (dos compras simultáneas del último lugar:
      pasa una sola, probado). Desde el 23/10 la devolución lleva una clave y
      no se aplica dos veces (limitación L4).
- [x] Crear evento requiere rol `organizer` (queda a su nombre) y actualizar
      cupo requiere rol `servicio`.
- [x] Tests del Session Bean y de la capa de datos (Eventos tiene 50).
- [x] `PUT /api/eventos/{eventoId}` (30/10, solo el organizador dueño; ajeno
      responde 404). Reemplaza el evento completo. El slug no cambia aunque
      cambie el título: lo lleva la URL del frontend. Con 409
      `sector-con-ventas` se frenan bajar el cupo por debajo de lo vendido,
      cambiar el precio de un sector con ventas (el tope de la reventa se
      calcula sobre lo que pagó cada comprador) y quitar un sector con
      entradas. Un evento que ya empezó o está cancelado no se edita.
- [x] Cancelar evento (30/10): `POST /api/eventos/{eventoId}/cancelacion`,
      solo el dueño. Es el segundo proceso asincrónico de punta a punta; el
      detalle está en la [Fase 6](#fase-6--mensajería-jms-pago-confirmado).
      El evento no se borra: sale del catálogo, su detalle sigue respondiendo
      con el motivo, no se le vende más y no se edita. Cancelar dos veces da
      409 `evento-cancelado`, y así nadie reembolsa dos veces. Migración V7.
- [ ] La pantalla de editar y el botón de cancelar en el panel (el backend ya está).
- [x] Relación evento ↔ organizador (23/10, limitación L18): el evento guarda
      quién lo creó, `GET /api/eventos/mios` (rol `organizer`) devuelve los
      del token y `GET /api/eventos/{eventoId}/organizador` (rol `servicio`)
      le dice a Validación de quién es un evento. La migración V6 pone los
      seis eventos de prueba a nombre de `organizador@palco.test`. Ningún
      evento recibe el slug `mios`. Escenario 21, sin correr en el servidor.
- [x] Subida de imágenes (30/10, decidido: **Supabase Storage**, no disco del
      servidor, porque el hosting que se piensa usar tiene disco efímero y la
      imagen se perdería al redesplegar). `POST /api/eventos/imagenes`
      devuelve la URL pública. Solo JPG, PNG o WEBP, hasta 2 MB; sin SVG, que
      puede traer scripts. El nombre del archivo lo pone el almacén con un
      UUID: si lo decidiera el nombre que llegó, un `../` escribiría fuera del
      bucket. Sin configurar, la subida se rechaza con un error que explica
      qué falta y el alta sigue usando la foto de la categoría. 12 tests.
- [ ] Probarla contra un bucket real: hoy solo está probada con dobles, porque
      el proyecto en Supabase todavía no existe.
- [ ] El input de imagen en el alta y la edición del frontend (el backend ya está).

## Fase 4 — `ServicioDeVentas` (stateful) y `ServicioDePagos`

- [x] Interfaz `IVentas` como `@Stateful` (`iniciarCompra`,
      `confirmarCompra`, `cancelarCompra`): mantiene los titulares entre
      llamadas y muere con `@Remove` al pagar o cancelar (sobrevive a un pago
      rechazado) o por `@StatefulTimeout`.
- [x] Ligar la instancia stateful al usuario desde REST: no se usó
      `@SessionScoped` (no hay sesión HTTP con JWT). `ComprasEnCurso`
      (`@ApplicationScoped`) indexa las compras abiertas y frena dos llamadas
      simultáneas sobre la misma (409 `operacion-en-curso`).
- [x] Reserva de cupo durante el checkout (llama a Eventos) y liberación al
      vencer: `VencimientoDeReservas` (`@Schedule`, una vez por minuto).
- [x] Validar `MAX_ENTRADAS_POR_ORDEN`, cupo disponible, titulares (DNI válido
      y único por venta) y calcular subtotal + cargo de servicio.
- [x] Idempotencia por `Idempotency-Key` al iniciar y al pagar.
- [x] Al aprobarse el pago, publicar `PAGO_CONFIRMADO` (Fase 6).
- [x] Pago rechazado (402, la compra sigue abierta y se puede reintentar) y
      pago sin respuesta: la conciliación de pagos (`ConciliacionDePagos`) le
      pregunta a Pagos por la clave y confirma o vence la venta (escenario 14).
- [x] Devolución de cupo segura de repetir (23/10, limitación L4): las ventas
      canceladas o vencidas quedan con la marca `devolucion_pendiente` hasta que
      Eventos acepta el cupo, el barrido `DevolucionDeCupos` las reintenta cada
      minuto y Eventos no resta dos veces la misma clave (`devolucion_de_cupo`).
      Probado en el servidor con el WAR de Eventos detenido (escenario 18).
- [x] `GET /api/v1/ventas` y `GET /api/v1/ventas/{id}` (solo el dueño), con
      el estado de emisión.
- [x] `ServicioDePagos` con la pasarela detrás de `PasarelaDePagos`
      (`FabricaDePasarela` elige; hoy solo `PagosFake`), consulta por clave y,
      desde el 23/10, reembolso de un pago aprobado.
- [ ] Pago pendiente y vencido: llegan con el webhook, que no existe.
- [ ] `IPagos` con implementación de Mercado Pago y su webhook.
      **Diseñado, pendiente (decisión del 13/10):** Pagos sigue con `PagosFake`
      y el requisito REST lo cubre la API propia. El diseño está en la sección
      12.1 del documento maestro. La cátedra aceptó la pasarela simulada el
      16/10: Mercado Pago real queda fuera del alcance.
- [~] Reembolsos: existen desde el 23/10 para la compra de reventa que Ventas
      no pudo transferir (Fase 7). Falta decidir si una compra del catálogo se
      reembolsa (por ejemplo, con un evento cancelado).
- [ ] "Asignar después por link" para que otro titular complete sus datos.

## Fase 5 — `ServicioDeFacturación` (ARCA, simulado)

Diseño decidido el 16/10, todo simulado (la cátedra aceptó que la integración
con ARCA sea simulada por ahora). Reusa el tópico, la suscripción durable y los
reintentos del broker, y Ventas no cambia. Lo implementó Santiago Loto.
Probado en el servidor el 16/10 (escenario 15).

- [x] ARCA como sistema legado simulado: un servicio SOAP propio (JAX-WS,
      clase con `@WebService`) en un WAR aparte. El WSDL lo publica WildFly
      desde la clase (capa `webservices`).
- [x] La numeración y el CAE los asigna el ARCA simulado.
- [x] MDB en Facturación con suscripción durable a `topico.compra-confirmada`:
      por cada compra confirmada pide la factura por SOAP y guarda número y CAE
      en su esquema (`Factura`). Desde el 23/10 factura también la compra en la
      reventa (`REVENTA_CONFIRMADA`).
- [x] Cliente SOAP con una interfaz anotada (`PuertoArca`) y `Service.create`
      sobre el WSDL, sin generar código.
- [x] Si ARCA no responde, el MDB falla y reintenta el broker (5 entregas
      cada 2 segundos y después la `DLQ`). No hay cola de reintento aparte.
- [x] Ventas no cambia: la factura se consulta en Facturación y
      `facturaEstado` sigue en `NO_APLICA`.
- [x] DTD y entidades externas deshabilitadas en el XML (SEC-I01): Facturación
      se niega a crear el cliente si alguien las habilita.
- [ ] Provocar en el servidor ARCA sin respuesta y ARCA sin desplegar (hoy
      solo con tests; punto 9 de 22.1).
- Descartado el 16/10: el cliente generado desde los WSDL reales de AFIP
  (WSAA y WSFEv1), el ambiente de homologación con certificado y la espera de
  5 segundos con cola de reintento.

## Fase 6 — Mensajería JMS: pago confirmado

Según el taller: el mismo evento se publica en una **cola** y en un **tópico**.

Desde el 30/10 hay un **segundo proceso asincrónico**: cancelar un evento.

```
ServicioDeVentas ──► cola.emision-entradas ──► Emisión de entradas (consumidor único)
                 └─► topico.compra-confirmada ─┬► Notificaciones: aviso (no durable)
                                               ├► Auditoría (durable)
                                               └► Facturación (durable)

ServicioDeEventos ─► topico.eventos-cancelados ──► Ventas: anula entradas y pide
                     (EVENTO_CANCELADO)              el reembolso a Pagos
                                                            │
ServicioDeVentas ──► topico.eventos-cancelados ─┬► Notificaciones: aviso (no durable)
                     (COMPRA_ANULADA, una        └► Auditoría de cancelaciones (durable)
                      por compra)
```

Por qué dos mensajes y no uno: la cancelación es un hecho del evento (uno por
evento) y la anulación es un hecho de cada compra (uno por comprador). Eventos
no sabe quién compró qué, así que su mensaje lleva solo el id del evento;
Ventas, que es la dueña de las ventas, abre un `COMPRA_ANULADA` por cada una,
que es el que trae a quién avisarle y cuánto se le devolvió.

- [x] **Diferencia entre documentos, resuelta:** vale el taller de mensajería,
      que es posterior: las entradas se emiten por la cola, después del pago.

### Broker y contrato
- [x] ActiveMQ Artemis embebido en WildFly (capa `embedded-activemq`).
- [x] `cola.emision-entradas` y `topico.compra-confirmada` creados por el
      script CLI.
- [x] `topico.eventos-cancelados` creado por el script CLI (30/10). Es un
      tópico aparte del de compras porque es otro proceso, con otros
      suscriptores y otro cuerpo de mensaje: en el mismo, cada consumidor
      tendría que filtrar los tipos del otro.
- [x] Mensaje `TextMessage` con JSON, mismo body en los dos canales:
      `ventaId`, `compradorId`, `eventoId`, `entradas[]` (`entradaId`,
      `sector`, `titular` con `nombre` y `dni`), `montoTotal`, `timestamp`.
- [x] Propiedad `tipoEvento=PAGO_CONFIRMADO` en cada mensaje.
- [x] Clase del mensaje en `common` (`PagoConfirmadoMensaje`), con JSON-B.
- [x] Contrato documentado en `docs/api/mensajeria-pago-confirmado.md`.
- [x] Clases de los mensajes de la cancelación en `common` (30/10):
      `EventoCanceladoMensaje` (`eventoId`, `titulo`, `motivo`, `timestamp`) y
      `CompraAnuladaMensaje` (`ventaId`, `compradorId`, `eventoId`, `titulo`,
      `motivo`, `monto`, `reembolsado`, `timestamp`), las dos con la misma
      validación y el mismo cuidado con el JSON cortado que
      `PagoConfirmadoMensaje`.
- [ ] Documentar el contrato de los dos mensajes nuevos, como está hecho el de
      `PAGO_CONFIRMADO`.
- [x] Mensaje de la compra en la reventa (23/10, limitación L13, decidido por
      el grupo): Ventas publica `REVENTA_CONFIRMADA` solo en el tópico, con el
      mismo cuerpo; aviso, auditoría y facturación lo reciben (selector
      `tipoEvento IN ('PAGO_CONFIRMADO', 'REVENTA_CONFIRMADA')`). La emisión no
      cambia. Probado en el servidor (escenario 17).

### Productor (`ServicioDeVentas`)
- [x] Publicar en la cola y en el tópico al confirmar el pago, con
      `JMSContext` inyectado (`java:/JmsXA`).
- [x] Enviar dentro de la misma transacción JTA que guarda la venta (si se
      deshace, el mensaje no sale: escenario 13). El datasource no es XA: la
      base entra como último recurso, y una caída entre los dos commits queda
      sin cubrir (limitación L3). Falta que la cátedra diga si lo acepta.

### Consumidor de la cola: módulo de emisión
- [x] Message-Driven Bean `EmisionDeEntradasMDB` escuchando
      `cola.emision-entradas`.
- [~] Poner el código de cada entrada y marcar la venta como emitida: hecho,
      pero el código es un UUID al azar, no un QR firmado (Fase 8).
- [x] Idempotente: si el broker reentrega el mensaje, no emite dos veces
      (escenario 8).
- [x] Reintentos y cola de mensajes fallidos (DLQ) configurados en Artemis
      (escenario 12).

### Cancelar un evento (segundo proceso, 30/10)
- [x] Eventos publica `EVENTO_CANCELADO` dentro de la misma transacción que
      marca el evento como cancelado (el publicador es `MANDATORY`): si el
      mensaje no sale, el evento no queda cancelado, y si la marca se deshace,
      nadie reembolsa.
- [x] `CancelacionDeEventoMDB` en Ventas, con suscripción **durable** y
      selector `tipoEvento = 'EVENTO_CANCELADO'` (sin el filtro se escucharía
      a sí mismo, porque los `COMPRA_ANULADA` van al mismo tópico).
- [x] Una transacción por compra (`REQUIRES_NEW`): si las N compras de un
      evento fueran una sola transacción, un fallo en la última desharía las
      anteriores y el reintento del mensaje volvería a pedirle a Pagos todos
      los reembolsos.
- [x] Idempotente por la base: anular una venta es un UPDATE condicional, así
      que una segunda entrega no cambia filas, no pide otro reembolso y no
      vuelve a avisar.
- [x] El reembolso no bloquea la anulación: si Pagos no contesta, la entrada se
      anula igual y el aviso dice que la devolución está en curso
      (`reembolsado = false`). Lo contrario sería peor: entradas que siguen
      valiendo para un evento que no existe.
- [x] `reembolsar` agregado a `Cobros` y a sus dos implementaciones
      (`ClienteDePagos` y `CobrosFake`).
- [ ] Probarlo en el servidor: el flujo completo solo tiene tests con dobles
      (ver Fase 11).

### Suscriptores del tópico
- [x] MDB de aviso al comprador (`AvisoDeCompraMDB`): suscripción no durable.
      Desde el 30/10 el aviso sale por email: Notificaciones le pide a
      Usuarios el email del comprador (`GET /api/usuarios/{usuarioId}`, rol
      `servicio`), porque el mensaje lleva ids y no datos personales. Si no lo
      consigue o el SMTP no contesta, el aviso queda en el log y el mensaje se
      da por atendido: un aviso que no sale no puede hacer que se reentregue y
      la compra se avise dos veces.
- [x] MDB de aviso de cancelación (`AvisoDeCancelacionMDB`, 30/10): escucha
      `COMPRA_ANULADA` y le manda el email a cada comprador. No durable, como
      el de compra.
- [x] MDB de auditoría de cancelaciones (`AuditoriaDeCancelacionesMDB`,
      30/10): suscripción **durable**. Anota la anulación de cada compra y no
      la cancelación del evento, porque la fila necesita venta, comprador y
      monto, y el mensaje del evento no los tiene.
- [x] MDB de auditoría (`AuditoriaDeComprasMDB`): guarda el evento con
      suscripción **durable** (escenario 11).
- [x] Selector por `tipoEvento` en cada MDB.

### Otros procesos asincrónicos (de la Entrega 1)
- [ ] Recordatorios antes del evento (`@Schedule` + Notificaciones).
- [x] Alertas de cancelación de evento (30/10): `COMPRA_ANULADA` →
      `AvisoDeCancelacionMDB`. `INotificaciones.enviarAlertaCancelacion`, que
      estaba declarada como pendiente desde la Entrega 1, ya existe.
- [ ] Aviso al vendedor cuando se vende su entrada en reventa.
- [x] Reintentos de factura (Fase 5): los hace el broker con los reintentos
      que ya existen (decisión del 16/10), sin cola aparte.

## Fase 7 — `ServicioDeReventa`

Implementado y probado en el servidor (escenarios 17, 19 y 20 y carpeta 6 de
Postman). Reventa guarda las publicaciones; las entradas siguen en Ventas, que
las publica, retira y transfiere por REST con el token de servicio.

- [x] `GET /api/v1/reventas?eventoId=` y `GET /api/v1/reventas/mias`.
- [x] `POST /api/v1/reventas`: publica una entrada propia y `VALIDA`, con el
      precio entre el 50 % y el 110 % de lo pagado, hasta 3 horas antes del
      evento. Quien publica es el comprador de la venta (el dueño de la
      entrada en Ventas); no se pide que sea el titular nominativo.
- [x] `DELETE /api/v1/reventas/{id}`: retirar; la entrada vuelve a `VALIDA`.
- [x] `POST /api/v1/reventas/{id}/compra`: cobra en Pagos precio más cargo,
      con idempotencia; Ventas deja la entrada original `VENDIDA` y crea una
      venta nueva con una entrada nueva, con otro código y el titular nuevo. El
      código anterior da `NO_HABILITADA` en la puerta.
- [x] Nadie compra su propia publicación (`compra-propia`).
- [~] Cierre 3 horas antes del evento: se rechazan publicar y comprar, pero
      las publicaciones siguen apareciendo en el listado hasta que alguien
      intenta comprarlas.
- [x] Conciliación de las compras de reventa sin resultado (23/10, limitación
      L1): la toma ya no vence a los 2 minutos y guarda el titular; el barrido
      `ConciliacionDeReventas` le pregunta a Pagos por la clave y transfiere la
      entrada o libera la publicación. Probado en el servidor (escenario 19).
- [x] Reembolso de la compra de reventa sin entrada (23/10, limitación L2): si
      Pagos cobra y Ventas rechaza la transferencia, se anota el reembolso, la
      publicación queda `ANULADA` y Pagos devuelve el pago
      (`POST /api/v1/pagos/{pagoId}/reembolso`); si Pagos no responde, el
      barrido lo reintenta. Probado en el servidor (escenario 20).
- [x] Mensaje de la compra en la reventa (`REVENTA_CONFIRMADA`, Fase 6).
- [ ] Aviso al vendedor y al comprador de un reembolso.
- [ ] Liquidación al vendedor.
- [ ] Diagrama de secuencia de la reventa (a reasignar; punto 5 de 22.1).

## Fase 8 — `ServicioDeValidación` (stateless)

Implementado y probado en el servidor por la API el 16/10 (escenario 16 y
carpeta 5 de Postman).

- [ ] QR firmado (HMAC o JWT con el id de la entrada) para que no se pueda
      falsificar. Hoy el código es un UUID al azar de Ventas: no se adivina,
      pero no lleva firma.
- [x] `POST /api/v1/validacion/escaneos` (solo `staff`): devuelve `VALIDA`,
      `YA_USADA`, `INEXISTENTE`, `OTRO_EVENTO` o `NO_HABILITADA` y le pide a
      Ventas (`POST /api/v1/entradas/usos`, rol `servicio`) que use la entrada
      con un UPDATE condicional (uso único aunque escaneen dos puertas a la vez).
- [x] Contrastar con el documento del titular (devuelve nombre y DNI para que
      el staff lo compare).
- [x] Registro de cada escaneo (código, resultado, quién y cuándo):
      `GET /api/v1/validacion/escaneos?eventoId=` para `staff` y `organizer`.
- [x] Pantalla `/puerta` conectada a Validación: el código se tipea o lo
      escribe un lector; muestra contadores de ingresos y rechazos y los
      últimos escaneos. No se probó todavía en el navegador.
- [x] Controlar que el organizador sea dueño del evento cuyo registro
      consulta (23/10, L17): Validación le pregunta a Eventos
      `GET /api/eventos/{eventoId}/organizador` con el token de servicio; un
      evento ajeno responde 404, igual que uno que no existe.
- [x] Asignar staff a eventos y controlarlo al escanear y al consultar el
      registro (23/10, lo que quedaba de L17): tabla `puerta_asignada` y
      `/api/v1/validacion/puertas` (el dueño asigna y quita por email, el
      staff ve sus puertas). Sin la puerta asignada el escaneo da 403
      `puerta-no-asignada` sin llamar a Ventas ni dejar renglón. El escaneo
      no consulta a Eventos: la asignación está en la base de Validación, así
      la puerta sigue funcionando aunque Eventos no responda. Sin correr en el
      servidor.
- [ ] Comprobar que el email que se asigna sea de una cuenta con rol `staff`:
      hoy un email mal escrito se acepta y simplemente no deja escanear a
      nadie, sin avisarle al organizador.
- [ ] Provocar en el servidor Ventas sin respuesta (504 y renglón
      `SIN_RESPUESTA`; hoy solo con tests).
- [ ] Modo offline: fuera del alcance de esta etapa.

## Fase 9 — Panel del organizador

- [x] Panel conectado: entradas vendidas, recaudado y cupo salen de Eventos;
      las publicaciones activas, de Reventa; la ocupación por sector, de
      Eventos; los accesos y la tabla de escaneos, de Validación. No se probó
      todavía en el navegador.
- [x] Borrar `src/data/organizer.ts`: ya no existe. Lo que no tiene servicio
      se sacó del panel en lugar de mostrarse con datos fijos.
- [x] Ventas por día y liquidación (30/10): `GET /api/v1/resumenes?eventoId=`
      en Ventas, que es la dueña de las ventas, con rol `organizer` y control
      de dueño preguntándole a Eventos. Devuelve el detalle por día y la
      liquidación: el comprador paga subtotal + cargo, Palco se queda el cargo
      y al organizador se le liquida el subtotal. Las ventas anuladas no se
      liquidan. El agrupado por día se hace en Java, no con un `group by`:
      sacar la fecha de un timestamp se escribe distinto en cada base. 6 tests.
- [ ] Mostrarlas en el panel: hoy el endpoint existe y el frontend no lo usa.
- [x] Filtrar por los eventos del organizador (23/10, L18): el panel usa
      `GET /api/eventos/mios` y, sin eventos, ofrece crear el primero. Sin
      probar en el navegador.
- [ ] (Opcional) Dashboard de analytics como nuevo suscriptor de
      `topico.compra-confirmada`.

## Fase 10 — Conexión frontend ↔ backend

- [x] Cliente HTTP en `src/lib/api.ts`: base URL desde `VITE_API_URL`, manejo
      de errores (Problem Details: `{type, title, status, detail, errores}`) y
      token `Authorization: Bearer` (el backend usa JWT, no cookies de sesión).
- [x] CORS: filtro en el backend (`PALCO_CORS_ORIGENES`); no hizo falta proxy
      de Vite.
- [x] Backend: el 401 de un token inválido sale con Problem Details y con las
      cabeceras CORS (`ErrorDeAutenticacionServlet`).
- [ ] Sacar de `api.ts` la comprobación del token que compensaba ese 401 sin
      CORS (`GET /api/usuarios/yo` y, si no se puede leer, el `health`).
- [ ] Backend: informar la hora del servidor (los segundos que faltan en la
      venta, o exponer la cabecera `Date` por CORS; hoy se exponen `Location`
      y `ETag`). Mientras tanto el checkout deduce el desfase de relojes de
      "la reserva dura 10 minutos".
- [x] Manejo de datos del servidor: `fetch` con `useEffect` y `useState`, sin
      librería.
- [ ] Tipos del front generados desde la API (MicroProfile OpenAPI de WildFly
      + generador de tipos TypeScript). Por ahora se traducen a mano en
      `src/servicios/*.ts`.
- [x] Reemplazar cada `dispatch` del store por la llamada a la API:
  - [x] `LOGIN`, `LOGOUT`, `REGISTER` → Usuarios (probado en navegador el 09/10).
  - [x] `CREAR_EVENTO` → `POST /api/eventos` (probado en navegador el 09/10).
  - [x] Checkout → Ventas y Pagos (probado en navegador el 13/10). Una cuenta
        nueva compra desde que verifica el email.
  - [x] Publicar, retirar y comprar en la reventa → `/api/v1/reventas/*`
        (`src/servicios/reventa.ts`).
  - [x] Escanear en la puerta → `/api/v1/validacion/escaneos`.
- [ ] **Conectar las seis operaciones que el backend ganó el 30/10.** Están
      implementadas y probadas, y el frontend todavía no las usa:
  - [ ] Editar evento → `PUT /api/eventos/{eventoId}` (falta la pantalla).
  - [ ] Cancelar evento → `POST /api/eventos/{eventoId}/cancelacion` (falta el
        botón en el panel, con su confirmación: dispara reembolsos).
  - [ ] Subir la foto del evento → `POST /api/eventos/imagenes`, y mandar la
        URL que devuelve como `imagenUrl` al crear o editar.
  - [ ] Olvidé mi contraseña → `POST /api/usuarios/password/olvide` y
        `/password/reinicio` (falta la pantalla con el código de 6 dígitos).
  - [ ] Ventas por día y liquidación → `GET /api/v1/resumenes?eventoId=`.
  - [ ] Mostrar el aviso de evento cancelado: `EventoDto` ahora trae
        `cancelado` y `cancelacionMotivo`, y el catálogo ya no los lista.
- [ ] **Probar en el navegador, contra el backend, las pantallas de reventa,
      puerta y panel** con el recorrido de la sección 15.5 del documento
      maestro (punto 3 de 22.1). El backend que usan pasó en el servidor.
- [x] Checkout por pasos contra el backend, confirmación con "emitiendo
      entradas…" y countdown sincronizado con el vencimiento real.
- [x] Estados de carga, error y vacío en cada pantalla, también en la reventa
      (ya no usa `sleep()`).
- [x] Errores de validación del servidor en los formularios.
- [x] `localStorage`: solo queda el carrito de la compra en curso
      (`palco.state.v3`). Entradas, publicaciones y escaneos se piden al
      servicio dueño cada vez que se muestran.
- [x] `ProtectedRoute` basado en la sesión real (token guardado, confirmado
      con `GET /api/usuarios/yo`).
- [x] Datos fijos: `src/data/listings.ts` y `organizer.ts` ya no existen;
      `events.ts` solo tiene las fotos locales.

## Fase 11 — Pruebas

### Backend
- [x] JUnit 5 + Mockito para la lógica de cada Session Bean, con las
      interfaces de los otros componentes como dobles: 761 tests al 30/10
      (detalle por servicio en la sección 16.1 del documento maestro).
- [x] Reglas de negocio: tope de reventa, mínimo 50 %, cargo de servicio,
      máximo por venta, cierre de reventa a 3 horas, DNI único.
- [x] Integración contra WildFly real: en lugar de Arquillian, 21 escenarios
      en Node (`scripts/pruebas/escenarios.mjs`) por la API y `jboss-cli`, y la
      colección de Postman (`scripts/postman`).
- [ ] Escenario de la cancelación de un evento con sus reembolsos (30/10): es
      el que prueba el segundo proceso de mensajería de punta a punta, y es lo
      que la defensa del 13/11 va a querer ver corriendo. Hoy el flujo solo
      tiene tests con dobles.
- [ ] Sumar a `openapi.yaml` y a la colección de Postman las 8 rutas nuevas
      del 30/10 (editar y cancelar evento, subir imagen, las dos de
      contraseña, el usuario por id y el resumen de ventas).
- [x] Endpoints: caso feliz, validaciones, 401 sin sesión, 403 con rol
      incorrecto (escenarios 4 y 5, carpetas de Postman).
- [x] Concurrencia contra H2: último lugar comprado por dos a la vez
      (`CupoEnH2Test`), misma publicación comprada por dos
      (`PublicacionesEnH2Test`), misma entrada escaneada dos veces
      (`UsoEnH2Test`), mismo reembolso anotado dos veces (`ReembolsosEnH2Test`).
- [x] Ciclo de vida del bean stateful: se libera al confirmar, cancelar o
      vencer.
- [x] Pagos y facturación con las implementaciones simuladas. El webhook no
      existe.
- [ ] Provocar en el servidor los casos de falla que solo tienen tests: ARCA
      sin respuesta y sin desplegar, un sobre con DTD contra CXF (L15),
      Validación con Ventas caída (punto 9 de 22.1).
- [ ] Pruebas de carga y de caída (limitación L24).

### Mensajería
- [x] Al confirmar un pago sale un mensaje a la cola y uno al tópico, con body
      y `tipoEvento` correctos (escenario 7).
- [x] Reentrega del mismo mensaje → la entrada se emite una sola vez
      (escenario 8).
- [x] Escenario del taller: con el consumidor de emisión detenido, el mensaje
      espera y la entrada se emite al volver (escenario 10).
- [x] Auditoría durable: con el MDB detenido, al volver recibe los eventos
      pendientes (escenario 11). El aviso, no durable, se acepta perderlo.
- [x] Mensaje inválido termina en la DLQ (escenario 12).
- [x] Si la transacción se deshace después de publicar, el mensaje no sale
      (escenario 13).

### Frontend
- [ ] Vitest + Testing Library (hoy el frontend no tiene tests propios).
- [ ] Unitarias de `src/lib/validate.ts` y `src/lib/format.ts`.
- [ ] Componentes: `Stepper`, `Input`, `ProtectedRoute`, selector de
      cantidades de `EventPage`.
- [ ] MSW para mockear la API.

### End to end
- [ ] Playwright contra front + WildFly + base de test. Se hicieron recorridos
      con Playwright, pero no están en el repo.
- [ ] Recorrido completo: catálogo → evento → checkout → confirmación (con
      emisión asíncrona) → publicar en reventa → comprar con otra cuenta →
      validar en puerta.
- [ ] Registro con verificación de email (depende del proveedor de email).
- [ ] Organizador crea evento y aparece en el catálogo.
- [ ] Accesos por rol.

## Fase 12 — Seguridad

- [x] Bean Validation en los DTOs de entrada.
- [x] `@RolesAllowed` en cada operación según rol; las internas, solo con rol
      `servicio`.
- [x] Autorización por recurso: cada usuario ve solo sus entradas, ventas y
      facturas (lo ajeno responde 404) y, desde el 23/10, cada organizador ve
      solo sus eventos y el registro y el equipo de puerta de ellos (L18), y
      cada cuenta de puerta escanea solo en los eventos que tiene asignados
      (L17). Queda sin correr en el servidor.
- [~] Límite de intentos: hecho en login (Fase 2) y, desde el 30/10, en el
      reinicio de contraseña (5 intentos por código, como la verificación).
      Falta en registro, en la verificación por email y en escaneos.
- [x] Credenciales nuevas fuera del repo (30/10): las de SMTP
      (`PALCO_SMTP_*`) y la `service_role key` de Supabase
      (`PALCO_SUPABASE_CLAVE`) van en `backend/.env`, que no se versiona, y en
      el hosting. La clave de Supabase permite escribir en el storage, así que
      nunca viaja al frontend: la imagen se sube al backend y él la reenvía.
- [x] Subida de archivos acotada (30/10): solo JPG, PNG y WEBP, hasta 2 MB; el
      nombre del archivo lo pone el servidor con un UUID, así el que llegó no
      decide la ruta; y no se acepta SVG, que puede traer scripts.
- [x] Cookies: no aplica, el token viaja en la cabecera `Authorization`.
      CORS restringido a `PALCO_CORS_ORIGENES`.
- [x] Usuario y contraseña en el broker (SEC-I02): riesgo aceptado el 16/10.
      El broker está embebido en el mismo WildFly, los servicios se conectan
      por in-vm y la interfaz pública del servidor queda en 127.0.0.1 por
      defecto: ningún sistema externo se conecta al broker. Se revisa si el
      sistema se despliega fuera de una sola máquina.
- [x] Secreto de servicios fuera de desarrollo, decidido el 16/10: no se
      bloquea el arranque. Es un requisito de despliegue: definir
      `PALCO_SECRETO_SERVICIOS` con el mismo valor en todos los servicios; si
      falta, el log lo avisa. En desarrollo se usa el de `backend/.env`.
- [x] Endpoints de prueba de Notificaciones (`/pruebas/*`), decidido el
      16/10: se mantienen porque los usan la demo, la evidencia y la colección
      de Postman; son solo de desarrollo y piden rol `servicio`. Para
      producción habría que deshabilitarlos.
- [ ] DNI en los mensajes del tópico: definir si viaja completo o solo ids
      (hoy viaja completo).
- [x] Credenciales fuera del repo: `backend/.env` no se versiona. ARCA y la
      pasarela son simuladas (16/10), así que no hay certificados ni
      credenciales de Mercado Pago.
- [x] DTD y entidades externas deshabilitadas al procesar XML en Facturación
      (SEC-I01).

## Fase 13 — Infraestructura y deploy

- [ ] CI (GitHub Actions): lint y build del front; `mvn verify` del backend.
- [ ] Hosting **(a confirmar)**: front estático (Vercel, Netlify) y WildFly en
      Render o Railway (construyen la imagen en la nube, no hace falta Docker
      local), base en Supabase.
- [ ] Ambientes de prueba y producción separados.
- [ ] Backups de la base.
- [ ] Monitoreo: métricas de WildFly, tamaño de colas y DLQ, logs.
- [ ] Dominio y HTTPS.

## Fase 14 — Pendientes del frontend actual

No dependen del backend.

- [~] Warnings de `npm run lint`: quedan 4 (tres de fast refresh en
      `AppContext`, `SesionContext` y `ToastProvider`, y un `Date.now` durante
      el render en `MyTicketsPage`). Sin errores.
- [x] `index.html`: `lang="es"`.
- [ ] `prefers-reduced-motion` también en framer-motion
      (`MotionConfig reducedMotion="user"`).
- [ ] Pasada de accesibilidad y responsive. Ya corregido: en pantalla angosta
      el detalle del evento no dejaba elegir entradas, y el modal "Crear
      evento" perdía el foco al escribir. Falta revisar el resto.
- [ ] Code splitting por ruta.
- [~] Mantener `README.md` al día a medida que avanza el backend.

## Entregas de la materia

- [x] Anotar fechas y qué pide cada una, y mapear a estas fases (hecho el
      13/10/2026).

El grupo cursa los **viernes** (08:45 a 11:45) y la consigna está escrita para
la comisión del lunes. Las fechas de esta tabla son las del cronograma que
publica la cátedra para los viernes; entre paréntesis va la fecha que figura
en la consigna, que no es la nuestra. "Qué pide" sale de la consigna
(secciones 5, 6 y 9); donde la consigna no dice nada, figura "a confirmar con
la cátedra".

| Viernes | Hito del cronograma | Qué pide la consigna | Estado al 23/10 | Fases |
|---|---|---|---|---|
| 04/09 | 1.er check point TPO | Entrega Parcial N.º 1 (31/08): componentes candidatos con su interfaz, diagrama de arquitectura, stack y un componente desplegado. Sin defensa | Ya pasó | — |
| 18/09 | 1.ª entrega parcial | Entrega Obligatoria N.º 1 (14/09): tres componentes desplegados, uno stateful y uno stateless, tres patrones, seguridad declarativa y documento de 5 a 8 páginas. Con defensa oral | Ya pasó | — |
| **23/10**, sin confirmar | No figura con nombre: ese día hay clase | Entrega Parcial N.º 2 (19/10), checkpoint formativo sin defensa: diagrama de arquitectura de integración (SOA); clasificación justificada de cada integración externa en síncrona (SOAP o REST) o asincrónica (cola o tópico); y un proceso de negocio de punta a punta con mensajería real y broker configurado | Documento, diagrama y evidencia en `docs/entrega3/`, congelados en el tag `entrega-parcial-2` (14/10). La subida la confirma el grupo | 2, 3, 4 y 6 |
| **06/11** | Entrega del diseño del TP Obligatorio | La consigna no la nombra. La cátedra respondió el 16/10 que la documentación es técnica, con diagramas de casos de uso y casos de uso, sin Figma ni manual de usuario | Hechos: el diagrama de casos de uso con los casos de uso (13.9), siete diagramas de secuencia, el WSDL y `docs/api/openapi.yaml` al día. Faltan los diagramas de secuencia de autenticación y de reventa, el diagrama SOA actualizado y el borrador del documento técnico; se sube el jueves 05/11 | 5 y 11 |
| **13/11** | 2.ª Entrega PARCIAL | Entrega Obligatoria N.º 2 — 2.º Parcial (09/11), con defensa oral de 20 a 25 minutos y nota mínima 4. Hay que entregar: dos procesos con mensajería de punta a punta (uno por cola y uno por tópico); un servicio SOAP con su WSDL, justificado como sistema legado; un servicio REST (partner moderno o API de consumo externo); transacciones declarativas en un flujo crítico de varios pasos; documento técnico actualizado, de 8 a 12 páginas de contenido (sin contar los anexos, confirmado el 16/10), con un diagrama de secuencia por integración; y la declaración del uso de IA. Hay que defender: una demo que cruce componentes, mensajería y servicios web; por qué cada integración es síncrona o asincrónica; y qué pasa con la transacción si algo falla a mitad del flujo | Hecho y probado en el servidor: la cola (emisión) y el tópico (aviso, auditoría y factura), el SOAP con su WSDL (ARCA simulado), la API REST propia, la transacción declarativa que confirma la compra y la prueba repetible de una falla a mitad del flujo (escenario 14). Desde el 30/10 hay un **segundo proceso asincrónico** completo: cancelar un evento (Eventos → Ventas → reembolso en Pagos → aviso y auditoría), que cubre de sobra los "dos procesos con mensajería" que pide la consigna; le falta correrlo en el servidor. Faltan el documento técnico con los dos diagramas de secuencia pendientes y el de la cancelación, la declaración del uso de IA (la escribe el grupo) y la demo recorrida en el navegador | 4, 5, 6 y 11 |
| **20/11** | 3.er Check point TPO | La consigna no lo nombra: a confirmar con la cátedra | Validación y Reventa están implementadas y probadas por la API. Falta recorrer sus pantallas en el navegador | 7, 8 y 10 |
| **27/11** | Prueba del TP Obligatorio | La consigna no la nombra así: qué se hace ese día, a confirmar con la cátedra. Se toma como límite para lo que pide la Entrega Final (30/11 en el detalle de la consigna): sistema completo que cumpla el checklist de la sección 6; documentación final de 10 a 15 páginas; historial de git incremental y con participación visible de todos; demo completa, preguntas individuales y reflexión del equipo (los aprendizajes los escribe el grupo) | Los 8 componentes están implementados. Desde el 30/10 el backend tiene también editar y cancelar evento con reembolsos, recuperar contraseña, el envío de emails, la subida de imágenes y las ventas por día. Faltan: las pantallas de esas seis cosas en el frontend, las pruebas del frontend y de punta a punta, la base y el bucket en Supabase, la documentación final, recorrer en el navegador el panel y la puerta, y que en git se vea a los cinco (hoy no hay commits a nombre de Joaquin Molina) | 9 a 13 |
| **04/12** | Final adelantado | El encabezado de la consigna da el 04/12 y el 11/12 para la Entrega Final. El grupo decidió el 16/10 defenderla el viernes 04/12 (final adelantado); el 11/12 (final regular) queda como alternativa | Decidido | — |

- El 16/10, el 23/10 y el 30/10 hay clase de "Desarrollo de una aplicación
  integrada", sin entrega con nombre.
- **El 23/10 sale del cronograma de los viernes.** La cátedra respondió el
  16/10 que se entrega con las fechas de ese cronograma. Como no nombra este
  checkpoint, se toma el 23/10 por el patrón de las demás fechas (las del
  viernes caen cuatro días después de las del lunes).
- **Formato de cada entrega** (consigna, sección 9): enlace al repositorio y
  documento técnico en PDF, subidos al aula virtual. La hora límite que da la
  consigna (13:30) es de la comisión del lunes y falta confirmarla: cada
  entrega se sube el jueves anterior.

### Respuestas de la cátedra (16/10)

- El grupo de cinco está aceptado.
- Las integraciones con ARCA son simuladas por ahora, y también se acepta la
  pasarela de pagos simulada (`PagosFake`).
- Se entrega con las fechas del cronograma de la comisión del viernes.
- La documentación que piden es técnica, con diagramas de casos de uso y
  casos de uso. La consigna no pide Figma ni manual de usuario.
- Las 8 a 12 páginas del documento técnico son de contenido, sin contar los
  anexos.
- Siguen sin respuesta: la hora límite de subida; si una cola y un tópico que
  salen del mismo evento cuentan como dos procesos asincrónicos (desde el
  23/10 el tópico tiene además `REVENTA_CONFIRMADA`, un proceso propio); y si
  se acepta la base como último recurso en lugar de un datasource XA.

### Decisiones del grupo (16/10)

- Vigencia de la sesión: 8 horas, sin refresh token (Fase 2).
- Permisos por destino en el broker: riesgo aceptado (Fase 12).
- Facturación por SOAP: todo simulado, con el diseño de la Fase 5. La
  implementa Santiago Loto para el 13/11.
- Secreto de servicios fuera de desarrollo: requisito de despliegue, sin
  bloquear el arranque (Fase 12).
- Endpoints `/pruebas/*` de Notificaciones: se mantienen (Fase 12).
- La declaración del uso de IA y los aprendizajes del equipo los escribe el
  grupo.
- La Entrega Final se defiende el viernes 04/12 (final adelantado).
- Los pendientes de Sebastian Deya pasan a Julian Zuchowicki: la conciliación
  de pagos con una prueba repetible de una falla a mitad de la compra; la capa
  de datos en Usuarios, Eventos y Notificaciones, con los tests de Eventos; y
  lo que quede de Ventas. Lo que Sebastian ya hizo no cambia. Hecho: la
  conciliación (escenario 14) y las capas de datos.

### Decisiones del grupo (23/10)

- La compra en la reventa publica `REVENTA_CONFIRMADA` solo en el tópico
  (L13, Fase 6).

### Pendientes del documento

- [x] Diagramas de casos de uso y casos de uso (sección 13.9 del documento
      maestro, `docs/documentacion/diagramas/casos-de-uso.svg`).
- [ ] Diagrama de secuencia de autenticación (S2). Responsable: a definir.
- [ ] Diagrama de secuencia de la reventa (S3). Responsable: a reasignar.
- [ ] Diagrama de integración SOA actualizado (Joaquin, con Santiago).
- [ ] Documento técnico de 8 a 12 páginas para el 13/11.
