# Palco — guía del proyecto

Trabajo práctico de Desarrollo de Aplicaciones II (UADE). Plataforma de venta de entradas con reventa oficial
(tope +10%). Lo evalúa una cátedra: **cada decisión tiene que poder defenderse por escrito y en vivo**, así que
preferí código simple y justificado antes que código ingenioso.

## Antes de tocar nada
1. Leé `TODO.md` (estado por fases) y `docs/PLAN_AGENTES.md` (qué te toca y qué no).
2. Trabajá solo en los módulos que tu tarea nombra. Si necesitás cambiar otro módulo, frená y acordalo antes con su
   dueño o dueña.
3. `backend/common` es compartido: no lo modifiques salvo que tu tarea lo diga explícitamente.

## Fechas de la comisión (viernes)
El grupo cursa los **viernes de 08:45 a 11:45**. La consigna (`docs/consigna/`) está escrita para la comisión del
lunes: **sus fechas no son las nuestras**. Usá las de esta tabla, que sale del cronograma que publica la cátedra.

| Viernes | Hito en el cronograma de la comisión | Cómo lo llama la consigna, y con qué fecha |
|---|---|---|
| 04/09 y 18/09 | 1.er check point y 1.ª entrega parcial (ya pasaron) | Entrega Parcial N.º 1 (31/08) y Entrega Obligatoria N.º 1 (14/09) |
| **23/10** | No figura con nombre. **Fecha sin confirmar con la cátedra** | Entrega Parcial N.º 2 (19/10): checkpoint sin defensa. El plan viejo la llamaba "Entrega 3" |
| **06/11** | Entrega del diseño del TP Obligatorio | No figura. Qué se entrega: a confirmar con la cátedra |
| **13/11** | 2.ª Entrega PARCIAL | Entrega Obligatoria N.º 2 — 2.º Parcial (09/11): con defensa oral. El plan viejo la llamaba "Entrega 4" |
| **20/11** | 3.er Check point TPO | No figura. Qué se entrega: a confirmar con la cátedra |
| **27/11** | Prueba del TP Obligatorio | No figura con ese nombre. Qué se hace ese día: a confirmar con la cátedra. Se toma como límite para tener el sistema completo funcionando, que es lo que pide la Entrega Final |
| 04/12 y 11/12 | Recuperatorio o final adelantado, y final regular | Entrega Final: el encabezado de la consigna da 04/12 u 11/12 y el detalle, 30/11. En cuál se defiende: a confirmar con la cátedra |

- El 16/10, el 23/10 y el 30/10 hay clase ("Desarrollo de una aplicación integrada").
- El 23/10 sale del patrón de las demás fechas: las del viernes caen cuatro días después de las del lunes. Es la
  fecha de trabajo hasta que la cátedra la confirme.
- La hora límite de la consigna (13:30) es de la comisión del lunes y también falta confirmarla. Por eso **toda
  entrega se sube el jueves anterior a la clase**.
- En texto nuevo escribí la fecha o el nombre de la consigna, no "Entrega 3" ni "Entrega 4". Esos nombres quedan
  solo en archivos y carpetas que ya existen (`docs/entrega3/`, `scripts/demo-entrega3.sh`).
- No cambies una fecha que cuenta algo que ya pasó, ni las de `docs/clases/`, `docs/entrega1/` y `docs/consigna/`.
- El calendario de trabajo y el reparto están en `docs/PLAN_AGENTES.md`. Qué pide cada hito y en qué estado está,
  en la última sección de `TODO.md`.

## Stack y estructura
- Backend: Jakarta EE 11 sobre WildFly 41, Java 25, Maven. **8 WAR independientes** en `backend/servicios/*`,
  uno por componente, cada uno con su esquema de base. Se hablan solo por REST o JMS, nunca por clases del otro
  servicio. Rutas: Usuarios, Eventos y Notificaciones en `/api/<servicio>`; Ventas en `/api/v1` (`/ventas`,
  `/entradas`) y Pagos en `/api/v1/pagos`. Los servicios nuevos van bajo `/api/v1`. Validación, Reventa y
  Facturación solo tienen `/api/<servicio>/health`.
- Frontend: React + Vite + TypeScript en `frontend/`. Sesión, catálogo, alta de eventos y compra usan el backend;
  reventa, puerta y estadísticas del organizador siguen con datos locales (`localStorage` y datos fijos).
- Base: H2 en modo PostgreSQL en desarrollo, Supabase (PostgreSQL) después. Migraciones Flyway en
  `src/main/resources/db/migracion` de cada servicio; el SQL tiene que correr en las dos bases.

## Convenciones (copiá el estilo de `servicios/eventos` y `servicios/usuarios`)
- Capas por servicio: `api/` (recurso JAX-RS + DTOs, capa delgada) → `negocio/` (interfaz `I<Servicio>` + Session
  Bean) → `dominio/` (entidades JPA). El recurso valida y delega; **la lógica de negocio no va en el recurso**.
- Nombres y comentarios en español. Dinero en pesos enteros (`long`), nunca `double`.
- Reglas de negocio (tope, tasa, límites) solo en `common/ReglasDeNegocio`. No dupliques constantes.
- Errores con `ErroresDeNegocio` (los traduce `ManejadoresDeErrores`). Nunca devuelvas stack traces.
- Seguridad declarativa: `@RolesAllowed` / `@PermitAll` en cada operación del recurso. Roles: `user`,
  `organizer`, `staff`, `servicio` (llamadas entre servicios).
- Cada componente se justifica como stateless o stateful, y el comentario de la clase dice por qué.
- Mensajería: contrato en `docs/superpowers/specs/mensajeria-pago-confirmado.md`. No lo cambies sin avisar.

## Cómo verificar (no des nada por terminado sin correr esto)
```bash
cd backend && mvn -q test                # tests
cd backend && ./dev.sh                   # levanta WildFly con los 8 servicios
curl localhost:8080/api/<servicio>/health    # Ventas: /api/v1/health; Pagos: /api/v1/pagos/health
cd frontend && npm run lint && npm run build
```
En Windows: `dev.sh` y los guiones de `scripts/` y `docs/entrega3/evidencia/` corren en Git Bash, con `JAVA_HOME`
apuntando a un JDK 25; Maven también corre desde PowerShell con `mvn -f backend\pom.xml test`.
Si no podés correr algo, decilo explícitamente al avisar que terminaste; no lo des por probado.

## Git
- Una rama por tarea: `feat/<servicio>-<tema>`. Commits chicos, en español, formato `tipo: qué cambió`.
- Los commits los hace la persona dueña de la tarea con su propia identidad de git (la cátedra revisa la
  participación de cada integrante). Nadie commitea con el `user.name` / `user.email` de otra persona.
- No hagas `push --force`, no reescribas historia, no commitees secretos (`.env`, certificados, tokens).

## Al terminar una tarea, avisá al grupo
Qué cambió, qué archivos se tocaron, cómo se probó (comando + resultado real) y qué quedó pendiente o dudoso.
