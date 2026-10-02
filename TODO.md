# Palco — TODO

Lista de trabajo para pasar de la demo solo frontend a un sistema real con
backend. Está ordenada por fases: cada fase depende, en general, de las
anteriores. Las decisiones marcadas como **(a confirmar)** son propuestas por
defecto; si se elige otra cosa, el resto de la lista sigue valiendo.

Convención: `[ ]` pendiente · `[x]` hecho · `[~]` en curso.

## Contexto (documentos de la materia)

Desarrollo de Aplicaciones II. Grupo: Deya, Frisoli, Loto, Molina, Zuchowicki.

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
| Integración | JAX-WS (AFIP/ARCA, SOAP) · cliente JAX-RS (Mercado Pago, REST) · JMS (ActiveMQ) |

### Los 8 componentes

| Componente | Interfaz | Tipo | Responsabilidad |
|---|---|---|---|
| `ServicioDeUsuarios` | `IUsuarios` | — | Registro, autenticación y roles (comprador, organizador, staff) |
| `ServicioDeEventos` | `IEventos` | Stateless | Catálogo de eventos, tipos de entrada y cupos |
| `ServicioDeVentas` | `IVentas` | **Stateful** | Orquesta el checkout de punta a punta y mantiene la compra en curso |
| `ServicioDePagos` | `IPagos` | — | Procesa, confirma y reembolsa pagos contra Mercado Pago |
| `ServicioDeValidación` | `IValidacion` | Stateless | Autenticidad y uso único de la entrada en puerta |
| `ServicioDeReventa` | `IReventa` | — | Publicación y compra de reventa oficial con tope |
| `ServicioDeNotificaciones` | `INotificaciones` | — | Confirmaciones, recordatorios y alertas, asincrónicas |
| `ServicioDeFacturación` | `IFacturacion` | — | Factura electrónica contra AFIP/ARCA |

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
- [ ] Crear cuenta de email de prueba **(a confirmar)**: Mailtrap (atrapa los
      emails en una bandeja web, reemplaza a MailHog).
- [x] Empaquetado: **8 WAR independientes (uno por servicio) en un solo
      WildFly**. Cada uno tiene su API en `/api/<servicio>` y su esquema de
      base, y se comunican solo por REST o JMS. Se pueden repartir en varios
      servidores más adelante sin cambiar código.
- [ ] Hostear el backend temprano (Render o Railway **a confirmar**) para que
      el grupo use una sola instancia y no tenga que correrlo en cada
      máquina. Requiere un `Dockerfile` que la plataforma construye en la
      nube a partir de `mvn package` (no hace falta Docker local).
- [x] Estructura del repo: `frontend/` (el front actual) y `backend/`
      (proyecto Maven).
- [x] Mover el front actual a `frontend/` sin romper `npm run dev` ni `build`.
- [x] Scaffold de `backend/`: `pom.xml` padre, `common` (lo compartido),
      `servicios/` (8 WAR) y `servidor/` (arma WildFly y despliega los 8).
- [x] Healthcheck `GET /api/<servicio>/health` (incluye la base) y manejo
      centralizado de errores (`ExceptionMapper` de JAX-RS), todo en `common`.
- [x] JSON con JSON-B en todos los servicios (`JsonProvider` en `common`).
- [x] Filtro CORS configurable con `PALCO_CORS_ORIGENES`.
- [x] Pasar a Java las constantes de negocio de `src/types/index.ts`
      → `ReglasDeNegocio` en `common`, con tests. El servidor pasa a ser la
      fuente de verdad.
- [x] WildFly sin instalarlo a mano: el módulo `servidor` lo arma con
      `wildfly-maven-plugin` solo con las capas que usamos (`jaxrs-server`,
      `ejb`, `embedded-activemq`, drivers H2 y PostgreSQL). `dev.sh` lo
      levanta y `redesplegar.sh` actualiza un servicio sin reiniciar.
- [x] Cola `cola.emision-entradas` y tópico `topico.compra-confirmada`
      creados por `servidor/src/main/wildfly/configurar.cli`.
- [x] Configuración de WildFly versionada (`configurar.cli`): datasource
      `PalcoDS` (H2 por defecto, PostgreSQL con variables `PALCO_DB_*`) y JMS.
