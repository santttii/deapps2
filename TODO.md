# Palco: TODO

Lista de trabajo para pasar de la demo solo frontend a un sistema real con
backend. Está ordenada por fases: cada fase depende, en general, de las
anteriores.

Convención: `[x]` hecho · `[ ]` abierto · `[~]` en curso. Lo que el trabajo
decidió no hacer figura como decisión, con su motivo, en el ítem o en la lista
"Decidido fuera del alcance" de su fase.

**Estado al 25/11/2026.** Se revisó cada fase contra el código y contra
`docs/documentacion/Palco_Documentacion_Completa.md`, que es la fuente del
detalle; acá queda el estado de cada tarea. Los 8 componentes, el asistente con
IA y el frontend están implementados y conectados. Sobre `12a08f2` (25/11), en
`master`, los workflows que corren con cada push (CI, Seguridad, ZAP, Contrato,
Pruebas de seguridad, Lighthouse y Mutación) terminaron en verde y el Quality
Gate de SonarCloud pasa; Carga y Estabilidad corren con los tags `v*` y a mano,
y la última vez fue el 24/11. La corrida con el detalle de cada cifra es la de
`cbdef38` (24/11): 1210 tests en los 11 módulos del backend, 60 tests de
integración contra PostgreSQL 17 y Artemis en contenedores, los 24 escenarios
de integración (`scripts/pruebas/escenarios.mjs`, 389 comprobaciones), la
colección de Postman con Newman (219 pedidos y 682 aserciones) y las 34 pruebas
E2E que había ese día (hoy son 39). Las cifras están en la sección 9.1 de
`docs/final/documentacion-final.md`, y el detalle de las corridas anteriores, en
la sección 16 del documento maestro y en `docs/testing/`; el estado de cada
entrega, en [Entregas de la materia](#entregas-de-la-materia).

## Contexto (documentos de la materia)

Desarrollo de Aplicaciones II. Grupo: Loto, Frisoli, Deya, Zuchowicki, Molina.
La cátedra aceptó el grupo de cinco el 16/10.

- **Entrega Parcial N.º 1: Arquitectura general** (exposición 18/09):
  arquitectura en tres capas, 8 componentes de negocio con una interfaz cada
  uno, stack Jakarta EE sobre WildFly.
- **Taller (arquitectura de integración)**: bus de integración (ESB
  conceptual, sin producto dedicado) apoyado en el broker JMS y en clientes
  SOAP/REST detrás de interfaces propias (`IPagos`, `IFacturacion`).
  `ServicioDeUsuarios` es el servicio reutilizable (principio SOA).
- **Taller (escenario de mensajería)**: el evento `PAGO_CONFIRMADO` se publica
  en una cola (emisión de entradas) y en un tópico (notificaciones y
  auditoría). Ver [Fase 6](#fase-6-mensajería-jms-pago-confirmado).

### Stack definido

| Capa | Tecnología |
|---|---|
| Presentación | React (SPA) consumiendo la API REST; es el front de este repo |
| Negocio | Jakarta EE: EJB + CDI, Session Beans desplegados en WildFly |
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

Además, el asistente con IA (`/api/v1/asistente`, stateless y sin base)
responde dudas sobre Palco a través de su gateway (`AsistenteIA`).

Reglas de diseño: una responsabilidad por componente, los componentes se
hablan solo por interfaz, nunca contra la implementación del otro.

---

## Fase 0. Decisiones y setup

- [x] `ServicioDeEventos`: no hay código previo, se arranca de cero.
- [x] Versiones: **Java 25 (LTS)** y **WildFly 41** (Jakarta EE 11).
- [x] Base de datos: **PostgreSQL**, como datasource JDBC de WildFly: el
      PostgreSQL 17 del compose, o Supabase (PostgreSQL gestionado) con las
      variables `PALCO_DB_*`. Para desarrollar, H2 en modo PostgreSQL.
- [x] Docker, decisión que cambió el 30/10: al principio la infraestructura
      iba a ser solo de servicios web. Desde el 30/10 `docker-compose.yml`
      levanta el sistema completo con un comando: PostgreSQL 17, WildFly con
      los 10 WAR y nginx con TLS. `backend/dev.sh` sigue para desarrollar con H2.
- [x] Herramientas en cada máquina: la imagen del backend compila con Maven y
      JDK 25 adentro (`backend/Dockerfile`), así que para levantar el sistema
      alcanza con Docker. Para desarrollar se usan JDK 25 y Maven.
- [x] Envío de emails resuelto sin atarse a un proveedor (30/10): `Correos` en
      `common/email` con `CorreoSmtp` (Jakarta Mail, que ya trae la
      plataforma) y `CorreoAlLog`, y `FabricaDeCorreos` elige según
      `PALCO_SMTP_HOST`. Sin servidor configurado los emails quedan en el log
      (un WARNING al arrancar lo avisa), con el destinatario enmascarado y el
      asunto, sin el cuerpo (con `PALCO_CORREO_LOG_COMPLETO=true`, enteros); con
      él, salen de verdad.
- [x] Empaquetado: **un WAR independiente por servicio en un solo WildFly**:
      los 8 componentes, el asistente y el ARCA simulado. Cada uno tiene su API
      y, si guarda datos, su esquema de base, y se comunican solo por REST,
      SOAP o JMS.
- [x] Estructura del repo: `frontend/` (el front actual) y `backend/`
      (proyecto Maven).
- [x] Mover el front actual a `frontend/` sin romper `npm run dev` ni `build`.
- [x] Scaffold de `backend/`: `pom.xml` padre, `common` (lo compartido),
      `servicios/` (los 9 servicios y el ARCA simulado) y `servidor/` (arma
      WildFly y los despliega).
- [x] Healthcheck `GET /api/<servicio>/health` (incluye la base) y manejo
      centralizado de errores con Problem Details (`ExceptionMapper` de
      JAX-RS), todo en `common`.
- [x] JSON con JSON-B en todos los servicios (`JsonProvider` en `common`).
- [x] Filtro CORS configurable con `PALCO_CORS_ORIGENES`. Sin la variable,
      `http://localhost:5173` (el Vite de desarrollo); el compose acepta solo
      la URL pública (DEF-42).
- [x] Pasar a Java las constantes de negocio de `src/types/index.ts`
      → `ReglasDeNegocio` en `common`, con tests. El servidor pasa a ser la
      fuente de verdad.
- [x] WildFly sin instalarlo a mano: el módulo `servidor` lo arma con
      `wildfly-maven-plugin` solo con las capas que usamos (`jaxrs-server`,
      `ejb`, `embedded-activemq`, `microprofile-jwt`, `webservices`, `mail`,
      drivers H2 y PostgreSQL). `dev.sh` lo levanta y `redesplegar.sh` actualiza un
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
      pasarela de Pagos y de Ventas, y las variables opcionales de SMTP,
      Supabase y el proveedor de IA, todas sin valor. No lleva credenciales de
      Mercado Pago ni certificados de ARCA porque esas integraciones son
      simuladas. `VITE_API_URL` es del frontend.

Decidido fuera del alcance:

- Hosting en la nube: la consigna no lo exige. El sistema se levanta entero
  con `docker compose up --build --wait`, que es lo que usan la CI y la demo.
- Proyecto en Supabase (base y bucket): sin despliegue en la nube no se creó.
  La base PostgreSQL real es la del compose (PostgreSQL 17) y las fotos se
  guardan en la base de Eventos (Fase 3). Supabase queda como destino por
  configuración (`PALCO_DB_*`, `PALCO_SUPABASE_*`), sin cambiar código.
- Proveedor de correo: no se contrató. Sin `PALCO_SMTP_HOST` los emails quedan
  en el log, que alcanza para la demo y las pruebas (los escenarios y las E2E
  leen el código del log). Con las variables `PALCO_SMTP_*` de un proveedor
  (por ejemplo Mailtrap) salen de verdad, sin cambiar código.

## Fase 1. Base de datos y entidades

- [x] Entidades JPA: `Usuario`, `CodigoVerificacion`, `CodigoDeReinicio`,
      `IntentoLogin`, `Evento`, `Sector`, `Imagen`, `DevolucionDeCupo`,
      `Venta`, `VentaItem`, `Entrada`, `TitularDeVenta`, `Pago`, `Escaneo`,
      `PuertaAsignada`, `Publicacion`, `Reembolso`, `RegistroDeAuditoria`,
      `Factura` y `ComprobanteRechazado`. La reserva no es una entidad: es la
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
- [x] Migraciones contra un PostgreSQL real: corren en PostgreSQL 17 en cada
      arranque del compose, también en la CI, y los `*EnPostgresIT`
      (Testcontainers, `postgres:17-alpine`) prueban repositorios y migraciones
      de Usuarios, Eventos, Ventas y Facturación.
- [x] Datos iniciales como migración: los 6 eventos con sus sectores y las
      cuentas de prueba. Las publicaciones de reventa no se cargan: se crean
      desde la pantalla o los escenarios.

## Fase 2. `ServicioDeUsuarios` (servicio reutilizable)

- [x] Interfaz `IUsuarios` y Session Bean `@Stateless`.
- [x] `POST /api/usuarios/registro`: valida datos (mismas reglas que
      `src/lib/validate.ts`, 16 años o más) y hashea la contraseña con
      PBKDF2-SHA256, con 600.000 iteraciones. Un hash guardado con menos se
      vuelve a calcular al iniciar sesión (DEF-44).
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
- [x] Tests de hash de contraseñas y firma de tokens (detalle por módulo en la
      sección 16.1 del documento maestro).
- [x] `PALCO_JWT_CLAVE_PRIVADA`: el compose la toma del entorno; si no viene,
      Usuarios genera una al arrancar y las sesiones abiertas se cierran al
      reiniciar el servidor. Sin hosting no hay otro ambiente donde fijarla.
- [x] Tokens para llamadas entre servicios (rol `servicio`, 5 minutos), con
      el secreto compartido `PALCO_SECRETO_SERVICIOS`.
- [x] Operaciones que consumen los otros servicios: Ventas consulta
      `GET /api/usuarios/yo` con el token del comprador; con rol `servicio`,
      Notificaciones pide el email del comprador (`GET /api/usuarios/{usuarioId}`)
      y Validación, el rol de la cuenta que se asigna a una puerta
      (`GET /api/usuarios/cuentas?email=`). Reventa no lo necesita: el dueño de
      una entrada y su titular están en Ventas.
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
- [x] La pantalla `/recuperar` ("¿Olvidaste tu contraseña?" en el ingreso), con
      el código de 6 dígitos (`RecuperarPage.tsx`, probada en
      `recuperar.spec.ts`).
- [x] Decidido: al cambiar la contraseña, las sesiones abiertas siguen valiendo
      hasta que vencen, como mucho 15 minutos, porque el token es un JWT firmado
      y no hay lista de revocados (DEF-38).
- [x] Límite de intentos de login: 5 fallidos y 15 minutos de espera, con 429
      y `Retry-After`. Es por email, no por IP: decisión con riesgo aceptado,
      porque es lo que pide CA-02.3 (DEF-41). Registro y escaneos no tienen
      límite (Fase 12).
- [x] Vigencia de la sesión (SEC-W05). El 16/10 se decidió mantenerla en 8
      horas, sin refresh token, como riesgo aceptado (DEF-38). El 25/11 bajó a
      15 minutos (`ServicioDeUsuarios.DURACION_SESION`): el token es un JWT que
      cada servicio valida sin consultar a Usuarios, así que no hay
      revocación, y lo que acota una sesión robada es la vigencia corta.
      Alcanza para una compra entera (la reserva dura 10 minutos) y, al vencer,
      el frontend pide ingresar de nuevo. El token entre servicios dura 5
      minutos. La prueba SEC-W05c de Postman comprueba que el token dura 1
      hora.

## Fase 3. `ServicioDeEventos`

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
      no se aplica dos veces (L4).
- [x] Crear evento requiere rol `organizer` (queda a su nombre) y actualizar
      cupo requiere rol `servicio`.
- [x] Tests del Session Bean y de la capa de datos (detalle por módulo en la
      sección 16.1 del documento maestro).
- [x] `PUT /api/eventos/{eventoId}` (30/10, solo el organizador dueño; ajeno
      responde 404). Reemplaza el evento completo. El slug no cambia aunque
      cambie el título: lo lleva la URL del frontend. Con 409
      `sector-con-ventas` se frenan bajar el cupo por debajo de lo vendido,
      cambiar el precio de un sector con ventas (el rango de la reventa de las
      entradas vendidas sale del precio de lista del sector, DEF-16) y quitar
      un sector con entradas. Un evento que ya empezó o está cancelado no se
      edita.
- [x] Cancelar evento (30/10): `POST /api/eventos/{eventoId}/cancelacion`,
      solo el dueño. Es el segundo proceso asincrónico de punta a punta; el
      detalle está en la [Fase 6](#fase-6-mensajería-jms-pago-confirmado).
      El evento no se borra: sale del catálogo, su detalle sigue respondiendo
      con el motivo, no se le vende más y no se edita. Cancelar dos veces da
      409 `evento-cancelado`, y así nadie reembolsa dos veces. Migración V7.
- [x] Editar y cancelar desde el panel del organizador: el formulario del alta
      en modo edición (`CreateEventModal.tsx`) y `CancelarEventoModal.tsx`, con
      su confirmación. Los prueba `organizador.spec.ts`.
- [x] Relación evento ↔ organizador (23/10, L18): el evento guarda
      quién lo creó, `GET /api/eventos/mios` (rol `organizer`) devuelve los
      del token y `GET /api/eventos/{eventoId}/organizador` (rol `servicio`)
      le dice a Validación de quién es un evento. La migración V6 pone los
      seis eventos de prueba a nombre de `organizador@palco.test`. Ningún
      evento recibe el slug `mios`. Lo prueba el escenario 21 en cada corrida
      de la CI.
- [x] Subida de imágenes (30/10, decidido: no en el disco del servidor, que en
      un contenedor se pierde al redesplegar). `POST /api/eventos/imagenes`
      devuelve la URL pública. Solo JPG, PNG o WEBP, hasta 2 MB; sin SVG, que
      puede traer scripts. El nombre del archivo lo pone el almacén con un
      UUID: si lo decidiera el nombre que llegó, un `../` escribiría fuera del
      bucket. 12 tests. Dónde se guarda lo elige `FabricaDeAlmacen`: desde el
      18/11, sin configurar nada, en la base de Eventos (`AlmacenEnBaseDeDatos`,
      migración V8); con `PALCO_SUPABASE_URL` y `PALCO_SUPABASE_CLAVE`, en
      Supabase Storage (`AlmacenEnSupabase`, probado con dobles). Con
      `PALCO_IMAGENES=ninguno` la subida se rechaza y el alta usa la foto de la
      categoría.
- [x] El input de imagen en el alta y la edición del frontend
      (`CreateEventModal.tsx`). `organizador.spec.ts` comprueba que la foto que
      sirve el servidor es la que se subió.

## Fase 4. `ServicioDeVentas` (stateful) y `ServicioDePagos`

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
- [x] Reglas entre compras: hasta 6 entradas por comprador y evento
      (`MAX_ENTRADAS_POR_EVENTO`, 409 `tope-por-evento`) y un DNI con una sola
      entrada por evento (409 `dni-con-entrada`), también con dos compras a la
      vez, del catálogo o de la reventa: `ReglasEntreCompras` las vuelve a mirar
      con un candado por comprador y evento y otro por DNI y evento
      (`ventas.candado_de_compra`, migración V5, DEF-46). Lo prueban
      `ReglasEntreComprasEnH2Test` y el escenario 23.
- [x] Idempotencia por `Idempotency-Key` al iniciar y al pagar.
- [x] Al aprobarse el pago, publicar `PAGO_CONFIRMADO` (Fase 6).
- [x] Pago rechazado (402, la compra sigue abierta y se puede reintentar) y
      pago sin respuesta: la conciliación de pagos (`ConciliacionDePagos`) le
      pregunta a Pagos por la clave y confirma o vence la venta (escenario 14).
- [x] Devolución de cupo segura de repetir (23/10, L4): las ventas
      canceladas o vencidas quedan con la marca `devolucion_pendiente` hasta que
      Eventos acepta el cupo, el barrido `DevolucionDeCupos` las reintenta cada
      minuto y Eventos no resta dos veces la misma clave (`devolucion_de_cupo`).
      Probado en el servidor con el WAR de Eventos detenido (escenario 18).
- [x] `GET /api/v1/ventas` y `GET /api/v1/ventas/{id}` (solo el dueño), con
      el estado de emisión.
- [x] `ServicioDePagos` con la pasarela detrás de `PasarelaDePagos`
      (`FabricaDePasarela` elige; hoy solo `PagosFake`), consulta por clave y,
      desde el 23/10, reembolso de un pago aprobado.
- [x] Reembolsos: Pagos devuelve un pago aprobado
      (`POST /api/v1/pagos/{pagoId}/reembolso`) en dos casos: la compra de
      reventa que Ventas no pudo transferir (desde el 23/10, Fase 7) y cada
      compra de un evento cancelado (desde el 30/10, Fase 6). Si Pagos no
      contesta, lo vuelven a pedir `DevolucionDeCobros` (Reventa) y
      `ReintentoDeReembolsos` (Ventas).

Decidido fuera del alcance:

- Mercado Pago real, con su webhook y los avisos de pago en espera o vencido que
  llegan por él (decisión del 13/10): Pagos sigue con `PagosFake` y el
  requisito REST lo cubre la API propia. La cátedra aceptó la pasarela
  simulada el 16/10. El diseño está en la sección 12.1 del documento maestro;
  una pasarela real se suma como otra implementación de `PasarelaDePagos`.
- Reembolsos a pedido del comprador: Palco devuelve un pago solo en los dos
  casos de arriba.
- "Asignar después por link" para que otro titular complete sus datos: la
  compra pide nombre y DNI de cada titular al reservar, que es lo que
  necesitan la entrada nominativa y la puerta.

## Fase 5. `ServicioDeFacturación` (ARCA, simulado)

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
- [x] ARCA sin respuesta, provocado contra el compose el 19/11: la compra se
      emite igual, el mensaje de Facturación llega a la DLQ después de 5
      entregas y, devuelto con `move-messages`, sale una sola factura (29 de
      29, `docs/testing/evidencia/checkpoint-3/arca-lento-dlq.txt`). ARCA sin
      desplegar lo prueba
      `ArcaSoapTest#conArcaCaidoDespliegaIgualFallaAlPedirYSeRecuperaCuandoVuelve`:
      Facturación baja el WSDL recién en el primer pedido.
- Descartado el 16/10: el cliente generado desde los WSDL reales de AFIP
  (WSAA y WSFEv1), el ambiente de homologación con certificado y la espera de
  5 segundos con cola de reintento.

## Fase 6. Mensajería JMS: pago confirmado

Según el taller: el mismo evento se publica en una **cola** y en un **tópico**.

Desde el 30/10 hay un **segundo proceso asincrónico**: cancelar un evento.

```
ServicioDeVentas ──► cola.emision-entradas ──► Emisión de entradas (consumidor único)
                 └─► topico.compra-confirmada ─┬► Notificaciones: aviso (no durable)
                                               ├► Auditoría (durable)
                                               └► Facturación (durable)

ServicioDeEventos ─► topico.eventos-cancelados ─┬► Ventas: anula entradas y pide
                     (EVENTO_CANCELADO)         │  el reembolso a Pagos
                                                └► Reventa: retira las publicaciones

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
      `sector`, `titular` con `nombre`), `montoTotal`, `timestamp`.
- [x] Propiedad `tipoEvento=PAGO_CONFIRMADO` en cada mensaje.
- [x] Clase del mensaje en `common` (`PagoConfirmadoMensaje`), con JSON-B.
- [x] Contrato documentado en `docs/api/mensajeria-pago-confirmado.md`.
- [x] Clases de los mensajes de la cancelación en `common` (30/10):
      `EventoCanceladoMensaje` (`eventoId`, `titulo`, `motivo`, `timestamp`) y
      `CompraAnuladaMensaje` (`ventaId`, `compradorId`, `eventoId`, `titulo`,
      `motivo`, `monto`, `reembolsado`, `timestamp`), las dos con la misma
      validación y el mismo cuidado con el JSON cortado que
      `PagoConfirmadoMensaje`.
- [x] Contrato de los dos mensajes de la cancelación, como el de
      `PAGO_CONFIRMADO`: `docs/api/mensajeria-cancelacion-de-eventos.md`.
- [x] Mensaje de la compra en la reventa (23/10, L13, decidido por
      el grupo): Ventas publica `REVENTA_CONFIRMADA` solo en el tópico, con el
      mismo cuerpo; aviso, auditoría y facturación lo reciben (selector
      `tipoEvento IN ('PAGO_CONFIRMADO', 'REVENTA_CONFIRMADA')`). La emisión no
      cambia. Probado en el servidor (escenario 17).

### Productor (`ServicioDeVentas`)
- [x] Publicar en la cola y en el tópico al confirmar el pago, con
      `JMSContext` inyectado (`java:/JmsXA`).
- [x] Enviar dentro de la misma transacción JTA que guarda la venta (si se
      deshace, el mensaje no sale: escenario 13). El datasource no es XA: la
      base entra como último recurso, y una caída del servidor entre los dos
      commits podría dejar la venta confirmada sin su mensaje. Es una decisión
      del grupo, con la tabla de salida (*outbox*) como alternativa descartada
      (ADR-004 y sección 7.4 del documento maestro).

### Consumidor de la cola: módulo de emisión
- [x] Message-Driven Bean `EmisionDeEntradasMDB` escuchando
      `cola.emision-entradas`.
- [x] Poner el código de cada entrada y marcar la venta como emitida. El código
      es un UUID al azar, que no se adivina; el QR firmado quedó fuera del
      alcance (Fase 8).
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
- [x] Reventa también escucha `EVENTO_CANCELADO` (`CancelacionDeEventoMDB`,
      durable) y retira las publicaciones activas del evento.
- [x] Probado contra el sistema levantado: el escenario 22 anula las compras,
      también la hecha en la reventa, devuelve cada pago, retira la reventa y
      avisa, en cada corrida de la CI. La pantalla, en `organizador.spec.ts`.

### Suscriptores del tópico
- [x] MDB de aviso al comprador (`AvisoDeCompraMDB`): suscripción no durable.
      Desde el 30/10 el aviso sale por email: Notificaciones le pide a
      Usuarios el email del comprador (`GET /api/usuarios/{usuarioId}`, rol
      `servicio`), porque el mensaje trae el id del comprador y no su email. Si no lo
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
- [x] Alertas de cancelación de evento (30/10): `COMPRA_ANULADA` →
      `AvisoDeCancelacionMDB`. `INotificaciones.enviarAlertaCancelacion`, que
      figuraba en la Entrega Parcial N.º 1, ya existe.
- [x] Reintentos de factura (Fase 5): los hace el broker con los reintentos
      que ya existen (decisión del 16/10), sin cola aparte.

Decidido fuera del alcance: los recordatorios antes del evento y el aviso al
vendedor cuando se vende su entrada en la reventa. Los dos procesos
asincrónicos que pide la consigna ya están (la compra, por cola y tópico, y la
cancelación de un evento, por su tópico), y ninguna historia de usuario pide
estos avisos. El aviso al vendedor sería un suscriptor más de
`topico.compra-confirmada`, sin cambiar a quien publica.

## Fase 7. `ServicioDeReventa`

Implementado y probado en el servidor (escenarios 17, 19 y 20 y carpeta 6 de
Postman). Reventa guarda las publicaciones; las entradas siguen en Ventas, que
las publica, retira y transfiere por REST con el token de servicio.

- [x] `GET /api/v1/reventas?eventoId=` y `GET /api/v1/reventas/mias`.
- [x] `POST /api/v1/reventas`: publica una entrada propia y `VALIDA`, con el
      precio entre el 50 % y el 110 % del precio de lista de su sector, que
      Reventa le pide a Eventos (DEF-16: sobre lo pagado, cada reventa subía el
      tope otro 10 %), hasta 3 horas antes del evento. Quien publica es el
      comprador de la venta (el dueño de la entrada en Ventas); no se pide que
      sea el titular nominativo.
- [x] `DELETE /api/v1/reventas/{id}`: retirar; la entrada vuelve a `VALIDA`.
- [x] `POST /api/v1/reventas/{id}/compra`: cobra en Pagos precio más cargo,
      con idempotencia; Ventas deja la entrada original `VENDIDA` y crea una
      venta nueva con una entrada nueva, con otro código y el titular nuevo. El
      código anterior da `NO_HABILITADA` en la puerta.
- [x] Nadie compra su propia publicación (`compra-propia`).
- [x] Las reglas entre compras también en la reventa: antes de tomar la
      publicación y de cobrar, Reventa le pregunta a Ventas si el comprador
      puede quedarse con la entrada
      (`POST /api/v1/operaciones-de-reventa/controles-de-compra`: 409
      `tope-por-evento` o `dni-con-entrada`, sin cobrar; DEF-30). La
      transferencia vuelve a mirar las dos reglas con los candados de la Fase 4
      (DEF-46): si otra compra se cruzó después del control y la regla rechaza
      una compra ya cobrada, Reventa devuelve el pago, la publicación queda libre
      y el comprador recibe el 409 de la regla. Lo prueban
      `ReglasEntreComprasEnH2Test`, `ReventaDeEntradasEnH2Test`,
      `ServicioDeReventaTest` y `ConciliacionDeReventasTest`, y la carpeta 6 de
      Postman manda el control directo.
- [x] Cierre 3 horas antes del evento: Reventa rechaza publicar y comprar (409
      `reventa-cerrada`). El listado sigue trayendo las publicaciones activas,
      y la pantalla, con la fecha del evento, muestra que la reventa cerró en
      lugar del botón de compra (`EventPage.tsx` y `ResalePage.tsx`).
- [x] Conciliación de las compras de reventa sin resultado (23/10, L1):
      la toma ya no vence a los 2 minutos y guarda el titular; el barrido
      `ConciliacionDeReventas` le pregunta a Pagos por la clave y transfiere la
      entrada o libera la publicación. Probado en el servidor (escenario 19).
- [x] Reembolso de la compra de reventa sin entrada (23/10, L2): si
      Pagos cobra y Ventas rechaza la transferencia, se anota el reembolso, la
      publicación queda `ANULADA` y Pagos devuelve el pago
      (`POST /api/v1/pagos/{pagoId}/reembolso`); si Pagos no responde, el
      barrido lo reintenta. Probado en el servidor (escenario 20).
- [x] Mensaje de la compra en la reventa (`REVENTA_CONFIRMADA`, Fase 6).
- [x] Retiro de las publicaciones de un evento cancelado (Fase 6).
- [x] Diagrama de secuencia de la reventa:
      `docs/documentacion/diagramas/secuencia-reventa-rest.svg`.

Decidido fuera del alcance: la liquidación al vendedor, porque Palco no mueve
dinero real (la pasarela es simulada), y el aviso al vendedor y al comprador
cuando se devuelve una compra de reventa que no se pudo transferir. Ese
reembolso queda anotado en Reventa y en Pagos, y la entrada del vendedor sigue
`VALIDA` en Mis entradas.

## Fase 8. `ServicioDeValidación` (stateless)

Implementado y probado en el servidor por la API el 16/10 (escenario 16 y
carpeta 5 de Postman).

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
      últimos escaneos. La prueban `puerta.spec.ts` y `accesibilidad.spec.ts`
      en cada corrida de la CI.
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
      la puerta sigue funcionando aunque Eventos no responda. Lo prueba el
      escenario 21 en cada corrida de la CI.
- [x] Solo se asignan cuentas de puerta: Validación le pregunta a Usuarios el
      rol del email, y uno sin cuenta `staff` responde 422 `no-es-de-puerta`.
      La pantalla es Equipo de puerta, en el panel del organizador.
- [x] Ventas sin respuesta: 504 y renglón `SIN_RESPUESTA` en el registro
      (`ServicioDeValidacionTest#siVentasNoRespondeSeRegistraElIntentoYSeAvisaConUn504`
      y `RegistroEnH2Test`).

Decidido fuera del alcance: el QR firmado (HMAC o JWT con el id de la entrada)
y el modo sin conexión de la puerta, que depende de él. El código es un UUID
al azar que genera Ventas: no se adivina, y cada escaneo lo consulta en Ventas,
que es la que sabe si la entrada sigue valiendo.

## Fase 9. Panel del organizador

- [x] Panel conectado: las entradas vendidas y lo recaudado salen de Ventas
      (`GET /api/v1/resumenes`); el cupo y la ocupación por sector, de Eventos
      (`GET /api/eventos/mios`); las publicaciones activas, de Reventa; los
      accesos y la tabla de escaneos, de Validación. Lo recorren
      `organizador.spec.ts` y `accesibilidad.spec.ts` en cada corrida de la CI.
- [x] Borrar `src/data/organizer.ts`: ya no existe. Lo que no tiene servicio
      se sacó del panel en lugar de mostrarse con datos fijos.
- [x] Ventas por día y liquidación (30/10): `GET /api/v1/resumenes?eventoId=`
      en Ventas, que es la dueña de las ventas, con rol `organizer` y control
      de dueño preguntándole a Eventos. Devuelve el detalle por día y la
      liquidación: el comprador paga subtotal + cargo, Palco se queda el cargo
      y al organizador se le liquida el subtotal. Las ventas anuladas no se
      liquidan. El agrupado por día se hace en Java, no con un `group by`:
      sacar la fecha de un timestamp se escribe distinto en cada base. 6 tests.
- [x] Mostrarlas en el panel (24/11, DEF-48): en `/organizador`, "Ventas y
      liquidación" de cada evento, con lo recaudado de los compradores, el cargo
      de Palco, lo que se le liquida al organizador y la tabla "Ventas por día".
      Lo prueba `organizador.spec.ts` ("ve las ventas por día y la liquidación
      de su evento (HU-12)").
- [x] Filtrar por los eventos del organizador (23/10, L18): el panel usa
      `GET /api/eventos/mios` y, sin eventos, ofrece crear el primero. Lo
      recorren `organizador.spec.ts` y `accesibilidad.spec.ts`.

Decidido fuera del alcance: el dashboard de analytics como nuevo suscriptor de
`topico.compra-confirmada`, que era opcional. Los números del panel salen del
resumen de Ventas, que lee las ventas de su propia base.

## Fase 10. Conexión frontend ↔ backend

- [x] Cliente HTTP en `src/lib/api.ts`: base URL desde `VITE_API_URL`, manejo
      de errores (Problem Details: `{type, title, status, detail, errores}`) y
      token `Authorization: Bearer` (el backend usa JWT, no cookies de sesión).
- [x] CORS: filtro en el backend (`PALCO_CORS_ORIGENES`); sin proxy
      de Vite. Con el compose, el frontend va por nginx en el mismo origen.
- [x] Backend: el 401 de un token inválido sale con Problem Details y con las
      cabeceras CORS (`ErrorDeAutenticacionServlet`).
- [x] Decidido: la comprobación del token en `api.ts` se mantiene. El 401 ya
      llega con CORS; la comprobación queda para cuando la respuesta no se
      puede leer, y distingue un token rechazado de un corte de red
      (`GET /api/usuarios/yo` y, si tampoco responde, el `health`), así un
      corte en medio de una compra no cierra la sesión.
- [x] Decidido: la hora del servidor no se expone (CORS expone `Location`,
      `ETag` y `Retry-After`). El checkout mide el desfase de relojes con el
      vencimiento de la reserva (`venceReservaISO` menos los 10 minutos de
      `MINUTOS_RESERVA`) e ignora diferencias de menos de 5 segundos
      (`CheckoutPage.tsx`).
- [x] Manejo de datos del servidor: `fetch` con `useEffect` y `useState`, sin
      librería.
- [x] Decidido: los tipos del front se escriben a mano en `src/servicios/*.ts`,
      sin generarlos desde la API. Que coincidan con el servidor lo controlan
      las E2E contra el backend real y `contrato.yml` (Schemathesis contra
      `docs/api/openapi.yaml`).
- [x] Reemplazar cada `dispatch` del store por la llamada a la API:
  - [x] `LOGIN`, `LOGOUT`, `REGISTER` → Usuarios (probado en navegador el 09/10).
  - [x] `CREAR_EVENTO` → `POST /api/eventos` (probado en navegador el 09/10).
  - [x] Checkout → Ventas y Pagos (probado en navegador el 13/10). Una cuenta
        nueva compra desde que verifica el email.
  - [x] Publicar, retirar y comprar en la reventa → `/api/v1/reventas/*`
        (`src/servicios/reventa.ts`).
  - [x] Escanear en la puerta → `/api/v1/validacion/escaneos`.
- [x] **Las operaciones que el backend ganó el 30/10, conectadas:**
  - [x] Editar evento → `PUT /api/eventos/{eventoId}` (`CreateEventModal.tsx`
        en modo edición).
  - [x] Cancelar evento → `POST /api/eventos/{eventoId}/cancelacion`
        (`CancelarEventoModal.tsx`, con su confirmación: dispara reembolsos).
  - [x] Subir la foto del evento → `POST /api/eventos/imagenes`, y mandar la
        URL que devuelve como `imagenUrl` al crear o editar.
  - [x] Olvidé mi contraseña → `POST /api/usuarios/password/olvide` y
        `/password/reinicio` (`RecuperarPage.tsx`).
  - [x] Ventas por día y liquidación → `GET /api/v1/resumenes?eventoId=`
        (`resumenDelEvento`): el panel muestra las entradas vendidas, lo
        recaudado y, de cada evento, la liquidación y la tabla de ventas por
        día (DEF-48).
  - [x] El aviso de evento cancelado: el detalle muestra el motivo y que ya no
        se venden entradas, y Mis entradas, la compra anulada.
- [x] **Las pantallas de reventa, puerta y panel, en el navegador contra el
      backend:** `reventa.spec.ts`, `puerta.spec.ts`, `organizador.spec.ts` y
      `accesibilidad.spec.ts`, en cada corrida de la CI contra el compose.
- [x] Checkout por pasos contra el backend, confirmación con "emitiendo
      entradas…" y countdown sincronizado con el vencimiento real.
- [x] Estados de carga, error y vacío en cada pantalla, también en la reventa
      (ya no usa `sleep()`).
- [x] Errores de validación del servidor en los formularios.
- [x] `localStorage` no guarda datos del negocio: entradas, publicaciones y
      escaneos se piden al servicio dueño cada vez que se muestran. Guarda el
      carrito de la compra en curso (`palco.state.v3`) y el tema elegido
      (`palco.tema`).
- [x] `ProtectedRoute` basado en la sesión real (token guardado, confirmado
      con `GET /api/usuarios/yo`).
- [x] Datos fijos: `src/data/listings.ts` y `organizer.ts` ya no existen;
      `events.ts` solo tiene las fotos locales.

## Fase 11. Pruebas

### Backend
- [x] JUnit 6 + Mockito para la lógica de cada Session Bean, con las
      interfaces de los otros componentes como dobles: 1210 tests en los 11
      módulos del backend en la CI del 24/11 (detalle por servicio en la
      sección 16.1 del documento maestro).
- [x] Reglas de negocio: tope de reventa, mínimo 50 %, cargo de servicio,
      máximo por venta, cierre de reventa a 3 horas, DNI único.
- [x] Integración contra WildFly real: en lugar de Arquillian, 24 escenarios
      en Node (`scripts/pruebas/escenarios.mjs`) por la API y `jboss-cli`, y la
      colección de Postman (`scripts/postman`).
- [x] Integración con dependencias reales: tests `*IT` con Testcontainers
      contra PostgreSQL 17 y Artemis 2.54.0, en `mvn verify`: 60 en Usuarios,
      Eventos, Ventas y Facturación en la CI del 24/11, y desde ese día también
      `MigracionesEnPostgresIT` de Notificaciones (la migración V3, DEF-37).
- [x] Escenario de la cancelación de un evento con sus reembolsos: el 22,
      que anula las compras, devuelve los pagos, retira la reventa y avisa
      (15 de 15 el 19/11, `docs/testing/evidencia/checkpoint-3/escenarios.txt`).
      Desde el 20/11 cubre también la compra hecha en la reventa (DEF-29).
- [x] Las 8 rutas nuevas del 30/10 (editar y cancelar evento, subir imagen,
      las dos de contraseña, el usuario por id y el resumen de ventas), en
      `openapi.yaml` y en la colección de Postman: 50 de las 52 operaciones
      tienen un caso de éxito y uno de error en la colección; las otras dos,
      en `docs/api/GUIA_POSTMAN.md`, sección 9.
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
- [x] Casos de falla contra el sistema levantado: ARCA sin respuesta (19/11,
      `arca-lento-dlq.txt`, Fase 5) y los sobres con DTD y entidades contra
      `/legado/arca` (`scripts/pruebas/seguridad.mjs`, parte D, en
      `pruebas-seguridad.yml`). ARCA sin desplegar y Validación con Ventas
      caída los prueban tests (Fases 5 y 8).
- [x] Carga: k6 con 50 usuarios virtuales durante 5 minutos sobre el catálogo,
      el detalle y abrir y cancelar una compra (`carga.yml`; sobre `fbd31dc`,
      14964 pedidos, 0 % de errores y el p95 más alto en 22,8 ms). Caída: `broker-apagado.mjs`
      (10 de 10), el contenedor recreado con un mensaje pendiente (16 de 16) y
      los escenarios 14, 18, 19 y 20.

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
Decidido: el frontend no tiene tests unitarios (Vitest, Testing Library ni un
mock de la API con MSW). Se prueba con Playwright contra el backend real
(abajo), que recorre esas piezas desde las pantallas: el selector de
cantidades, el `Stepper` y el checkout (`compra.spec.ts`), `ProtectedRoute`
(`sesion.spec.ts`) y los errores del servidor en los formularios
(`recuperar.spec.ts`, las credenciales incorrectas de `sesion.spec.ts` y la
tarjeta rechazada de `compra.spec.ts`). Un mock de la API probaría contra un
contrato que puede no ser el del servidor.

### End to end
- [x] Playwright contra el frontend y el backend real: `frontend/e2e/`
      (`npm run e2e`, configuración en `frontend/playwright.config.ts`): 39
      pruebas en 15 archivos (`npx playwright test --list`). La corre el job E2E del CI contra el
      `docker compose`, con Chromium; en una máquina con Edge, con
      `PALCO_E2E_CANAL=msedge`.
- [x] Recorrido completo, en tres pruebas: catálogo → evento → checkout →
      confirmación (con emisión asíncrona) → la entrada con su QR en Mis
      entradas (`compra.spec.ts`); publicar en reventa y comprar con otra
      cuenta (`reventa.spec.ts`); validar en puerta (`puerta.spec.ts`).
- [x] Registro con verificación de email: el código se lee del `server.log`
      (`registro.spec.ts`).
- [x] Organizador crea evento y aparece en el catálogo; además lo edita y lo
      cancela, y ve las ventas por día y la liquidación (`organizador.spec.ts`).
- [x] Accesos por rol (`sesion.spec.ts`).
- [x] Además: responsive y accesibilidad con axe a 360, 768 y 1280 px
      (`accesibilidad.spec.ts`), los tres temas (`temas.spec.ts`), XSS
      (`xss.spec.ts`), recuperar la contraseña, la factura, el asistente, el
      catálogo sin conexión con el servidor (`catalogo.spec.ts`, DEF-54) y la
      carga diferida de una pantalla (`carga-diferida.spec.ts`, DEF-49).

## Fase 12. Seguridad

- [x] Bean Validation en los DTOs de entrada.
- [x] `@RolesAllowed` en cada operación según rol; las internas, solo con rol
      `servicio`. `SeguridadDeclarativaTest`, en cada servicio con recursos
      JAX-RS, falla si una operación queda sin `@RolesAllowed`, `@PermitAll` ni
      `@DenyAll` (DEF-43).
- [x] Autorización por recurso: cada usuario ve solo sus entradas, ventas y
      facturas (lo ajeno responde 404) y, desde el 23/10, cada organizador ve
      solo sus eventos y el registro y el equipo de puerta de ellos (L18), y
      cada cuenta de puerta escanea solo en los eventos que tiene asignados
      (L17). Lo prueban los escenarios 5 y 21 en cada corrida de la CI.
- [x] Límite de intentos: en el login (Fase 2) y en los códigos de 6 dígitos,
      con 5 intentos por código en la verificación del email y, desde el
      30/10, en el reinicio de la contraseña. El registro no cuenta intentos:
      riesgo aceptado (DEF-75, CA-01.2). Los escaneos tampoco: solo los hace
      una cuenta `staff` asignada a la puerta del evento, y cada uno queda en
      el registro con su cuenta.
- [x] Credenciales nuevas fuera del repo (30/10): las de SMTP
      (`PALCO_SMTP_*`) y la `service_role key` de Supabase
      (`PALCO_SUPABASE_CLAVE`) van en `backend/.env`, que no se versiona. La
      clave del proveedor de IA (`PALCO_ASISTENTE_OPENAI_CLAVE`) va en
      `backend/.env` o, con el compose, en el `.env` de la raíz, que tampoco se
      versiona. La clave de Supabase permite escribir en el storage, así que
      nunca viaja al frontend: la imagen se sube al backend y él la reenvía.
- [x] Subida de archivos acotada (30/10): solo JPG, PNG y WEBP, hasta 2 MB; el
      nombre del archivo lo pone el servidor con un UUID, así el que llegó no
      decide la ruta; y no se acepta SVG, que puede traer scripts.
- [x] Cookies: no aplica, el token viaja en la cabecera `Authorization`.
      El token queda en `localStorage`: riesgo aceptado (DEF-58). CORS
      restringido a `PALCO_CORS_ORIGENES`; en el compose, solo la URL pública
      (DEF-42).
- [x] Usuario y contraseña en el broker (SEC-I02): riesgo aceptado el 16/10.
      El broker está embebido en el mismo WildFly, los servicios se conectan
      por in-vm y la interfaz pública del servidor queda en 127.0.0.1 por
      defecto: ningún sistema externo se conecta al broker (DEF-39, ADR-005).
      Se revisa si el sistema se despliega fuera de una sola máquina.
- [x] Secreto de servicios fuera de desarrollo, decidido el 16/10: no se
      bloquea el arranque. Es un requisito de despliegue: definir
      `PALCO_SECRETO_SERVICIOS` con el mismo valor en todos los servicios. Si
      no viene, `backend/dev.sh` (que lo toma de `backend/.env` si está) y el
      arranque del contenedor (`backend/docker/arrancar.sh`) generan uno para
      ese arranque, y el log lo avisa. Ningún guion tiene uno por defecto
      (DEF-56).
- [x] Endpoints de prueba de Notificaciones (`/pruebas/*`), decidido el
      16/10: se mantienen porque los usan la demo, la evidencia y la colección
      de Postman; son solo de desarrollo y piden rol `servicio`. Para
      producción habría que deshabilitarlos.
- [x] DNI en los mensajes del tópico (24/11, DEF-37): el titular de
      `PAGO_CONFIRMADO` y de `REVENTA_CONFIRMADA` viaja solo con su nombre, la
      auditoría guarda el cuerpo sin el DNI y la migración
      `V3__sacar_el_dni_de_la_auditoria.sql` de Notificaciones se lo saca a las
      filas anteriores. Contrato: `docs/api/mensajeria-pago-confirmado.md`,
      "Datos personales".
- [x] Log sin datos personales: el paso "Control de logs"
      (`scripts/qa/control-de-logs.sh`) de `ci.yml`, `contrato.yml`,
      `pruebas-seguridad.yml` y `zap.yml` falla si el `server.log` tiene un
      token, una contraseña de prueba, un DNI o, con los emails enmascarados, un
      email `@palco.test` entero (DEF-40). El log de accesos de nginx escribe
      `<email>` en lugar del email de las rutas de puertas y de `?email=`
      (DEF-72).
- [x] Credenciales fuera del repo: `backend/.env` no se versiona. ARCA y la
      pasarela son simuladas (16/10), así que no hay certificados ni
      credenciales de Mercado Pago.
- [x] DTD y entidades externas deshabilitadas al procesar XML en Facturación
      (SEC-I01).

## Fase 13. Infraestructura y deploy

- [x] CI (GitHub Actions, `.github/workflows/ci.yml`): gitleaks; `mvn -B verify`
      con los IT, JaCoCo, PMD y la tabla de cobertura en el resumen de la
      corrida; SonarCloud; lint y build del front; `docker compose` con los
      guiones de `scripts/pruebas/`; la suite E2E; y la colección de Postman
      con Newman. Los análisis que atacan al sistema o tardan más van en
      workflows propios: `mutacion.yml` (PIT), `contrato.yml` (Schemathesis),
      `pruebas-seguridad.yml`, `zap.yml` (OWASP ZAP), `seguridad.yml` (Trivy,
      sobre las dependencias y las tres imágenes del compose, también
      `postgres:17-alpine`: DEF-73), `carga.yml` (k6), `lighthouse.yml` y
      `estabilidad.yml`. Sobre `12a08f2` (25/11), los workflows del push
      terminaron en verde y el Quality Gate de SonarCloud pasa; `carga.yml` y
      `estabilidad.yml` corren con los tags `v*` y a mano. El README tiene la
      tabla de qué corre cada uno.
- [x] HTTPS en el compose: nginx con TLS 1.2 y 1.3 y un certificado
      autofirmado que se genera al arrancar el contenedor
      (`frontend/docker/certificado.sh`); los datos quedan en los volúmenes
      `datos` (la base) y `datos-servidor` (el journal del broker).
- [x] nginx del compose: cabeceras de seguridad (CSP, HSTS, `nosniff`,
      `Referrer-Policy`, `X-Frame-Options`, `Permissions-Policy`,
      `Cross-Origin-Opener-Policy` y `Cross-Origin-Resource-Policy`; COEP no
      va, justificado en `.zap/reglas.tsv`: DEF-74); el ARCA simulado no se
      publica (`/legado/` responde 404, DEF-45) y queda en el puerto del
      backend, que el compose publica solo en 127.0.0.1; los 502, 503 y 504 que
      arma nginx salen en Problem Details.

Decidido fuera del alcance: hosting en la nube, ambientes de prueba y
producción separados, backups, monitoreo, dominio y un certificado firmado por
una autoridad. La consigna no pide desplegar en la nube: el sistema se levanta
en una máquina con `docker compose`, que es también lo que prueba la CI.

## Fase 14. Frontend: detalles que no dependen del backend

- [x] `npm run lint` sin avisos (24/11, DEF-50): `useApp`, `useSesion` y
      `useToast` pasaron a archivos propios (`src/store/useApp.ts`,
      `src/store/useSesion.ts` y `src/components/useToast.ts`).
- [x] `index.html`: `lang="es"`.
- [x] `prefers-reduced-motion` también en framer-motion
      (`MotionConfig reducedMotion="user"` en `main.tsx`).
- [x] Accesibilidad y responsive: `accesibilidad.spec.ts` pasa axe en las
      pantallas principales a 360, 768 y 1280 px sin desborde horizontal, y
      `temas.spec.ts`, en los tres temas. Lighthouse dio 100 en accesibilidad
      en las 5 pantallas públicas, en teléfono y escritorio (`lighthouse.yml`,
      sobre `9caa865`, run 45889484906: `docs/testing/evidencia/final/lighthouse.txt`).
- [x] Code splitting por ruta (24/11, DEF-49): cada pantalla, menos el
      catálogo, con `React.lazy` en `App.tsx`; si el archivo no llega, la
      pantalla avisa y ofrece recargar (`carga-diferida.spec.ts`).
- [x] Con el servidor caído (502 o 503 de nginx, o un corte de red), la
      pantalla dice "No pudimos conectar con el servidor" (DEF-54,
      `catalogo.spec.ts`).
- [x] `README.md` con cómo levantar, probar y entrar al sistema.

## Entregas de la materia

- [x] Anotar fechas y qué pide cada una, y mapear a estas fases (hecho el
      13/10/2026).

El grupo cursa los **viernes** (08:45 a 11:45) y la consigna está escrita para
la comisión del lunes. Las fechas de esta tabla son las del cronograma que
publica la cátedra para los viernes; entre paréntesis va la fecha que figura
en la consigna, que no es la nuestra. "Qué pide" sale de la consigna
(secciones 5, 6 y 9) y, donde la consigna no dice nada, de lo que pidió la
cátedra para esa fecha.

| Viernes | Hito del cronograma | Qué pide la consigna | Estado (al 25/11) | Fases |
|---|---|---|---|---|
| 04/09 | 1.er check point TPO | Entrega Parcial N.º 1 (31/08): componentes candidatos con su interfaz, diagrama de arquitectura, stack y un componente desplegado. Sin defensa | Ya pasó | No aplica |
| 18/09 | 1.ª entrega parcial | Entrega Obligatoria N.º 1 (14/09): tres componentes desplegados, uno stateful y uno stateless, tres patrones, seguridad declarativa y documento de 5 a 8 páginas. Con defensa oral | Ya pasó | No aplica |
| 23/10 | No figura con nombre: ese día hay clase | Entrega Parcial N.º 2 (19/10), checkpoint formativo sin defensa: diagrama de arquitectura de integración (SOA); clasificación justificada de cada integración externa en síncrona (SOAP o REST) o asincrónica (cola o tópico); y un proceso de negocio de punta a punta con mensajería real y broker configurado | Ya pasó. Documento, diagrama y evidencia en `docs/entrega3/`, congelados en el tag `entrega-parcial-2` (14/10) | 2, 3, 4 y 6 |
| 06/11 | Entrega del diseño del TP Obligatorio | La consigna no la nombra. La cátedra respondió el 16/10 que la documentación es técnica, con diagramas de casos de uso y casos de uso, sin Figma ni manual de usuario | Ya pasó. Hoy están el diagrama de casos de uso con los casos de uso (`docs/documentacion/casos-de-uso.md`), los 12 diagramas de secuencia y el de integración SOA en `docs/documentacion/diagramas/`, el WSDL y `docs/api/openapi.yaml` | 5 y 11 |
| **13/11** | 2.ª Entrega PARCIAL | Entrega Obligatoria N.º 2 (2.º Parcial, 09/11), con defensa oral de 20 a 25 minutos y nota mínima 4. Hay que entregar: dos procesos con mensajería de punta a punta (uno por cola y uno por tópico); un servicio SOAP con su WSDL, justificado como sistema legado; un servicio REST (partner moderno o API de consumo externo); transacciones declarativas en un flujo crítico de varios pasos; documento técnico actualizado, de 8 a 12 páginas de contenido (sin contar los anexos, confirmado el 16/10), con un diagrama de secuencia por integración; y la declaración del uso de IA. Hay que defender: una demo que cruce componentes, mensajería y servicios web; por qué cada integración es síncrona o asincrónica; y qué pasa con la transacción si algo falla a mitad del flujo | Ya pasó. Hecho y probado: la cola (emisión) y el tópico (aviso, auditoría y factura), el SOAP con su WSDL (ARCA simulado), la API REST propia, la transacción declarativa que confirma la compra y la prueba repetible de una falla a mitad del flujo (escenario 14). Desde el 30/10 hay un **segundo proceso asincrónico** completo: cancelar un evento (Eventos → Ventas y Reventa → reembolso en Pagos → aviso y auditoría), que cubre de sobra los "dos procesos con mensajería" que pide la consigna y lo prueba el escenario 22. El documento técnico está en `docs/entrega-obligatoria-2/` | 4, 5, 6 y 11 |
| 20/11 | 3.er Check point TPO | La consigna no lo nombra. La cátedra pidió: integraciones SOAP, REST y mensajería funcionando; resiliencia (timeout de 5 s con fallback, consumidores idempotentes y DLQ, sin pérdida de mensajes si se apaga el broker, `Idempotency-Key` y `ETag`, errores en Problem Details); la IA a través de su gateway; CI con reporte de cobertura parcial; primera versión del plan de pruebas, de la matriz de trazabilidad y del manual de usuario; y Jira al día | Ya pasó. Al 19/11: hecho y con evidencia en el repo, salvo el tablero, que se lleva en Notion. Detalle en "3.er check point (20/11): estado al 19/11"; el documento, en `docs/checkpoint-3/` | 6, 11 y 13 |
| **27/11** | Prueba del TP Obligatorio | La consigna no la nombra así. El cronograma de exposiciones que la cátedra publicó el 12/11 pone ese día, a las 08:40, la defensa de la Entrega Final del grupo. La Entrega Final pide (30/11 en el detalle de la consigna): sistema completo que cumpla el checklist de la sección 6; documentación final de 10 a 15 páginas; historial de git incremental y con participación visible de todos; demo completa, preguntas individuales y reflexión del equipo (los aprendizajes los escribe el grupo) | Los 8 componentes, el asistente y el frontend, completos: cada caso de uso tiene pantalla y prueba en la CI, que sobre `12a08f2` (25/11) terminó en verde. La documentación final está en `docs/final/`, y en git hay commits de los cinco integrantes | 9 a 13 |
| 04/12 | Final adelantado | El encabezado de la consigna da el 04/12 y el 11/12 para la Entrega Final. El grupo había decidido el 16/10 defenderla el viernes 04/12 (final adelantado); el 20/11 la pasó al viernes 27/11, el día que le asigna el cronograma de exposiciones de la cátedra del 12/11 | No se usa | No aplica |

- El 16/10, el 23/10 y el 30/10 hay clase de "Desarrollo de una aplicación
  integrada", sin entrega con nombre.
- **El 23/10 sale del cronograma de los viernes.** La cátedra respondió el
  16/10 que se entrega con las fechas de ese cronograma. Como no nombra este
  checkpoint, se toma el 23/10 por el patrón de las demás fechas (las del
  viernes caen cuatro días después de las del lunes).
- **Formato de cada entrega** (consigna, sección 9): enlace al repositorio y
  documento técnico en PDF, subidos al aula virtual. La hora límite que da la
  consigna (13:30) es de la comisión del lunes: por eso cada entrega se sube
  el jueves anterior.

### 3.er check point (20/11): estado al 19/11

Cada pedido de la cátedra con su evidencia. Las salidas de las corridas del
18/11 están en `docs/testing/evidencia/checkpoint-3/`; el detalle, en las
filas C3 (sección 2.2) y en las secciones 4.10, 8.8, 10.8 y 16.7 del documento
maestro.

| Pedido | Estado | Evidencia |
|---|---|---|
| Integraciones SOAP, REST y mensajería funcionando | Hecho | SOAP: escenario 15 (factura con CAE del ARCA simulado). REST: `node scripts/pruebas/escenarios.mjs`, 24 de 24 y 379 comprobaciones (`checkpoint-3/escenarios.txt`), y la colección de Postman, nueve carpetas sin fallas el 19/11 (`docs/api/GUIA_POSTMAN.md`). Mensajería: escenarios 7 a 13 (la compra, por cola y tópico) y 22 (la cancelación de un evento, por su tópico) |
| Timeout de 5 s con fallback | Hecho | 2 s para conectar y 5 para leer en cada cliente REST y en el SOAP, con su fallback (tabla de la sección 8.8). `node scripts/pruebas/asistente.mjs`, parte C: con el modelo demorado 8 s, el fallback llega a los 5007 ms (`checkpoint-3/asistente.txt`). Escenarios 14, 18, 19 y 20 |
| Consumidores idempotentes y DLQ | Hecho | Escenario 8 (mensaje repetido sin efecto) y 12 (mensaje inválido en la DLQ después de 5 entregas). Contra el compose, con el ARCA sin responder: la compra se emite igual, el mensaje de Facturación llega a la DLQ y, devuelto con `move-messages`, sale una sola factura, 29 de 29 (`checkpoint-3/arca-lento-dlq.txt`) |
| Sin pérdida de mensajes si se apaga el broker | Hecho | `node scripts/pruebas/broker-apagado.mjs`: 10 de 10 (`checkpoint-3/broker-apagado.txt`). En el compose, el journal va en el volumen `datos-servidor` (ADR-005): con un mensaje pendiente, `docker compose down` y `up` no lo pierden, 16 de 16 (`checkpoint-3/broker-recreado.txt`) |
| `Idempotency-Key` y `ETag` | Hecho | Escenario 2. `node scripts/pruebas/etag.mjs`: 19 de 19, 304 en los 10 GET con `ETag` y el 412 de `If-Match` (`checkpoint-3/etag.txt`). `PUT /api/eventos/{eventoId}` acepta `If-Match` y responde 412 si el evento cambió (`ServicioDeEventosTest`, sin fallas el 19/11) |
| Errores en Problem Details | Hecho | `backend/common/src/main/java/com/palco/common/rest/ManejadoresDeErrores.java`; escenarios 4 y 5 |
| IA a través de su gateway | Hecho, con el modelo simulado por defecto | `backend/servicios/asistente` (gateway `AsistenteIA`, sección 4.10). `node scripts/pruebas/asistente.mjs`: 11 de 11. `EvaluacionDelAsistenteTest`: 26 de 26 y 11 de 11 adversariales (`checkpoint-3/evaluacion-ia.txt`). `mvn -f backend/pom.xml -pl servicios/asistente -am test`: 115 tests, 0 fallas (19/11). Carpeta 9 de Postman |
| CI con reporte de cobertura parcial | Hecho | `.github/workflows/ci.yml`: secretos, backend (`mvn -B verify` con JaCoCo y la tabla de `scripts/qa/cobertura.mjs` en el resumen), frontend, integración (los 9 `health`, el del asistente incluido, y los guiones de `scripts/pruebas/`) y E2E (`npm run e2e`). Cobertura: 78,4 % de las líneas (`checkpoint-3/cobertura.md`) |
| Primera versión del plan de pruebas y de la matriz de trazabilidad | Hecho | `docs/testing/plan-de-pruebas.md`; `docs/testing/evidencia/matriz-de-trazabilidad.md`, que arma `scripts/qa/matriz.mjs`: 62 criterios, los 62 con caso y en verde |
| Primera versión del manual de usuario | Hecho | `docs/manual/manual-de-usuario.md`, con capturas en `docs/manual/img/` |
| Jira al día | Fuera del repo | El tablero se lleva en Notion y hay una exportación en el formato de Jira |

Además del pedido: la suite E2E con Playwright (`frontend/e2e/`, `npm run e2e`: 17 de 17,
`checkpoint-3/e2e.txt`), la carpeta 9 Asistente de la colección de Postman (la corrida entera da 171 requests
y 541 aserciones, sin fallas: `checkpoint-3/postman.txt`), el asistente probado por HTTP contra un proveedor local con la
API de chat de OpenAI (29 de 29, `checkpoint-3/asistente-proveedor.txt`) y el diagrama de integración
SOA al día (`docs/documentacion/diagramas/integracion-soa.svg`).

### Respuestas de la cátedra (16/10)

- El grupo de cinco está aceptado.
- Las integraciones con ARCA son simuladas por ahora, y también se acepta la
  pasarela de pagos simulada (`PagosFake`).
- Se entrega con las fechas del cronograma de la comisión del viernes.
- La documentación que piden es técnica, con diagramas de casos de uso y
  casos de uso. La consigna no pide Figma ni manual de usuario.
- Las 8 a 12 páginas del documento técnico son de contenido, sin contar los
  anexos.
- Tres preguntas quedaron sin respuesta ese día, y el grupo siguió así:
  - La hora límite de subida: cada entrega se sube el jueves anterior.
  - Si una cola y un tópico que salen del mismo evento cuentan como dos
    procesos asincrónicos: desde el 23/10 el tópico tiene además
    `REVENTA_CONFIRMADA`, y desde el 30/10 hay un segundo proceso propio, la
    cancelación de un evento, así que la consigna se cumple con cualquiera de
    las dos lecturas.
  - Si se acepta la base como último recurso en lugar de un datasource XA: es
    una decisión del grupo, justificada en ADR-004 y en la sección 7.4 del
    documento maestro.

### Respuestas de la cátedra (12/11)

- El cronograma de exposiciones pone la defensa de la Entrega Final del grupo
  el viernes 27/11, a las 08:40.
- La primera versión del manual de usuario se suma al 3.er check point.

### Decisiones del grupo (16/10)

- Vigencia de la sesión: 8 horas, sin refresh token (Fase 2). Desde el 25/11,
  15 minutos (DEF-38).
- Permisos por destino en el broker: riesgo aceptado (Fase 12).
- Facturación por SOAP: todo simulado, con el diseño de la Fase 5. La
  implementa Santiago Loto para el 13/11.
- Secreto de servicios fuera de desarrollo: requisito de despliegue, sin
  bloquear el arranque (Fase 12).
- Endpoints `/pruebas/*` de Notificaciones: se mantienen (Fase 12).
- La declaración del uso de IA y los aprendizajes del equipo los escribe el
  grupo.
- La Entrega Final se defiende el viernes 04/12 (final adelantado).
- Las tareas que le quedaban a Sebastian Deya pasan a Julián Zuchowicki: la conciliación
  de pagos con una prueba repetible de una falla a mitad de la compra; la capa
  de datos en Usuarios, Eventos y Notificaciones, con los tests de Eventos; y
  lo que quede de Ventas. Lo que Sebastian ya hizo no cambia. Hecho: la
  conciliación (escenario 14) y las capas de datos.

### Decisiones del grupo (23/10)

- La compra en la reventa publica `REVENTA_CONFIRMADA` solo en el tópico
  (L13, Fase 6).

### Decisiones del grupo (20/11)

- La Entrega Final se defiende el viernes 27/11 a las 08:40, el día que le
  asigna el cronograma de exposiciones de la cátedra del 12/11, y no el 04/12.

### Documentación

- [x] Diagramas de casos de uso y casos de uso (sección 13.9 del documento
      maestro, `docs/documentacion/diagramas/casos-de-uso.svg`).
- [x] Diagrama de secuencia de autenticación (S2):
      `docs/documentacion/diagramas/secuencia-seguridad-jwt.svg`.
- [x] Diagrama de secuencia de la reventa (S3):
      `docs/documentacion/diagramas/secuencia-reventa-rest.svg`.
- [x] Diagrama de integración SOA actualizado:
      `docs/documentacion/diagramas/integracion-soa.svg` (sección 3.6 del
      documento maestro).
- [x] Documento técnico de la Entrega Obligatoria N.º 2:
      `docs/entrega-obligatoria-2/Palco_Documento_Tecnico.md` y su PDF.
- [x] El asistente con IA en el documento maestro (secciones 4.10 y 5.14).
- [x] Diagrama de secuencia de la cancelación de un evento:
      `docs/documentacion/diagramas/secuencia-cancelacion-topico.svg`.
- [x] Documento del 3.er check point: `docs/checkpoint-3/`.
- [x] Documentación final de la Entrega Final
      (`docs/final/documentacion-final.md`) y la declaración del uso de IA
      (`docs/final/declaracion-uso-ia.md`). Los aprendizajes y la reflexión
      del equipo los escribe el grupo (decisión del 16/10).
