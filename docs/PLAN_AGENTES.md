# Plan de trabajo con agentes — hacia la Entrega 3 (19/10) y la Entrega 4 (09/11)

Hecho el 09/10/2026 a partir de: la consigna del TP (`docs/consigna/TP_Integrador_...docx`), `TODO.md`, el código de
`backend/` y los apuntes de clase. Leí el código y los commits; **no compilé ni levanté WildFly** (no hay JDK 25
donde lo armé), así que el estado de "funciona" sale del `TODO.md` y del código, no de haberlo corrido.

---

## 1. Dónde estamos vs. lo que pide la cátedra

### Estado real de los 8 servicios

| Servicio | Estado en el repo |
|---|---|
| Usuarios | Funciona: registro, verificación, login JWT, roles, tests de hash/tokens |
| Eventos | Funciona: catálogo, detalle, crear, cupo atómico. `@Stateless` |
| Ventas, Pagos, Facturación, Notificaciones, Reventa, Validación | **Solo el esqueleto** (`*Application.java` + `pom.xml`) |
| Mensajería | Cola `cola.emision-entradas` y tópico `topico.compra-confirmada` creados en `configurar.cli`; **ningún productor ni consumidor** |
| Frontend | Completo pero 100% mock (`localStorage`); nada llama al backend |

### Brechas contra la consigna

**Entrega 3 — 19/10 (checkpoint, formativo).** Pide:

| Requisito | Estado |
|---|---|
| Diagrama de integración (SOA): componentes, sistemas externos y canal | Diseñado en los talleres; **falta el diagrama como archivo en `docs/`** |
| Clasificación justificada síncrona/asincrónica de cada integración externa | Está en apuntes de Clase 4 y en la actividad de Clase 9; **falta consolidarla en un solo documento** |
| **1 proceso de negocio con mensajería real de punta a punta, con broker configurado** | **Falta lo central**: no hay productor, MDB ni venta que publique |
| Documentación funcional, manual de usuario y prototipo Figma (Anexo) | No existen |

**Entrega 4 — 09/11 (obligatoria, defensa oral).** Además de lo anterior: 2 procesos asincrónicos (1 cola + 1 tópico),
1 servicio SOAP **con su WSDL**, 1 servicio REST, transacciones declarativas en un flujo crítico, documento técnico de
8–12 páginas con diagramas de secuencia, y que cualquier integrante pueda defender cualquier parte.

**Checklist transversal (Sección 6) — lo que hoy no se cumple y se evalúa en la entrega final:**

| Requisito | Estado |
|---|---|
| ≥ 1 componente **stateful** con ciclo de vida del contenedor | No hay ningún `@Stateful` (Ventas debería serlo) |
| ≥ 3 patrones de diseño aplicados y justificados | Los servicios usan `EntityManager` directo; **no hay Repository/DAO, Facade ni Adapter explícitos** |
| SOAP + WSDL / REST / cola / tópico | Solo REST propio; el resto sin implementar |
| Transacciones declarativas | Sin implementar |
| Seguridad declarativa en ≥ 2 operaciones | ✅ Cumplido (`@RolesAllowed` en Usuarios y Eventos) |
| Git incremental con **participación visible de todos** | ⚠️ Ver riesgo 1 abajo |

### Riesgos que no son de código

1. **Autoría en git.** Hay 4 autores y uno es `Improvement <Improvement@mesucan.com.ar>` (probablemente una
   identidad mal configurada) y no aparece ningún commit de Joaquin Molina. La entrega final exige "participación
   visible de todos los integrantes". Cada uno tiene que corregir `git config user.name/user.email` **antes** de
   volver a commitear. Los commits viejos se pueden mapear con un `.mailmap` sin reescribir historia.
2. **Los agentes no deben commitear a nombre de una sola persona.** Cada tarea la corre su dueño/a con su identidad.
3. **Defensa oral individual.** Que un agente escriba el código no alcanza: cada dueño/a tiene que poder explicar
   su parte. Por eso cada brief pide un `NOTAS_<tarea>.md` corto con el "por qué" de cada decisión.
4. El tablero de Notion está atrasado respecto del repo (Eventos y Usuarios figuran "Sin empezar" y están hechos).

---

## 2. Cómo trabajar con agentes (reglas)

- **Un agente = una rama = una tarea del tablero = un/a dueño/a.** Rama `feat/<servicio>-<tema>`, de preferencia en su
  propio `git worktree` (`git worktree add ../palco-ventas feat/ventas-core`) para correr varios en paralelo sin pisarse.
- **Archivos disjuntos.** Cada brief dice qué módulos puede tocar. `backend/common` solo lo toca el agente de
  mensajería en la Ola 1.