- [ ] Seguridad en `configurar.cli` (Fase 2).
- [~] `backend/.env.example` con lo que cambia por ambiente (hecho: CORS y base;
      falta credenciales de Mercado Pago y Mailtrap, certificados de AFIP,
      `VITE_API_URL`).

## Fase 1 — Base de datos y entidades

- [~] Entidades JPA (hechas: `Evento`, `Sector`): `Usuario`, `Venta`
      (hoy `Orden` en el front), `VentaItem`, `Entrada`, `Publicacion`,
      `Reserva`, `Escaneo`, `CodigoVerificacion`, `Auditoria`, `Factura`.
- [ ] Repositorios: `UsuarioRepository`, `EventoRepository`,
      `EntradaRepository`, `VentaRepository` (los de la Entrega 1) y los que
      falten.
- [ ] Restricciones en la base, no solo en código:
  - [ ] `email` y `dni` únicos en usuarios.
  - [x] `slug` único en eventos.
  - [x] `vendidas <= cupo` en sectores.
  - [ ] DNI único por venta en entradas.
  - [ ] Una sola publicación activa por entrada.
- [ ] Estado de emisión en `Entrada` (`PENDIENTE_EMISION` → `VALIDA`), porque
      con mensajería la entrada se emite después del pago.
- [~] Montos en pesos enteros (`long`), nunca en `double` (hecho en eventos).
- [ ] Formato de ids: el taller usa `V-00231`, `U-00789`, `E-00042`,
      `T-000981`. Definir si son ids reales o códigos públicos aparte del id
      interno.
- [x] Migraciones con Flyway: cada servicio corre al desplegar sus scripts de
      `src/main/resources/db/migracion` en su propio esquema. SQL compatible
      con PostgreSQL; en desarrollo corren sobre H2 en modo PostgreSQL.
- [ ] Probar las migraciones contra un PostgreSQL real (Supabase) apenas esté.
- [~] Datos iniciales como migración (hechos: los 6 eventos con sus
      sectores). Faltan las 4 publicaciones y las 3 cuentas de prueba.

## Fase 2 — `ServicioDeUsuarios` (servicio reutilizable)

- [ ] Interfaz `IUsuarios` y Session Bean.
- [ ] `POST /api/auth/registro`: valida datos (mismas reglas que
      `src/lib/validate.ts`) y hashea la contraseña (PBKDF2 o BCrypt).
- [ ] Verificación de email con código de 6 dígitos: generar, enviar (vía
      `ServicioDeNotificaciones`), expirar, limitar intentos, reenviar.
- [ ] `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/yo`.
- [ ] Seguridad declarativa del contenedor: Jakarta Security / Elytron con
      `@RolesAllowed` para los roles `user`, `organizer`, `staff`.
- [ ] Mecanismo de sesión **(a confirmar)**: sesión HTTP con cookie (encaja con
      el bean stateful de Ventas) o JWT (MicroProfile JWT).
- [ ] Operaciones que consumen los otros servicios: identificar al comprador y
      a cada titular (Ventas), verificar que quien publica es el titular
      (Reventa), contrastar identidad en puerta (Validación).
- [ ] Verificación de identidad (`identidadVerificada`): definir qué se valida.
- [ ] Recuperar contraseña (no existe hoy en el front).

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
      pasa una sola, probado).
- [ ] Proteger crear evento (rol `organizer`) y actualizar cupo (solo otros
      servicios) cuando exista la Fase 2.
- [ ] `PUT /api/eventos/{id}` (solo el organizador dueño).
- [ ] Tests del Session Bean (Fase 11).
- [ ] Cancelar evento (dispara alertas de cancelación por Notificaciones).
- [ ] Relación evento ↔ organizador (hoy no existe en el front).
- [ ] Subida de imágenes **(a confirmar dónde)**: disco del servidor en
      desarrollo o storage externo. Guardar solo la URL; reemplaza el base64 en
      `localStorage`.

## Fase 4 — `ServicioDeVentas` (stateful) y `ServicioDePagos`

- [ ] Interfaz `IVentas` como `@Stateful`: mantiene selección, titulares y
      pago a lo largo de varias llamadas del mismo usuario. Se libera
      (`@Remove`) al confirmar o cancelar.
