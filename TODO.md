# Palco — TODO

Lista de trabajo para pasar de la demo solo frontend a un sistema real con
backend. Está ordenada por fases: cada fase depende, en general, de las
anteriores. Las decisiones marcadas como **(a confirmar)** son propuestas por
defecto; si se elige otra cosa, el resto de la lista sigue valiendo.

Convención: `[ ]` pendiente · `[x]` hecho · `[~]` en curso.

Contexto: el taller grupal "Escenario de mensajería (Palco)" define que el
evento **pago confirmado** se resuelve con JMS (cola + tópico). Eso está en la
[Fase 5](#fase-5--mensajería-jms-pago-confirmado) y condiciona el stack.

---

## Fase 0 — Decisiones y setup

- [ ] Definir stack del backend **(a confirmar)**. Como el taller usa JMS,
      la propuesta es Java 21 + Spring Boot 3, con:
  - Spring Web para la API REST.
  - Spring Data JPA + PostgreSQL, migraciones con Flyway.
  - Spring JMS + ActiveMQ Artemis como broker.
  - Spring Security para autenticación y roles.
  - Bean Validation para validar entradas.
- [ ] Definir cómo se dividen los servicios **(a confirmar)**. Los del taller
      son `ServicioDeVentas`, `ServicioDePagos`, `ServicioDeFacturación`,
      módulo de emisión de entradas, `ServicioDeNotificaciones` y Auditoría.
      Propuesta: empezar como un monolito modular (un módulo Maven por
      servicio, un solo deploy) que se comunica por JMS entre módulos, y
      separar en apps independientes solo si la materia lo pide.
- [ ] Revisar qué existe ya de `ServicioDePagos` y `ServicioDeFacturación`
      (el taller dice que ya usan `TextMessage` con JSON) para reutilizarlo.
- [ ] Estructura del repo **(a confirmar)**. Propuesta: `frontend/` (el front
      actual) y `backend/` (proyecto Maven multimódulo) en el mismo repo.
- [ ] Mover el front actual a `frontend/` sin romper `npm run dev` ni `build`.
- [ ] Scaffold de `backend/`: app Spring Boot, healthcheck (Actuator), logs,
      manejo centralizado de errores (`@ControllerAdvice`).
- [ ] Pasar a Java las constantes de negocio de `src/types/index.ts`
      (`TOPE_REVENTA`, `TASA_SERVICIO`, `MAX_ENTRADAS_POR_ORDEN`,
      `HORAS_LIMITE_REVENTA`, `MINUTOS_RESERVA`). El servidor pasa a ser la
      fuente de verdad; el front las puede leer de un endpoint de config.
- [ ] Configuración por ambiente: `application.yml` + `.env.example`
      (`DATABASE_URL`, `JWT_SECRET`, `ARTEMIS_URL`, `VITE_API_URL`,
      credenciales de pagos, email y storage).
- [ ] `docker-compose.yml` para desarrollo local: PostgreSQL, ActiveMQ
      Artemis (con consola web) y MailHog para ver emails.
- [ ] Un comando para levantar todo (front + backend + docker).

## Fase 1 — Base de datos

- [ ] Esquema con tablas para: `usuarios`, `eventos`, `sectores`, `ventas`
      (hoy `Orden` en el front), `venta_items`, `entradas`, `publicaciones`,
      `reservas`, `escaneos`, `codigos_verificacion`, `auditoria`.
- [ ] Restricciones en la base, no solo en código:
  - [ ] `email` y `dni` únicos en `usuarios`.
  - [ ] `slug` único en `eventos`.
  - [ ] `vendidas <= cupo` en `sectores` (check constraint).
  - [ ] DNI único por venta en `entradas` (índice único `venta_id + dni`).
  - [ ] Una sola publicación `activa` por entrada (índice único parcial).
- [ ] Estado de emisión en `entradas` (`pendiente_emision` → `valida`), porque
      con mensajería la entrada se emite después del pago, no en el mismo
      momento.
- [ ] Montos en enteros (pesos o centavos), nunca en `double`.
- [ ] Formato de ids: el taller usa `V-00231`, `U-00789`, `E-00042`,
      `T-000981`. Definir si son ids reales o códigos públicos aparte del id
      interno.
- [ ] Migraciones Flyway versionadas.
- [ ] Seed con los datos actuales de `src/data/` (6 eventos, 4 publicaciones)
      y las 3 cuentas de prueba.

## Fase 2 — Autenticación y usuarios

- [ ] `POST /auth/registro`: valida datos (mismas reglas que
      `src/lib/validate.ts`), hashea la contraseña con BCrypt.
- [ ] Verificación de email con código de 6 dígitos: generar, enviar, expirar,
      limitar intentos, reenviar con espera.
- [ ] `POST /auth/login` y `POST /auth/logout`.
- [ ] Sesión con JWT (acceso corto + refresh en cookie `httpOnly`) o sesión en
      cookie **(a confirmar)**.
- [ ] `GET /auth/yo`: devuelve el usuario logueado.
- [ ] Roles `user`, `organizer`, `staff` en Spring Security.
- [ ] Verificación de identidad (`identidadVerificada`): definir qué se valida
      y cómo se marca.
- [ ] Recuperar contraseña (no existe hoy en el front).

## Fase 3 — Eventos

- [ ] `GET /eventos` con filtros por categoría y búsqueda insensible a acentos
      (hoy se hace en el front con `normalizar()`; en Postgres, `unaccent`).
- [ ] `GET /eventos/{slug}` con sectores y cupo disponible.
- [ ] `POST /eventos` (solo `organizer`): crea evento con sectores.
- [ ] `PATCH /eventos/{id}` y cancelar evento (solo el organizador dueño).
- [ ] Relación evento ↔ organizador (hoy no existe: todos los eventos se
      muestran a cualquier organizador).
- [ ] Subida de imágenes a un storage (S3, Cloudflare R2 o disco local en
      desarrollo) **(a confirmar)**: validar tipo y tamaño, guardar solo la
      URL. Reemplaza el base64 en `localStorage`.

## Fase 4 — Compra (`ServicioDeVentas` + `ServicioDePagos`)

- [ ] `POST /reservas`: bloquea cupo por `MINUTOS_RESERVA` dentro de una
      transacción. Valida `MAX_ENTRADAS_POR_ORDEN` y cupo disponible.
- [ ] Liberar reservas vencidas (`@Scheduled` o chequeo al consultar cupo).
- [ ] `POST /ventas`: confirma la reserva, valida titulares (DNI válido, DNI
      único por venta), calcula subtotal + `TASA_SERVICIO` en el servidor.
- [ ] Integración con pasarela de pago **(a confirmar, propuesta: Mercado
      Pago)**: crear el pago, recibir el webhook, idempotencia ante webhooks
      repetidos.
- [ ] Cuando el pago queda aprobado, `ServicioDeVentas` cierra el checkout y
      publica el evento `PAGO_CONFIRMADO` (ver Fase 5). Ya no emite las
      entradas ni manda el email directamente.
- [ ] Manejar pago rechazado, pendiente (transferencia) y vencimiento.
- [ ] Facturación: definir si `ServicioDeFacturación` también se suscribe al
      tópico o se llama aparte.
- [ ] "Asignar después por link": generar link para que otra persona complete
      sus datos de titular.
- [ ] `GET /ventas/{id}` (solo el dueño) para la pantalla de confirmación,
      incluyendo el estado de emisión de cada entrada.

## Fase 5 — Mensajería JMS: pago confirmado

Según el taller: el mismo evento se publica en una **cola** y en un **tópico**.

```
ServicioDeVentas ──► cola.emision-entradas ──► Módulo de emisión (consumidor único)
                 └─► topico.compra-confirmada ─┬► ServicioDeNotificaciones
                                               └► Auditoría / Historial (durable)
```

### Broker y contrato
- [ ] Levantar ActiveMQ Artemis en `docker-compose.yml`.
- [ ] Crear `cola.emision-entradas` (punto a punto) y
      `topico.compra-confirmada` (pub/sub).
- [ ] Mensaje `TextMessage` con JSON, mismo body en los dos canales:
      `ventaId`, `compradorId`, `eventoId`, `entradas[]` (`entradaId`,
      `sector`, `titular` con `nombre` y `dni`), `montoTotal`, `timestamp`.
- [ ] Propiedad `tipoEvento=PAGO_CONFIRMADO` en cada mensaje.
- [ ] Clase/record Java del mensaje compartida entre productor y consumidores,
      y serialización con Jackson.
- [ ] Documentar el contrato (campos, tipos, ejemplo) en `docs/`.

### Productor (`ServicioDeVentas`)
- [ ] Publicar en la cola y en el tópico al confirmar el pago.
- [ ] Que la venta y el envío no queden inconsistentes: si se guarda la venta
      pero falla el envío al broker (o al revés). Opciones: transacción JMS +
      base, o patrón outbox (tabla de mensajes pendientes que un job publica)
      **(a confirmar)**.

### Consumidor de la cola: módulo de emisión
- [ ] Escuchar `cola.emision-entradas`.
- [ ] Generar el QR firmado de cada entrada (ver Fase 7) y marcar la venta como
      emitida.
- [ ] Consumidor idempotente: si el broker reentrega el mensaje (por ejemplo,
      después de una caída), no emitir dos veces. Chequear por `ventaId` /
      `entradaId` antes de emitir.
- [ ] Reintentos con espera y cola de mensajes fallidos (DLQ) para los que
      fallan siempre.

### Suscriptores del tópico
- [ ] `ServicioDeNotificaciones`: email (y SMS si entra en alcance) al
      comprador. Suscripción no durable, según el taller.
- [ ] Auditoría / Historial: guarda el evento en la tabla `auditoria`.
      Suscripción **durable**, porque no se puede perder ningún evento.
- [ ] Usar `tipoEvento` como selector de mensajes, para que cada suscriptor
      filtre solo lo que le interesa.

### Próximos eventos candidatos (opcional)
- [ ] Evaluar los mismos patrones para: reventa concretada, entrada escaneada,
      evento cancelado, reserva vencida.

## Fase 6 — Entradas y reventa

- [ ] `GET /entradas/mias`.
- [ ] `POST /publicaciones`: valida que la entrada sea del usuario y esté
      `valida`, precio entre 50% y `TOPE_REVENTA`, y que falten más de
      `HORAS_LIMITE_REVENTA` para el evento.
- [ ] `DELETE /publicaciones/{id}`: retirar, la entrada vuelve a `valida`.
- [ ] `GET /publicaciones` con filtros (lo que hoy muestra `/reventa`).
- [ ] `POST /publicaciones/{id}/compra`: en una transacción, marca la
      publicación como `vendida`, la entrada original como `vendida` y emite
      una entrada nueva a nombre del comprador. Pasa por la pasarela de pago
      (y puede reutilizar el flujo de la Fase 5 para emitir y notificar).
- [ ] Bloquear que alguien compre su propia publicación.
- [ ] Cerrar automáticamente las publicaciones activas cuando faltan
      `HORAS_LIMITE_REVENTA` para el evento.
- [ ] Liquidación al vendedor: registrar monto a pagar y estado.

## Fase 7 — Validación en puerta

- [ ] QR firmado: el contenido es un token firmado (HMAC o JWT) con el id de la
      entrada, para que no se pueda falsificar. Reemplaza el QR decorativo de
      `src/components/QRCode.tsx` por uno escaneable.
- [ ] Invalidar el QR anterior al revender la entrada.
- [ ] `POST /puerta/escaneos` (solo `staff`): devuelve `valida`, `usada` o
      `invalida` y marca la entrada como `usada` de forma atómica (dos escaneos
      simultáneos no pueden pasar los dos).
- [ ] Ingreso manual por código `PLC-XXXXXX`.
- [ ] Contadores en vivo y últimos escaneos por evento.
- [ ] Asignar staff a eventos (hoy cualquier staff valida cualquier evento).
- [ ] Modo offline **(a confirmar si entra en alcance)**: descargar lista de
      entradas válidas y sincronizar al volver la conexión.

## Fase 8 — Panel del organizador

- [ ] `GET /organizador/resumen`: stats reales (vendidas, recaudación,
      ocupación) de sus eventos.
- [ ] Ventas por día calculadas desde `ventas`.
- [ ] Ocupación por sector.
- [ ] Monitor de reventa con publicaciones reales.
- [ ] Accesos en vivo desde `escaneos`.
- [ ] Liquidación real del organizador.
- [ ] (Opcional) Dashboard de analytics como nuevo suscriptor de
      `topico.compra-confirmada`, como plantea el taller.
- [ ] Borrar `src/data/organizer.ts` cuando todo venga de la API.

## Fase 9 — Conexión frontend ↔ backend

- [ ] Cliente HTTP en `src/lib/api.ts`: base URL desde `VITE_API_URL`, manejo
      de errores, envío de credenciales, refresh de sesión.
- [ ] Proxy de Vite al backend en desarrollo, o CORS configurado en Spring.
- [ ] Decidir manejo de datos del servidor **(a confirmar)**. Propuesta:
      TanStack Query para cache, loading y reintentos.
- [ ] Generar los tipos del front desde la API (OpenAPI con springdoc +
      generador de tipos TypeScript) para no mantenerlos a mano.
- [ ] Reemplazar cada `dispatch` del store por la llamada a la API:
  - [ ] `LOGIN`, `LOGOUT`, `REGISTER` → `/auth/*`
  - [ ] `CREAR_EVENTO` → `POST /eventos`
  - [ ] `SET_CARRITO` + `CREAR_ORDEN` → `/reservas` + `/ventas` + pago
  - [ ] `PUBLICAR_REVENTA`, `RETIRAR_PUBLICACION`, `COMPRAR_REVENTA` → `/publicaciones/*`
  - [ ] `MARCAR_ENTRADA_USADA` → `/puerta/escaneos`
- [ ] Conectar páginas a la API: catálogo, detalle, checkout, confirmación,
      mis entradas, reventa, organizador, puerta.
- [ ] Pantalla de confirmación con estado "emitiendo entradas…": como la
      emisión es asíncrona, consultar `GET /ventas/{id}` hasta que las
      entradas estén emitidas y recién ahí mostrar el QR.
- [ ] Countdown del checkout sincronizado con el vencimiento real de la reserva.
- [ ] Estados de carga, error y vacío en cada pantalla (hoy se simulan con
      `sleep()`).
- [ ] Mostrar los errores de validación que devuelve el servidor en los
      formularios.
- [ ] Sacar la persistencia de `palco.state` en `localStorage`; dejar solo lo
      que sea preferencia local (por ejemplo, el carrito antes del login).
- [ ] `ProtectedRoute` basado en la sesión real (`GET /auth/yo`).
- [ ] Borrar `src/data/events.ts` y `src/data/listings.ts` del front (pasan al
      seed de la base).

## Fase 10 — Pruebas

### Backend
- [ ] JUnit 5 + Testcontainers (PostgreSQL y Artemis reales en los tests).
- [ ] Unitarias de reglas de negocio: tope de reventa, mínimo 50%, tasa de
      servicio, máximo por venta, cierre de reventa a 3 horas, DNI único.
- [ ] Integración por endpoint (MockMvc): caso feliz, validaciones, 401 sin
      sesión, 403 con rol incorrecto.
- [ ] Concurrencia: dos compras simultáneas del último lugar (no sobrevender),
      dos compras de la misma publicación, dos escaneos de la misma entrada.
- [ ] Webhooks de pago: aprobado, rechazado, duplicado (idempotencia).
- [ ] Vencimiento de reservas libera cupo.

### Mensajería
- [ ] Al confirmar un pago se publica un mensaje en la cola y uno en el tópico,
      con el body y la propiedad `tipoEvento` correctos.
- [ ] La cola entrega a un solo consumidor aunque haya varias instancias del
      módulo de emisión.
- [ ] Mensaje duplicado (reentrega) → la entrada se emite una sola vez.
- [ ] Caída del consumidor (el escenario del taller): con el módulo de emisión
      detenido 5 minutos el mensaje espera en la cola y la entrada se emite al
      volver.
- [ ] Suscripción durable: con Auditoría caída, los eventos se registran al
      volver. Con Notificaciones caída, se acepta perder el email.
- [ ] Mensaje inválido termina en la DLQ y no bloquea la cola.

### Frontend
- [ ] Configurar Vitest + Testing Library.
- [ ] Unitarias de `src/lib/validate.ts` y `src/lib/format.ts`.
- [ ] Componentes: `Stepper`, `Input` con errores, `ProtectedRoute`,
      selector de cantidades de `EventPage`.
- [ ] Mock de la API con MSW para probar páginas sin backend.

### End to end
- [ ] Configurar Playwright contra front + backend + docker de test.
- [ ] Recorrido completo de la demo: catálogo → evento → checkout → confirmación
      (con emisión asíncrona) → publicar en reventa → comprar con otra cuenta →
      validar en puerta.
- [ ] Registro con verificación de email (leyendo el código desde MailHog).
- [ ] Organizador crea evento con imagen y aparece en el catálogo.
- [ ] Accesos por rol (usuario común no entra a `/organizador` ni `/puerta`).

## Fase 11 — Seguridad

- [ ] Validar todo input en el servidor (nunca confiar en el front).
- [ ] Rate limiting en login, registro, verificación de email y escaneos.
- [ ] Headers de seguridad y CORS restringido en Spring Security.
- [ ] Cookies `httpOnly`, `secure`, `sameSite`.
- [ ] Autorización por recurso: un usuario solo ve sus entradas y ventas; un
      organizador solo sus eventos.
- [ ] Broker con usuario y contraseña; que solo los servicios puedan publicar.
- [ ] Datos personales (DNI) en los mensajes: viajan en el body del tópico, así
      que cada suscriptor nuevo los recibe. Definir si se envían completos o
      solo ids.
- [ ] Secretos fuera del repo.
- [ ] Definir retención de datos personales y quién puede verlos.

## Fase 12 — Infraestructura y deploy

- [ ] CI (GitHub Actions): lint, typecheck y build del front; build y tests
      del backend (con Testcontainers) en cada PR.
- [ ] Elegir hosting **(a confirmar)**: front (Vercel, Netlify), backend
      (Railway, Render, Fly), base PostgreSQL gestionada, broker Artemis
      (contenedor propio o servicio gestionado).
- [ ] Ambientes `staging` y `producción` con bases y brokers separados.
- [ ] Migraciones Flyway automáticas en deploy.
- [ ] Backups de la base.
- [ ] Monitoreo: métricas de Actuator, tamaño de las colas y de la DLQ, errores
      (Sentry o similar), logs centralizados.
- [ ] Dominio y HTTPS.

## Fase 13 — Pendientes del frontend actual

Se pueden hacer en cualquier momento, no dependen del backend.

- [ ] Arreglar los 8 warnings de `npm run lint` (`Date.now()` y
      `Math.random()` en render en `MyTicketsPage` y `ResalePage`, `setState`
      en effect en `CheckoutPage`, exports mixtos en `AppContext` y
      `ToastProvider`).
- [ ] `index.html`: `lang="es"` (hoy dice `en`).
- [ ] Respetar `prefers-reduced-motion` también en las animaciones de
      framer-motion (`MotionConfig reducedMotion="user"`).
- [ ] Pasada de accesibilidad y responsive (paso 13 del spec, sin verificar).
- [ ] Code splitting por ruta (el bundle pesa 473 kB).
- [ ] Actualizar `README.md` a medida que avanza el backend (limitaciones,
      cómo levantar backend + docker, variables de entorno, arquitectura de
      mensajería).