- **Plan antes de código.** Pedirle a cada agente que primero escriba su plan (qué archivos, qué decisiones) y que
  vos lo apruebes; recién después implementa. Es lo que evita que decida solo cosas que después tenés que defender.
- **Verificar antes de dar por hecho.** Todo agente termina corriendo `mvn -q test` y el `curl` de su servicio, y
  reporta el resultado real. Al final de cada ola, un agente revisor distinto contrasta contra la consigna.
- **`CLAUDE.md` en la raíz** (incluido junto a este plan) le da a cada agente las convenciones del repo; sin eso cada
  uno inventa su estilo.
- **Orden de merge** por ola (abajo). Un agente no arranca si su dependencia no está mergeada.

---

## 3. Olas de trabajo

```
OLA 0 (hoy)        humanos: identidad git, .env, correr ./dev.sh, definir proceso de la Entrega 3
OLA 1 (en paralelo) A1 ventas-core · A2 mensajeria · A3 pagos · A4 docs-entrega3 · (humano) Figma
OLA 2 (tras merge)  B1 integracion-e2e · B2 frontend-checkout · B3 validacion-qr · B4 patrones-y-tests · Revisor
                    ─── ENTREGA 3 · 19/10 ───
OLA 3 (hasta 09/11) C1 facturacion-soap · C2 reventa · C3 transacciones · C4 openapi-y-resiliencia · C5 doc-tecnico
                    ─── ENTREGA 4 · 09/11 ───
OLA 4 (hasta 30/11) pulido, ADRs, demo completa, defensa individual
```

### Calendario hasta la Entrega 3

| Día | Qué |
|---|---|
| Vie 09/10 | Ola 0 |
| Sáb 10 – Dom 11 | Ola 1 corriendo (los agentes trabajan, ustedes revisan planes) |
| Lun 12 (feriado) | Revisar y mergear Ola 1, en este orden: A2 → A3 → A1 → A4 |
| Mar 13 – Jue 15 | Ola 2 |
| Vie 16 – Sáb 17 | Congelar funcionalidades; Revisor; ensayo de la demo; Figma y manual terminados |
| Dom 18 | Colchón |
| Lun 19 | Entrega 3 |

### Reparto sugerido (alineado con el tablero de Notion)

| Persona | Ola 1 | Ola 2 |
|---|---|---|
| Sebastian Deya | A1 ventas-core (stateful) | B4 patrones-y-tests (parte Ventas) |
| Julian Zuchowicki | A2 mensajería (JMS, MDBs) | B1 integración e2e |
| Valentina Frisoli | A3 pagos + **Figma** | B2 frontend-checkout |
| Joaquin Molina | A4 docs-entrega3 (con apoyo de Santiago) | B3 validación-qr |
| Santiago Loto | Ola 0, coordinación de merges | Revisor, ensayo de la demo |

---

## 4. Ola 0 — humanos, hoy (30–60 min)

- [ ] Cada integrante: `git config user.name "Nombre Apellido"` y `user.email` (el de su cuenta de GitHub).
- [ ] Agregar `.mailmap` para unificar los commits viejos de `Improvement`.
- [ ] Copiar `CLAUDE.md` a la raíz y este plan a `docs/PLAN_AGENTES.md`; commitear.
- [ ] `cd backend && cp .env.example .env && ./dev.sh` → confirmar que arrancan los 8 servicios y `curl localhost:8080/api/eventos/health`.
- [ ] **Decidir el proceso de la Entrega 3.** Propuesta: `PAGO_CONFIRMADO` (ya diseñado en el taller y en
      `docs/superpowers/specs/mensajeria-pago-confirmado.md`) con cola de emisión + tópico de notificaciones/auditoría.
      Para el checkpoint alcanza con **uno**; el segundo canal queda para el 09/11.
- [ ] **Decidir la base para la demo:** H2 en memoria alcanza para el 19/10; Supabase puede esperar.
- [ ] Decisión que condiciona A1: cómo se ata el bean `@Stateful` al usuario si la sesión es JWT y hay 8 WAR
      (`TODO.md` Fase 4 propone `@SessionScoped` con sesión HTTP). Si no la toman hoy, A1 la propone en su plan y
      la aprueban ustedes; queda como ADR.
- [ ] Actualizar Notion (puedo hacerlo yo: Eventos, Usuarios y "Configurar proyecto base" → Listo).

---

## 5. Briefs de la Ola 1 (copiar y pegar a cada agente)

> Todos empiezan con: *"Leé `CLAUDE.md`, `TODO.md` y `docs/PLAN_AGENTES.md`. Antes de escribir código, mostrame tu
> plan (archivos y decisiones) y esperá mi OK."* y terminan con: *"Cuando termines, corré los tests y el curl, y
> reportá el resultado real. Escribí `docs/notas/<tarea>.md` (máx. 1 página) explicando el porqué de cada decisión
> para que pueda defenderla oralmente."*