- [ ] Ligar la instancia stateful al usuario desde REST: guardarla en un bean
      CDI `@SessionScoped` (requiere sesión HTTP). Definir timeout igual a
      `MINUTOS_RESERVA`.
- [ ] Reserva de cupo durante el checkout (llama a `IEventos`), con
      liberación al vencer (`@Schedule` o timeout del bean).
- [ ] Validar `MAX_ENTRADAS_POR_ORDEN`, cupo disponible, titulares (DNI válido
      y único por venta) y calcular subtotal + `TASA_SERVICIO`.
- [ ] `IPagos` con implementación `MercadoPagoPagos`: cliente JAX-RS contra la
      API REST de Mercado Pago (credenciales de prueba / sandbox).
- [ ] Webhook de Mercado Pago (`POST /api/pagos/webhook`) con idempotencia.
- [ ] Reembolsos (`IPagos` los menciona; definir cuándo: evento cancelado).
- [ ] Al aprobarse el pago, publicar `PAGO_CONFIRMADO` (Fase 6).
- [ ] Manejar pago rechazado, pendiente y vencido.
- [ ] "Asignar después por link" para que otro titular complete sus datos.
- [ ] `GET /api/ventas/{id}` (solo el dueño), con el estado de emisión.

## Fase 5 — `ServicioDeFacturación` (AFIP/ARCA)

- [ ] `IFacturacion` con implementación `AfipFacturacion`: cliente JAX-WS
      generado desde los WSDL de AFIP (WSAA para autenticación, WSFEv1 para
      factura electrónica).
- [ ] Usar el ambiente de **homologación** de AFIP (requiere certificado de
      prueba).
- [ ] Implementación simulada `FacturacionFake` para desarrollo y tests,
      elegible por configuración (es el desacoplamiento que plantea el ESB).
- [ ] Disparar la facturación de forma asincrónica después del pago
      (suscriptor del tópico o cola propia) y reintentar si AFIP no responde.
- [ ] Guardar CAE y número de comprobante en `Factura`.

## Fase 6 — Mensajería JMS: pago confirmado

Según el taller: el mismo evento se publica en una **cola** y en un **tópico**.

```
ServicioDeVentas ──► cola.emision-entradas ──► Módulo de emisión (consumidor único)
                 └─► topico.compra-confirmada ─┬► ServicioDeNotificaciones
                                               └► Auditoría / Historial (durable)
```

- [ ] **Resolver diferencia entre documentos**: en la Entrega 1 las entradas se
      emiten de forma sincrónica (paso 4) y por la cola salen mail y factura;
      en el taller de mensajería la emisión pasa a la cola. Definir cuál vale
      (propuesta: el taller, que es posterior).

### Broker y contrato
- [ ] Usar el ActiveMQ Artemis embebido en WildFly (`standalone-full.xml`).
- [ ] Crear `cola.emision-entradas` y `topico.compra-confirmada` por script
      CLI de WildFly.
- [ ] Mensaje `TextMessage` con JSON, mismo body en los dos canales:
      `ventaId`, `compradorId`, `eventoId`, `entradas[]` (`entradaId`,
      `sector`, `titular` con `nombre` y `dni`), `montoTotal`, `timestamp`.
- [ ] Propiedad `tipoEvento=PAGO_CONFIRMADO` en cada mensaje.
- [ ] Clase del mensaje en el módulo `common`, serializada con JSON-B.
- [ ] Documentar el contrato en `docs/`.

### Productor (`ServicioDeVentas`)
- [ ] Publicar en la cola y en el tópico al confirmar el pago, con
      `JMSContext` inyectado.
- [ ] Enviar dentro de la misma transacción JTA que guarda la venta, para que
      no quede la venta guardada sin mensaje (o al revés). WildFly lo resuelve
      con XA entre la base y el broker embebido.

### Consumidor de la cola: módulo de emisión
- [ ] Message-Driven Bean (MDB) escuchando `cola.emision-entradas`.
- [ ] Generar el QR firmado de cada entrada (Fase 8) y marcar la venta como
      emitida.
- [ ] Idempotente: si el broker reentrega el mensaje, no emitir dos veces.
- [ ] Reintentos y cola de mensajes fallidos (DLQ) configurados en Artemis.

### Suscriptores del tópico
- [ ] MDB de `ServicioDeNotificaciones`: email al comprador. Suscripción no
      durable.