### A1 · `ventas-core` — Sebastian · rama `feat/ventas-core`
- **Toca:** solo `backend/servicios/ventas/`. **No toca** `common` ni otros servicios.
- **Objetivo:** `ServicioDeVentas` como componente **stateful**: `IVentas` + `@Stateful` que mantiene
  selección → titulares → pago a lo largo de varias llamadas; `@PostConstruct`/`@PreDestroy`, `@StatefulTimeout`
  igual a `MINUTOS_RESERVA`, `@Remove` al confirmar/cancelar (esto es la "evidencia de ciclo de vida" que pide la cátedra).
- Entidades `Venta`, `VentaItem`, `Entrada` (estado `PENDIENTE_EMISION` → `VALIDA`) + migración Flyway V1 en el
  esquema de ventas. Montos `long`. Validar `MAX_ENTRADAS_POR_ORDEN`, DNI válido y único por venta, subtotal + tasa.
- API `/api/ventas`: iniciar compra, agregar entradas, cargar titulares, confirmar, cancelar, `GET /{id}` (solo dueño).
- **Mensajería:** programar contra `PublicadorPagoConfirmado.publicar(PagoConfirmadoMensaje)` (lo crea A2 en
  `common`; hasta que esté mergeado, usar una interfaz stub local). Al confirmar el pago, publicar **dentro de la
  misma transacción** que guarda la venta. Agregar el **MDB de emisión** (`cola.emision-entradas`, consumidor único,
  idempotente): crea las entradas y pasa la venta a emitida.
- **Aceptación:** test del bean (Mockito) para las reglas; ciclo de vida demostrado (log de `@PreDestroy` al confirmar);
  el flujo `iniciar → confirmar` con pago fake deja la venta guardada y un mensaje en la cola.
- **Decisión a justificar en `docs/notas/`:** cómo se ata el bean stateful al usuario (ADR).

### A2 · `mensajeria` — Julian · rama `feat/mensajeria`
- **Toca:** `backend/common/` (solo paquete nuevo `com.palco.common.mensajeria`) y `backend/servicios/notificaciones/`.
- **Objetivo:** `record PagoConfirmadoMensaje` según el contrato del `.md` (serializado con JSON-B),
  `PublicadorPagoConfirmado` (inyecta `JMSContext` con el connection factory transaccional `java:/JmsXA`; publica el
  **mismo** body en la cola y en el tópico, con la propiedad `tipoEvento=PAGO_CONFIRMADO`).
- En `notificaciones`: MDB suscriptor **no durable** del tópico (email → por ahora log; Mailtrap después) y MDB de
  **auditoría durable** (`subscriptionDurability=Durable`, `clientId`, `subscriptionName`) que guarda el evento en una
  tabla (migración Flyway). Ambos con `messageSelector = "tipoEvento = 'PAGO_CONFIRMADO'"`.
- DLQ / reintentos para mensajes inválidos (config en `configurar.cli`).
- **Aceptación:** test unitario del (de)serializador; un `POST` de prueba (endpoint interno solo para desarrollo, rol
  `servicio`) publica un mensaje y se ve en el log: el MDB de notificaciones lo recibe y la tabla de auditoría gana una fila.
  Con el MDB de auditoría detenido y vuelto a arrancar, recibe lo pendiente.
- **Es lo primero que se mergea:** todo lo demás depende de `common/mensajeria`.

### A3 · `pagos` — Valentina · rama `feat/pagos`
- **Toca:** solo `backend/servicios/pagos/`.
- **Objetivo:** `IPagos` (`procesarPago`, `confirmarPago`, `reembolsar`) con **dos implementaciones elegibles por
  configuración** (MicroProfile Config `palco.pagos.proveedor=fake|mercadopago`): `PagosFake` (default, aprueba salvo
  tarjetas de prueba rechazadas) y `MercadoPagoPagos` (cliente JAX-RS contra la API sandbox, credenciales por variable
  de entorno, **nunca** en el repo). Esto es el **Adapter/Strategy** que se defiende como patrón.
- Webhook `POST /api/pagos/webhook` **idempotente** (mismo `paymentId` dos veces no procesa dos veces) que responde
  200 rápido; rechazo/pendiente/vencido contemplados. Cliente JAX-RS con timeout corto.
- **Aceptación:** tests del fake y de la idempotencia del webhook; `curl` de un pago aprobado y uno rechazado.
- **Además (humano):** armar el **prototipo en Figma** con las pantallas del frontend (catálogo, evento, checkout,
  confirmación, mis entradas, reventa, panel organizador, puerta). Se puede partir de capturas del front actual.

### A4 · `docs-entrega3` — Joaquin (con Santiago) · rama `docs/entrega3`
- **Toca:** solo `docs/entrega3/`. No toca código.
- **Objetivo:** (1) diagrama de integración SOA en draw.io (partir de `docs/entrega1/Diagrama_Capas_EntradasEventos.drawio`):
  componentes internos, sistemas externos, canal y etiqueta SÍNCRONO/ASÍNCRONO por flecha; (2) documento de
  **clasificación justificada** de cada integración (consolidar `Taller_Arquitectura_Integracion.md`,
  `Taller_Mensajeria.md`, `Actividad_Integradora_Clase9.md`, los tres en `docs/clases/`); (3) **documentación funcional** (flujos de comprador,
  organizador, staff, reventa — ya redactados en la sección 6 de `docs/entrega1/Entrega_Parcial_1_Arquitectura.docx`);
  (4) **manual de usuario** con pasos y capturas del front.
- **Regla:** el documento debe describir **lo que el sistema hace hoy**, no lo planeado; si algo no está
  implementado, se marca como "diseñado, pendiente".
- Formato: Word (como la Entrega Parcial N.º 1). Pedir primero un índice y aprobarlo.

---

## 6. Briefs de la Ola 2 (resumen; el detalle se escribe cuando termine la Ola 1)

| Agente | Objetivo | Depende de |
|---|---|---|
| **B1 integracion-e2e** (Julian) | Conectar Ventas → Pagos (REST entre servicios con token rol `servicio`) → publicar → MDBs. Script `scripts/demo-entrega3.sh` que corre el flujo completo con `curl`. Arreglar lo que aparezca. **Es el entregable central del 19/10.** | A1, A2, A3 |
| **B2 frontend-checkout** (Valentina) | `frontend/src/lib/api.ts` + catálogo, login y checkout contra la API real (Fase 10 de `TODO.md`, solo el subconjunto necesario); estado "emitiendo entradas…" hasta que el MDB termine. | B1 parcial |
| **B3 validacion-qr** (Joaquin) | `ServicioDeValidación` **stateless**: QR firmado, `POST /api/validacion/escaneos` (rol `staff`), uso único atómico. | A1 (entidad `Entrada`) |
| **B4 patrones-y-tests** (Sebastian) | Introducir **Repository/DAO** en Eventos, Usuarios y Ventas (hoy `EntityManager` directo), y documentar los 3 patrones: Repository, Adapter/Strategy (`IPagos`), Facade (recurso→servicio). Tests Mockito de los Session Beans. | A1–A3 mergeados |
| **Revisor** (Santiago) | Agente de solo lectura, **distinto** a los que escribieron el código: contrasta rama vs. consigna (Entrega 3 y checklist) y devuelve lista de brechas con evidencia. Corre al final de cada ola. | cada ola |

---

## 7. Ola 3 — hacia el 09/11 (planificar después del 19/10)

- **C1 facturación-soap:** servicio SOAP "legado" simulado (JAX-WS, `@WebService`, WSDL publicado por WildFly) y
  cliente en `ServicioDeFacturación` con timeout de 5 s; ante timeout/Fault, mensaje a `cola.reintento-facturacion`
  y venta "confirmada, factura pendiente" (ya diseñado en `docs/clases/clase-09-actividad-integradora/Actividad_Integradora_Clase9.md`). Interfaz `IFacturacion` con
  `FacturacionFake`/`AfipFacturacion` (Strategy/Factory). Verificar en la doc de WildFly el nombre exacto de la propiedad de timeout.
- **C2 reventa:** `ServicioDeReventa` completo (tope +10%, mínimo 50%, cierre 3 h antes, no comprar la propia).
- **C3 transacciones:** `@TransactionAttribute` en el flujo reservar → cobrar → confirmar; demostrar rollback ante
  falla a mitad (es una pregunta explícita de la defensa del 09/11).
- **C4 openapi-y-resiliencia:** MicroProfile OpenAPI + contrato REST (Richardson nivel 2, nivel 3 en un recurso),
  `Problem Details`, `Idempotency-Key` en `POST /api/ventas`, aislamiento de fallas (suma puntos extra).
- **C5 doc-técnico:** documento de 8–12 páginas con diagramas de secuencia de cada integración.

## 8. Qué NO delegar a un agente

Las decisiones de arquitectura que van a preguntarte en la defensa (stateful vs. sesión, cola vs. tópico, qué
transacción abarca qué), el Figma final, el ensayo de la demo y la lectura del código de tu propia parte.
Los agentes aceleran la escritura; la defensa oral individual es de ustedes.