- [ ] MDB de Auditoría / Historial: guarda el evento. Suscripción **durable**
      (`subscriptionDurability=Durable`, `clientId`, `subscriptionName`).
- [ ] `messageSelector = "tipoEvento = 'PAGO_CONFIRMADO'"` en cada MDB.

### Otros procesos asincrónicos (de la Entrega 1)
- [ ] Recordatorios antes del evento (`@Schedule` + Notificaciones).
- [ ] Alertas de cancelación de evento.
- [ ] Aviso al vendedor cuando se vende su entrada en reventa.
- [ ] Reintentos de factura (Fase 5).

## Fase 7 — `ServicioDeReventa`

- [ ] `GET /api/entradas/mias`.
- [ ] `POST /api/publicaciones`: valida (con `IUsuarios`) que quien publica sea
      el titular verificado, que la entrada esté válida, precio entre 50% y
      `TOPE_REVENTA`, y que falten más de `HORAS_LIMITE_REVENTA`.
- [ ] `DELETE /api/publicaciones/{id}`: retirar, la entrada vuelve a válida.
- [ ] `GET /api/publicaciones` con filtros.
- [ ] Comprar una publicación: cobra por `IPagos`, marca publicación y entrada
      original como vendidas, emite entrada nueva al comprador (invalida el QR
      anterior vía `IValidacion`) y notifica al vendedor.
- [ ] Bloquear que alguien compre su propia publicación.
- [ ] Cerrar publicaciones automáticamente a `HORAS_LIMITE_REVENTA` del evento.
- [ ] Liquidación al vendedor.

## Fase 8 — `ServicioDeValidación` (stateless)

- [ ] QR firmado (HMAC o JWT con el id de la entrada) para que no se pueda
      falsificar. Reemplaza el QR decorativo de `src/components/QRCode.tsx`.
- [ ] `POST /api/validacion/escaneos` (solo `staff`): devuelve `valida`,
      `usada` o `invalida` y marca la entrada como usada de forma atómica
      (uso único aunque escaneen dos puertas a la vez).
- [ ] Contrastar con el documento del titular (devuelve nombre y DNI para que
      el staff lo compare).
- [ ] Ingreso manual por código `PLC-XXXXXX`.
- [ ] Contadores en vivo y últimos escaneos por evento.
- [ ] Asignar staff a eventos.
- [ ] Modo offline **(a confirmar si entra en alcance)**.

## Fase 9 — Panel del organizador

- [ ] `GET /api/organizador/resumen`: vendidas, recaudación y ocupación reales.
- [ ] Ventas por día, ocupación por sector, monitor de reventa, accesos en
      vivo y liquidación desde la base.
- [ ] (Opcional) Dashboard de analytics como nuevo suscriptor de
      `topico.compra-confirmada`.
- [ ] Borrar `src/data/organizer.ts` cuando todo venga de la API.

## Fase 10 — Conexión frontend ↔ backend

- [ ] Cliente HTTP en `src/lib/api.ts`: base URL desde `VITE_API_URL`, manejo
      de errores, envío de cookies de sesión (`credentials: 'include'`).
- [ ] CORS en JAX-RS (filtro) o proxy de Vite al WildFly en desarrollo.
- [ ] Manejo de datos del servidor **(a confirmar)**: TanStack Query.
- [ ] Tipos del front generados desde la API (MicroProfile OpenAPI de WildFly
      + generador de tipos TypeScript).
- [ ] Reemplazar cada `dispatch` del store por la llamada a la API:
  - [ ] `LOGIN`, `LOGOUT`, `REGISTER` → `/api/auth/*`
  - [ ] `CREAR_EVENTO` → `POST /api/eventos`
  - [ ] `SET_CARRITO` + `CREAR_ORDEN` → checkout de `ServicioDeVentas` + pago
  - [ ] `PUBLICAR_REVENTA`, `RETIRAR_PUBLICACION`, `COMPRAR_REVENTA` → `/api/publicaciones/*`
  - [ ] `MARCAR_ENTRADA_USADA` → `/api/validacion/escaneos`
- [ ] Checkout por pasos contra el bean stateful (cada paso es una llamada).
- [ ] Confirmación con estado "emitiendo entradas…" hasta que la emisión
      asíncrona termine.
- [ ] Countdown del checkout sincronizado con el vencimiento real.
- [ ] Estados de carga, error y vacío en cada pantalla (hoy `sleep()`).
- [ ] Errores de validación del servidor en los formularios.
- [ ] Sacar la persistencia de `palco.state` en `localStorage`.
- [ ] `ProtectedRoute` basado en la sesión real (`GET /api/auth/yo`).
- [ ] Borrar `src/data/events.ts` y `src/data/listings.ts`.

## Fase 11 — Pruebas

### Backend
- [ ] JUnit 5 + Mockito para la lógica de cada Session Bean (mockeando las
      interfaces de los otros componentes).
- [ ] Reglas de negocio: tope de reventa, mínimo 50%, tasa de servicio, máximo
      por venta, cierre de reventa a 3 horas, DNI único.
- [ ] Integración contra WildFly real **(a confirmar herramienta)**:
      Arquillian, o REST Assured contra WildFly levantado con `wildfly-maven-plugin`.
- [ ] Endpoints: caso feliz, validaciones, 401 sin sesión, 403 con rol
      incorrecto.
- [ ] Concurrencia: último lugar comprado por dos a la vez, misma publicación
      comprada por dos, misma entrada escaneada dos veces.
- [ ] Ciclo de vida del bean stateful: se libera al confirmar, cancelar o
      vencer.
- [ ] Pagos y facturación con las implementaciones fake de `IPagos` e
      `IFacturacion`; webhook duplicado no procesa dos veces.

### Mensajería
- [ ] Al confirmar un pago se publica un mensaje en la cola y uno en el tópico
      con body y `tipoEvento` correctos.
- [ ] Con varias instancias del MDB de emisión, cada mensaje lo procesa una
      sola.
- [ ] Reentrega del mismo mensaje → la entrada se emite una sola vez.
- [ ] Escenario del taller: con el consumidor de emisión detenido 5 minutos, el
      mensaje espera y la entrada se emite al volver.
- [ ] Auditoría durable: con el MDB detenido, al volver recibe los eventos
      pendientes. Notificaciones no durable: se acepta perderlo.
- [ ] Mensaje inválido termina en la DLQ.

### Frontend
- [ ] Vitest + Testing Library.
- [ ] Unitarias de `src/lib/validate.ts` y `src/lib/format.ts`.
- [ ] Componentes: `Stepper`, `Input`, `ProtectedRoute`, selector de
      cantidades de `EventPage`.
- [ ] MSW para mockear la API.

### End to end
- [ ] Playwright contra front + WildFly + base de test (proyecto o schema
      de Supabase aparte para tests).
- [ ] Recorrido completo: catálogo → evento → checkout → confirmación (con
      emisión asíncrona) → publicar en reventa → comprar con otra cuenta →
      validar en puerta.
- [ ] Registro con verificación de email (código leído desde la API de
      Mailtrap).
- [ ] Organizador crea evento y aparece en el catálogo.
- [ ] Accesos por rol.

## Fase 12 — Seguridad

- [ ] Bean Validation en todos los DTOs de entrada.
- [ ] `@RolesAllowed` en cada operación según rol.
- [ ] Autorización por recurso: cada usuario ve solo sus entradas y ventas;
      cada organizador solo sus eventos.
- [ ] Rate limiting en login, registro, verificación y escaneos.
- [ ] Cookies `HttpOnly`, `Secure`, `SameSite`; CORS restringido.
- [ ] Usuario y contraseña en el broker; solo los componentes publican.
- [ ] DNI en los mensajes del tópico: definir si viaja completo o solo ids.
- [ ] Certificados de AFIP y credenciales de Mercado Pago fuera del repo.

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

- [ ] Arreglar los 8 warnings de `npm run lint`.
- [ ] `index.html`: `lang="es"` (hoy dice `en`).
- [ ] `prefers-reduced-motion` también en framer-motion
      (`MotionConfig reducedMotion="user"`).
- [ ] Pasada de accesibilidad y responsive.
- [ ] Code splitting por ruta (el bundle pesa 473 kB).
- [ ] Actualizar `README.md` a medida que avanza el backend.

## Entregas de la materia

Según el taller de integración: Parcial N.º 2, Obligatoria N.º 2 y Final.
- [ ] Anotar fechas y qué pide cada una, y mapear a estas fases.
